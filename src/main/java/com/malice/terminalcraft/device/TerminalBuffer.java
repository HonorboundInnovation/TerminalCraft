package com.malice.terminalcraft.device;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Headless ComputerCraft-compatible character-cell terminal state.
 * Coordinates exposed by this class are one-based, matching the ComputerCraft term API.
 */
public class TerminalBuffer {
    public static final int PALETTE_SIZE = 16;
    private static final int[] DEFAULT_PALETTE = {
            0xF0F0F0, 0xF2B233, 0xE57FD8, 0x99B2F2,
            0xDEDE6C, 0x7FCC19, 0xF2B2CC, 0x4C4C4C,
            0x999999, 0x4C99B2, 0xB266E5, 0x3366CC,
            0x7F664C, 0x57A64E, 0xCC4C4C, 0x111111
    };

    private final int width;
    private final int height;
    private final char[][] text;
    private final byte[][] foreground;
    private final byte[][] background;
    private final long[][] cellRevision;
    private final int[] palette = DEFAULT_PALETTE.clone();
    private int cursorX = 1;
    private int cursorY = 1;
    private int textColor;
    private int backgroundColor = 15;
    private boolean cursorBlink;
    private double textScale = 1.0;
    private long revision;

    public TerminalBuffer(int width, int height) {
        if (width <= 0 || height <= 0 || width * (long) height > 1_048_576L) {
            throw new IllegalArgumentException("terminal dimensions must be positive and bounded");
        }
        this.width = width;
        this.height = height;
        text = new char[height][width];
        foreground = new byte[height][width];
        background = new byte[height][width];
        cellRevision = new long[height][width];
        long initialRevision = nextRevision();
        for (int row = 0; row < height; row++) {
            fillRow(row);
            Arrays.fill(cellRevision[row], initialRevision);
        }
    }

    public int width() { return width; }
    public int height() { return height; }
    public int cursorX() { return cursorX; }
    public int cursorY() { return cursorY; }
    public boolean cursorBlink() { return cursorBlink; }
    public int textColor() { return textColor; }
    public int backgroundColor() { return backgroundColor; }
    public double textScale() { return textScale; }
    /** Monotonically increasing server-side surface revision. Revision zero is the empty history. */
    public long revision() { return revision; }

    /** Returns the current 16-entry RGB palette in stable color-index order. */
    public List<Integer> palette() {
        List<Integer> result = new ArrayList<>(PALETTE_SIZE);
        for (int color : palette) result.add(color);
        return List.copyOf(result);
    }

    public char characterAt(int x, int y) {
        requireCell(x, y);
        return text[y][x];
    }

    public int foregroundAt(int x, int y) {
        requireCell(x, y);
        return foreground[y][x] & 15;
    }

    public int backgroundAt(int x, int y) {
        requireCell(x, y);
        return background[y][x] & 15;
    }

    /** Applies one bounded cell mutation for composite surfaces such as monitor walls. */
    public void setCell(int x, int y, char character, int foregroundColor, int backgroundColor) {
        requireCell(x, y);
        if (foregroundColor < 0 || foregroundColor >= PALETTE_SIZE
                || backgroundColor < 0 || backgroundColor >= PALETTE_SIZE) {
            throw new IllegalArgumentException("cell colors must be from 0 to 15");
        }
        long mutationRevision = nextRevision();
        text[y][x] = character;
        foreground[y][x] = (byte) foregroundColor;
        background[y][x] = (byte) backgroundColor;
        cellRevision[y][x] = mutationRevision;
    }

    /** A bounded immutable character-cell update used by passive display clients. */
    public record CellDelta(int x, int y, char character, int foreground, int background) {}

    /**
     * A revisioned surface update. When {@code complete} is false, the caller must not advance
     * its acknowledged revision and should retry with a larger cell budget or request a full
     * snapshot. The metadata always describes the current surface, while cells are only advanced
     * when the delta is complete.
     */
    public record SurfaceDelta(long fromRevision, long toRevision, boolean complete,
                               List<CellDelta> cells, int cursorX, int cursorY,
                               boolean cursorBlink, int textColor, int backgroundColor,
                               double textScale, List<Integer> palette,
                               int nextOffset, int totalCells) {
        public SurfaceDelta {
            cells = List.copyOf(cells);
            palette = List.copyOf(palette);
        }
    }

    /**
     * Returns all current cells changed after {@code sinceRevision}, bounded by {@code maxCells}.
     * A cell is represented once at its latest value, so a client can safely skip intermediate
     * revisions while still converging on the current authoritative surface.
     */
    public SurfaceDelta deltaSince(long sinceRevision, int maxCells) {
        return deltaSince(sinceRevision, maxCells, 0);
    }

    /** Returns one bounded page of a revisioned delta. A non-zero offset continues an incomplete page. */
    public SurfaceDelta deltaSince(long sinceRevision, int maxCells, int offset) {
        if (sinceRevision < 0 || sinceRevision > revision) {
            throw new IllegalArgumentException("surface revision is outside the current history");
        }
        if (maxCells <= 0 || maxCells > 1_048_576) {
            throw new IllegalArgumentException("surface cell budget is outside the bounded range");
        }
        if (offset < 0) throw new IllegalArgumentException("surface delta offset must not be negative");
        List<CellDelta> cells = new ArrayList<>();
        int changedCells = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (cellRevision[y][x] <= sinceRevision) continue;
                if (changedCells >= offset && cells.size() < maxCells) {
                    cells.add(new CellDelta(x, y, text[y][x], foreground[y][x] & 15, background[y][x] & 15));
                }
                changedCells++;
            }
        }
        boolean complete = offset + cells.size() >= changedCells;
        return new SurfaceDelta(sinceRevision, complete ? revision : sinceRevision, complete, cells,
                cursorX, cursorY, cursorBlink, textColor, backgroundColor, textScale, palette(),
                complete ? 0 : offset + cells.size(), changedCells);
    }

    /** Persists the bounded authoritative surface, including revision metadata for reconnecting clients. */
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("Width", width);
        tag.putInt("Height", height);
        tag.putLong("Revision", revision);
        tag.putInt("CursorX", cursorX);
        tag.putInt("CursorY", cursorY);
        tag.putBoolean("CursorBlink", cursorBlink);
        tag.putInt("TextColor", textColor);
        tag.putInt("BackgroundColor", backgroundColor);
        tag.putDouble("TextScale", textScale);
        tag.putIntArray("Palette", palette);
        ListTag textRows = new ListTag();
        for (char[] row : text) textRows.add(StringTag.valueOf(new String(row)));
        tag.put("Text", textRows);
        tag.putByteArray("Foreground", flatten(foreground));
        tag.putByteArray("Background", flatten(background));
        tag.putLongArray("CellRevision", flatten(cellRevision));
        return tag;
    }

    /**
     * Loads a surface only when its shape and bounded arrays are valid. A false result leaves the
     * current surface untouched so malformed or legacy data can fall back to line reconstruction.
     */
    public boolean load(CompoundTag tag) {
        if (tag == null || !tag.contains("Width", Tag.TAG_INT) || !tag.contains("Height", Tag.TAG_INT)
                || tag.getInt("Width") != width || tag.getInt("Height") != height) return false;
        int cellCount = width * height;
        long loadedRevision = tag.contains("Revision", Tag.TAG_LONG) ? tag.getLong("Revision") : 0;
        if (loadedRevision < 0) return false;
        int loadedTextColor = tag.contains("TextColor", Tag.TAG_INT) ? tag.getInt("TextColor") : textColor;
        int loadedBackgroundColor = tag.contains("BackgroundColor", Tag.TAG_INT)
                ? tag.getInt("BackgroundColor") : backgroundColor;
        if (loadedTextColor < 0 || loadedTextColor >= PALETTE_SIZE
                || loadedBackgroundColor < 0 || loadedBackgroundColor >= PALETTE_SIZE) return false;
        double loadedScale = tag.contains("TextScale", Tag.TAG_DOUBLE) ? tag.getDouble("TextScale") : textScale;
        if (!Double.isFinite(loadedScale) || loadedScale < 0.5 || loadedScale > 5.0
                || loadedScale * 2 != Math.rint(loadedScale * 2)) return false;

        int[] loadedPalette = palette.clone();
        if (tag.contains("Palette", Tag.TAG_INT_ARRAY)) {
            int[] values = tag.getIntArray("Palette");
            if (values.length != PALETTE_SIZE) return false;
            for (int value : values) if (value < 0 || value > 0xFFFFFF) return false;
            loadedPalette = values;
        }

        char[][] loadedText = new char[height][width];
        for (char[] row : loadedText) Arrays.fill(row, ' ');
        if (tag.contains("Text", Tag.TAG_LIST)) {
            ListTag textRows = tag.getList("Text", Tag.TAG_STRING);
            if (textRows.size() > height) return false;
            for (int row = 0; row < textRows.size(); row++) {
                String value = textRows.getString(row);
                if (value.length() > width) return false;
                value.getChars(0, value.length(), loadedText[row], 0);
            }
        }

        byte[] loadedForeground = new byte[cellCount];
        byte[] loadedBackground = new byte[cellCount];
        Arrays.fill(loadedForeground, (byte) loadedTextColor);
        Arrays.fill(loadedBackground, (byte) loadedBackgroundColor);
        if (tag.contains("Foreground", Tag.TAG_BYTE_ARRAY)) {
            byte[] values = tag.getByteArray("Foreground");
            if (values.length != cellCount || !validColors(values)) return false;
            loadedForeground = values;
        }
        if (tag.contains("Background", Tag.TAG_BYTE_ARRAY)) {
            byte[] values = tag.getByteArray("Background");
            if (values.length != cellCount || !validColors(values)) return false;
            loadedBackground = values;
        }

        long[] loadedCellRevision = new long[cellCount];
        if (tag.contains("CellRevision", Tag.TAG_LONG_ARRAY)) {
            long[] values = tag.getLongArray("CellRevision");
            if (values.length != cellCount) return false;
            for (long value : values) if (value < 0 || value > loadedRevision) return false;
            loadedCellRevision = values;
        } else {
            Arrays.fill(loadedCellRevision, loadedRevision);
        }

        int loadedCursorX = tag.contains("CursorX", Tag.TAG_INT) ? tag.getInt("CursorX") : 1;
        int loadedCursorY = tag.contains("CursorY", Tag.TAG_INT) ? tag.getInt("CursorY") : 1;
        boolean loadedCursorBlink = tag.contains("CursorBlink", Tag.TAG_BYTE) && tag.getBoolean("CursorBlink");

        for (int row = 0; row < height; row++) {
            System.arraycopy(loadedText[row], 0, text[row], 0, width);
            System.arraycopy(loadedForeground, row * width, foreground[row], 0, width);
            System.arraycopy(loadedBackground, row * width, background[row], 0, width);
            System.arraycopy(loadedCellRevision, row * width, cellRevision[row], 0, width);
        }
        revision = loadedRevision;
        cursorX = loadedCursorX;
        cursorY = loadedCursorY;
        cursorBlink = loadedCursorBlink;
        textColor = loadedTextColor;
        backgroundColor = loadedBackgroundColor;
        textScale = loadedScale;
        System.arraycopy(loadedPalette, 0, palette, 0, PALETTE_SIZE);
        return true;
    }

    /** ComputerCraft permits the cursor to sit outside the viewport; writes are clipped. */
    public void setCursor(int x, int y) {
        if (cursorX == x && cursorY == y) return;
        cursorX = x;
        cursorY = y;
        nextRevision();
    }

    public void setCursorBlink(boolean blink) {
        if (cursorBlink == blink) return;
        cursorBlink = blink;
        nextRevision();
    }

    public void setTextColor(int color) {
        color = requireColor(color);
        if (textColor == color) return;
        textColor = color;
        nextRevision();
    }
    public void setBackgroundColor(int color) {
        color = requireColor(color);
        if (backgroundColor == color) return;
        backgroundColor = color;
        nextRevision();
    }

    public void setTextScale(double scale) {
        if (!Double.isFinite(scale) || scale < 0.5 || scale > 5.0 || scale * 2 != Math.rint(scale * 2)) {
            throw new IllegalArgumentException("text scale must be from 0.5 to 5.0 in increments of 0.5");
        }
        if (textScale == scale) return;
        textScale = scale;
        nextRevision();
    }

    public int paletteColor(int color) { return palette[requireColor(color)]; }

    public void setPaletteColor(int color, int rgb) {
        if (rgb < 0 || rgb > 0xFFFFFF) throw new IllegalArgumentException("RGB color is outside 24-bit range");
        if (palette[requireColor(color)] == rgb) return;
        palette[requireColor(color)] = rgb;
        nextRevision();
    }

    public void write(String value) {
        String safe = value == null ? "" : value;
        String colors = Integer.toHexString(textColor).repeat(safe.length());
        String backgrounds = Integer.toHexString(backgroundColor).repeat(safe.length());
        blit(safe, colors, backgrounds);
    }

    public void blit(String value, String textColors, String backgroundColors) {
        String safe = value == null ? "" : value;
        if (textColors == null || backgroundColors == null
                || safe.length() != textColors.length() || safe.length() != backgroundColors.length()) {
            throw new IllegalArgumentException("text and color strings must have equal lengths");
        }
        int[] foregroundValues = new int[safe.length()];
        int[] backgroundValues = new int[safe.length()];
        for (int i = 0; i < safe.length(); i++) {
            foregroundValues[i] = parseColor(textColors.charAt(i));
            backgroundValues[i] = parseColor(backgroundColors.charAt(i));
        }
        long mutationRevision = safe.isEmpty() ? 0 : nextRevision();
        int row = cursorY - 1;
        for (int i = 0; i < safe.length(); i++) {
            int fg = foregroundValues[i];
            int bg = backgroundValues[i];
            int column = cursorX - 1 + i;
            if (row >= 0 && row < height && column >= 0 && column < width) {
                text[row][column] = safe.charAt(i);
                foreground[row][column] = (byte) fg;
                background[row][column] = (byte) bg;
                cellRevision[row][column] = mutationRevision;
            }
        }
        if (!safe.isEmpty()) cursorX += safe.length();
    }

    public void clear() {
        long mutationRevision = nextRevision();
        for (int row = 0; row < height; row++) {
            fillRow(row);
            Arrays.fill(cellRevision[row], mutationRevision);
        }
    }

    public void clearLine() {
        int row = cursorY - 1;
        if (row >= 0 && row < height) {
            long mutationRevision = nextRevision();
            fillRow(row);
            Arrays.fill(cellRevision[row], mutationRevision);
        }
    }

    public void scroll(int lines) {
        if (lines == 0) return;
        long mutationRevision = nextRevision();
        if (Math.abs((long) lines) >= height) {
            for (int row = 0; row < height; row++) {
                fillRow(row);
                Arrays.fill(cellRevision[row], mutationRevision);
            }
            return;
        }
        if (lines > 0) {
            for (int row = 0; row < height - lines; row++) copyRow(row + lines, row);
            for (int row = height - lines; row < height; row++) fillRow(row);
        } else {
            int count = -lines;
            for (int row = height - 1; row >= count; row--) copyRow(row - count, row);
            for (int row = 0; row < count; row++) fillRow(row);
        }
        for (long[] row : cellRevision) Arrays.fill(row, mutationRevision);
    }

    /** Compatibility helper for the pre-cell line API. */
    public void setLine(int zeroBasedRow, String value) {
        if (zeroBasedRow < 0 || zeroBasedRow >= height) throw new IllegalArgumentException("row is outside the terminal");
        int oldX = cursorX;
        int oldY = cursorY;
        cursorX = 1;
        cursorY = zeroBasedRow + 1;
        clearLine();
        write(value == null ? "" : value.substring(0, Math.min(value.length(), width)));
        cursorX = oldX;
        cursorY = oldY;
    }

    public List<String> lines() {
        List<String> result = new ArrayList<>(height);
        for (char[] row : text) result.add(stripRight(new String(row)));
        while (!result.isEmpty() && result.get(result.size() - 1).isEmpty()) result.remove(result.size() - 1);
        return List.copyOf(result);
    }

    public String line(int oneBasedRow) {
        if (oneBasedRow < 1 || oneBasedRow > height) throw new IllegalArgumentException("row is outside the terminal");
        return new String(text[oneBasedRow - 1]);
    }

    public String foregroundLine(int oneBasedRow) { return colorLine(foreground, oneBasedRow); }
    public String backgroundLine(int oneBasedRow) { return colorLine(background, oneBasedRow); }

    private String colorLine(byte[][] source, int row) {
        if (row < 1 || row > height) throw new IllegalArgumentException("row is outside the terminal");
        StringBuilder result = new StringBuilder(width);
        for (byte color : source[row - 1]) result.append(Integer.toHexString(color & 15));
        return result.toString();
    }

    private void fillRow(int row) {
        Arrays.fill(text[row], ' ');
        Arrays.fill(foreground[row], (byte) textColor);
        Arrays.fill(background[row], (byte) backgroundColor);
    }

    private long nextRevision() {
        if (revision == Long.MAX_VALUE) throw new IllegalStateException("surface revision exhausted");
        return ++revision;
    }

    private static byte[] flatten(byte[][] values) {
        byte[] result = new byte[values.length * values[0].length];
        for (int row = 0; row < values.length; row++) {
            System.arraycopy(values[row], 0, result, row * values[row].length, values[row].length);
        }
        return result;
    }

    private static long[] flatten(long[][] values) {
        long[] result = new long[values.length * values[0].length];
        for (int row = 0; row < values.length; row++) {
            System.arraycopy(values[row], 0, result, row * values[row].length, values[row].length);
        }
        return result;
    }

    private static boolean validColors(byte[] values) {
        for (byte value : values) if ((value & 0xFF) >= PALETTE_SIZE) return false;
        return true;
    }

    private void copyRow(int from, int to) {
        System.arraycopy(text[from], 0, text[to], 0, width);
        System.arraycopy(foreground[from], 0, foreground[to], 0, width);
        System.arraycopy(background[from], 0, background[to], 0, width);
    }

    private static int requireColor(int color) {
        if (color < 0 || color >= PALETTE_SIZE) throw new IllegalArgumentException("color index must be from 0 to 15");
        return color;
    }

    private void requireCell(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IllegalArgumentException("cell is outside the terminal surface");
        }
    }

    private static int parseColor(char value) {
        int color = Character.digit(value, 16);
        if (color < 0) throw new IllegalArgumentException("blit colors must use hexadecimal digits 0-f");
        return color;
    }

    private static String stripRight(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == ' ') end--;
        return value.substring(0, end);
    }
}
