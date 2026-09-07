package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraftforge.gametest.GameTestHolder;

/** Live contract for PLC program output reaching ordinary Minecraft redstone consumers. */
@GameTestHolder("terminalcraft")
public final class PlcRedstoneGameTests {
    private static final BlockPos PLC = new BlockPos(2, 2, 2);
    private static final BlockPos LAMP = PLC.west();

    private PlcRedstoneGameTests() {}

    @GameTest(template = "empty")
    public static void cabinetSelectionFollowsFrontPanel(GameTestHelper helper) {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            var state = ModRegistries.PROGRAMMABLE_LOGIC_CONTROLLER_BLOCK.get().defaultBlockState()
                    .setValue(com.malice.terminalcraft.block.ProgrammableLogicControllerBlock.FACING, facing);
            helper.setBlock(PLC, state);
            var shape = state.getShape(helper.getLevel(), helper.absolutePos(PLC));
            double x = 0.5 + facing.getStepX() * 0.46875;
            double z = 0.5 + facing.getStepZ() * 0.46875;
            helper.assertTrue(shape.toAabbs().stream().anyMatch(box -> box.contains(x, 0.5, z)),
                    "selection includes raised front panel facing " + facing);
            helper.assertTrue(shape.toAabbs().stream().noneMatch(box -> box.contains(1 - x, 0.5, 1 - z)),
                    "selection excludes empty space behind the inset cabinet facing " + facing);
            helper.assertTrue(shape.toAabbs().stream().noneMatch(box -> box.contains(0.03, 0.5, 0.03)),
                    "selection excludes empty cabinet corner");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void wiredSensorDrivesPlcAndReportsCableLoss(GameTestHelper helper) {
        BlockPos cableA = PLC.east();
        BlockPos cableB = cableA.east();
        BlockPos sensorPos = cableB.east();
        BlockPos terminalPos = cableA.south();
        helper.setBlock(PLC, ModRegistries.PROGRAMMABLE_LOGIC_CONTROLLER_BLOCK.get());
        helper.setBlock(LAMP, Blocks.REDSTONE_LAMP);
        helper.setBlock(terminalPos, ModRegistries.TERMINAL_BLOCK.get());
        var cable = ModRegistries.NETWORK_CABLE_BLOCK.get().defaultBlockState()
                .setValue(com.malice.terminalcraft.block.NetworkCableBlock.FACE, Direction.UP);
        for (BlockPos pos : java.util.List.of(cableA, cableB)) {
            helper.setBlock(pos.below(), Blocks.STONE);
            helper.setBlock(pos, cable);
        }
        helper.setBlock(sensorPos, ModRegistries.REDSTONE_SENSOR_BLOCK.get().defaultBlockState()
                .setValue(com.malice.terminalcraft.block.StandaloneSensorBlock.FACING, Direction.NORTH));
        var sensor = (StandaloneSensorBlockEntity) helper.getBlockEntity(sensorPos);
        sensor.setLabel("line-signal");
        sensor.setInterval(1);
        var terminal = (TerminalBlockEntity) helper.getBlockEntity(terminalPos);
        var plc = (ProgrammableLogicControllerBlockEntity) helper.getBlockEntity(PLC);
        var owner = com.malice.terminalcraft.device.DeviceCallContext.player(java.util.UUID.randomUUID(),
                "wired-commissioner", java.util.Set.of("device.read", "device.write"));
        plc.setOwner(owner.principalId());
        helper.runAfterDelay(4, () -> {
            var discovery = terminal.getShell().executeForResult("sensor discover", owner);
            helper.assertTrue(discovery.succeeded() && discovery.outputLines().stream().anyMatch(line -> line.contains("line-signal")),
                    "terminal discovers sensor over the physical cable: " + discovery);
            helper.assertTrue(terminal.getShell().getVfs().writeFile("/home/player/wired.plc",
                    "IN ENABLE SENSOR line-signal/value\nOUT MOTOR REDSTONE WEST\nRUNG MOTOR = ENABLE"), "wired program file created");
            var loaded = terminal.getShell().executeForResult("plc remote load " + plc.getDeviceId() + " /home/player/wired.plc", owner);
            helper.assertTrue(loaded.succeeded(), "wired sensor program remotely loaded: " + loaded);
            helper.assertTrue(terminal.getShell().executeForResult("device call " + plc.getDeviceId() + " control.run", owner).succeeded(),
                    "wired sensor program started");
            helper.setBlock(sensorPos.north(), Blocks.REDSTONE_BLOCK);
            helper.runAfterDelay(6, () -> {
                helper.assertTrue(sensor.signal() == 15 && helper.getBlockState(LAMP).getValue(RedstoneLampBlock.LIT),
                        "remote sensor signal drives the PLC lamp across network cable");
                helper.assertTrue(terminal.getShell().executeForResult("sensor @line-signal read value", owner).succeeded(),
                        "terminal reads the wired sensor's value channel");
                helper.setBlock(cableB, Blocks.AIR);
                helper.runAfterDelay(10, () -> {
                    helper.assertTrue(!helper.getBlockState(LAMP).getValue(RedstoneLampBlock.LIT),
                            "cable loss clears the PLC output");
                    helper.assertTrue(!terminal.getShell().executeForResult("sensor @line-signal read value", owner).succeeded(),
                            "terminal reports disconnected sensor rather than a reachable stale reading");
                    helper.setBlock(cableB, cable);
                    helper.runAfterDelay(4, () -> {
                        helper.assertTrue(terminal.getShell().executeForResult("sensor @line-signal read value", owner).succeeded(),
                                "reconnecting the cable restores sensor access");
                        helper.assertTrue(!terminal.getShell().executeForResult("device call " + plc.getDeviceId() + " control.run", owner).succeeded()
                                        && !plc.isRunning(), "run reports the latched fault instead of claiming success");
                        helper.assertTrue(terminal.getShell().executeForResult("device call " + plc.getDeviceId() + " control.reset", owner).succeeded(),
                                "operator explicitly resets the fault before restarting");
                        helper.assertTrue(terminal.getShell().executeForResult("device call " + plc.getDeviceId() + " control.run", owner).succeeded(),
                                "operator restarts controller after restoring input");
                        helper.runAfterDelay(6, () -> {
                            helper.assertTrue(helper.getBlockState(LAMP).getValue(RedstoneLampBlock.LIT),
                                    "restored cable and restarted PLC recover the working output");
                            helper.succeed();
                        });
                    });
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void terminalCommissionsRemotePlcAndControlsRealOutput(GameTestHelper helper) {
        BlockPos terminalPos = new BlockPos(5, 2, 2);
        helper.setBlock(terminalPos, ModRegistries.TERMINAL_BLOCK.get());
        helper.setBlock(PLC, ModRegistries.PROGRAMMABLE_LOGIC_CONTROLLER_BLOCK.get());
        helper.setBlock(LAMP, Blocks.REDSTONE_LAMP);
        var terminal = (TerminalBlockEntity) helper.getBlockEntity(terminalPos);
        var plc = (ProgrammableLogicControllerBlockEntity) helper.getBlockEntity(PLC);
        var owner = com.malice.terminalcraft.device.DeviceCallContext.player(java.util.UUID.randomUUID(),
                "commissioning-owner", java.util.Set.of("device.read", "device.write"));
        plc.setOwner(owner.principalId());
        helper.runAfterDelay(3, () -> {
            String id = plc.getDeviceId().toString();
            var discovered = terminal.getShell().executeForResult("device list programmable_logic_controller", owner);
            helper.assertTrue(discovered.succeeded() && discovered.outputLines().stream().anyMatch(line -> line.contains(id)),
                    "terminal discovers the nonadjacent PLC through the device API: " + discovered);
            helper.assertTrue(terminal.getShell().getVfs().writeFile("/home/player/interlock.plc",
                    "IN ENABLE REDSTONE NORTH\nOUT MOTOR REDSTONE WEST\nRUNG MOTOR = ENABLE"), "program file created");
            var loaded = terminal.getShell().executeForResult("plc remote load " + id + " /home/player/interlock.plc", owner);
            helper.assertTrue(loaded.succeeded() && plc.compileError().isEmpty(), "remote program compiles: " + loaded);
            var started = terminal.getShell().executeForResult("device call " + id + " control.run", owner);
            helper.assertTrue(started.succeeded() && plc.isRunning(), "owner starts PLC from terminal: " + started);
            helper.setBlock(PLC.north(), Blocks.REDSTONE_BLOCK);
            helper.runAfterDelay(6, () -> {
                helper.assertTrue(helper.getBlockState(LAMP).getValue(RedstoneLampBlock.LIT),
                        "remote-commissioned program turns north input into west lamp power");
                var telemetry = terminal.getShell().executeForResult("device call " + id + " io.get", owner);
                helper.assertTrue(telemetry.succeeded() && telemetry.outputLines().stream().anyMatch(line -> line.contains("ENABLE")),
                        "terminal receives live PLC input telemetry: " + telemetry);
                helper.setBlock(PLC.north(), Blocks.AIR);
                helper.runAfterDelay(10, () -> {
                    helper.assertTrue(!helper.getBlockState(LAMP).getValue(RedstoneLampBlock.LIT),
                            "removing the real input turns the output off");
                    var stopped = terminal.getShell().executeForResult("device call " + id + " control.stop", owner);
                    helper.assertTrue(stopped.succeeded() && !plc.isRunning(), "terminal stops the commissioned controller");
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void constantProgramPowersSelectedVanillaRedstoneFace(GameTestHelper helper) {
        helper.setBlock(PLC, ModRegistries.PROGRAMMABLE_LOGIC_CONTROLLER_BLOCK.get());
        helper.setBlock(LAMP, Blocks.REDSTONE_LAMP);
        helper.setBlock(PLC.east(), Blocks.REDSTONE_LAMP);
        ProgrammableLogicControllerBlockEntity plc =
                (ProgrammableLogicControllerBlockEntity) helper.getBlockEntity(PLC);
        helper.assertTrue(plc.loadProgram("OUT TEST REDSTONE WEST\nRUNG TEST = ON"),
                "constant redstone test program must compile");
        plc.start();

        helper.runAfterDelay(5, () -> {
            BlockPos absolutePlc = helper.absolutePos(PLC);
            helper.assertTrue(plc.getRedstoneOutput("west") == 15,
                    "PLC runtime must retain the requested west output");
            helper.assertTrue(helper.getLevel().getSignal(absolutePlc, Direction.EAST) == 15,
                    "PLC block must expose the requested west signal to Minecraft");
            helper.assertTrue(helper.getBlockState(LAMP).getValue(RedstoneLampBlock.LIT),
                    "an adjacent vanilla redstone lamp must receive PLC power");
            helper.assertTrue(!helper.getBlockState(PLC.east()).getValue(RedstoneLampBlock.LIT),
                    "the opposite face must not receive the selected west output");
            helper.assertTrue(plc.loadProgram("OUT TEST REDSTONE WEST\nRUNG TEST = OFF"),
                    "replacement off program must compile");
            plc.start();
            helper.runAfterDelay(10, () -> {
                helper.assertTrue(plc.getRedstoneOutput("west") == 0,
                        "replacement program must clear the west output");
                helper.assertTrue(!helper.getBlockState(LAMP).getValue(RedstoneLampBlock.LIT),
                        "the lamp must turn off after clearing PLC output");
                helper.succeed();
            });
        });
    }
}
