package com.malice.terminalcraft.device;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Device API adapter exposing both the legacy line API and a ComputerCraft-style term surface. */
public final class MonitorDeviceEndpoint implements DeviceEndpoint {
    private static final DeviceParameterDescriptor ROW = number("row", "Zero-based screen row");
    private static final DeviceParameterDescriptor TEXT = string("text", "Text to write");
    private static final DeviceParameterDescriptor COLOR = number("color", "ComputerCraft single-color bit flag (1..32768)");
    private static final DeviceMethodDescriptor TITLE_GET = read("title.get", "Returns the monitor title", List.of(), DeviceValueType.STRING);
    private static final DeviceMethodDescriptor TITLE_SET = write("title.set", "Sets the monitor title", List.of(string("title", "New title")));
    private static final DeviceMethodDescriptor LINES_GET = read("lines.get", "Returns visible monitor rows", List.of(), DeviceValueType.LIST);
    private static final DeviceMethodDescriptor WRITE = write("write", "Appends one legacy line", List.of(TEXT));
    private static final DeviceMethodDescriptor LINE_SET = write("line.set", "Replaces one legacy row", List.of(ROW, TEXT));
    private static final DeviceMethodDescriptor PALETTE_GET = read("palette.get", "Returns legacy foreground and background RGB colors", List.of(), DeviceValueType.MAP);
    private static final DeviceMethodDescriptor PALETTE_SET = write("palette.set", "Sets legacy foreground and background RGB colors",
            List.of(number("foreground", "24-bit RGB foreground"), number("background", "24-bit RGB background")));
    private static final DeviceMethodDescriptor CLEAR = write("clear", "Clears all monitor rows", List.of());
    private static final DeviceMethodDescriptor TERM_DELTA = read("term.delta",
            "Returns a bounded revisioned character-cell delta", List.of(
                    number("since_revision", "Last acknowledged surface revision"),
                    number("max_cells", "Maximum number of cells to return"),
                    optionalNumber("offset", "Cell-page offset from the current delta")), DeviceValueType.MAP);
    private static final DeviceMethodDescriptor TERM_FRAME = write("term.frame",
            "Atomically applies bounded full-color rows to a monitor wall", List.of(
                    list("lines", "Full-width character rows"),
                    list("text_colors", "Full-width hexadecimal foreground rows"),
                    list("background_colors", "Full-width hexadecimal background rows"),
                    list("palette", "Sixteen 24-bit RGB palette entries")));

    private static final List<DeviceMethodDescriptor> TERM_METHODS = List.of(
            read("term.get_size", "Returns width and height", List.of(), DeviceValueType.MAP),
            read("term.get_cursor_pos", "Returns the one-based cursor position", List.of(), DeviceValueType.MAP),
            write("term.set_cursor_pos", "Sets the one-based cursor position", List.of(number("x", "Column"), number("y", "Row"))),
            read("term.get_cursor_blink", "Returns cursor blink state", List.of(), DeviceValueType.BOOLEAN),
            write("term.set_cursor_blink", "Sets cursor blink state", List.of(bool("blink", "Blink enabled"))),
            write("term.write", "Writes text at the cursor and advances it", List.of(TEXT)),
            write("term.blit", "Writes text with per-cell hexadecimal colors",
                    List.of(TEXT, string("text_colors", "Foreground digits 0-f"), string("background_colors", "Background digits 0-f"))),
            write("term.clear", "Clears the terminal", List.of()),
            write("term.clear_line", "Clears the cursor row", List.of()),
            write("term.scroll", "Scrolls content by signed rows", List.of(number("lines", "Signed row count"))),
            read("term.get_text_color", "Returns the foreground color index", List.of(), DeviceValueType.NUMBER),
            write("term.set_text_color", "Sets the foreground color index", List.of(COLOR)),
            read("term.get_background_color", "Returns the background color index", List.of(), DeviceValueType.NUMBER),
            write("term.set_background_color", "Sets the background color index", List.of(COLOR)),
            read("term.is_color", "Returns whether this is a color terminal", List.of(), DeviceValueType.BOOLEAN),
            read("term.get_palette_color", "Returns a palette entry as RGB and channels", List.of(COLOR), DeviceValueType.MAP),
            write("term.set_palette_color", "Sets a palette entry from 24-bit RGB", List.of(COLOR, number("rgb", "24-bit RGB"))),
            TERM_FRAME,
            TERM_DELTA,
            read("monitor.get_text_scale", "Returns monitor text scale", List.of(), DeviceValueType.NUMBER),
            write("monitor.set_text_scale", "Sets monitor text scale from 0.5 to 5", List.of(number("scale", "Half-step scale")))
    );

    private final UUID deviceId;
    private final String address;
    private final MonitorDevice monitor;
    private final BooleanSupplier online;
    private final BooleanSupplier loaded;
    private final int maxLines;
    private final int maxLineLength;
    private final TerminalBuffer terminal;
    private String lastTitle;
    private List<String> lastLines;
    /** Prevent synchronous output events from recursively re-importing a shared monitor surface. */
    private boolean importingSurface;

    public MonitorDeviceEndpoint(UUID deviceId, String address, MonitorDevice monitor,
                                 BooleanSupplier online, BooleanSupplier loaded) {
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
        this.address = requireAddress(address);
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        this.online = Objects.requireNonNull(online, "online");
        this.loaded = Objects.requireNonNull(loaded, "loaded");
        maxLines = requirePositiveBound(monitor.maxLines(), "max lines");
        maxLineLength = requireStringBound(monitor.maxLineLength(), "max line length");
        TerminalBuffer suppliedSurface = monitor.terminalSurface();
        terminal = suppliedSurface != null && suppliedSurface.width() == maxLineLength
                && suppliedSurface.height() == maxLines
                ? suppliedSurface : new TerminalBuffer(maxLineLength, maxLines);
        lastTitle = "Monitor";
        lastLines = List.of();
        if (suppliedSurface == null || !terminal.lines().equals(safeLines(monitor.lines()))) {
            importMonitorLines();
        }
        lastLines = safeLines(monitor.lines());
        refreshSnapshot();
    }

    @Override
    public synchronized DeviceDescriptor descriptor() {
        boolean currentlyLoaded = loaded.getAsBoolean();
        boolean currentlyOnline = online.getAsBoolean();
        if (currentlyLoaded && currentlyOnline) refreshSnapshot();
        Map<String, DeviceValue> properties = new LinkedHashMap<>();
        properties.put("title", DeviceValue.of(lastTitle));
        properties.put("line_count", DeviceValue.of(lastLines.size()));
        properties.put("rows", DeviceValue.of(maxLines));
        properties.put("columns", DeviceValue.of(maxLineLength));
        properties.put("max_lines", DeviceValue.of(maxLines));
        properties.put("max_line_length", DeviceValue.of(maxLineLength));
        properties.put("foreground", DeviceValue.of(monitor.foregroundColor()));
        properties.put("background", DeviceValue.of(monitor.backgroundColor()));
        properties.put("color", DeviceValue.of(true));
        properties.put("text_scale", DeviceValue.of(terminal.textScale()));
        properties.put("surface_revision", DeviceValue.of(terminal.revision()));
        List<DeviceMethodDescriptor> methods = new ArrayList<>(List.of(
                TITLE_GET, TITLE_SET, LINES_GET, WRITE, LINE_SET, PALETTE_GET, PALETTE_SET, CLEAR));
        methods.addAll(TERM_METHODS);
        return new DeviceDescriptor(deviceId, "terminalcraft:monitor", "monitor", lastTitle,
                "terminalcraft", address, Set.of("monitor_output", "monitor_ui", "computer_terminal"), properties,
                List.copyOf(methods), Set.of("output_changed", "touch", "monitor_resize"),
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE), currentlyOnline, currentlyLoaded);
    }

    @Override
    public synchronized DeviceResult call(String method, List<DeviceValue> arguments) {
        if (method == null || method.isBlank()) return failure("method is required");
        List<DeviceValue> args = arguments == null ? List.of() : arguments;
        return switch (method) {
            case "title.get" -> DeviceResult.success(DeviceValue.of(safeTitle(monitor.title())));
            case "title.set" -> setTitle(stringValue(args, 0));
            case "lines.get" -> DeviceResult.success(lineValues(monitor.lines()));
            case "write" -> appendLine(stringValue(args, 0));
            case "line.set" -> setLine(integer(args, 0, 0, maxLines - 1, "row"), stringValue(args, 1));
            case "palette.get" -> DeviceResult.success(DeviceValue.map(Map.of(
                    "foreground", DeviceValue.of(monitor.foregroundColor()), "background", DeviceValue.of(monitor.backgroundColor()))));
            case "palette.set" -> setLegacyPalette(args);
            case "clear" -> {
                monitor.clear();
                terminal.clear();
                lastLines = List.of();
                yield DeviceResult.success();
            }
            case "term.get_size" -> DeviceResult.success(DeviceValue.map(Map.of(
                    "width", DeviceValue.of(maxLineLength), "height", DeviceValue.of(maxLines))));
            case "term.get_cursor_pos" -> DeviceResult.success(DeviceValue.map(Map.of(
                    "x", DeviceValue.of(terminal.cursorX()), "y", DeviceValue.of(terminal.cursorY()))));
            case "term.set_cursor_pos" -> mutate(() -> terminal.setCursor(
                    integer(args, 0, Integer.MIN_VALUE, Integer.MAX_VALUE, "x"),
                    integer(args, 1, Integer.MIN_VALUE, Integer.MAX_VALUE, "y")), false);
            case "term.get_cursor_blink" -> DeviceResult.success(DeviceValue.of(terminal.cursorBlink()));
            case "term.set_cursor_blink" -> mutate(() -> terminal.setCursorBlink(boolValue(args, 0)), false);
            case "term.write" -> mutate(() -> terminal.write(stringValue(args, 0)), true);
            case "term.blit" -> mutate(() -> terminal.blit(stringValue(args, 0), stringValue(args, 1), stringValue(args, 2)), true);
            case "term.clear" -> mutate(terminal::clear, true);
            case "term.clear_line" -> mutate(terminal::clearLine, true);
            case "term.scroll" -> mutate(() -> terminal.scroll(integer(args, 0, -maxLines, maxLines, "lines")), true);
            case "term.get_text_color" -> DeviceResult.success(DeviceValue.of(1 << terminal.textColor()));
            case "term.set_text_color" -> mutate(() -> { terminal.setTextColor(colorIndex(args, 0)); syncActivePalette(); }, false);
            case "term.get_background_color" -> DeviceResult.success(DeviceValue.of(1 << terminal.backgroundColor()));
            case "term.set_background_color" -> mutate(() -> { terminal.setBackgroundColor(colorIndex(args, 0)); syncActivePalette(); }, false);
            case "term.is_color" -> DeviceResult.success(DeviceValue.of(true));
            case "term.get_palette_color" -> paletteColor(colorIndex(args, 0));
            case "term.set_palette_color" -> mutate(() -> {
                terminal.setPaletteColor(colorIndex(args, 0), integer(args, 1, 0, 0xFFFFFF, "rgb"));
                syncActivePalette();
            }, false);
            case "term.frame" -> applyFrame(args);
            case "term.delta" -> surfaceDelta(longValue(args, 0, "since_revision"),
                    integer(args, 1, 1, 128, "max_cells"),
                    args.size() > 2 ? integer(args, 2, 0, Integer.MAX_VALUE, "offset") : 0);
            case "monitor.get_text_scale" -> DeviceResult.success(DeviceValue.of(terminal.textScale()));
            case "monitor.set_text_scale" -> mutate(() -> terminal.setTextScale(numberValue(args, 0)), false);
            default -> DeviceResult.failure(DeviceErrorCode.UNSUPPORTED, "method is unsupported", false);
        };
    }

    private DeviceResult mutate(Runnable action, boolean syncText) {
        action.run();
        if (syncText) syncTerminalLines();
        return DeviceResult.success();
    }

    private DeviceResult setTitle(String value) {
        value = value.trim();
        if (value.isEmpty() || value.length() > 32) return failure("title must contain 1 to 32 characters");
        monitor.setTitle(value);
        lastTitle = value;
        return DeviceResult.success();
    }

    private DeviceResult appendLine(String text) {
        DeviceResult invalid = validateText(text);
        if (invalid != null) return invalid;
        monitor.writeLine(text);
        importMonitorLines();
        return DeviceResult.success();
    }

    private DeviceResult setLine(int row, String text) {
        DeviceResult invalid = validateText(text);
        if (invalid != null) return invalid;
        monitor.setLine(row, text);
        terminal.setLine(row, text);
        lastLines = safeLines(monitor.lines());
        return DeviceResult.success();
    }

    private DeviceResult setLegacyPalette(List<DeviceValue> args) {
        int foreground = integer(args, 0, 0, 0xFFFFFF, "foreground");
        int background = integer(args, 1, 0, 0xFFFFFF, "background");
        monitor.setPalette(foreground, background);
        terminal.setPaletteColor(terminal.textColor(), foreground);
        terminal.setPaletteColor(terminal.backgroundColor(), background);
        return DeviceResult.success();
    }

    private DeviceResult surfaceDelta(long sinceRevision, int maxCells, int offset) {
        TerminalBuffer.SurfaceDelta delta = terminal.deltaSince(sinceRevision, maxCells, offset);
        List<DeviceValue> cells = delta.cells().stream().map(cell -> DeviceValue.map(Map.of(
                "x", DeviceValue.of(cell.x()),
                "y", DeviceValue.of(cell.y()),
                "char", DeviceValue.of(String.valueOf(cell.character())),
                "foreground", DeviceValue.of(cell.foreground()),
                "background", DeviceValue.of(cell.background())))).toList();
        List<DeviceValue> colors = delta.palette().stream().map(DeviceValue::of).toList();
        return DeviceResult.success(DeviceValue.map(Map.ofEntries(
                Map.entry("from_revision", DeviceValue.of(delta.fromRevision())),
                Map.entry("to_revision", DeviceValue.of(delta.toRevision())),
                Map.entry("complete", DeviceValue.of(delta.complete())),
                Map.entry("cells", DeviceValue.list(cells)),
                Map.entry("cursor_x", DeviceValue.of(delta.cursorX())),
                Map.entry("cursor_y", DeviceValue.of(delta.cursorY())),
                Map.entry("cursor_blink", DeviceValue.of(delta.cursorBlink())),
                Map.entry("text_color", DeviceValue.of(1L << delta.textColor())),
                Map.entry("background_color", DeviceValue.of(1L << delta.backgroundColor())),
                Map.entry("text_scale", DeviceValue.of(delta.textScale())),
                Map.entry("palette", DeviceValue.list(colors)),
                Map.entry("next_offset", DeviceValue.of(delta.nextOffset())),
                Map.entry("total_cells", DeviceValue.of(delta.totalCells())))));
    }

    private DeviceResult applyFrame(List<DeviceValue> args) {
        if (args.size() != 4) return failure("term.frame requires lines, text colors, background colors, and palette");
        List<String> lines = stringList(args.get(0), maxLines, "lines");
        List<String> foreground = stringList(args.get(1), maxLines, "text colors");
        List<String> background = stringList(args.get(2), maxLines, "background colors");
        List<Integer> palette = integerList(args.get(3), TerminalBuffer.PALETTE_SIZE, 0, 0xFFFFFF, "palette");
        if (lines.size() != foreground.size() || lines.size() != background.size()) {
            return failure("term.frame row lists must have equal lengths");
        }
        for (int row = 0; row < lines.size(); row++) {
            if (lines.get(row).length() != maxLineLength || foreground.get(row).length() != maxLineLength
                    || background.get(row).length() != maxLineLength) {
                return failure("term.frame rows must match the monitor width " + maxLineLength);
            }
        }
        monitor.renderFrame(lines, foreground, background, palette);
        lastLines = safeLines(monitor.lines());
        return DeviceResult.success();
    }

    private DeviceResult paletteColor(int index) {
        int rgb = terminal.paletteColor(index);
        return DeviceResult.success(DeviceValue.map(Map.of(
                "rgb", DeviceValue.of(rgb),
                "r", DeviceValue.of(((rgb >> 16) & 255) / 255.0),
                "g", DeviceValue.of(((rgb >> 8) & 255) / 255.0),
                "b", DeviceValue.of((rgb & 255) / 255.0))));
    }

    private void syncActivePalette() {
        monitor.setPalette(terminal.paletteColor(terminal.textColor()), terminal.paletteColor(terminal.backgroundColor()));
    }

    private void syncTerminalLines() {
        for (int row = 0; row < maxLines; row++) {
            monitor.setLineFromSurface(row, stripRight(terminal.line(row + 1)));
        }
        lastLines = safeLines(monitor.lines());
    }

    private void importMonitorLines() {
        if (importingSurface) return;
        importingSurface = true;
        try {
            List<String> lines = safeLines(monitor.lines());
            terminal.clear();
            for (int row = 0; row < lines.size(); row++) terminal.setLine(row, lines.get(row));
            terminal.setCursor(1, 1);
            lastLines = lines;
        } finally {
            importingSurface = false;
        }
    }

    private void refreshSnapshot() {
        if (importingSurface) return;
        if (!loaded.getAsBoolean() || !online.getAsBoolean()) return;
        String currentTitle = safeTitle(monitor.title());
        List<String> currentLines = safeLines(monitor.lines());
        if (!currentLines.equals(lastLines)) {
            // A monitor with a supplied surface already exposes the authoritative cell state.
            // Its legacy line mirror can change when a screensaver or a colored blit updates the
            // surface, so importing the lines here would erase the per-cell colors with the
            // endpoint's current default palette. Only import when the surface truly disagrees.
            if (!terminal.lines().equals(currentLines)) importMonitorLines();
            currentLines = safeLines(monitor.lines());
        }
        lastTitle = currentTitle;
        lastLines = currentLines;
    }

    private DeviceResult validateText(String text) {
        if (text.length() > maxLineLength || text.contains("\n") || text.contains("\r"))
            return failure("line must be single-line and at most " + maxLineLength + " characters");
        return null;
    }

    private List<String> safeLines(List<String> lines) {
        List<String> source = Objects.requireNonNullElse(lines, List.of());
        if (source.size() > maxLines) throw new IllegalStateException("monitor advertised too many lines");
        List<String> copy = new ArrayList<>();
        for (String line : source) {
            String safe = Objects.requireNonNullElse(line, "");
            if (safe.length() > maxLineLength) throw new IllegalStateException("monitor advertised an oversized line");
            copy.add(safe);
        }
        return List.copyOf(copy);
    }

    private DeviceValue lineValues(List<String> lines) {
        return DeviceValue.list(safeLines(lines).stream().map(DeviceValue::of).toList());
    }

    private static String stringValue(List<DeviceValue> args, int index) { return ((DeviceValue.StringValue) args.get(index)).value(); }
    private static boolean boolValue(List<DeviceValue> args, int index) { return ((DeviceValue.BooleanValue) args.get(index)).value(); }
    private static double numberValue(List<DeviceValue> args, int index) { return ((DeviceValue.NumberValue) args.get(index)).value(); }
    private static long longValue(List<DeviceValue> args, int index, String name) {
        double raw = numberValue(args, index);
        if (!Double.isFinite(raw) || raw != Math.rint(raw) || raw < 0 || raw > Long.MAX_VALUE) {
            throw new IllegalArgumentException(name + " must be a non-negative integer");
        }
        return (long) raw;
    }
    private static int integer(List<DeviceValue> args, int index, int minimum, int maximum, String name) {
        double raw = numberValue(args, index);
        if (!Double.isFinite(raw) || raw != Math.rint(raw) || raw < minimum || raw > maximum)
            throw new IllegalArgumentException(name + " must be an integer from " + minimum + " to " + maximum);
        return (int) raw;
    }

    private static int colorIndex(List<DeviceValue> args, int index) {
        int flag = integer(args, index, 1, 32768, "color");
        if ((flag & (flag - 1)) != 0) throw new IllegalArgumentException("color must be a single ComputerCraft color flag");
        return Integer.numberOfTrailingZeros(flag);
    }

    private static List<String> stringList(DeviceValue value, int maximum, String name) {
        if (!(value instanceof DeviceValue.ListValue list) || list.values().size() > maximum) {
            throw new IllegalArgumentException(name + " list is outside bounds");
        }
        List<String> result = new ArrayList<>(list.values().size());
        for (DeviceValue entry : list.values()) {
            if (!(entry instanceof DeviceValue.StringValue text)) {
                throw new IllegalArgumentException(name + " must contain strings");
            }
            result.add(text.value());
        }
        return List.copyOf(result);
    }

    private static List<Integer> integerList(DeviceValue value, int exactSize,
                                             int minimum, int maximum, String name) {
        if (!(value instanceof DeviceValue.ListValue list) || list.values().size() != exactSize) {
            throw new IllegalArgumentException(name + " must contain exactly " + exactSize + " numbers");
        }
        List<Integer> result = new ArrayList<>(exactSize);
        for (DeviceValue entry : list.values()) {
            if (!(entry instanceof DeviceValue.NumberValue number) || number.value() != Math.rint(number.value())
                    || number.value() < minimum || number.value() > maximum) {
                throw new IllegalArgumentException(name + " contains an invalid number");
            }
            result.add((int) number.value());
        }
        return List.copyOf(result);
    }

    private static DeviceParameterDescriptor string(String name, String description) { return new DeviceParameterDescriptor(name, DeviceValueType.STRING, true, description); }
    private static DeviceParameterDescriptor number(String name, String description) { return new DeviceParameterDescriptor(name, DeviceValueType.NUMBER, true, description); }
    private static DeviceParameterDescriptor list(String name, String description) { return new DeviceParameterDescriptor(name, DeviceValueType.LIST, true, description); }
    private static DeviceParameterDescriptor optionalNumber(String name, String description) { return new DeviceParameterDescriptor(name, DeviceValueType.NUMBER, false, description); }
    private static DeviceParameterDescriptor bool(String name, String description) { return new DeviceParameterDescriptor(name, DeviceValueType.BOOLEAN, true, description); }
    private static DeviceMethodDescriptor read(String name, String description, List<DeviceParameterDescriptor> params, DeviceValueType type) { return new DeviceMethodDescriptor(name, description, params, type, DeviceCallContext.READ); }
    private static DeviceMethodDescriptor write(String name, String description, List<DeviceParameterDescriptor> params) { return new DeviceMethodDescriptor(name, description, params, DeviceValueType.NULL, DeviceCallContext.WRITE); }
    private static DeviceResult failure(String message) { return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT, message, false); }
    private static String safeTitle(String value) { return value == null || value.isBlank() ? "Monitor" : value.substring(0, Math.min(32, value.length())); }
    private static int requirePositiveBound(int value, String name) { if (value <= 0 || value > DeviceValue.MAX_COLLECTION_ENTRIES) throw new IllegalArgumentException(name + " must be positive and bounded"); return value; }
    private static int requireStringBound(int value, String name) { if (value <= 0 || value > DeviceValue.MAX_STRING_LENGTH) throw new IllegalArgumentException(name + " must be positive and bounded"); return value; }
    private static String requireAddress(String value) { Objects.requireNonNull(value, "address"); if (value.isBlank() || value.length() > DeviceValue.MAX_STRING_LENGTH) throw new IllegalArgumentException("address must be non-blank and bounded"); return value; }
    private static String stripRight(String value) { int end = value.length(); while (end > 0 && value.charAt(end - 1) == ' ') end--; return value.substring(0, end); }
}
