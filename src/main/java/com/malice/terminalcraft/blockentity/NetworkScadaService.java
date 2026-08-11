package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.network.RednetNetwork;
import com.malice.terminalcraft.network.ScadaRemoteRequest;
import com.malice.terminalcraft.scada.ScadaSample;
import com.malice.terminalcraft.scada.ScadaSavedData;
import com.malice.terminalcraft.scada.ScadaSnapshot;
import com.malice.terminalcraft.scada.ScadaTag;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Read-only typed RedNet gateway hosted by an adjacent TerminalCraft server rack. */
final class NetworkScadaService {
    static final int MAX_REQUESTS_PER_TICK = 8;
    static final int MAX_RESPONSE_CHARS = 3_800;

    private NetworkScadaService() {}

    static ServerRackBlockEntity resolveTarget(ModemBlockEntity modem) {
        Level level = modem.getLevel();
        if (level == null) return null;
        ServerRackBlockEntity found = null;
        for (Direction direction : Direction.values()) {
            BlockEntity candidate = level.getBlockEntity(modem.getBlockPos().relative(direction));
            if (!(candidate instanceof ServerRackBlockEntity rack)) continue;
            if (found != null && found != rack) return null;
            found = rack;
        }
        return found;
    }

    static int tick(ModemBlockEntity modem) {
        if (!(modem.getLevel() instanceof ServerLevel level) || resolveTarget(modem) == null
                || modem.scadaServices().isEmpty()) return 0;
        int handled = 0;
        for (RednetNetwork.PendingMessage message : RednetNetwork.receiveProtocol(
                level, modem.getModemId(), ScadaRemoteRequest.PROTOCOL, MAX_REQUESTS_PER_TICK)) {
            if (!modem.hasScadaServiceOnPort(message.channel)) continue;
            ScadaRemoteRequest request = ScadaRemoteRequest.decode(message.message).orElse(null);
            if (request == null) continue;
            String response = response(level, request);
            if (response.length() > MAX_RESPONSE_CHARS) response = response.substring(0, MAX_RESPONSE_CHARS);
            if (message.replyChannel >= 0 && !message.senderId.isBlank()) {
                RednetNetwork.transmitTo(level, modem.getModemId(), modem.getBlockPos(), message.senderId,
                        message.replyChannel, 0, response, modem.isWireless(), modem.getRange());
            }
            handled++;
        }
        return handled;
    }

    static String response(ServerLevel level, ScadaRemoteRequest request) {
        ScadaSavedData data = ScadaSavedData.get(level.getServer());
        long now = level.getServer().overworld().getGameTime();
        if (!data.initialized()) return "scada|1|error|uninitialized";
        return switch (request.operation()) {
            case STATUS -> status(data, now);
            case TAGS -> tags(data, request.selector(), request.limit(), now);
            case READ -> read(data, request.selector(), now);
            case HISTORY -> history(data, request.selector(), request.limit());
            case ALARMS -> alarms(data, request.limit(), now);
        };
    }

    private static String status(ScadaSavedData data, long now) {
        long active = data.alarms(ScadaSavedData.MAX_ALARMS, now).stream()
                .filter(alarm -> alarm.state() != ScadaSavedData.AlarmState.NORMAL).count();
        return "scada|1|status|online|tags=" + data.tags("", ScadaSavedData.MAX_TAGS).size()
                + "|alarms=" + active + "|dashboards=" + data.dashboards().size()
                + "|advanced_hmi=" + data.hmiDashboards().size();
    }

    private static String tags(ScadaSavedData data, String prefix, int limit, long now) {
        List<String> values = new ArrayList<>();
        for (ScadaTag tag : data.tags(prefix, limit)) {
            ScadaSnapshot snapshot = data.snapshot(tag.name(), now).orElse(null);
            values.add(tag.name() + "=" + (snapshot == null || snapshot.value() == null ? "pending" : wire(snapshot.value().display()))
                    + (tag.unit().isBlank() ? "" : " " + wire(tag.unit())) + "["
                    + (snapshot == null ? "pending" : snapshot.quality().id()) + "]");
        }
        return "scada|1|tags|" + String.join(",", values);
    }

    private static String read(ScadaSavedData data, String name, long now) {
        ScadaTag tag = data.tag(name).orElse(null);
        ScadaSnapshot snapshot = tag == null ? null : data.snapshot(name, now).orElse(null);
        if (tag == null) return "scada|1|read|" + name + "|not_found";
        if (snapshot == null) return "scada|1|read|" + name + "|pending";
        return "scada|1|read|" + tag.name() + "|"
                + (snapshot.value() == null ? "" : wire(snapshot.value().display())) + "|" + wire(tag.unit())
                + "|" + snapshot.quality().id() + "|" + snapshot.sampledAt();
    }

    private static String history(ScadaSavedData data, String name, int limit) {
        List<String> points = new ArrayList<>();
        for (ScadaSample sample : data.history(name, limit)) points.add(sample.gameTime() + ":"
                + (sample.value() == null ? "" : wire(sample.value().display())) + ":" + sample.quality().id());
        return "scada|1|history|" + name + "|" + String.join(",", points);
    }

    private static String alarms(ScadaSavedData data, int limit, long now) {
        List<String> values = data.alarms(limit, now).stream().map(alarm -> alarm.rule().name() + ":"
                + alarm.state().name().toLowerCase(Locale.ROOT) + ":"
                + alarm.rule().severity().name().toLowerCase(Locale.ROOT) + ":" + alarm.rule().tagName()).toList();
        return "scada|1|alarms|" + String.join(",", values);
    }

    /** Percent-escapes the compact protocol's structural delimiters and strips line controls. */
    private static String wire(String value) {
        return value.replace("%", "%25").replace("|", "%7C").replace(",", "%2C")
                .replace(":", "%3A").replace("=", "%3D").replace("[", "%5B")
                .replace("]", "%5D").replace('\n', ' ').replace('\r', ' ');
    }
}
