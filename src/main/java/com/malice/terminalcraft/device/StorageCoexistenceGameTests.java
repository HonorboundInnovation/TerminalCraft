package com.malice.terminalcraft.device;

import com.malice.terminalcraft.blockentity.TerminalBlockEntity;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.Set;
import java.util.UUID;

/** Player-facing transfer across two installed storage ecosystems. */
@GameTestHolder("terminalcraft")
@PrefixGameTestTemplate(false)
public final class StorageCoexistenceGameTests {
    @GameTest(template = "serverrackgametests.empty", timeoutTicks = 80)
    public static void terminalTransfersDrawerItemsIntoSophisticatedChest(GameTestHelper helper) {
        BlockPos host = new BlockPos(2, 2, 2);
        var drawer = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("storagedrawers", "oak_full_drawers_1"));
        var chest = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("sophisticatedstorage", "chest"));
        helper.assertTrue(drawer != null && chest != null, "both installed storage blocks exist");
        helper.setBlock(host, ModRegistries.TERMINAL_BLOCK.get());
        helper.setBlock(host.west(), drawer);
        helper.setBlock(host.east(), chest);
        helper.runAfterDelay(8, () -> {
            var source = helper.getBlockEntity(host.west()).getCapability(
                    ForgeCapabilities.ITEM_HANDLER, Direction.EAST).resolve().orElseThrow();
            var destination = helper.getBlockEntity(host.east()).getCapability(
                    ForgeCapabilities.ITEM_HANDLER, Direction.WEST).resolve().orElseThrow();
            helper.assertTrue(source.insertItem(0, new ItemStack(Items.DIAMOND, 24), false).isEmpty(),
                    "drawer accepts initial inventory");
            String dimension = helper.getLevel().dimension().location().toString();
            var sourceId = AdjacentForgeEndpointResolver.adjacent(dimension, helper.absolutePos(host), Direction.WEST).id();
            var destinationId = AdjacentForgeEndpointResolver.adjacent(dimension, helper.absolutePos(host), Direction.EAST).id();
            var caller = DeviceCallContext.player(UUID.randomUUID(), "storage-operator",
                    Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
            var shell = ((TerminalBlockEntity) helper.getBlockEntity(host)).getShell();
            var before = shell.executeForResult("storage count " + sourceId + " minecraft:diamond", caller);
            helper.assertTrue(before.succeeded() && before.outputLines().stream().anyMatch(line -> line.contains("24")),
                    "terminal counts the drawer stock: " + before);
            var transfer = shell.executeForResult("device transfer " + UUID.randomUUID() + " "
                    + sourceId + " " + destinationId + " minecraft:diamond 24", caller);
            helper.assertTrue(transfer.succeeded(), "terminal transfers across storage mods: " + transfer);
            helper.assertTrue(source.extractItem(0, 1, true).isEmpty(), "drawer stock moved out");
            int received = 0;
            for (int slot = 0; slot < destination.getSlots(); slot++) {
                var stack = destination.getStackInSlot(slot);
                if (stack.is(Items.DIAMOND)) received += stack.getCount();
            }
            helper.assertTrue(received == 24, "Sophisticated chest receives all 24 diamonds");
            var after = shell.executeForResult("storage count " + destinationId + " minecraft:diamond", caller);
            helper.assertTrue(after.succeeded() && after.outputLines().stream().anyMatch(line -> line.contains("24")),
                    "terminal counts the destination stock: " + after);
            helper.succeed();
        });
    }
}
