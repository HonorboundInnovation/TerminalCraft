package com.malice.terminalcraft.blockentity;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Bounded server-tick geometric screensaver for a complete connected monitor wall. */
public final class MonitorScreensaver {
    private static final long FRAME_INTERVAL_TICKS = 2;
    private static final int[] COLOR_PALETTE = {
            0x07111F, 0x102A43, 0x1B4965, 0x2EC4B6,
            0x4CC9F0, 0x4361EE, 0x7209B7, 0xF72585,
            0xFF595E, 0xFF924C, 0xFFCA3A, 0x8AC926,
            0x52B788, 0xC77DFF, 0xF1FAEE, 0x02040A
    };
    private static final Map<MinecraftServer, Map<MonitorBlockEntity, Animation>> ACTIVE = new WeakHashMap<>();

    private MonitorScreensaver() {}

    /** Handles the shell-facing start/stop/status operation for one adjacent wall. */
    public static String command(MonitorBlockEntity monitor, String action) {
        if (!(monitor.getLevel() instanceof ServerLevel level)) return "monitor screensaver unavailable";
        MinecraftServer server = level.getServer();
        MonitorGroupDevice.Group group = MonitorGroupDevice.discover(monitor);
        MonitorBlockEntity anchor = group.anchor();
        String normalized = action == null || action.isBlank() ? "start" : action.toLowerCase(java.util.Locale.ROOT);
        Map<MonitorBlockEntity, Animation> animations = ACTIVE.computeIfAbsent(server, ignored -> new java.util.IdentityHashMap<>());
        return switch (normalized) {
            case "start", "run", "on" -> {
                Animation animation = new Animation(anchor, false);
                animations.put(anchor, animation);
                animation.render(level.getGameTime());
                yield "geometric screensaver started on " + group.width() + "x" + group.height()
                        + " wall (" + (group.width() * MonitorBlockEntity.MAX_LINE_LEN) + "x"
                        + (group.height() * MonitorBlockEntity.MAX_LINES) + " cells)";
            }
            case "color", "fullcolor", "rainbow" -> {
                Animation animation = new Animation(anchor, true);
                animations.put(anchor, animation);
                animation.render(level.getGameTime());
                yield "full-color geometric screensaver started on " + group.width() + "x" + group.height()
                        + " wall (" + (group.width() * MonitorBlockEntity.MAX_LINE_LEN) + "x"
                        + (group.height() * MonitorBlockEntity.MAX_LINES) + " cells)";
            }
            case "stop", "off" -> animations.remove(anchor) == null
                    ? "geometric screensaver is not running"
                    : "geometric screensaver stopped";
            case "status" -> animations.containsKey(anchor)
                    ? "geometric " + (animations.get(anchor).color ? "full-color " : "") + "screensaver running"
                    : "geometric screensaver stopped";
            default -> "usage: monitor screensaver [start|color|stop|status] [side]";
        };
    }

    /** Advances all active screensavers once per logical server tick. */
    public static void tick(MinecraftServer server) {
        Map<MonitorBlockEntity, Animation> animations = ACTIVE.get(server);
        if (animations == null || animations.isEmpty()) return;
        List<Animation> rekeyed = new ArrayList<>();
        var iterator = animations.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<MonitorBlockEntity, Animation> entry = iterator.next();
            MonitorBlockEntity previousAnchor = entry.getKey();
            Animation animation = entry.getValue();
            if (!animation.tick(server)) {
                iterator.remove();
            } else if (animation.anchor() != previousAnchor) {
                iterator.remove();
                rekeyed.add(animation);
            }
        }
        for (Animation animation : rekeyed) animations.put(animation.anchor(), animation);
    }

    /** Clears transient animation state when the logical server stops. */
    public static void clear(MinecraftServer server) {
        ACTIVE.remove(server);
    }

    /** Pure bounded frame generator: every row is one global wall row. */
    static List<String> frame(int width, int height, long frame) {
        if (width <= 0 || height <= 0 || width > 320 || height > 120) {
            throw new IllegalArgumentException("screensaver dimensions are outside the bounded wall range");
        }
        char[][] canvas = new char[height][width];
        for (char[] row : canvas) java.util.Arrays.fill(row, ' ');
        double phase = frame * 0.19;
        double centerX = (width - 1) / 2.0;
        double centerY = (height - 1) / 2.0;
        double scaleX = Math.max(1.0, width / 2.0);
        double scaleY = Math.max(1.0, height / 2.0) * 1.65;
        double rotation = phase * 0.72;
        double cos = Math.cos(rotation);
        double sin = Math.sin(rotation);
        String glyphs = ".:-=+*#%@";

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double px = (x - centerX) / scaleX;
                double py = (y - centerY) / scaleY;
                double rx = px * cos - py * sin;
                double ry = px * sin + py * cos;
                double radius = Math.sqrt(rx * rx + ry * ry);
                double diamond = Math.abs(rx) + Math.abs(ry);
                double square = Math.max(Math.abs(rx), Math.abs(ry));
                double ring = 0.58 + 0.08 * Math.sin(phase + radius * 8.0);
                double diamondRing = 0.33 + 0.07 * Math.cos(phase * 1.4 - radius * 6.0);
                double pulse = Math.sin(phase * 1.8 + radius * 15.0 + Math.atan2(ry, rx) * 4.0);
                boolean ringHit = Math.abs(radius - ring) < 0.035;
                boolean diamondHit = Math.abs(diamond - diamondRing) < 0.035;
                boolean squareHit = Math.abs(square - (0.82 + 0.05 * Math.sin(phase * 0.8))) < 0.025;
                boolean spokeHit = radius < 0.88 && Math.abs(Math.sin(Math.atan2(ry, rx) * 6.0 + phase)) < 0.045;
                if (ringHit || diamondHit || squareHit || spokeHit) {
                    int intensity = Math.max(0, Math.min(glyphs.length() - 1,
                            (int) Math.floor((pulse + 1.0) * 0.5 * glyphs.length())));
                    canvas[y][x] = glyphs.charAt(intensity);
                }
            }
        }

        drawOrbit(canvas, centerX, centerY, scaleX * 0.72, scaleY * 0.42, phase, '@');
        drawOrbit(canvas, centerX, centerY, scaleX * 0.52, scaleY * 0.68, -phase * 1.3, '*');
        List<String> result = new ArrayList<>(height);
        for (char[] row : canvas) result.add(new String(row));
        return List.copyOf(result);
    }

    /** Pure bounded full-color frame: text and palette indexes share the complete global canvas. */
    static ColorFrame colorFrame(int width, int height, long frame) {
        if (width <= 0 || height <= 0 || width > 320 || height > 120) {
            throw new IllegalArgumentException("screensaver dimensions are outside the bounded wall range");
        }
        char[][] canvas = new char[height][width];
        char[][] foreground = new char[height][width];
        char[][] background = new char[height][width];
        double phase = frame * 0.19;
        double centerX = (width - 1) / 2.0;
        double centerY = (height - 1) / 2.0;
        double scaleX = Math.max(1.0, width / 2.0);
        double scaleY = Math.max(1.0, height / 2.0) * 1.65;
        double rotation = phase * 0.72;
        double cos = Math.cos(rotation);
        double sin = Math.sin(rotation);
        String glyphs = ".:-=+*#%@";

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                canvas[y][x] = ' ';
                double backgroundWave = Math.sin((x * 0.17) + (y * 0.31) + phase * 0.9);
                background[y][x] = (char) ('0' + Math.max(0, Math.min(3,
                        (int) Math.floor((backgroundWave + 1.0) * 1.5))));
                double px = (x - centerX) / scaleX;
                double py = (y - centerY) / scaleY;
                double rx = px * cos - py * sin;
                double ry = px * sin + py * cos;
                double radius = Math.sqrt(rx * rx + ry * ry);
                double angle = Math.atan2(ry, rx);
                double diamond = Math.abs(rx) + Math.abs(ry);
                double square = Math.max(Math.abs(rx), Math.abs(ry));
                double ring = 0.58 + 0.08 * Math.sin(phase + radius * 8.0);
                double diamondRing = 0.33 + 0.07 * Math.cos(phase * 1.4 - radius * 6.0);
                double pulse = Math.sin(phase * 1.8 + radius * 15.0 + angle * 4.0);
                boolean ringHit = Math.abs(radius - ring) < 0.035;
                boolean diamondHit = Math.abs(diamond - diamondRing) < 0.035;
                boolean squareHit = Math.abs(square - (0.82 + 0.05 * Math.sin(phase * 0.8))) < 0.025;
                boolean spokeHit = radius < 0.88 && Math.abs(Math.sin(angle * 6.0 + phase)) < 0.045;
                if (ringHit || diamondHit || squareHit || spokeHit) {
                    int intensity = Math.max(0, Math.min(glyphs.length() - 1,
                            (int) Math.floor((pulse + 1.0) * 0.5 * glyphs.length())));
                    int hue = Math.floorMod((int) Math.floor(
                            ((angle + Math.PI) / (Math.PI * 2.0)) * 12.0 + phase * 1.7 + radius * 5.0), 12) + 3;
                    canvas[y][x] = glyphs.charAt(intensity);
                    foreground[y][x] = (char) ('0' + hue);
                    background[y][x] = (char) ('0' + (hue % 4));
                }
            }
        }

        placeColorOrbit(canvas, foreground, background, centerX, centerY,
                scaleX * 0.72, scaleY * 0.42, phase, '@', 14, 1);
        placeColorOrbit(canvas, foreground, background, centerX, centerY,
                scaleX * 0.52, scaleY * 0.68, -phase * 1.3, '*', 7, 2);
        List<String> lines = new ArrayList<>(height);
        List<String> foregroundRows = new ArrayList<>(height);
        List<String> backgroundRows = new ArrayList<>(height);
        for (int row = 0; row < height; row++) {
            lines.add(new String(canvas[row]));
            foregroundRows.add(new String(foreground[row]));
            backgroundRows.add(new String(background[row]));
        }
        return new ColorFrame(lines, foregroundRows, backgroundRows);
    }

    private static void placeColorOrbit(char[][] canvas, char[][] foreground, char[][] background,
                                        double centerX, double centerY, double radiusX, double radiusY,
                                        double phase, char glyph, int foregroundColor, int backgroundColor) {
        int x = (int) Math.round(centerX + Math.cos(phase) * radiusX);
        int y = (int) Math.round(centerY + Math.sin(phase) * radiusY);
        if (y >= 0 && y < canvas.length && x >= 0 && x < canvas[0].length) {
            canvas[y][x] = glyph;
            foreground[y][x] = (char) ('0' + foregroundColor);
            background[y][x] = (char) ('0' + backgroundColor);
        }
    }

    record ColorFrame(List<String> lines, List<String> foreground, List<String> background) {
        ColorFrame {
            lines = List.copyOf(lines);
            foreground = List.copyOf(foreground);
            background = List.copyOf(background);
            if (lines.size() != foreground.size() || lines.size() != background.size()) {
                throw new IllegalArgumentException("colored frame rows must have matching lengths");
            }
            for (int row = 0; row < lines.size(); row++) {
                if (lines.get(row).length() != foreground.get(row).length()
                        || lines.get(row).length() != background.get(row).length()) {
                    throw new IllegalArgumentException("colored frame cell rows must have matching lengths");
                }
            }
        }

        int width() { return lines.isEmpty() ? 0 : lines.get(0).length(); }
        int height() { return lines.size(); }
    }

    private static void drawOrbit(char[][] canvas, double centerX, double centerY,
                                  double radiusX, double radiusY, double phase, char glyph) {
        int x = (int) Math.round(centerX + Math.cos(phase) * radiusX);
        int y = (int) Math.round(centerY + Math.sin(phase) * radiusY);
        if (y >= 0 && y < canvas.length && x >= 0 && x < canvas[0].length) canvas[y][x] = glyph;
    }

    private static final class Animation {
        private MonitorBlockEntity anchor;
        private final boolean color;
        private long nextTick = Long.MIN_VALUE;
        private long frame;

        private Animation(MonitorBlockEntity anchor, boolean color) {
            this.anchor = anchor;
            this.color = color;
        }

        private MonitorBlockEntity anchor() { return anchor; }

        private boolean tick(MinecraftServer server) {
            if (!(anchor.getLevel() instanceof ServerLevel level)
                    || level.getBlockEntity(anchor.getBlockPos()) != anchor || anchor.isRemoved()) return false;
            long now = level.getGameTime();
            if (now < nextTick) return true;
            MonitorGroupDevice.Group group = MonitorGroupDevice.discover(anchor);
            anchor = group.anchor();
            render(now);
            return true;
        }

        private void render(long now) {
            if (!(anchor.getLevel() instanceof ServerLevel level)) return;
            MonitorGroupDevice group = new MonitorGroupDevice(anchor);
            if (color) {
                group.renderColorFrame(colorFrame(frameWidth(group), frameHeight(group), frame++), COLOR_PALETTE);
            } else {
                group.renderFrame(frame(frameWidth(group), frameHeight(group), frame++));
            }
            nextTick = now + FRAME_INTERVAL_TICKS;
        }

        private static int frameWidth(MonitorGroupDevice group) { return group.maxLineLength(); }
        private static int frameHeight(MonitorGroupDevice group) { return group.maxLines(); }
    }
}
