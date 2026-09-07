package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.network.MonitorRemoteRequest;
import com.malice.terminalcraft.network.RednetNetwork;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;

import java.util.UUID;

/** Live coverage for both supported physical remote-monitor layouts. */
@GameTestHolder("terminalcraft")
public final class NetworkMonitorServiceGameTests {
    private static final int PORT = 42;

    private NetworkMonitorServiceGameTests() {}

    private static com.malice.terminalcraft.device.DeviceCallContext fixtureAdmin(GameTestHelper helper) {
        var scada = com.malice.terminalcraft.scada.ScadaSavedData.get(helper.getLevel().getServer());
        var principal = scada.roles().stream()
                .filter(role -> role.role() == com.malice.terminalcraft.scada.ScadaRole.ADMIN
                        && role.principal().name().equals("scada-commissioner"))
                .map(com.malice.terminalcraft.scada.ScadaSavedData.RoleAssignment::principal)
                .findFirst().orElseGet(() -> com.malice.terminalcraft.device.PrincipalIdentity.player(
                        UUID.nameUUIDFromBytes("terminalcraft-scada-world-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        "scada-commissioner"));
        return new com.malice.terminalcraft.device.DeviceCallContext(principal, java.util.Set.of("device.read", "device.write"));
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void hmiAndRackJobsControlPlcWithCallerAuthority(GameTestHelper helper) {
        BlockPos terminalPos = new BlockPos(1, 2, 2);
        BlockPos plcPos = new BlockPos(3, 2, 2);
        BlockPos monitorPos = new BlockPos(5, 2, 2);
        BlockPos rackPos = new BlockPos(5, 2, 4);
        helper.setBlock(terminalPos, ModRegistries.TERMINAL_BLOCK.get());
        helper.setBlock(plcPos, ModRegistries.PROGRAMMABLE_LOGIC_CONTROLLER_BLOCK.get());
        helper.setBlock(plcPos.west(), net.minecraft.world.level.block.Blocks.REDSTONE_LAMP);
        helper.setBlock(monitorPos, ModRegistries.MONITOR_BLOCK.get());
        helper.setBlock(rackPos, ModRegistries.SERVER_RACK_BLOCK.get());
        var terminal = (TerminalBlockEntity) helper.getBlockEntity(terminalPos);
        var plc = (ProgrammableLogicControllerBlockEntity) helper.getBlockEntity(plcPos);
        var monitor = (MonitorBlockEntity) helper.getBlockEntity(monitorPos);
        var rack = (ServerRackBlockEntity) helper.getBlockEntity(rackPos);
        helper.assertTrue(rack.installModule(0, new net.minecraft.world.item.ItemStack(ModRegistries.SERVER_BLADE.get()), false), "rack compute blade installed");
        var admin = fixtureAdmin(helper);
        plc.setOwner(admin.principalId());
        helper.assertTrue(plc.loadProgram("OUT MOTOR REDSTONE WEST\nRUNG MOTOR = ON"), "PLC control fixture compiles");
        var scada = com.malice.terminalcraft.scada.ScadaSavedData.get(helper.getLevel().getServer());
        helper.runAfterDelay(4, () -> {
            for (String command : java.util.List.of(scada.initialized() ? "scada status" : "scada init",
                    "scada tag add hmi.start " + plc.getDeviceId() + " status state - 2 20 @control.run",
                    "scada tag add hmi.stop " + plc.getDeviceId() + " status state - 2 20 @control.stop",
                    "scada hmi create control-test " + monitor.getDeviceId() + " 2 CONTROLS",
                    "scada hmi widget add control-test overview start button 0 2 5 3 hmi.start command START",
                    "scada hmi widget add control-test overview stop button 7 2 5 3 hmi.stop command STOP")) {
                var result = terminal.getShell().executeForResult(command, admin);
                helper.assertTrue(result.succeeded(), "HMI commissioning: " + command + " " + result);
            }
            String activate = "device call " + com.malice.terminalcraft.scada.ScadaRuntime.DEVICE_ID
                    + " hmi.widget.activate control-test ";
            var guest = com.malice.terminalcraft.device.DeviceCallContext.player(UUID.randomUUID(), "hmi-guest",
                    java.util.Set.of("device.read", "device.write"));
            long now = helper.getLevel().getGameTime();
            helper.assertTrue(scada.grantRole(admin, guest.principal(), com.malice.terminalcraft.scada.ScadaRole.VIEWER, now).success(), "viewer role granted");
            helper.assertTrue(!terminal.getShell().executeForResult(activate + "start", guest).succeeded() && !plc.isRunning(), "viewer cannot activate control");
            helper.assertTrue(scada.grantRole(admin, guest.principal(), com.malice.terminalcraft.scada.ScadaRole.OPERATOR, now).success(), "operator role granted");
            helper.assertTrue(!terminal.getShell().executeForResult(activate + "start", guest).succeeded() && !plc.isRunning(), "SCADA operator cannot bypass PLC ownership");
            helper.assertTrue(terminal.getShell().executeForResult(activate + "start", admin).succeeded(), "PLC owner activates HMI start");
            helper.runAfterDelay(8, () -> {
                helper.assertTrue(plc.isRunning() && helper.getBlockState(plcPos.west()).getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT), "HMI start drives real output");
                helper.assertTrue(monitor.getLines().stream().anyMatch(line -> line.contains("START")), "advanced monitor renders control widget");
                helper.assertTrue(terminal.getShell().executeForResult(activate + "stop", admin).succeeded() && !plc.isRunning(), "HMI stop reaches controller");
                var job = rack.getShell().executeForResult("server submit \"" + activate + "start\"", admin);
                helper.assertTrue(job.succeeded(), "rack accepts owned HMI control job: " + job);
                helper.runAfterDelay(8, () -> {
                    helper.assertTrue(plc.isRunning() && helper.getBlockState(plcPos.west()).getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT), "rack job preserves caller authority through HMI and PLC control");
                    var jobs = rack.getShell().executeForResult("server list", admin);
                    helper.assertTrue(jobs.succeeded() && jobs.outputLines().stream().anyMatch(line -> line.contains("completed") && line.contains("exit=0")), "scheduled control finishes successfully: " + jobs);
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void scadaSensorDashboardShowsLiveAndOfflineQuality(GameTestHelper helper) {
        BlockPos terminalPos = new BlockPos(1, 2, 2);
        BlockPos sensorPos = new BlockPos(3, 2, 2);
        BlockPos monitorPos = new BlockPos(5, 2, 2);
        helper.setBlock(terminalPos, ModRegistries.TERMINAL_BLOCK.get());
        helper.setBlock(sensorPos, ModRegistries.REDSTONE_SENSOR_BLOCK.get().defaultBlockState()
                .setValue(com.malice.terminalcraft.block.StandaloneSensorBlock.FACING, net.minecraft.core.Direction.NORTH));
        helper.setBlock(sensorPos.north(), net.minecraft.world.level.block.Blocks.REDSTONE_BLOCK);
        helper.setBlock(monitorPos, ModRegistries.MONITOR_BLOCK.get());
        BlockPos clientModemPos = terminalPos.south();
        BlockPos gatewayPos = sensorPos.south();
        BlockPos cablePos = clientModemPos.east();
        helper.setBlock(clientModemPos, ModRegistries.MODEM_BLOCK.get());
        helper.setBlock(gatewayPos, ModRegistries.MODEM_BLOCK.get());
        helper.setBlock(gatewayPos.south(), ModRegistries.SERVER_RACK_BLOCK.get());
        helper.setBlock(cablePos.below(), net.minecraft.world.level.block.Blocks.STONE);
        var cable = ModRegistries.NETWORK_CABLE_BLOCK.get().defaultBlockState()
                .setValue(com.malice.terminalcraft.block.NetworkCableBlock.FACE, net.minecraft.core.Direction.UP);
        helper.setBlock(cablePos, cable);
        var clientModem = (ModemBlockEntity) helper.getBlockEntity(clientModemPos);
        var gateway = (ModemBlockEntity) helper.getBlockEntity(gatewayPos);
        clientModem.setWireless(false);
        gateway.setWireless(false);
        clientModem.openChannel(41);
        gateway.openChannel(42);
        var terminal = (TerminalBlockEntity) helper.getBlockEntity(terminalPos);
        var sensor = (StandaloneSensorBlockEntity) helper.getBlockEntity(sensorPos);
        var monitor = (MonitorBlockEntity) helper.getBlockEntity(monitorPos);
        var scada = com.malice.terminalcraft.scada.ScadaSavedData.get(helper.getLevel().getServer());
        var admin = fixtureAdmin(helper);
        helper.runAfterDelay(4, () -> {
            helper.assertTrue(gateway.registerScadaService("plant-scada-test", 42), "rack gateway advertises SCADA service");
            for (String command : java.util.List.of(scada.initialized() ? "scada status" : "scada init",
                    "scada tag add plant.signal " + sensor.getDeviceId() + " sensor.read value level 2 20 - s:value",
                    "scada alarm add signal-lost plant.signal bad_quality - high 0 Signal unavailable",
                    "scada dashboard add " + monitor.getDeviceId() + " plant 10 PLANT")) {
                var result = terminal.getShell().executeForResult(command, admin);
                helper.assertTrue(result.succeeded(), "SCADA commissioning command succeeds: " + command + " " + result);
            }
            helper.runAfterDelay(20, () -> {
                helper.assertTrue(monitor.getLines().stream().anyMatch(line -> line.contains("plant.signal")
                                && line.contains("good") && line.contains("15")),
                        "real monitor displays acquired sensor value and good quality: " + monitor.getLines());
                var request = terminal.getShell().executeForResult("modem scada request plant-scada-test read plant.signal 1 41", admin);
                helper.assertTrue(request.succeeded(), "remote terminal sends typed SCADA query: " + request);
                helper.runAfterDelay(4, () -> {
                var response = terminal.getShell().executeForResult("modem recv 8", admin);
                helper.assertTrue(response.succeeded() && response.outputLines().stream().anyMatch(line ->
                                line.contains("scada|1|read|plant.signal|15|level|good|")),
                        "rack gateway returns the acquired value over the wired route: " + response);
                helper.setBlock(sensorPos, net.minecraft.world.level.block.Blocks.AIR);
                helper.runAfterDelay(20, () -> {
                    helper.assertTrue(monitor.getLines().stream().anyMatch(line -> line.contains("plant.signal")
                                    && line.contains("offline") && line.contains("15") && line.startsWith("!")),
                            "removed sensor retains last value with visible offline quality and alarm: " + monitor.getLines());
                    var history = terminal.getShell().executeForResult("scada history plant.signal 8", admin);
                    helper.assertTrue(history.succeeded() && history.outputLines().stream().anyMatch(line -> line.contains("offline")),
                            "historian records unavailable source quality: " + history);
                    helper.assertTrue(terminal.getShell().executeForResult(
                            "modem scada request plant-scada-test read plant.signal 1 41", admin).succeeded(),
                            "remote terminal can query unavailable-source status");
                    helper.runAfterDelay(4, () -> {
                        var offline = terminal.getShell().executeForResult("modem recv 8", admin);
                        helper.assertTrue(offline.succeeded() && offline.outputLines().stream().anyMatch(line ->
                                        line.contains("scada|1|read|plant.signal|15|level|offline|")),
                                "gateway reports retained value with offline quality: " + offline);
                        helper.setBlock(cablePos, net.minecraft.world.level.block.Blocks.AIR);
                        helper.assertTrue(!terminal.getShell().executeForResult(
                                "modem scada request plant-scada-test status - 1 41", admin).succeeded(),
                                "disconnected wired gateway cannot be queried");
                        helper.succeed();
                    });
                });
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void directModemTargetAcceptsTypedPublication(GameTestHelper helper) {
        BlockPos modemPos = new BlockPos(2, 2, 2);
        BlockPos monitorPos = new BlockPos(3, 2, 2);
        helper.setBlock(modemPos, ModRegistries.MODEM_BLOCK.get());
        helper.setBlock(monitorPos, ModRegistries.MONITOR_BLOCK.get());
        helper.runAfterDelay(3, () -> publishAndAssert(helper, modemPos, monitorPos,
                "direct-dashboard", "Direct path online"));
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void computerGatewayTargetAcceptsTypedPublication(GameTestHelper helper) {
        BlockPos modemPos = new BlockPos(2, 2, 2);
        BlockPos computerPos = new BlockPos(3, 2, 2);
        BlockPos monitorPos = new BlockPos(4, 2, 2);
        helper.setBlock(modemPos, ModRegistries.MODEM_BLOCK.get());
        helper.setBlock(computerPos, ModRegistries.TERMINAL_BLOCK.get());
        helper.setBlock(monitorPos, ModRegistries.MONITOR_BLOCK.get());
        helper.runAfterDelay(3, () -> publishAndAssert(helper, modemPos, monitorPos,
                "gateway-dashboard", "Gateway path online"));
    }

    private static void publishAndAssert(GameTestHelper helper, BlockPos modemPos,
                                         BlockPos monitorPos, String service, String text) {
        ModemBlockEntity modem = (ModemBlockEntity) helper.getBlockEntity(modemPos);
        MonitorBlockEntity monitor = (MonitorBlockEntity) helper.getBlockEntity(monitorPos);
        helper.assertTrue(modem.openChannel(PORT), "receiver port must open");
        helper.assertTrue(modem.registerMonitorService(service, PORT),
                "one unambiguous wall must register as a typed monitor service");

        UUID sender = UUID.randomUUID();
        BlockPos senderPos = helper.absolutePos(new BlockPos(7, 2, 2));
        RednetNetwork.open(helper.getLevel(), sender, 99, senderPos, true, 256);
        helper.assertTrue(RednetNetwork.transmitService(helper.getLevel(), sender, senderPos,
                        service, 99, MonitorRemoteRequest.set(0, text).encode(), true, 256,
                        MonitorRemoteRequest.PROTOCOL),
                "typed sender must resolve and reach the monitor service");
        helper.assertTrue(NetworkMonitorService.tick(modem) == 1,
                "monitor dispatcher must consume exactly one valid request");
        helper.assertTrue(monitor.getLines().equals(java.util.List.of(text)),
                "remote text must be visible on the selected wall");
        RednetNetwork.close(helper.getLevel(), sender, 99);
        helper.succeed();
    }
}
