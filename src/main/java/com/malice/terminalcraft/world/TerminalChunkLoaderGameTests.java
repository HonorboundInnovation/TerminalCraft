package com.malice.terminalcraft.world;

import com.malice.terminalcraft.blockentity.TerminalBlockEntity;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;

import java.util.UUID;

/** Live characterization of shared terminal chunk ownership across unload, reload, and removal. */
@GameTestHolder("terminalcraft")
public final class TerminalChunkLoaderGameTests {
    private static final BlockPos FIRST = new BlockPos(2, 2, 2);
    private static final BlockPos SECOND = new BlockPos(2, 3, 2);

    private TerminalChunkLoaderGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void routineUnloadReloadRetainsOwnershipAndFinalRemovalReleasesIt(GameTestHelper helper) {
        helper.setBlock(FIRST, ModRegistries.TERMINAL_BLOCK.get());
        helper.setBlock(SECOND, ModRegistries.TERMINAL_BLOCK.get());
        helper.runAfterDelay(5, () -> {
            ServerLevel level = helper.getLevel();
            TerminalBlockEntity first = (TerminalBlockEntity) helper.getBlockEntity(FIRST);
            TerminalBlockEntity second = (TerminalBlockEntity) helper.getBlockEntity(SECOND);
            UUID firstId = first.getDeviceId();
            UUID secondId = second.getDeviceId();
            BlockPos firstAbsolute = helper.absolutePos(FIRST);
            BlockPos secondAbsolute = helper.absolutePos(SECOND);

            helper.assertTrue(TerminalChunkLoader.tracksTerminal(level, firstId)
                            && TerminalChunkLoader.tracksTerminal(level, secondId),
                    "both live terminals must be tracked");
            int initialOwners = TerminalChunkLoader.trackedOwners(level, firstAbsolute);
            helper.assertTrue(initialOwners >= 2,
                    "terminals in one chunk must share one ownership record");
            helper.assertTrue(!TerminalChunkLoader.ownsTicket(level, firstAbsolute),
                    "default server policy must track lifecycle without silently force-loading the chunk");
            helper.assertTrue(TerminalChunkLoader.snapshot(level.getServer()).ownedTickets() == 0,
                    "default diagnostics must report no TerminalCraft-owned tickets");

            CompoundTag saved = first.saveWithFullMetadata();
            BlockState state = level.getBlockState(firstAbsolute);
            level.removeBlockEntity(firstAbsolute);
            helper.assertTrue(TerminalChunkLoader.tracksTerminal(level, firstId),
                    "routine block-entity unload must not release terminal ownership");

            TerminalBlockEntity reloaded = new TerminalBlockEntity(firstAbsolute, state);
            reloaded.load(saved);
            level.setBlockEntity(reloaded);
            TerminalBlockEntity.serverTick(level, firstAbsolute, state, reloaded);
            helper.assertTrue(firstId.equals(reloaded.getDeviceId())
                            && TerminalChunkLoader.tracksTerminal(level, firstId),
                    "reload must restore the same identity without duplicating ownership");
            helper.assertTrue(TerminalChunkLoader.trackedOwners(level, firstAbsolute) == initialOwners,
                    "routine reload must not duplicate or release owners");

            helper.setBlock(FIRST, Blocks.AIR);
            helper.assertTrue(!TerminalChunkLoader.tracksTerminal(level, firstId)
                            && TerminalChunkLoader.tracksTerminal(level, secondId),
                    "actual removal must release only the removed terminal");
            helper.assertTrue(TerminalChunkLoader.trackedOwners(level, secondAbsolute) == initialOwners - 1,
                    "shared chunk ownership must remain for the surviving terminal");

            helper.setBlock(SECOND, Blocks.AIR);
            helper.assertTrue(!TerminalChunkLoader.tracksTerminal(level, secondId)
                            && TerminalChunkLoader.trackedOwners(level, secondAbsolute) == initialOwners - 2,
                    "removing both test terminals must release both runtime owners");
            helper.succeed();
        });
    }
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void stableIdentityRelocationReleasesPreviousChunkOwnership(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        UUID terminalId = UUID.randomUUID();
        BlockPos first = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos moved = first.offset(32, 0, 0);

        TerminalChunkLoader.ensureLoaded(level, first, terminalId);
        helper.assertTrue(TerminalChunkLoader.trackedOwners(level, first) == 1,
                "initial chunk must track the terminal identity once");

        TerminalChunkLoader.ensureLoaded(level, moved, terminalId);
        helper.assertTrue(TerminalChunkLoader.trackedOwners(level, first) == 0,
                "moving one stable identity must release its previous chunk ownership");
        helper.assertTrue(TerminalChunkLoader.trackedOwners(level, moved) == 1,
                "moved identity must be tracked exactly once in its new chunk");

        TerminalChunkLoader.terminalRemoved(level, terminalId);
        helper.assertTrue(TerminalChunkLoader.trackedOwners(level, moved) == 0,
                "final removal must leave no relocated ownership record");
        helper.succeed();
    }

}
