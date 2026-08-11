package com.malice.terminalcraft.scada;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Pure advanced-HMI renderer shared by monitor walls, the terminal viewer and tests. */
public final class ScadaHmiRenderer {
    public static final List<Integer> PALETTE = List.of(
            0xE8F5EE, 0xF59E0B, 0xD946EF, 0x7DD3FC,
            0xFDE047, 0x4ADE80, 0xF9A8D4, 0x475569,
            0x94A3B8, 0x22D3EE, 0xA78BFA, 0x2563EB,
            0x92400E, 0x16A34A, 0xEF4444, 0x06110C);
    private static final char[] SPARKS = {'.', ':', '-', '=', '+', '*', '#', '@'};

    private static final int WHITE = 0;
    private static final int YELLOW = 4;
    private static final int GREEN = 5;
    private static final int GRAY = 8;
    private static final int CYAN = 9;
    private static final int BLUE = 11;
    private static final int DARK_GREEN = 13;
    private static final int RED = 14;
    private static final int BLACK = 15;

    private ScadaHmiRenderer() {}

    public static ScadaHmiFrame render(ScadaSavedData data, ScadaHmiDashboard dashboard,
                                       long gameTime, int width, int height) {
        return render(data, dashboard, gameTime, width, height, "");
    }

    public static ScadaHmiFrame render(ScadaSavedData data, ScadaHmiDashboard dashboard,
                                       long gameTime, int width, int height, String highlightedWidget) {
        if (data == null || dashboard == null) throw new IllegalArgumentException("HMI data and dashboard are required");
        if (width < 20 || width > ScadaHmiFrame.MAX_WIDTH || height < 6 || height > ScadaHmiFrame.MAX_HEIGHT) {
            throw new IllegalArgumentException("HMI frame must be 20..320 by 6..32 cells");
        }
        Canvas canvas = new Canvas(width, height);
        ScadaHmiPage page = dashboard.selectedPage();
        List<ScadaSavedData.AlarmView> active = data.alarms(ScadaSavedData.MAX_ENUMERATION, gameTime).stream()
                .filter(alarm -> alarm.state() != ScadaSavedData.AlarmState.NORMAL).toList();

        canvas.fill(0, 0, width, 1, ' ', WHITE, active.isEmpty() ? DARK_GREEN : RED);
        canvas.text(1, 0, fit(dashboard.title(), Math.max(1, width / 2)), BLACK,
                active.isEmpty() ? GREEN : RED);
        String status = "[" + page.name() + "] ALM " + active.size() + " T" + gameTime;
        canvas.text(Math.max(1, width - status.length() - 1), 0, status, BLACK,
                active.isEmpty() ? GREEN : RED);

        for (ScadaHmiWidget widget : page.widgets()) {
            renderWidget(canvas, data, dashboard, widget, gameTime,
                    widget.id().equalsIgnoreCase(highlightedWidget));
        }

        canvas.fill(0, height - 1, width, 1, ' ', GRAY, BLACK);
        String pages = dashboard.pages().stream().map(candidate -> candidate.name().equals(dashboard.activePage())
                ? "[" + candidate.name() + "]" : " " + candidate.name() + " ").reduce((a, b) -> a + " " + b).orElse("");
        canvas.text(0, height - 1, fit(pages, width), CYAN, BLACK);
        return canvas.frame();
    }

    public static ScadaHmiWidget widgetAt(ScadaHmiDashboard dashboard, int width, int height,
                                          int column, int row, boolean interactiveOnly) {
        if (dashboard == null || column < 0 || row < 0 || column >= width || row >= height) return null;
        List<ScadaHmiWidget> widgets = dashboard.selectedPage().widgets();
        for (int index = widgets.size() - 1; index >= 0; index--) {
            ScadaHmiWidget widget = widgets.get(index);
            Bounds bounds = bounds(widget, width, height);
            if (bounds.contains(column, row) && (!interactiveOnly || widget.type().interactive())) return widget;
        }
        return null;
    }

    public static Bounds bounds(ScadaHmiWidget widget, int width, int height) {
        if (widget == null || width < 1 || height < 3) throw new IllegalArgumentException("invalid HMI bounds request");
        int contentHeight = height - 2;
        int left = widget.x() * width / ScadaHmiWidget.GRID_WIDTH;
        int right = Math.max(left + 1, (widget.x() + widget.width()) * width / ScadaHmiWidget.GRID_WIDTH);
        int top = 1 + widget.y() * contentHeight / ScadaHmiWidget.GRID_HEIGHT;
        int bottom = Math.max(top + 1, 1 + (widget.y() + widget.height()) * contentHeight / ScadaHmiWidget.GRID_HEIGHT);
        return new Bounds(left, top, Math.min(width, right), Math.min(height - 1, bottom));
    }

    private static void renderWidget(Canvas canvas, ScadaSavedData data, ScadaHmiDashboard dashboard,
                                     ScadaHmiWidget widget, long gameTime, boolean highlighted) {
        Bounds box = bounds(widget, canvas.width, canvas.height);
        int color = highlighted ? CYAN : widget.type().interactive() ? GREEN : GRAY;
        if (widget.type() == ScadaHmiWidget.Type.TEXT) {
            canvas.text(box.left, box.top, fit(widget.label(), box.width()), WHITE, BLACK);
            return;
        }
        drawBox(canvas, box, color);
        if (box.width() > 2) canvas.text(box.left + 1, box.top,
                fit(widget.label(), box.width() - 2), color, BLACK);
        switch (widget.type()) {
            case VALUE -> renderValue(canvas, data, widget, box, gameTime);
            case GAUGE -> renderGauge(canvas, data, widget, box, gameTime);
            case TREND -> renderTrend(canvas, data, widget, box);
            case ALARMS -> renderAlarms(canvas, data, widget, box, gameTime);
            case BUTTON -> renderButton(canvas, data, widget, box, gameTime);
            case PAGE_LINK -> renderPageLink(canvas, dashboard, widget, box);
            case TEXT -> { }
        }
    }

    private static void renderValue(Canvas canvas, ScadaSavedData data, ScadaHmiWidget widget,
                                    Bounds box, long gameTime) {
        ScadaTag tag = data.tag(widget.source()).orElse(null);
        ScadaSnapshot snapshot = data.snapshot(widget.source(), gameTime).orElse(null);
        String value = snapshot == null || snapshot.value() == null ? "(pending)" : snapshot.value().display();
        if (tag != null && !tag.unit().isBlank()) value += " " + tag.unit();
        int color = qualityColor(snapshot == null ? null : snapshot.quality());
        canvas.center(box, Math.min(box.bottom - 1, box.top + Math.max(1, box.height() / 2)), value, color, BLACK);
        if (box.height() >= 4) canvas.center(box, box.bottom - 1,
                snapshot == null ? "pending" : snapshot.quality().id(), color, BLACK);
    }

    private static void renderGauge(Canvas canvas, ScadaSavedData data, ScadaHmiWidget widget,
                                    Bounds box, long gameTime) {
        ScadaSnapshot snapshot = data.snapshot(widget.source(), gameTime).orElse(null);
        Double measured = snapshot != null && snapshot.value() != null
                && snapshot.value().type() == ScadaScalar.Type.NUMBER ? snapshot.value().numberValue() : null;
        int percent = measured == null ? 0 : (int) Math.round((measured - widget.minimum())
                * 100.0 / (widget.maximum() - widget.minimum()));
        percent = Math.max(0, Math.min(100, percent));
        int row = Math.min(box.bottom - 1, box.top + Math.max(1, box.height() / 2));
        int usable = Math.max(1, box.width() - 4);
        int filled = (usable * percent + 50) / 100;
        String bar = "[" + "#".repeat(filled) + ".".repeat(usable - filled) + "]";
        canvas.center(box, row, bar, qualityColor(snapshot == null ? null : snapshot.quality()), BLACK);
        if (box.height() >= 4) canvas.center(box, box.bottom - 1,
                (measured == null ? "--" : formatNumber(measured)) + " / " + percent + "%",
                qualityColor(snapshot == null ? null : snapshot.quality()), BLACK);
    }

    private static void renderTrend(Canvas canvas, ScadaSavedData data, ScadaHmiWidget widget, Bounds box) {
        int width = Math.max(1, box.width() - 2);
        List<Double> values = data.history(widget.source(), Math.min(256, width)).stream()
                .filter(sample -> sample.value() != null && sample.value().type() == ScadaScalar.Type.NUMBER)
                .map(sample -> sample.value().numberValue()).toList();
        String trend;
        if (values.isEmpty()) trend = "(no numeric history)";
        else {
            double minimum = values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            double maximum = values.stream().mapToDouble(Double::doubleValue).max().orElse(minimum);
            double range = Math.max(0.000001, maximum - minimum);
            StringBuilder result = new StringBuilder();
            for (double value : values) {
                int index = Math.max(0, Math.min(SPARKS.length - 1,
                        (int) Math.round((value - minimum) * (SPARKS.length - 1) / range)));
                result.append(SPARKS[index]);
            }
            trend = result.toString();
            if (box.height() >= 4) canvas.text(box.left + 1, box.bottom - 1,
                    fit(formatNumber(minimum) + ".." + formatNumber(maximum), width), GRAY, BLACK);
        }
        canvas.text(box.left + 1, Math.min(box.bottom - 1, box.top + Math.max(1, box.height() / 2)),
                fit(trend, width), CYAN, BLACK);
    }

    private static void renderAlarms(Canvas canvas, ScadaSavedData data, ScadaHmiWidget widget,
                                     Bounds box, long gameTime) {
        List<ScadaSavedData.AlarmView> alarms = data.alarms(ScadaSavedData.MAX_ENUMERATION, gameTime).stream()
                .filter(alarm -> alarm.state() != ScadaSavedData.AlarmState.NORMAL)
                .filter(alarm -> hierarchyMatches(alarm.rule().tagName(), widget.source())).toList();
        int row = box.top + 1;
        if (alarms.isEmpty() && row < box.bottom) canvas.text(box.left + 1, row, "NO ACTIVE ALARMS", GREEN, BLACK);
        for (ScadaSavedData.AlarmView alarm : alarms) {
            if (row >= box.bottom) break;
            String marker = alarm.state() == ScadaSavedData.AlarmState.ACKNOWLEDGED ? "A" : "!";
            canvas.text(box.left + 1, row++, fit(marker + " " + alarm.rule().name(), Math.max(1, box.width() - 2)),
                    alarm.rule().severity() == ScadaAlarmRule.Severity.CRITICAL ? RED : YELLOW, BLACK);
        }
    }

    private static void renderButton(Canvas canvas, ScadaSavedData data, ScadaHmiWidget widget,
                                     Bounds box, long gameTime) {
        ScadaSnapshot snapshot = data.snapshot(widget.source(), gameTime).orElse(null);
        int background = snapshot != null && snapshot.quality() == ScadaQuality.GOOD ? DARK_GREEN : BLUE;
        if (box.width() > 2 && box.height() > 2) {
            canvas.fill(box.left + 1, box.top + 1, box.width() - 2, box.height() - 2, ' ', WHITE, background);
        }
        canvas.center(box, box.top + Math.max(1, box.height() / 2), widget.label(), WHITE, background);
    }

    private static void renderPageLink(Canvas canvas, ScadaHmiDashboard dashboard,
                                       ScadaHmiWidget widget, Bounds box) {
        int background = dashboard.activePage().equals(widget.source()) ? CYAN : BLUE;
        if (box.width() > 2 && box.height() > 2) {
            canvas.fill(box.left + 1, box.top + 1, box.width() - 2, box.height() - 2, ' ', WHITE, background);
        }
        canvas.center(box, box.top + Math.max(1, box.height() / 2), widget.label(), WHITE, background);
    }

    private static void drawBox(Canvas canvas, Bounds box, int color) {
        if (box.width() < 2 || box.height() < 2) return;
        for (int x = box.left; x < box.right; x++) {
            canvas.cell(x, box.top, x == box.left || x == box.right - 1 ? '+' : '-', color, BLACK);
            canvas.cell(x, box.bottom - 1, x == box.left || x == box.right - 1 ? '+' : '-', color, BLACK);
        }
        for (int y = box.top + 1; y < box.bottom - 1; y++) {
            canvas.cell(box.left, y, '|', color, BLACK);
            canvas.cell(box.right - 1, y, '|', color, BLACK);
        }
    }

    private static int qualityColor(ScadaQuality quality) {
        if (quality == null) return GRAY;
        return switch (quality) {
            case GOOD -> GREEN;
            case STALE -> YELLOW;
            case OFFLINE, ACCESS_DENIED, BAD_RESPONSE, CONFIG_ERROR -> RED;
        };
    }

    private static boolean hierarchyMatches(String tag, String prefix) {
        return prefix == null || prefix.isBlank() || tag.equals(prefix) || tag.startsWith(prefix + ".");
    }

    private static String formatNumber(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : String.format(Locale.ROOT, "%.2f", value);
    }

    private static String fit(String value, int width) {
        String safe = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
        return safe.length() <= width ? safe : safe.substring(0, Math.max(0, width));
    }

    public record Bounds(int left, int top, int right, int bottom) {
        public int width() { return Math.max(0, right - left); }
        public int height() { return Math.max(0, bottom - top); }
        public boolean contains(int x, int y) { return x >= left && x < right && y >= top && y < bottom; }
    }

    private static final class Canvas {
        private final int width;
        private final int height;
        private final char[][] text;
        private final char[][] foreground;
        private final char[][] background;

        private Canvas(int width, int height) {
            this.width = width;
            this.height = height;
            text = new char[height][width];
            foreground = new char[height][width];
            background = new char[height][width];
            for (int row = 0; row < height; row++) {
                Arrays.fill(text[row], ' ');
                Arrays.fill(foreground[row], hex(WHITE));
                Arrays.fill(background[row], hex(BLACK));
            }
        }

        private void cell(int x, int y, char value, int fg, int bg) {
            if (x < 0 || y < 0 || x >= width || y >= height) return;
            text[y][x] = value;
            foreground[y][x] = hex(fg);
            background[y][x] = hex(bg);
        }

        private void fill(int x, int y, int fillWidth, int fillHeight, char value, int fg, int bg) {
            for (int row = Math.max(0, y); row < Math.min(height, y + fillHeight); row++) {
                for (int column = Math.max(0, x); column < Math.min(width, x + fillWidth); column++) {
                    cell(column, row, value, fg, bg);
                }
            }
        }

        private void text(int x, int y, String value, int fg, int bg) {
            if (value == null) return;
            for (int index = 0; index < value.length() && x + index < width; index++) {
                cell(x + index, y, value.charAt(index), fg, bg);
            }
        }

        private void center(Bounds box, int row, String value, int fg, int bg) {
            String safe = fit(value, Math.max(1, box.width() - 2));
            int x = box.left + Math.max(1, (box.width() - safe.length()) / 2);
            text(x, row, safe, fg, bg);
        }

        private ScadaHmiFrame frame() {
            List<String> lines = new ArrayList<>(height);
            List<String> fg = new ArrayList<>(height);
            List<String> bg = new ArrayList<>(height);
            for (int row = 0; row < height; row++) {
                lines.add(new String(text[row]));
                fg.add(new String(foreground[row]));
                bg.add(new String(background[row]));
            }
            return new ScadaHmiFrame(lines, fg, bg, PALETTE);
        }

        private static char hex(int color) { return Character.forDigit(color & 15, 16); }
    }
}
