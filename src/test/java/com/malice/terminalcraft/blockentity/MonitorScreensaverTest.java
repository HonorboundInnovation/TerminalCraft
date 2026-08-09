package com.malice.terminalcraft.blockentity;

import java.util.List;

/** Headless checks for the global geometric screensaver frame generator. */
public final class MonitorScreensaverTest {
    private MonitorScreensaverTest() {}

    public static void main(String[] args) {
        List<String> first = MonitorScreensaver.frame(80, 40, 0);
        List<String> second = MonitorScreensaver.frame(80, 40, 1);
        require(first.size() == 40, "frame height is global wall height");
        require(first.stream().allMatch(line -> line.length() == 80),
                "frame rows use global wall width");
        require(first.stream().anyMatch(line -> line.chars().anyMatch(character -> character != ' ')),
                "frame contains geometry");
        require(!first.equals(second), "animation advances between frames");

        MonitorScreensaver.ColorFrame color = MonitorScreensaver.colorFrame(80, 40, 0);
        MonitorScreensaver.ColorFrame nextColor = MonitorScreensaver.colorFrame(80, 40, 1);
        require(color.width() == 80 && color.height() == 40, "color frame keeps global wall bounds");
        require(color.lines().stream().anyMatch(line -> line.chars().anyMatch(character -> character != ' ')),
                "color frame contains geometry");
        require(color.foreground().stream().anyMatch(line -> line.chars().anyMatch(character -> character >= '3')),
                "color frame contains bright foreground indexes");
        require(color.background().stream().anyMatch(line -> line.chars().anyMatch(character -> character != '0')),
                "color frame contains animated background indexes");
        require(!color.lines().equals(nextColor.lines()) || !color.foreground().equals(nextColor.foreground()),
                "color animation advances between frames");
        System.out.println("Monitor screensaver tests: OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
