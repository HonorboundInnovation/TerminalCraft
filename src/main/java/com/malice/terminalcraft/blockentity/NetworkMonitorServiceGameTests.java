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
