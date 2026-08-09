package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.network.RednetNetwork;
import com.malice.terminalcraft.network.SensorRemoteRequest;
import com.malice.terminalcraft.sensor.SensorReading;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Bounded RedNet request dispatcher for an explicitly published adjacent Sensor Array. */
final class NetworkSensorService {
    static final int MAX_REQUESTS_PER_TICK = 8;
    private static final int MAX_RESPONSE_CHARS = 3_800;

    private NetworkSensorService() {}

    static SensorArrayBlockEntity resolveTarget(ModemBlockEntity modem) {
        Level level = modem.getLevel();
        if (level == null) return null;
        for (Direction direction : Direction.values()) {
            BlockEntity candidate = level.getBlockEntity(modem.getBlockPos().relative(direction));
            if (candidate instanceof SensorArrayBlockEntity array) return array;
        }
        return null;
    }

    static int tick(ModemBlockEntity modem) {
        SensorArrayBlockEntity target = resolveTarget(modem);
        if (target == null || modem.sensorServices().isEmpty()) return 0;
        int handled = 0;
        for (RednetNetwork.PendingMessage message : RednetNetwork.receiveProtocol(
                modem.getLevel(), modem.getModemId(), SensorRemoteRequest.PROTOCOL, MAX_REQUESTS_PER_TICK)) {
            if (!modem.hasSensorServiceOnPort(message.channel)) continue;
            SensorRemoteRequest request = SensorRemoteRequest.decode(message.message).orElse(null);
            if (request == null) continue;
            String response = switch (request.operation()) {
                case LIST -> list(target);
                case SNAPSHOT -> snapshot(target);
                case READ -> read(target, request.channel());
            };
            if (response.length() > MAX_RESPONSE_CHARS) response = response.substring(0, MAX_RESPONSE_CHARS);
            if (message.replyChannel >= 0 && !message.senderId.isBlank()) {
                RednetNetwork.transmitTo(modem.getLevel(), modem.getModemId(), modem.getBlockPos(),
                        message.senderId, message.replyChannel, response, modem.isWireless(), modem.getRange());
            }
            handled++;
        }
        return handled;
    }

    private static String list(SensorArrayBlockEntity target) {
        List<String> channels = new ArrayList<>();
        target.channels().forEach(channel -> channels.add(channel.name() + ":" + channel.kind().id()
                + ":" + channel.metric() + ":" + (channel.enabled() ? "on" : "off")));
        return "sensor|1|list|" + String.join(",", channels);
    }

    private static String snapshot(SensorArrayBlockEntity target) {
        List<String> samples = new ArrayList<>();
        target.readings().values().forEach(reading -> samples.add(format(reading)));
        return "sensor|1|snapshot|" + String.join(",", samples);
    }

    private static String read(SensorArrayBlockEntity target, String requested) {
        SensorReading reading = target.reading(requested);
        return reading == null ? "sensor|1|read|" + requested + "|missing" : "sensor|1|read|" + format(reading);
    }

    private static String format(SensorReading reading) {
        String value = reading.numeric() ? Double.toString(reading.numericValue()) : reading.textValue();
        return reading.channel() + "=" + value + " " + reading.unit()
                + "[" + reading.quality().id() + "]";
    }
}
