package com.malice.terminalcraft.device;

/** Focused headless regression coverage for ComputerCraft-compatible terminal semantics. */
public final class TerminalBufferTest {
    private TerminalBufferTest() {}

    public static void main(String[] args) {
        TerminalBuffer terminal = new TerminalBuffer(5, 3);
        assertEquals(5, terminal.width(), "width");
        assertEquals(3, terminal.height(), "height");
        assertEquals("     ", terminal.line(1), "initial blank line");

        terminal.write("abc");
        assertEquals("abc  ", terminal.line(1), "write at cursor");
        assertEquals(4, terminal.cursorX(), "write advances cursor");
        terminal.setCursor(5, 1);
        terminal.write("XY");
        assertEquals("abc X", terminal.line(1), "write clips at right edge");
        assertEquals(7, terminal.cursorX(), "clipped write still advances cursor");

        terminal.setCursor(1, 2);
        terminal.blit("RGB", "e1a", "012");
        assertEquals("RGB  ", terminal.line(2), "blit text");
        assertEquals("e1a00", terminal.foregroundLine(2), "blit foreground colors");
        assertEquals("012ff", terminal.backgroundLine(2), "blit background colors");
        expectFailure(() -> terminal.blit("x", "00", "0"), "blit length mismatch");
        expectFailure(() -> terminal.blit("x", "z", "0"), "invalid color digit");

        terminal.setTextColor(2);
        terminal.setBackgroundColor(3);
        terminal.setCursor(2, 3);
        terminal.clearLine();
        assertEquals("22222", terminal.foregroundLine(3), "clearLine foreground");
        assertEquals("33333", terminal.backgroundLine(3), "clearLine background");

        terminal.setLine(0, "one");
        terminal.setLine(1, "two");
        terminal.setLine(2, "three");
        terminal.scroll(1);
        assertEquals("two  ", terminal.line(1), "scroll up first row");
        assertEquals("three", terminal.line(2), "scroll up second row");
        assertEquals("     ", terminal.line(3), "scroll creates blank row");
        terminal.scroll(-1);
        assertEquals("     ", terminal.line(1), "scroll down creates blank row");
        assertEquals("two  ", terminal.line(2), "scroll down moves content");

        terminal.setCursor(-2, 1);
        terminal.write("abcd");
        assertEquals("d    ", terminal.line(1), "negative cursor positions clip correctly");
        terminal.setCursorBlink(true);
        assertTrue(terminal.cursorBlink(), "cursor blink");
        terminal.setTextScale(2.5);
        assertEquals(2.5, terminal.textScale(), "half-step scale");
        expectFailure(() -> terminal.setTextScale(2.25), "invalid scale increment");
        terminal.setPaletteColor(4, 0x123456);
        assertEquals(0x123456, terminal.paletteColor(4), "palette mutation");

        System.out.println("Terminal buffer tests: OK");
    }

    private static void expectFailure(Runnable action, String message) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError(message + ": expected IllegalArgumentException");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
