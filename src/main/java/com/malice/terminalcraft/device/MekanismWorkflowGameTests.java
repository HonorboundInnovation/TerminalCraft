package com.malice.terminalcraft.device;

import com.malice.terminalcraft.blockentity.TerminalBlockEntity;
import com.malice.terminalcraft.registry.ModRegistries;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.interfaces.IRedstoneControl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.Set;
import java.util.UUID;

/** Ordinary public-machine control through the terminal, using the installed Mekanism runtime. */
@GameTestHolder("terminalcraft")
@PrefixGameTestTemplate(false)
public final class MekanismWorkflowGameTests {
    @GameTest(template = "serverrackgametests.empty", timeoutTicks = 60)
    public static void terminalReadsAndConfiguresSmelter(GameTestHelper helper) {
        BlockPos terminalPos = new BlockPos(2, 2, 2);
        BlockPos machinePos = terminalPos.north();
        var block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("mekanism", "energized_smelter"));
        helper.assertTrue(block != null, "installed Mekanism smelter is available");
        helper.setBlock(terminalPos, ModRegistries.TERMINAL_BLOCK.get());
        helper.setBlock(machinePos, block);
        var caller = DeviceCallContext.player(UUID.randomUUID(), "mekanism-operator",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        helper.runAfterDelay(8, () -> {
            var terminal = (TerminalBlockEntity) helper.getBlockEntity(terminalPos);
            var machine = (TileEntityMekanism) helper.getBlockEntity(machinePos);
            String dimension = helper.getLevel().dimension().location().toString();
            var id = AdjacentForgeEndpointResolver.adjacent(dimension,
                    helper.absolutePos(terminalPos), Direction.NORTH).id();
            var status = terminal.getShell().executeForResult("device call " + id + " mekanism.status", caller);
            helper.assertTrue(status.succeeded() && status.outputLines().stream()
                    .anyMatch(line -> line.contains("mekanism_redstone_control")),
                    "terminal reads native machine status: " + status);
            var high = terminal.getShell().executeForResult(
                    "device call " + id + " mekanism.redstone_control.set high", caller);
            helper.assertTrue(high.succeeded()
                    && machine.getControlType() == IRedstoneControl.RedstoneControl.HIGH,
                    "terminal changes native smelter control to high: " + high);
            var readback = terminal.getShell().executeForResult("device call " + id + " mekanism.status", caller);
            helper.assertTrue(readback.succeeded() && readback.outputLines().stream()
                    .anyMatch(line -> line.contains("mekanism_redstone_control") && line.contains("high")),
                    "terminal reads back the changed setting: " + readback);
            var disabled = terminal.getShell().executeForResult(
                    "device call " + id + " mekanism.redstone_control.set disabled", caller);
            helper.assertTrue(disabled.succeeded()
                    && machine.getControlType() == IRedstoneControl.RedstoneControl.DISABLED,
                    "terminal restores normal redstone-independent operation: " + disabled);
            helper.succeed();
        });
    }
}
