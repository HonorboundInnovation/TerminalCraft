package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.device.DeviceValue;
import com.malice.terminalcraft.device.ServerDeviceManager;
import com.malice.terminalcraft.device.SensorArrayDeviceEndpoint;
import com.malice.terminalcraft.persistence.PersistedDataLimits;
import com.malice.terminalcraft.persistence.PersistedDataVersions;
import com.malice.terminalcraft.registry.ModRegistries;
import com.malice.terminalcraft.sensor.SensorChannel;
import com.malice.terminalcraft.sensor.SensorKind;
import com.malice.terminalcraft.sensor.SensorProbe;
import com.malice.terminalcraft.sensor.SensorQuality;
import com.malice.terminalcraft.sensor.SensorReading;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Persistent, server-authoritative universal sensor hub. */
public class SensorArrayBlockEntity extends BlockEntity {
    public static final int MAX_CHANNELS = SensorChannel.MAX_CHANNELS;
    private static final int MAX_SUMMARY_LINES = 32;

    private UUID deviceId = UUID.randomUUID();
    private String label = "sensor-array";
    private final List<SensorChannel> channels = new ArrayList<>();
    private final Map<String, SensorReading> readings = new LinkedHashMap<>();

    public SensorArrayBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistries.SENSOR_ARRAY_BLOCK_ENTITY.get(), pos, state);
    }

    public UUID getDeviceId() { return deviceId; }

    public String getDeviceAddress() {
        String dimension = level == null ? "unbound" : level.dimension().location().toString();
        return dimension + ":" + worldPosition.getX() + "," + worldPosition.getY() + "," + worldPosition.getZ();
    }

    public String getLabel() { return label; }

    public void setLabel(String value) {
        label = PersistedDataLimits.truncate(value == null || value.isBlank() ? "sensor-array" : value.trim(),
                PersistedDataLimits.MAX_LABEL_CHARS);
        setChanged();
    }

    public List<SensorChannel> channels() { return List.copyOf(channels); }
    public Map<String, SensorReading> readings() { return Map.copyOf(readings); }

    public SensorChannel channel(String requested) {
        String name = SensorChannel.canonicalName(requested);
        return channels.stream().filter(channel -> channel.name().equals(name)).findFirst().orElse(null);
    }

    public SensorReading reading(String requested) {
        SensorChannel channel = channel(requested);
        return channel == null ? null : readings.get(channel.name());
    }

    /** Returns a calibrated 0..15 value for PLC input, or -1 when the sample is unusable. */
    public int signal(String requested) {
        SensorChannel channel = channel(requested);
        SensorReading reading = channel == null ? null : readings.get(channel.name());
        if (reading == null && channel != null && level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            reading = SensorProbe.read(serverLevel, worldPosition, channel, serverLevel.getGameTime());
            readings.put(channel.name(), reading);
        }
        return reading == null ? -1 : reading.signal(channel);
    }

    public boolean configure(String requestedName, SensorKind kind, String target, String metric,
                             String selector, int interval) {
        if (kind == null) return false;
        SensorChannel next;
        try {
            next = SensorChannel.create(requestedName, kind, target, metric, selector, interval, true);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        int existing = indexOf(next.name());
        if (existing < 0 && channels.size() >= MAX_CHANNELS) return false;
        if (existing < 0) channels.add(next); else channels.set(existing, next);
        readings.remove(next.name());
        setChanged();
        sync();
        return true;
    }

    public boolean remove(String requestedName) {
        int index = indexOf(SensorChannel.canonicalName(requestedName));
        if (index < 0) return false;
        String name = channels.remove(index).name();
        readings.remove(name);
        setChanged();
        sync();
        return true;
    }

    public boolean setEnabled(String requestedName, boolean enabled) {
        int index = indexOf(SensorChannel.canonicalName(requestedName));
        if (index < 0) return false;
        channels.set(index, channels.get(index).withEnabled(enabled));
        setChanged();
        sync();
        return true;
    }

    public boolean calibrate(String requestedName, double minimum, double maximum, boolean invert) {
        int index = indexOf(SensorChannel.canonicalName(requestedName));
        if (index < 0) return false;
        try {
            channels.set(index, channels.get(index).withCalibration(minimum, maximum, invert));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        setChanged();
        sync();
        return true;
    }

    public boolean setInterval(String requestedName, int ticks) {
        int index = indexOf(SensorChannel.canonicalName(requestedName));
        if (index < 0 || ticks < SensorChannel.MIN_INTERVAL || ticks > SensorChannel.MAX_INTERVAL) return false;
        channels.set(index, channels.get(index).withInterval(ticks));
        setChanged();
        sync();
        return true;
    }

    public List<String> summary() {
        List<String> result = new ArrayList<>();
        result.add("sensor-array " + label + " channels=" + channels.size() + "/" + MAX_CHANNELS);
        for (SensorChannel channel : channels) {
            SensorReading reading = readings.get(channel.name());
            result.add(channel.name() + " " + channel.kind().id() + " target=" + channel.target()
                    + " metric=" + channel.metric() + " " + (channel.enabled() ? "on" : "off")
                    + (reading == null ? " sample=(pending)" : " sample=" + display(reading)));
            if (result.size() >= MAX_SUMMARY_LINES) break;
        }
        if (channels.isEmpty()) result.add("no channels configured");
        return List.copyOf(result);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, SensorArrayBlockEntity array) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) return;
        ServerDeviceManager.ensureRegistered(array, array.deviceId, array.getDeviceAddress(),
                () -> new SensorArrayDeviceEndpoint(array.deviceId, array.getDeviceAddress(), array,
                        () -> !array.isRemoved(), () -> !array.isRemoved()));
        long time = serverLevel.getGameTime();
        for (SensorChannel channel : array.channels) {
            if (!channel.enabled() || time % channel.interval() != 0) continue;
            SensorReading next = SensorProbe.read(serverLevel, pos, channel, time);
            SensorReading previous = array.readings.put(channel.name(), next);
            if (!same(previous, next)) array.publishChange(next, time);
        }
    }

    private void publishChange(SensorReading reading, long time) {
        Map<String, DeviceValue> payload = new LinkedHashMap<>();
        payload.put("channel", DeviceValue.of(reading.channel()));
        payload.put("kind", DeviceValue.of(reading.kind().id()));
        payload.put("metric", DeviceValue.of(reading.metric()));
        payload.put("quality", DeviceValue.of(reading.quality().id()));
        payload.put("value", reading.numeric() ? DeviceValue.of(reading.numericValue())
                : DeviceValue.of(reading.textValue()));
        payload.put("unit", DeviceValue.of(reading.unit()));
        payload.put("detail", DeviceValue.of(reading.detail()));
        ServerDeviceManager.publishEvent(this, "sensor_changed", time, new DeviceValue.MapValue(payload));
        setChanged();
    }

    private static boolean same(SensorReading left, SensorReading right) {
        if (left == null || right == null) return left == right;
        return left.quality() == right.quality() && left.numeric() == right.numeric()
                && (left.numeric() ? Double.compare(left.numericValue(), right.numericValue()) == 0
                : left.textValue().equals(right.textValue()));
    }

    private static String display(SensorReading reading) {
        String value = reading.numeric() ? reading.textValue() : reading.textValue();
        return value + (reading.unit().isEmpty() ? "" : reading.unit())
                + "[" + reading.quality().id() + "]";
    }

    private int indexOf(String name) {
        if (name == null || name.isBlank()) return -1;
        for (int i = 0; i < channels.size(); i++) if (channels.get(i).name().equals(name)) return i;
        return -1;
    }

    private void sync() {
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        PersistedDataVersions.stampCurrent(tag);
        tag.putUUID("SensorId", deviceId);
        tag.putString("Label", label);
        ListTag saved = new ListTag();
        for (SensorChannel channel : channels) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Name", channel.name());
            entry.putString("Kind", channel.kind().id());
            entry.putString("Target", channel.target());
            entry.putString("Metric", channel.metric());
            entry.putString("Selector", channel.selector());
            entry.putInt("Interval", channel.interval());
            entry.putDouble("Minimum", channel.minimum());
            entry.putDouble("Maximum", channel.maximum());
            entry.putBoolean("Invert", channel.invert());
            entry.putBoolean("Enabled", channel.enabled());
            saved.add(entry);
        }
        tag.put("Channels", saved);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.hasUUID("SensorId")) deviceId = tag.getUUID("SensorId");
        label = PersistedDataLimits.readString(tag, "Label", PersistedDataLimits.MAX_LABEL_CHARS, "sensor-array");
        channels.clear();
        readings.clear();
        if (!tag.contains("Channels", Tag.TAG_LIST)) return;
        ListTag saved = tag.getList("Channels", Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(saved.size(), MAX_CHANNELS); i++) {
            CompoundTag entry = saved.getCompound(i);
            try {
                SensorKind kind = SensorKind.parse(entry.getString("Kind"));
                if (kind == null) continue;
                SensorChannel channel = new SensorChannel(
                        entry.getString("Name"), kind, entry.getString("Target"), entry.getString("Metric"),
                        PersistedDataLimits.truncate(entry.getString("Selector"), SensorChannel.MAX_SELECTOR),
                        entry.getInt("Interval"), entry.getDouble("Minimum"), entry.getDouble("Maximum"),
                        entry.getBoolean("Invert"), !entry.contains("Enabled") || entry.getBoolean("Enabled"));
                if (indexOf(channel.name()) < 0) channels.add(channel);
            } catch (IllegalArgumentException ignored) {
                // A malformed channel is discarded while preserving all other channels.
            }
        }
    }

    @Override
    public void setRemoved() {
        ServerDeviceManager.invalidate(this);
        super.setRemoved();
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        saveAdditional(tag);
        return tag;
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) {
        CompoundTag tag = packet.getTag();
        if (tag != null) load(tag);
    }
}
