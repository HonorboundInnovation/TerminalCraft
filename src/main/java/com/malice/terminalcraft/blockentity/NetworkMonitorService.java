package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.network.MonitorRemoteRequest;
import com.malice.terminalcraft.network.RednetNetwork;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/** Server-side bounded monitor-service dispatcher for direct and computer-gateway layouts. */
final class NetworkMonitorService {
    static final int MAX_REQUESTS_PER_TICK = 8;

    private NetworkMonitorService() {}

    static MonitorBlockEntity resolveTarget(ModemBlockEntity modem) {
        Level level = modem.getLevel();
        if (level == null) return null;
        Map<java.util.UUID, MonitorBlockEntity> anchors = new LinkedHashMap<>();

        // Layout one: the modem touches any tile in the monitor wall directly.
        for (Direction direction : Direction.values()) {
            addMonitor(anchors, level.getBlockEntity(modem.getBlockPos().relative(direction)));
        }

        // Layout two: modem -> adjacent computer/turtle -> adjacent monitor wall.
        for (Direction direction : Direction.values()) {
            BlockEntity gateway = level.getBlockEntity(modem.getBlockPos().relative(direction));
            if (!(gateway instanceof TerminalBlockEntity) && !(gateway instanceof TurtleBlockEntity)) continue;
            for (Direction monitorDirection : Direction.values()) {
                addMonitor(anchors, level.getBlockEntity(gateway.getBlockPos().relative(monitorDirection)));
            }
        }
        return anchors.size() == 1 ? anchors.values().iterator().next() : null;
    }

    private static void addMonitor(Map<java.util.UUID, MonitorBlockEntity> anchors, BlockEntity candidate) {
        if (!(candidate instanceof MonitorBlockEntity monitor)) return;
        MonitorBlockEntity anchor = MonitorGroupDevice.discover(monitor).anchor();
        anchors.put(anchor.getDeviceId(), anchor);
    }

    static int tick(ModemBlockEntity modem) {
        MonitorBlockEntity target = resolveTarget(modem);
        if (target == null || modem.monitorServices().isEmpty()) return 0;
        int applied = 0;
        for (RednetNetwork.PendingMessage message : RednetNetwork.receiveProtocol(
                modem.getLevel(), modem.getModemId(), MonitorRemoteRequest.PROTOCOL, MAX_REQUESTS_PER_TICK)) {
            MonitorRemoteRequest request = MonitorRemoteRequest.decode(message.message).orElse(null);
            if (request == null || !modem.hasMonitorServiceOnPort(message.channel)) continue;
            if (apply(target, request)) applied++;
        }
        return applied;
    }

    private static boolean apply(MonitorBlockEntity target, MonitorRemoteRequest request) {
        MonitorGroupDevice wall = new MonitorGroupDevice(target);
        try {
            switch (request.operation()) {
                case CLEAR -> wall.clear();
                case WRITE -> wall.writeLine(request.text());
                case SET -> wall.setLine(request.row(), request.text());
                case TITLE -> wall.setTitle(request.text());
                case PALETTE -> wall.setPalette(request.foreground(), request.background());
            }
            return true;
        } catch (IllegalArgumentException rejected) {
            return false;
        }
    }
}
