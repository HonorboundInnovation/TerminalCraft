package com.malice.terminalcraft.device;

import java.util.List;

/** Headless contract tests for bounded monitor widgets. */
public final class MonitorWidgetsTest {
    private MonitorWidgetsTest() {}

    public static void main(String[] args) {
        require(MonitorWidgets.bar("Load", 50, 10).contains("#####....."), "bar renders the midpoint");
        require(MonitorWidgets.bar("Load", 999, 100).length() < 64, "bar width is bounded");
        require(MonitorWidgets.led("Pump", true).startsWith("● ON"), "LED renders on state");
        require(MonitorWidgets.sparkline("Trend", List.of(0, 5, 10)).contains("▁"), "sparkline renders samples");
        System.out.println("Monitor widget tests: OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
