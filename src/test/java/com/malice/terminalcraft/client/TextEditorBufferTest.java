package com.malice.terminalcraft.client;

/** Headless regression tests for the GUI script editor model. */
public final class TextEditorBufferTest {
    private TextEditorBufferTest() {}

    public static void main(String[] args) {
        TextEditorBuffer editor = new TextEditorBuffer("one\ntwo");
        assertEquals(2, editor.lineCount(), "line count");
        editor.moveLineBoundary(true, false, true);
        editor.insert("\nthree");
        assertEquals("one\ntwo\nthree", editor.text(), "multiline insert");
        editor.moveVertical(-1, false);
        assertEquals(new TextEditorBuffer.Position(1, 3), editor.position(editor.cursor()), "vertical movement");
        editor.moveLineBoundary(false, false, false);
        editor.moveHorizontal(3, true, false);
        assertEquals("two", editor.selectedText(), "keyboard selection");
        editor.insert("TWO");
        assertEquals("one\nTWO\nthree", editor.text(), "selection replacement");
        editor.undo();
        assertEquals("one\ntwo\nthree", editor.text(), "undo");
        editor.redo();
        assertEquals("one\nTWO\nthree", editor.text(), "redo");
        editor.selectAll();
        editor.insert("echo hello\r\necho world");
        assertEquals("echo hello\necho world", editor.text(), "paste normalization");
        editor.backspace();
        assertEquals("echo hello\necho worl", editor.text(), "backspace");
        editor.delete();
        assertEquals("echo hello\necho worl", editor.text(), "delete at end");
        assertTrue(editor.dirty(), "dirty after edits");
        editor.markSaved();
        assertTrue(!editor.dirty(), "mark saved");

        TextEditorBuffer bounded = new TextEditorBuffer("");
        bounded.insert("x".repeat(TextEditorBuffer.MAX_LENGTH + 10));
        assertEquals(TextEditorBuffer.MAX_LENGTH, bounded.text().length(), "file size bound");

        System.out.println("GUI text editor buffer tests: OK");
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
