package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.device.TerminalBuffer;
import com.malice.terminalcraft.device.MonitorWidgets;
import com.malice.terminalcraft.plc.PlcProgram;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Colored, wall-sized PLC control surface rendered into an existing monitor canvas. */
final class PlcDashboard {
    private static final int[] PALETTE = {
            0xF2F7FF, 0x8BE9FD, 0x50FA7B, 0xF1FA8C,
            0xFFB86C, 0xFF79C6, 0xBD93F9, 0x6272A4,
            0x44475A, 0x6272A4, 0x8BE9FD, 0x50FA7B,
            0xF1FA8C, 0xFFB86C, 0xFF5555, 0x12141C
    };

    private PlcDashboard() {}

    static void render(ProgrammableLogicControllerBlockEntity plc, MonitorBlockEntity target) {
        MonitorGroupDevice.Group group = MonitorGroupDevice.discover(target);
        int width = group.width() * MonitorBlockEntity.MAX_LINE_LEN;
        int height = group.height() * MonitorBlockEntity.MAX_LINES;
        char[][] text = new char[height][width];
        char[][] foreground = new char[height][width];
        char[][] background = new char[height][width];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                text[row][column] = ' ';
                foreground[row][column] = digit(0);
                background[row][column] = digit(15);
            }
        }

        PlcProgram.Compiled program = plc.dashboardProgram();
        Map<String, Boolean> signals = plc.dashboardSignals();
        String state = !plc.compileError().isEmpty() ? "FAULT" : plc.isRunning() ? "RUNNING" : "STOPPED";
        int page = plc.dashboardPage();
        write(text, foreground, background, 0, 0,
                "PLC OPERATIONS // " + safe(plc.getLabel(), 24), 1, 8);
        write(text, foreground, background, 0, 1,
                "● " + state + "   SCAN " + plc.dashboardScanCount()
                        + "   CYCLE " + program.scanIntervalTicks() + "t  PAGE " + (page + 1) + "/4",
                stateColor(state), 15);

        button(text, foreground, background, 2, 2, "RUN", 3, state.equals("RUNNING") ? 2 : 0);
        button(text, foreground, background, 10, 2, "STOP", 14, state.equals("STOPPED") ? 2 : 0);
        button(text, foreground, background, 19, 2, "RESET", 13, 0);
        button(text, foreground, background, 29, 2, "<", 7, 0);
        button(text, foreground, background, 35, 2, ">", 7, 0);
        write(text, foreground, background, 0, 3,
                "────────────────────────────────────────",
                plc.alarmLatched() ? 14 : 7, 15);

        if (page == 1) {
            renderWatch(text, foreground, background, plc, width, height);
            commit(target, linesFor(text), linesFor(foreground), linesFor(background));
            return;
        }
        if (page == 2) {
            renderProgram(text, foreground, background, plc, width, height);
            commit(target, linesFor(text), linesFor(foreground), linesFor(background));
            return;
        }
        if (page == 3) {
            renderTrend(text, foreground, background, plc, width, height);
            commit(target, linesFor(text), linesFor(foreground), linesFor(background));
            return;
        }

        int row = 4;
        write(text, foreground, background, 0, row++, "DIGITAL INPUTS", 10, 15);
        for (PlcProgram.Binding input : program.inputs()) {
            if (row >= height) break;
            String value = signals.getOrDefault(input.name(), false) ? "ON " : "OFF";
            write(text, foreground, background, 1, row++, fit(input.name(), 16) + "  [" + value + "]",
                    signals.getOrDefault(input.name(), false) ? 2 : 8, 15);
        }

        if (row < height) row++;
        if (row < height) write(text, foreground, background, 0, row++, "DIGITAL OUTPUTS", 11, 15);
        for (PlcProgram.Binding output : program.outputs()) {
            if (row >= height) break;
            boolean value = signals.getOrDefault(output.name(), false);
            write(text, foreground, background, 1, row++, fit(output.name(), 16) + "  [" + (value ? "ON " : "OFF") + "]",
                    value ? 2 : 8, 15);
        }

        int programColumn = Math.min(width / 2, 24);
        int programRow = 4;
        if (programColumn + 12 < width) {
            write(text, foreground, background, programColumn, programRow++, "CONTROL LOGIC", 13, 15);
            List<String> lines = plc.programSource().isEmpty()
                    ? List.of("(empty program)") : plc.programSource().lines().toList();
            for (int index = 0; index < lines.size() && programRow < height; index++) {
                write(text, foreground, background, programColumn, programRow++,
                        String.format("%02d %s", index + 1, fit(lines.get(index), Math.max(1, width - programColumn - 1))),
                        0, 15);
            }
        }

        if (plc.alarmLatched() && height > 2) {
            write(text, foreground, background, 0, height - 2,
                    fit("ALARM LATCHED — ACKNOWLEDGE ON PLC OR DASHBOARD", width), 14, 15);
        }
        if (!plc.compileError().isEmpty() && height > 1) {
            write(text, foreground, background, 0, height - 1, fit("FAULT: " + plc.compileError(), width), 14, 15);
        } else if (!plc.controllerFault().isEmpty() && height > 1) {
            write(text, foreground, background, 0, height - 1, fit("FAULT: " + plc.controllerFault(), width), 14, 15);
        }

        commit(target, linesFor(text), linesFor(foreground), linesFor(background));
    }

    static boolean handleTouch(ProgrammableLogicControllerBlockEntity plc, int x, int y,
                              net.minecraft.world.entity.player.Player player) {
        if (y < 1 || y > 3) return false;
        if (x >= 28 && x < 34) { plc.previousDashboardPage(); return true; }
        if (x >= 34 && x < 40) { plc.nextDashboardPage(); return true; }
        if (!plc.canControl(player)) return false;
        if (x >= 1 && x < 9) { plc.start(); return true; }
        if (x >= 9 && x < 18) { plc.stop(); return true; }
        if (x >= 18 && x < 28) { plc.resetController(); return true; }
        return false;
    }

    private static void renderWatch(char[][] text, char[][] foreground, char[][] background,
                                    ProgrammableLogicControllerBlockEntity plc, int width, int height) {
        Map<String, Boolean> signals = plc.dashboardSignals();
        Map<String, Boolean> forcedInputs = plc.forcedInputs();
        Map<String, Integer> forcedOutputs = plc.forcedOutputs();
        int row = 4;
        write(text, foreground, background, 0, row++, "LIVE WATCH TABLE", 10, 15);
        write(text, foreground, background, 0, row++, "FORCE = operator override", 8, 15);
        for (PlcProgram.Binding input : plc.dashboardProgram().inputs()) {
            if (row >= height - 2) break;
            boolean value = signals.getOrDefault(input.name(), false);
            String marker = forcedInputs.containsKey(input.name()) ? " F" : "  ";
            write(text, foreground, background, 0, row++, fit(input.name(), 15) + " "
                    + (value ? "ON " : "OFF") + marker, value ? 2 : 8, 15);
        }
        for (PlcProgram.Binding output : plc.dashboardProgram().outputs()) {
            if (row >= height - 2) break;
            boolean value = signals.getOrDefault(output.name(), false);
            String marker = forcedOutputs.containsKey(output.name()) ? " F" : "  ";
            int strength = forcedOutputs.getOrDefault(output.name(), value ? 15 : 0);
            write(text, foreground, background, 0, row++, fit(output.name(), 15) + " "
                    + bar(strength, 8) + marker, value ? 2 : 8, 15);
        }
        int right = Math.min(width / 2, 22);
        int rightRow = 4;
        write(text, foreground, background, right, rightRow++, "RUNTIME", 13, 15);
        for (Map.Entry<String, Integer> timer : plc.dashboardTimerElapsed().entrySet()) {
            if (rightRow >= height - 2) break;
            write(text, foreground, background, right, rightRow++, fit(timer.getKey(), 14) + " " + timer.getValue() + "t", 0, 15);
        }
        for (Map.Entry<String, Integer> counter : plc.dashboardCounterValues().entrySet()) {
            if (rightRow >= height - 2) break;
            write(text, foreground, background, right, rightRow++, fit(counter.getKey(), 14) + " #" + counter.getValue(), 0, 15);
        }
        for (Map.Entry<String, Boolean> latch : plc.dashboardLatchValues().entrySet()) {
            if (rightRow >= height - 2) break;
            write(text, foreground, background, right, rightRow++, fit(latch.getKey(), 14) + " " + (latch.getValue() ? "SET" : "RESET"),
                    latch.getValue() ? 2 : 8, 15);
        }
        if (plc.alarmLatched() && height > 1) write(text, foreground, background, 0, height - 1,
                fit("ALARM: " + (plc.controllerFault().isEmpty() ? "fault history" : plc.controllerFault()), width), 14, 15);
    }

    private static void renderProgram(char[][] text, char[][] foreground, char[][] background,
                                      ProgrammableLogicControllerBlockEntity plc, int width, int height) {
        write(text, foreground, background, 0, 4, "PROGRAM SOURCE", 13, 15);
        List<String> source = plc.programSource().isEmpty() ? List.of("(empty program)") : plc.programSource().lines().toList();
        for (int index = 0; index < source.size() && index + 5 < height - 1; index++) {
            write(text, foreground, background, 0, index + 5,
                    String.format("%03d %s", index + 1, fit(source.get(index), Math.max(1, width - 5))), 0, 15);
        }
        if (plc.faultHistory().isEmpty()) write(text, foreground, background, width / 2, 4, "FAULT HISTORY: none", 2, 15);
        else {
            write(text, foreground, background, width / 2, 4, "FAULT HISTORY", 14, 15);
            int row = 5;
            for (String fault : plc.faultHistory()) {
                if (row >= height - 1) break;
                write(text, foreground, background, width / 2, row++, fit(fault, width / 2 - 1), 14, 15);
            }
        }
    }

    private static void renderTrend(char[][] text, char[][] foreground, char[][] background,
                                    ProgrammableLogicControllerBlockEntity plc, int width, int height) {
        write(text, foreground, background, 0, 4, "OSCILLOSCOPE / TREND HISTORY", 10, 15);
        write(text, foreground, background, 0, 5,
                "0..15 signal scale • last 64 scan samples", 8, 15);
        int row = 7;
        for (Map.Entry<String, List<Integer>> entry : plc.dashboardTrend().entrySet()) {
            if (row >= height - 1) break;
            String sparkline = MonitorWidgets.sparkline(entry.getKey(), entry.getValue());
            write(text, foreground, background, 0, row++, fit(sparkline, width), 0, 15);
        }
        if (row == 7) write(text, foreground, background, 0, row,
                "No samples yet — start the controller to capture a trend.", 8, 15);
    }

    private static String bar(int value, int width) {
        int filled = Math.max(0, Math.min(width, (value * width + 7) / 15));
        return "[" + "#".repeat(filled) + ".".repeat(width - filled) + "]";
    }

    private static List<String> linesFor(char[][] values) {
        List<String> lines = new ArrayList<>(values.length);
        for (char[] row : values) lines.add(new String(row));
        return lines;
    }

    private static void commit(MonitorBlockEntity target, List<String> lines, List<String> fg, List<String> bg) {
        new MonitorGroupDevice(target).renderColorFrame(new MonitorScreensaver.ColorFrame(lines, fg, bg), PALETTE);
    }

    private static void button(char[][] text, char[][] fg, char[][] bg, int x, int y,
                               String label, int color, int activeBackground) {
        write(text, fg, bg, x, y, "[ " + label + " ]", color, activeBackground == 0 ? 15 : activeBackground);
    }

    private static void write(char[][] text, char[][] fg, char[][] bg, int x, int y,
                              String value, int foreground, int background) {
        if (y < 0 || y >= text.length || x >= text[0].length) return;
        String safe = value == null ? "" : value;
        int start = Math.max(0, x);
        int offset = start - x;
        int length = Math.min(safe.length() - offset, text[0].length - start);
        if (length <= 0) return;
        for (int i = 0; i < length; i++) {
            text[y][start + i] = safe.charAt(offset + i);
            fg[y][start + i] = digit(foreground);
            bg[y][start + i] = digit(background);
        }
    }

    private static int stateColor(String state) { return "RUNNING".equals(state) ? 2 : "FAULT".equals(state) ? 14 : 3; }
    private static char digit(int value) { return (char) ('0' + Math.max(0, Math.min(15, value))); }
    private static String safe(String value, int max) { return fit(value == null ? "PLC" : value, max); }
    private static String fit(String value, int max) {
        String safe = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
        return safe.length() <= max ? safe : safe.substring(0, Math.max(0, max));
    }
}
