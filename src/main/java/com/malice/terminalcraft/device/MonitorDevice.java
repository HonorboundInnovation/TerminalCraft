package com.malice.terminalcraft.device;

import java.util.List;

/** Bounded, scriptable text UI surface independent of Minecraft implementation types. */
public interface MonitorDevice {
    int maxLines();

    int maxLineLength();

    String title();

    void setTitle(String title);

    List<String> lines();

    void writeLine(String text);

    /** Replaces one zero-based screen row without scrolling the remaining rows. */
    void setLine(int row, String text);

    /** Optional persistent character-cell surface; null retains the legacy endpoint-local model. */
    default TerminalBuffer terminalSurface() { return null; }

    /** Updates legacy text from an authoritative character-cell surface without resetting cells. */
    default void setLineFromSurface(int row, String text) { setLine(row, text); }

    /** 24-bit RGB foreground color used by the world renderer. */
    int foregroundColor();

    /** 24-bit RGB background color used by the world renderer. */
    int backgroundColor();

    /** Changes the 24-bit RGB text palette atomically. */
    void setPalette(int foreground, int background);

    /**
     * Applies a complete bounded character-cell frame. Implementations may override this to batch
     * a monitor wall update into one synchronization event per tile.
     */
    default void renderFrame(List<String> lines, List<String> foreground,
                             List<String> background, List<Integer> palette) {
        if (lines == null || foreground == null || background == null || palette == null
                || lines.size() != foreground.size() || lines.size() != background.size()
                || lines.size() > maxLines() || palette.size() != TerminalBuffer.PALETTE_SIZE) {
            throw new IllegalArgumentException("invalid monitor frame dimensions");
        }
        TerminalBuffer surface = terminalSurface();
        if (surface == null) throw new IllegalArgumentException("monitor has no character-cell surface");
        for (int color = 0; color < palette.size(); color++) surface.setPaletteColor(color, palette.get(color));
        surface.clear();
        for (int row = 0; row < lines.size(); row++) {
            String text = lines.get(row);
            String fg = foreground.get(row);
            String bg = background.get(row);
            if (text.length() != fg.length() || text.length() != bg.length() || text.length() > maxLineLength()) {
                throw new IllegalArgumentException("invalid monitor frame row");
            }
            surface.setCursor(1, row + 1);
            surface.blit(text, fg, bg);
            setLineFromSurface(row, text);
        }
    }

    void clear();
}
