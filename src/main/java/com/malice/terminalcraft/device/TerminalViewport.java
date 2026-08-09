package com.malice.terminalcraft.device;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Bounded clipped view into a character-cell surface. Coordinates are zero-based here. */
public record TerminalViewport(int x, int y, int width, int height) {
    public static final int MAX_COORDINATE = 1024;
    public static final int MAX_CELLS = 4096;

    public TerminalViewport {
        if (x < 0 || y < 0 || width <= 0 || height <= 0
                || x > MAX_COORDINATE || y > MAX_COORDINATE
                || width > MAX_COORDINATE || height > MAX_COORDINATE
                || width * (long) height > MAX_CELLS) {
            throw new IllegalArgumentException("terminal viewport is outside bounded limits");
        }
    }

    /** Returns the visible rectangle after clipping against the current surface dimensions. */
    public Bounds clippedTo(TerminalBuffer surface) {
        Objects.requireNonNull(surface, "surface");
        int clippedWidth = Math.max(0, Math.min(width, surface.width() - x));
        int clippedHeight = Math.max(0, Math.min(height, surface.height() - y));
        return new Bounds(x, y, clippedWidth, clippedHeight);
    }

    /** Captures only visible cells, never allocating beyond the viewport bound. */
    public List<TerminalBuffer.CellDelta> cells(TerminalBuffer surface) {
        Bounds bounds = clippedTo(surface);
        List<TerminalBuffer.CellDelta> result = new ArrayList<>(bounds.width * bounds.height);
        for (int row = 0; row < bounds.height; row++) {
            for (int column = 0; column < bounds.width; column++) {
                int cellX = bounds.x + column;
                int cellY = bounds.y + row;
                result.add(new TerminalBuffer.CellDelta(cellX, cellY,
                        surface.characterAt(cellX, cellY),
                        surface.foregroundAt(cellX, cellY),
                        surface.backgroundAt(cellX, cellY)));
            }
        }
        return List.copyOf(result);
    }

    public boolean contains(int cellX, int cellY) {
        return cellX >= x && cellX < x + width && cellY >= y && cellY < y + height;
    }

    public record Bounds(int x, int y, int width, int height) {
        public Bounds {
            if (x < 0 || y < 0 || width < 0 || height < 0) {
                throw new IllegalArgumentException("clipped viewport bounds must not be negative");
            }
        }
    }
}
