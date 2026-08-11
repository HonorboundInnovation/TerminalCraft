package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.network.RednetNetwork;
import com.malice.terminalcraft.network.RednetAutoConfiguration;
import com.malice.terminalcraft.network.SensorRemoteRequest;
import com.malice.terminalcraft.sensor.SensorChannel;
import com.malice.terminalcraft.sensor.SensorReading;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Bounded RedNet request dispatcher for adjacent and automatically wireless sensors. */
final class NetworkSensorService {
    static final int MAX_REQUESTS_PER_TICK = 8;
    static final int MAX_RESPONSE_CHARS = 3_800;

    private NetworkSensorService() {}

    static BlockEntity resolveTarget(ModemBlockEntity modem) {
        Level level = modem.getLevel();
        if (level == null) return null;
        for (Direction direction : Direction.values()) {
            BlockEntity candidate = level.getBlockEntity(modem.getBlockPos().relative(direction));
            if (candidate instanceof SensorArrayBlockEntity || candidate instanceof StandaloneSensorBlockEntity) return candidate;
        }
        return null;
    }

    static int tick(ModemBlockEntity modem) {
        BlockEntity target = resolveTarget(modem);
        if (target == null || modem.sensorServices().isEmpty()) return 0;
        int handled = 0;
        for (RednetNetwork.PendingMessage message : RednetNetwork.receiveProtocol(
                modem.getLevel(), modem.getModemId(), SensorRemoteRequest.PROTOCOL, MAX_REQUESTS_PER_TICK)) {
            if (!modem.hasSensorServiceOnPort(message.channel)) continue;
            SensorRemoteRequest request = SensorRemoteRequest.decode(message.message).orElse(null);
            if (request == null) continue;
            String response = response(target, request);
            if (response.length() > MAX_RESPONSE_CHARS) response = response.substring(0, MAX_RESPONSE_CHARS);
            if (message.replyChannel >= 0 && !message.senderId.isBlank()) {
                RednetNetwork.transmitTo(modem.getLevel(), modem.getModemId(), modem.getBlockPos(),
                        message.senderId, message.replyChannel, 0, response, modem.isWireless(), modem.getRange());
            }
            handled++;
        }
        return handled;
    }

    /** Handles the same bounded telemetry protocol directly on a wireless sensor endpoint. */
    static int tickWireless(Level level, UUID sensorId, BlockPos position, BlockEntity target) {
        if (level == null || level.isClientSide || sensorId == null || position == null || target == null) return 0;
        int handled = 0;
        for (RednetNetwork.PendingMessage message : RednetNetwork.receiveProtocol(
                level, sensorId, SensorRemoteRequest.PROTOCOL, MAX_REQUESTS_PER_TICK)) {
            if (message.channel != RednetAutoConfiguration.SENSOR_CHANNEL) continue;
            SensorRemoteRequest request = SensorRemoteRequest.decode(message.message).orElse(null);
            if (request == null) continue;
            String response = response(target, request);
            if (response.length() > MAX_RESPONSE_CHARS) response = response.substring(0, MAX_RESPONSE_CHARS);
            if (message.replyChannel >= 0 && !message.senderId.isBlank()) {
                RednetNetwork.transmitTo(level, sensorId, position, message.senderId,
                        message.replyChannel, 0, response, true, RednetAutoConfiguration.SENSOR_WIRELESS_RANGE);
            }
            handled++;
        }
        return handled;
    }

    private static String response(BlockEntity target, SensorRemoteRequest request) {
        return switch (request.operation()) {
            case LIST -> list(target);
            case SNAPSHOT -> snapshot(target);
            case READ -> read(target, request.channel());
        };
    }

    static String list(BlockEntity target) {
        if (target instanceof StandaloneSensorBlockEntity sensor) {
            SensorChannel channel = sensor.configuration();
            return "sensor|1|list|" + channel.name() + ":" + channel.kind().id() + ":"
                    + channel.metric() + ":" + (channel.enabled() ? "on" : "off");
        }
        List<String> channels = new ArrayList<>();
        ((SensorArrayBlockEntity) target).channels().forEach(channel -> channels.add(channel.name() + ":" + channel.kind().id()
                + ":" + channel.metric() + ":" + (channel.enabled() ? "on" : "off")));
        return "sensor|1|list|" + String.join(",", channels);
    }

    static String snapshot(BlockEntity target) {
        List<String> samples = new ArrayList<>();
        if (target instanceof SensorArrayBlockEntity array) array.readings().values().forEach(reading -> samples.add(format(reading)));
        if (target instanceof StandaloneSensorBlockEntity sensor && sensor.reading() != null) samples.add(format(sensor.reading()));
        return "sensor|1|snapshot|" + String.join(",", samples);
    }

    static String read(BlockEntity target, String requested) {
        SensorReading reading;
        if (target instanceof SensorArrayBlockEntity array) reading = array.reading(requested);
        else reading = "value".equalsIgnoreCase(requested) ? ((StandaloneSensorBlockEntity) target).reading() : null;
        return reading == null ? "sensor|1|read|" + requested + "|missing" : "sensor|1|read|" + format(reading);
    }

    static String format(SensorReading reading) {
        String value = reading.numeric() ? Double.toString(reading.numericValue()) : reading.textValue();
        return reading.channel() + "=" + value + " " + reading.unit()
                + "[" + reading.quality().id() + "]";
    }
}
