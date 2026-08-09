package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.device.TerminalBuffer;

import java.util.ArrayList;
import java.util.List;

/**
 * Bounded character-cell view over a connected monitor wall. Cell state is kept by the individual
 * monitor block entities, while this adapter supplies one global coordinate space to the device
 * endpoint and renderer-facing synchronization path.
 */
final class MonitorWallSurface extends TerminalBuffer {
    private static final int TILE_WIDTH = MonitorBlockEntity.MAX_LINE_LEN;
    private static final int TILE_HEIGHT = MonitorBlockEntity.MAX_LINES;

    private final MonitorGroupDevice.Group group;
    private final MonitorBlockEntity anchor;
    private int cursorX;
    private int cursorY;
    private boolean cursorBlink;
    private int textColor;
    private int backgroundColor;
    private double textScale;
    private final List<Integer> palette;

    MonitorWallSurface(MonitorGroupDevice.Group group) {
        super(group.width() * TILE_WIDTH, group.height() * TILE_HEIGHT);
        this.group = group;
        this.anchor = group.anchor();
        TerminalBuffer source = anchor.terminalSurface();
        cursorX = source.cursorX();
        cursorY = source.cursorY();
        cursorBlink = source.cursorBlink();
        textColor = source.textColor();
        backgroundColor = source.backgroundColor();
        textScale = source.textScale();
        palette = new ArrayList<>(source.palette());
    }

    @Override
    public long revision() {
        // A max() aggregation can hide a mutation on a lower-revision tile while another tile
        // retains the larger value. Summing the bounded per-tile revisions preserves monotonicity
        // for every tile mutation and keeps the wall delta cursor authoritative across the wall.
        long revision = 0;
        for (MonitorBlockEntity tile : group.tiles()) {
            long tileRevision = tile.terminalSurface().revision();
            if (Long.MAX_VALUE - revision < tileRevision) return Long.MAX_VALUE;
            revision += tileRevision;
        }
        return revision;
    }

    @Override public int cursorX() { return cursorX; }
    @Override public int cursorY() { return cursorY; }
    @Override public boolean cursorBlink() { return cursorBlink; }
    @Override public int textColor() { return textColor; }
    @Override public int backgroundColor() { return backgroundColor; }
    @Override public double textScale() { return textScale; }
    @Override public List<Integer> palette() { return List.copyOf(palette); }

    @Override
    public char characterAt(int x, int y) {
        TerminalBuffer tile = tileSurface(x, y);
        return tile == null ? ' ' : tile.characterAt(x % TILE_WIDTH, y % TILE_HEIGHT);
    }

    @Override
    public int foregroundAt(int x, int y) {
        TerminalBuffer tile = tileSurface(x, y);
        return tile == null ? textColor : tile.foregroundAt(x % TILE_WIDTH, y % TILE_HEIGHT);
    }

    @Override
    public int backgroundAt(int x, int y) {
        TerminalBuffer tile = tileSurface(x, y);
        return tile == null ? backgroundColor : tile.backgroundAt(x % TILE_WIDTH, y % TILE_HEIGHT);
    }

    @Override
    public void setCell(int x, int y, char character, int foreground, int background) {
        TerminalBuffer tile = tileSurface(x, y);
        if (tile != null) tile.setCell(x % TILE_WIDTH, y % TILE_HEIGHT, character, foreground, background);
    }

    @Override
    public List<String> lines() {
        List<String> result = new ArrayList<>(height());
        for (int y = 0; y < height(); y++) result.add(stripTrailing(line(y + 1)));
        while (!result.isEmpty() && result.get(result.size() - 1).isEmpty()) result.remove(result.size() - 1);
        return List.copyOf(result);
    }

    @Override
    public String line(int oneBasedRow) {
        if (oneBasedRow < 1 || oneBasedRow > height()) throw new IllegalArgumentException("row is outside the terminal");
        StringBuilder result = new StringBuilder(width());
        for (int x = 0; x < width(); x++) result.append(characterAt(x, oneBasedRow - 1));
        return result.toString();
    }

    @Override
    public String foregroundLine(int oneBasedRow) {
        if (oneBasedRow < 1 || oneBasedRow > height()) throw new IllegalArgumentException("row is outside the terminal");
        StringBuilder result = new StringBuilder(width());
        for (int x = 0; x < width(); x++) result.append(Integer.toHexString(foregroundAt(x, oneBasedRow - 1)));
        return result.toString();
    }

    @Override
    public String backgroundLine(int oneBasedRow) {
        if (oneBasedRow < 1 || oneBasedRow > height()) throw new IllegalArgumentException("row is outside the terminal");
        StringBuilder result = new StringBuilder(width());
        for (int x = 0; x < width(); x++) result.append(Integer.toHexString(backgroundAt(x, oneBasedRow - 1)));
        return result.toString();
    }

    @Override
    public void setCursor(int x, int y) {
        cursorX = x;
        cursorY = y;
        anchor.terminalSurface().setCursor(x, y);
    }

    @Override
    public void setCursorBlink(boolean blink) {
        cursorBlink = blink;
        anchor.terminalSurface().setCursorBlink(blink);
    }

    @Override
    public void setTextColor(int color) {
        textColor = requireColorIndex(color);
        anchor.terminalSurface().setTextColor(textColor);
    }

    @Override
    public void setBackgroundColor(int color) {
        backgroundColor = requireColorIndex(color);
        anchor.terminalSurface().setBackgroundColor(backgroundColor);
    }

    @Override
    public void setTextScale(double scale) {
        if (!Double.isFinite(scale) || scale < 0.5 || scale > 5.0 || scale * 2 != Math.rint(scale * 2)) {
            throw new IllegalArgumentException("text scale must be from 0.5 to 5.0 in increments of 0.5");
        }
        textScale = scale;
        anchor.terminalSurface().setTextScale(scale);
    }

    @Override
    public int paletteColor(int color) {
        return palette.get(requireColorIndex(color));
    }

    @Override
    public void setPaletteColor(int color, int rgb) {
        if (rgb < 0 || rgb > 0xFFFFFF) throw new IllegalArgumentException("RGB color is outside 24-bit range");
        int index = requireColorIndex(color);
        palette.set(index, rgb);
        anchor.terminalSurface().setPaletteColor(index, rgb);
    }

    @Override
    public void setLine(int row, String value) {
        if (row < 0 || row >= height()) throw new IllegalArgumentException("row is outside the terminal");
        String safe = value == null ? "" : value;
        if (safe.length() > width()) throw new IllegalArgumentException("line exceeds terminal width");
        for (int x = 0; x < width(); x++) {
            setCell(x, row, x < safe.length() ? safe.charAt(x) : ' ', textColor, backgroundColor);
        }
    }

    @Override
    public void clear() {
        for (MonitorBlockEntity tile : group.tiles()) tile.clear();
    }

    @Override
    public void clearLine() {
        int row = cursorY - 1;
        if (row < 0 || row >= height()) return;
        for (int x = 0; x < width(); x++) setCell(x, row, ' ', textColor, backgroundColor);
    }

    @Override
    public void scroll(int lines) {
        if (lines == 0) return;
        char[][] text = new char[height()][width()];
        int[][] foreground = new int[height()][width()];
        int[][] background = new int[height()][width()];
        for (int y = 0; y < height(); y++) {
            for (int x = 0; x < width(); x++) {
                text[y][x] = characterAt(x, y);
                foreground[y][x] = foregroundAt(x, y);
                background[y][x] = backgroundAt(x, y);
            }
        }
        for (int y = 0; y < height(); y++) {
            int sourceY = y + lines;
            for (int x = 0; x < width(); x++) {
                if (sourceY >= 0 && sourceY < height()) {
                    setCell(x, y, text[sourceY][x], foreground[sourceY][x], background[sourceY][x]);
                } else {
                    setCell(x, y, ' ', textColor, backgroundColor);
                }
            }
        }
    }

    @Override
    public void blit(String value, String textColors, String backgroundColors) {
        String safe = value == null ? "" : value;
        if (textColors == null || backgroundColors == null || safe.length() != textColors.length()
                || safe.length() != backgroundColors.length()) {
            throw new IllegalArgumentException("text and color strings must have equal lengths");
        }
        int[] foreground = new int[safe.length()];
        int[] background = new int[safe.length()];
        for (int i = 0; i < safe.length(); i++) {
            foreground[i] = parseColor(textColors.charAt(i));
            background[i] = parseColor(backgroundColors.charAt(i));
        }
        int row = cursorY - 1;
        for (int i = 0; i < safe.length(); i++) {
            int column = cursorX - 1 + i;
            if (column >= 0 && column < width() && row >= 0 && row < height()) {
                setCell(column, row, safe.charAt(i), foreground[i], background[i]);
            }
        }
        cursorX += safe.length();
        anchor.terminalSurface().setCursor(cursorX, cursorY);
    }

    @Override
    public SurfaceDelta deltaSince(long sinceRevision, int maxCells) {
        return deltaSince(sinceRevision, maxCells, 0);
    }

    @Override
    public SurfaceDelta deltaSince(long sinceRevision, int maxCells, int offset) {
        long currentRevision = revision();
        if (sinceRevision < 0 || sinceRevision > currentRevision) {
            throw new IllegalArgumentException("surface revision is outside the current history");
        }
        if (maxCells <= 0 || maxCells > 1_048_576 || offset < 0) {
            throw new IllegalArgumentException("surface delta bounds are invalid");
        }
        int total = sinceRevision < currentRevision ? width() * height() : 0;
        List<CellDelta> cells = new ArrayList<>();
        if (total > 0) {
            for (int y = 0; y < height(); y++) {
                for (int x = 0; x < width(); x++) {
                    int index = y * width() + x;
                    if (index >= offset && cells.size() < maxCells) {
                        cells.add(new CellDelta(x, y, characterAt(x, y), foregroundAt(x, y), backgroundAt(x, y)));
                    }
                }
            }
        }
        boolean complete = offset + cells.size() >= total;
        return new SurfaceDelta(sinceRevision, complete ? currentRevision : sinceRevision, complete, cells,
                cursorX, cursorY, cursorBlink, textColor, backgroundColor, textScale, palette(),
                complete ? 0 : offset + cells.size(), total);
    }

    private TerminalBuffer tileSurface(int x, int y) {
        if (x < 0 || x >= width() || y < 0 || y >= height()) {
            throw new IllegalArgumentException("cell is outside the terminal surface");
        }
        MonitorBlockEntity tile = group.at(x / TILE_WIDTH, y / TILE_HEIGHT);
        return tile == null ? null : tile.terminalSurface();
    }

    private static int requireColorIndex(int color) {
        if (color < 0 || color >= PALETTE_SIZE) throw new IllegalArgumentException("color index must be from 0 to 15");
        return color;
    }

    private static int parseColor(char value) {
        int color = Character.digit(value, 16);
        if (color < 0) throw new IllegalArgumentException("blit colors must use hexadecimal digits 0-f");
        return color;
    }

    private static String stripTrailing(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == ' ') end--;
        return value.substring(0, end);
    }
}
