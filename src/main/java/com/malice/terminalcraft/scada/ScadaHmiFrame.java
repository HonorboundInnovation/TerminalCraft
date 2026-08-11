package com.malice.terminalcraft.scada;

import java.util.List;
import java.util.Objects;

/** Immutable full-color character frame suitable for a terminal or monitor wall. */
public record ScadaHmiFrame(List<String> lines, List<String> foreground,
                            List<String> background, List<Integer> palette) {
    public static final int MAX_WIDTH = 320;
    public static final int MAX_HEIGHT = 32;

    public ScadaHmiFrame {
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        foreground = List.copyOf(Objects.requireNonNull(foreground, "foreground"));
        background = List.copyOf(Objects.requireNonNull(background, "background"));
        palette = List.copyOf(Objects.requireNonNull(palette, "palette"));
        if (lines.isEmpty() || lines.size() > MAX_HEIGHT || lines.size() != foreground.size()
                || lines.size() != background.size()) throw new IllegalArgumentException("invalid HMI frame height");
        int width = lines.get(0).length();
        if (width < 1 || width > MAX_WIDTH) throw new IllegalArgumentException("invalid HMI frame width");
        for (int row = 0; row < lines.size(); row++) {
            if (lines.get(row).length() != width || foreground.get(row).length() != width
                    || background.get(row).length() != width) {
                throw new IllegalArgumentException("HMI frame rows must have equal cell widths");
            }
            requireColors(foreground.get(row));
            requireColors(background.get(row));
        }
        if (palette.size() != 16 || palette.stream().anyMatch(color -> color == null || color < 0 || color > 0xFFFFFF)) {
            throw new IllegalArgumentException("HMI frame palette must contain sixteen RGB colors");
        }
    }

    public int width() { return lines.get(0).length(); }
    public int height() { return lines.size(); }

    private static void requireColors(String colors) {
        for (int index = 0; index < colors.length(); index++) {
            if (Character.digit(colors.charAt(index), 16) < 0) {
                throw new IllegalArgumentException("HMI frame colors must use hexadecimal indexes");
            }
        }
    }
}
