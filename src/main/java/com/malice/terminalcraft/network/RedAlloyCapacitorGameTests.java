package com.malice.terminalcraft.network;

import com.malice.terminalcraft.block.RedAlloyCapacitorBlock;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;

/** In-world contract for independent straight-through Red Alloy signal regeneration. */
@GameTestHolder("terminalcraft")
public final class RedAlloyCapacitorGameTests {
    private RedAlloyCapacitorGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void eachInputRestoresOnlyItsOpposingOutput(GameTestHelper helper) {
        BlockPos capacitorPos = new BlockPos(3, 2, 3);
        helper.setBlock(capacitorPos.west(), Blocks.REDSTONE_BLOCK);
        helper.setBlock(capacitorPos.north(), Blocks.REDSTONE_BLOCK);
        helper.setBlock(capacitorPos, ModRegistries.RED_ALLOY_CAPACITOR_BLOCK.get());

        helper.runAfterDelay(3, () -> {
            BlockPos worldPos = helper.absolutePos(capacitorPos);
            BlockState state = helper.getLevel().getBlockState(worldPos);
            RedAlloyCapacitorBlock block = (RedAlloyCapacitorBlock) state.getBlock();
            helper.assertTrue(block.getSignal(state, helper.getLevel(), worldPos, Direction.EAST) == 15,
                    "west input must restore strength 15 only on the east output");
            helper.assertTrue(block.getSignal(state, helper.getLevel(), worldPos, Direction.SOUTH) == 15,
                    "north input must restore strength 15 only on the south output");
            helper.assertTrue(block.getSignal(state, helper.getLevel(), worldPos, Direction.WEST) == 0
                            && block.getSignal(state, helper.getLevel(), worldPos, Direction.NORTH) == 0
                            && block.getSignal(state, helper.getLevel(), worldPos, Direction.UP) == 0
                            && block.getSignal(state, helper.getLevel(), worldPos, Direction.DOWN) == 0,
                    "capacitor inputs must never leak sideways or reflect back toward their sources");

            helper.destroyBlock(capacitorPos.west());
            helper.runAfterDelay(3, () -> {
                BlockState updated = helper.getLevel().getBlockState(worldPos);
                helper.assertTrue(block.getSignal(updated, helper.getLevel(), worldPos, Direction.EAST) == 0
                                && block.getSignal(updated, helper.getLevel(), worldPos, Direction.SOUTH) == 15,
                        "removing one input must clear only its opposing output path");
                helper.succeed();
            });
        });
    }
}
