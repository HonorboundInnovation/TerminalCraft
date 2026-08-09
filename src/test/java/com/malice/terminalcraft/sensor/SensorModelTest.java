package com.malice.terminalcraft.sensor;

/** Headless tests for bounded sensor channel configuration and PLC normalization. */
public final class SensorModelTest {
    private SensorModelTest() {}

    public static void main(String[] args) {
        aliasesAndBoundsAreCanonical();
        dedicatedFamiliesHaveStableDefaults();
        numericReadingsNormalizeToPlcSignals();
        unavailableReadingsFailClosed();
        System.out.println("Sensor model tests: OK");
    }

    private static void aliasesAndBoundsAreCanonical() {
        require(SensorKind.parse("fe") == SensorKind.ENERGY, "FE alias resolves to energy");
        require(SensorKind.parse("create") == SensorKind.KINETIC, "Create alias resolves to kinetic");
        SensorChannel channel = SensorChannel.create("1", SensorKind.REDSTONE, "north", "level", "", 99, true);
        require("ch1".equals(channel.name()), "numeric channel names are stable");
        require(channel.interval() == SensorChannel.MAX_INTERVAL, "sample interval is bounded");
        require(SensorChannel.create("load", SensorKind.INVENTORY, "east", "fill_percent", "", 1, true)
                .selector().isEmpty(), "selector defaults to empty");
    }

    private static void numericReadingsNormalizeToPlcSignals() {
        SensorChannel channel = new SensorChannel("load", SensorKind.ENERGY, "east", "stored", "",
                1, 0, 100, false, true);
        require(SensorReading.numeric("load", SensorKind.ENERGY, "stored", 50, "fe", 1).signal(channel) == 8,
                "midpoint maps to a bounded PLC signal");
        SensorChannel inverted = channel.withCalibration(0, 100, true);
        require(SensorReading.numeric("load", SensorKind.ENERGY, "stored", 100, "fe", 1).signal(inverted) == 0,
                "inversion is applied after normalization");
    }

    private static void dedicatedFamiliesHaveStableDefaults() {
        for (SensorKind kind : SensorKind.values()) {
            SensorChannel channel = SensorChannel.create("value", kind, "north", "", "", 1, true);
            require("value".equals(channel.name()), "dedicated sensors use the value channel");
            require(channel.kind() == kind, "dedicated sensor family remains fixed");
            require(channel.maximum() > channel.minimum(), "dedicated sensor has a valid default calibration");
        }
    }

    private static void unavailableReadingsFailClosed() {
        SensorChannel channel = SensorChannel.create("active", SensorKind.MACHINE, "north", "active", "", 1, true);
        SensorReading unavailable = SensorReading.unavailable("active", SensorKind.MACHINE, "active",
                SensorQuality.UNSUPPORTED, "not exposed", 1);
        require(unavailable.signal(channel) < 0, "unsupported values do not become PLC signals");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
