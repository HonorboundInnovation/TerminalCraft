package com.malice.terminalcraft.device;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Headless ComputerCraft-compatible character-cell terminal state.
 * Coordinates exposed by this class are one-based, matching the ComputerCraft term API.
 */
public final class TerminalBuffer {
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
    private final int[] palette = DEFAULT_PALETTE.clone();
    private int cursorX = 1;
    private int cursorY = 1;
    private int textColor;
    private int backgroundColor = 15;
    private boolean cursorBlink;
    private double textScale = 1.0;

    public TerminalBuffer(int width, int height) {
        if (width <= 0 || height <= 0 || width * (long) height > 1_048_576L) {
            throw new IllegalArgumentException("terminal dimensions must be positive and bounded");
        }
        this.width = width;
        this.height = height;
        text = new char[height][width];
        foreground = new byte[height][width];
        background = new byte[height][width];
        clear();
    }

    public int width() { return width; }
    public int height() { return height; }
    public int cursorX() { return cursorX; }
    public int cursorY() { return cursorY; }
    public boolean cursorBlink() { return cursorBlink; }
    public int textColor() { return textColor; }
    public int backgroundColor() { return backgroundColor; }
    public double textScale() { return textScale; }

    /** ComputerCraft permits the cursor to sit outside the viewport; writes are clipped. */
    public void setCursor(int x, int y) {
        cursorX = x;
        cursorY = y;
    }

    public void setCursorBlink(boolean blink) { cursorBlink = blink; }

    public void setTextColor(int color) { textColor = requireColor(color); }
    public void setBackgroundColor(int color) { backgroundColor = requireColor(color); }

    public void setTextScale(double scale) {
        if (!Double.isFinite(scale) || scale < 0.5 || scale > 5.0 || scale * 2 != Math.rint(scale * 2)) {
            throw new IllegalArgumentException("text scale must be from 0.5 to 5.0 in increments of 0.5");
        }
        textScale = scale;
    }

    public int paletteColor(int color) { return palette[requireColor(color)]; }

    public void setPaletteColor(int color, int rgb) {
        if (rgb < 0 || rgb > 0xFFFFFF) throw new IllegalArgumentException("RGB color is outside 24-bit range");
        palette[requireColor(color)] = rgb;
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
        int row = cursorY - 1;
        for (int i = 0; i < safe.length(); i++) {
            int fg = parseColor(textColors.charAt(i));
            int bg = parseColor(backgroundColors.charAt(i));
            int column = cursorX - 1 + i;
            if (row >= 0 && row < height && column >= 0 && column < width) {
                text[row][column] = safe.charAt(i);
                foreground[row][column] = (byte) fg;
                background[row][column] = (byte) bg;
            }
        }
        cursorX += safe.length();
    }

    public void clear() {
        for (int row = 0; row < height; row++) fillRow(row);
    }

    public void clearLine() {
        int row = cursorY - 1;
        if (row >= 0 && row < height) fillRow(row);
    }

    public void scroll(int lines) {
        if (lines == 0) return;
        if (Math.abs((long) lines) >= height) {
            clear();
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

    private void copyRow(int from, int to) {
        System.arraycopy(text[from], 0, text[to], 0, width);
        System.arraycopy(foreground[from], 0, foreground[to], 0, width);
        System.arraycopy(background[from], 0, background[to], 0, width);
    }

    private static int requireColor(int color) {
        if (color < 0 || color >= PALETTE_SIZE) throw new IllegalArgumentException("color index must be from 0 to 15");
        return color;
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
