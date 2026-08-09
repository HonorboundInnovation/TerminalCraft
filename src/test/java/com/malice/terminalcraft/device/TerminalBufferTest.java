package com.malice.terminalcraft.device;

import net.minecraft.nbt.CompoundTag;

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

        TerminalBuffer revisioned = new TerminalBuffer(3, 2);
        long initialRevision = revisioned.revision();
        TerminalBuffer.SurfaceDelta initial = revisioned.deltaSince(0, 16);
        assertTrue(initial.complete(), "initial surface delta is complete");
        assertEquals(6, initial.cells().size(), "initial surface includes every cell");
        assertEquals(initialRevision, initial.toRevision(), "initial surface revision");
        TerminalBuffer.SurfaceDelta pageOne = revisioned.deltaSince(0, 2, 0);
        TerminalBuffer.SurfaceDelta pageTwo = revisioned.deltaSince(0, 2, pageOne.nextOffset());
        TerminalBuffer.SurfaceDelta pageThree = revisioned.deltaSince(0, 2, pageTwo.nextOffset());
        assertTrue(!pageOne.complete() && !pageTwo.complete() && pageThree.complete(),
                "large initial surfaces paginate to a complete acknowledgement");
        assertEquals(6, pageOne.totalCells(), "paged delta reports total changed cells");
        assertEquals(2, pageThree.cells().size(), "final page contains the remaining cells");
        revisioned.setCursor(2, 2);
        revisioned.write("XY");
        TerminalBuffer.SurfaceDelta capped = revisioned.deltaSince(initialRevision, 1);
        assertTrue(!capped.complete(), "oversized delta reports an incomplete transfer");
        assertEquals(initialRevision, capped.toRevision(), "incomplete delta does not advance acknowledgement");
        TerminalBuffer.SurfaceDelta update = revisioned.deltaSince(initialRevision, 2);
        assertTrue(update.complete(), "bounded cell delta is complete");
        assertEquals(2, update.cells().size(), "delta contains only changed cells");
        assertEquals(4, update.cursorX(), "delta carries cursor metadata");
        assertEquals(2, update.cursorY(), "delta carries cursor metadata row");

        revisioned.setCursorBlink(true);
        revisioned.setTextColor(2);
        revisioned.setBackgroundColor(3);
        revisioned.setTextScale(2.5);
        revisioned.setPaletteColor(4, 0x123456);
        CompoundTag saved = revisioned.save(new CompoundTag());
        TerminalBuffer restored = new TerminalBuffer(3, 2);
        assertTrue(restored.load(saved), "valid surface NBT loads");
        assertEquals(revisioned.revision(), restored.revision(), "surface revision persists");
        assertEquals(revisioned.line(2), restored.line(2), "surface text persists");
        assertEquals(revisioned.foregroundLine(2), restored.foregroundLine(2), "cell foreground persists");
        assertEquals(revisioned.backgroundLine(2), restored.backgroundLine(2), "cell background persists");
        assertTrue(restored.cursorBlink(), "cursor blink persists");
        assertEquals(2.5, restored.textScale(), "text scale persists");
        assertEquals(0x123456, restored.paletteColor(4), "palette persists");
        CompoundTag malformed = new CompoundTag();
        malformed.putInt("Width", 99);
        malformed.putInt("Height", 2);
        assertTrue(!restored.load(malformed), "malformed surface NBT is rejected");

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
