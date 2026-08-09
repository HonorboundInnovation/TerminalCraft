package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.block.StandaloneSensorBlock;
import com.malice.terminalcraft.device.DeviceValue;
import com.malice.terminalcraft.device.ServerDeviceManager;
import com.malice.terminalcraft.device.StandaloneSensorDeviceEndpoint;
import com.malice.terminalcraft.persistence.PersistedDataLimits;
import com.malice.terminalcraft.persistence.PersistedDataVersions;
import com.malice.terminalcraft.registry.ModRegistries;
import com.malice.terminalcraft.sensor.SensorChannel;
import com.malice.terminalcraft.sensor.SensorKind;
import com.malice.terminalcraft.sensor.SensorProbe;
import com.malice.terminalcraft.sensor.SensorReading;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persistent one-channel sensor. Its block family fixes the SensorKind. */
public class StandaloneSensorBlockEntity extends BlockEntity {
    public static final String CHANNEL = "value";

    private UUID deviceId = UUID.randomUUID();
    private String label = "sensor";
    private String metric;
    private String selector = "";
    private int interval = SensorChannel.MIN_INTERVAL;
    private double minimum;
    private double maximum;
    private boolean invert;
    private boolean enabled = true;
    private SensorReading reading;

    public StandaloneSensorBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistries.STANDALONE_SENSOR_BLOCK_ENTITY.get(), pos, state);
        SensorChannel defaults = SensorChannel.create(CHANNEL, sensorKind(), target(), "", "", interval, true);
        metric = defaults.metric();
        minimum = defaults.minimum();
        maximum = defaults.maximum();
        label = sensorKind().id() + "-sensor";
    }

    public UUID getDeviceId() { return deviceId; }

    public String getDeviceAddress() {
        String dimension = level == null ? "unbound" : level.dimension().location().toString();
        return dimension + ":" + worldPosition.getX() + "," + worldPosition.getY() + "," + worldPosition.getZ();
    }

    public String getLabel() { return label; }

    public void setLabel(String value) {
        label = PersistedDataLimits.truncate(value == null || value.isBlank() ? sensorKind().id() + "-sensor" : value.trim(),
                PersistedDataLimits.MAX_LABEL_CHARS);
        setChanged();
    }

    public SensorKind sensorKind() {
        return getBlockState().getBlock() instanceof StandaloneSensorBlock block ? block.kind() : SensorKind.BLOCK_STATE;
    }

    public String target() {
        return getBlockState().hasProperty(StandaloneSensorBlock.FACING)
                ? getBlockState().getValue(StandaloneSensorBlock.FACING).getName() : "north";
    }

    public SensorChannel configuration() {
        return new SensorChannel(CHANNEL, sensorKind(), target(), metric, selector, interval,
                minimum, maximum, invert, enabled);
    }

    public SensorReading reading() {
        if (reading == null && level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            reading = SensorProbe.read(serverLevel, worldPosition, configuration(), serverLevel.getGameTime());
        }
        return reading;
    }

    /** Returns a calibrated 0..15 value for PLC input, or -1 when disabled/unusable. */
    public int signal() {
        if (!enabled) return -1;
        SensorReading sample = reading();
        return sample == null ? -1 : sample.signal(configuration());
    }

    public boolean configure(String requestedMetric, String requestedSelector, int requestedInterval) {
        try {
            SensorChannel next = SensorChannel.create(CHANNEL, sensorKind(), target(), requestedMetric,
                    requestedSelector, requestedInterval, enabled);
            metric = next.metric();
            selector = next.selector();
            interval = next.interval();
            reading = null;
            setChanged();
            sync();
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public boolean setEnabled(boolean value) {
        enabled = value;
        setChanged();
        sync();
        return true;
    }

    public boolean calibrate(double min, double max, boolean reverse) {
        try {
            SensorChannel next = new SensorChannel(CHANNEL, sensorKind(), target(), metric, selector,
                    interval, min, max, reverse, enabled);
            minimum = next.minimum();
            maximum = next.maximum();
            invert = next.invert();
            setChanged();
            sync();
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public boolean setInterval(int ticks) {
        if (ticks < SensorChannel.MIN_INTERVAL || ticks > SensorChannel.MAX_INTERVAL) return false;
        interval = ticks;
        setChanged();
        sync();
        return true;
    }

    public List<String> summary() {
        SensorReading sample = reading;
        return List.of(
                "standalone-sensor " + label + " kind=" + sensorKind().id() + " target=" + target(),
                "value metric=" + metric + " " + (enabled ? "on" : "off")
                        + (sample == null ? " sample=(pending)" : " sample=" + display(sample)),
                "calibration=" + minimum + ".." + maximum + (invert ? " inverted" : "")
                        + " interval=" + interval + "t");
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, StandaloneSensorBlockEntity sensor) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) return;
        ServerDeviceManager.ensureRegistered(sensor, sensor.deviceId, sensor.getDeviceAddress(),
                () -> new StandaloneSensorDeviceEndpoint(sensor.deviceId, sensor.getDeviceAddress(), sensor,
                        () -> !sensor.isRemoved(), () -> !sensor.isRemoved()));
        long time = serverLevel.getGameTime();
        if (!sensor.enabled || time % sensor.interval != 0) return;
        SensorReading next = SensorProbe.read(serverLevel, pos, sensor.configuration(), time);
        SensorReading previous = sensor.reading;
        sensor.reading = next;
        if (!same(previous, next)) sensor.publishChange(next, time);
        sensor.setChanged();
    }

    private void publishChange(SensorReading sample, long time) {
        Map<String, DeviceValue> payload = new LinkedHashMap<>();
        payload.put("channel", DeviceValue.of(sample.channel()));
        payload.put("kind", DeviceValue.of(sample.kind().id()));
        payload.put("metric", DeviceValue.of(sample.metric()));
        payload.put("quality", DeviceValue.of(sample.quality().id()));
        payload.put("value", sample.numeric() ? DeviceValue.of(sample.numericValue()) : DeviceValue.of(sample.textValue()));
        payload.put("unit", DeviceValue.of(sample.unit()));
        payload.put("detail", DeviceValue.of(sample.detail()));
        ServerDeviceManager.publishEvent(this, "sensor_changed", time, new DeviceValue.MapValue(payload));
    }

    private static boolean same(SensorReading left, SensorReading right) {
        if (left == null || right == null) return left == right;
        return left.quality() == right.quality() && left.numeric() == right.numeric()
                && (left.numeric() ? Double.compare(left.numericValue(), right.numericValue()) == 0
                : left.textValue().equals(right.textValue()));
    }

    private static String display(SensorReading sample) {
        return sample.textValue() + (sample.unit().isEmpty() ? "" : sample.unit())
                + "[" + sample.quality().id() + "]";
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
        tag.putString("Metric", metric);
        tag.putString("Selector", selector);
        tag.putInt("Interval", interval);
        tag.putDouble("Minimum", minimum);
        tag.putDouble("Maximum", maximum);
        tag.putBoolean("Invert", invert);
        tag.putBoolean("Enabled", enabled);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.hasUUID("SensorId")) deviceId = tag.getUUID("SensorId");
        label = PersistedDataLimits.readString(tag, "Label", PersistedDataLimits.MAX_LABEL_CHARS, sensorKind().id() + "-sensor");
        SensorChannel defaults = SensorChannel.create(CHANNEL, sensorKind(), target(), tag.getString("Metric"),
                tag.getString("Selector"), tag.contains("Interval") ? tag.getInt("Interval") : 1, true);
        metric = defaults.metric();
        selector = defaults.selector();
        interval = defaults.interval();
        minimum = tag.contains("Minimum") ? tag.getDouble("Minimum") : defaults.minimum();
        maximum = tag.contains("Maximum") ? tag.getDouble("Maximum") : defaults.maximum();
        invert = tag.getBoolean("Invert");
        enabled = !tag.contains("Enabled") || tag.getBoolean("Enabled");
        reading = null;
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
    public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }

    @Override
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) {
        CompoundTag tag = packet.getTag();
        if (tag != null) load(tag);
    }
}
