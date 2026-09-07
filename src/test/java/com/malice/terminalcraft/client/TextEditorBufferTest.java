package com.malice.terminalcraft.client;

/** Headless regression tests for the GUI script editor model. */
public final class TextEditorBufferTest {
    private TextEditorBufferTest() {}

    public static void main(String[] args) {
        assertEquals(430, PlcViewportLayout.editorRightOffset(640, false), "PLC source stays outside watch panel");
        assertEquals(632, PlcViewportLayout.editorRightOffset(640, true), "PLC ladder keeps full canvas");
        assertEquals(300, PlcViewportLayout.editorRightOffset(308, false), "compact PLC source uses available width");
        DisplayChannelDraft draft = new DisplayChannelDraft("old");
        draft.edit("new");
        draft.observe("old");
        assertEquals("new", draft.value(), "server refresh preserves channel edit after focus moves");
        assertTrue(draft.dirty(), "channel stays unsaved until observed on server");
        draft.observe("new");
        assertTrue(!draft.dirty(), "server readback acknowledges channel edit");
        draft.observe("remote");
        assertEquals("remote", draft.value(), "unedited channel follows server changes");
        assertEquals(76, GuideViewportLayout.navigationWidth(300, 118), "guide navigation fits minimum ordinary window");
        assertEquals(80, GuideViewportLayout.navigationWidth(820, 205), "wide guide keeps standard navigation");
        assertEquals(412, TerminalViewportLayout.panelWidth(428), "terminal fits ordinary scaled window width");
        assertEquals(224, TerminalViewportLayout.panelHeight(240), "terminal fits ordinary scaled window height");
        assertEquals(296, TerminalViewportLayout.editorWidth(320), "editor fits minimum ordinary GUI width");
        assertEquals(216, TerminalViewportLayout.editorHeight(240), "editor buttons fit ordinary GUI height");
        assertTrue(!TerminalViewportLayout.showStatusRail(412, 224), "compact window prioritizes command area");
        assertTrue(TerminalViewportLayout.showStatusRail(440, 270), "full panel keeps status rail");
        assertEquals(18, TerminalViewportLayout.outputRows(270), "full panel retains eighteen output rows");
        assertEquals(14, TerminalViewportLayout.outputRows(224), "compact output fits above command line");
        assertEquals(13, TerminalViewportLayout.pageStep(14), "page scroll retains one context row");
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
        assertTrue(!bounded.insert("x".repeat(TextEditorBuffer.MAX_LENGTH + 10)), "oversized paste rejected");
        assertEquals("", bounded.text(), "no partial paste");
        assertTrue(!bounded.dirty(), "rejection does not dirty clean buffer");
        assertTrue(bounded.insert("x".repeat(TextEditorBuffer.MAX_LENGTH)), "exact capacity accepted");
        bounded.markSaved();
        assertTrue(!bounded.insert("\n"), "typing at capacity rejected");
        assertEquals(TextEditorBuffer.MAX_LENGTH, bounded.cursor(), "rejection preserves cursor");
        assertTrue(!bounded.dirty(), "rejection preserves saved status");
        bounded.selectAll();
        assertTrue(!bounded.insert("y".repeat(TextEditorBuffer.MAX_LENGTH + 1)), "oversized replacement rejected");
        assertEquals("x".repeat(TextEditorBuffer.MAX_LENGTH), bounded.selectedText(), "selection and text retained");
        assertTrue(bounded.insert("short"), "replacement can reclaim capacity");
        bounded.undo();
        assertTrue(!bounded.insert("z".repeat(TextEditorBuffer.MAX_LENGTH + 1)), "rejection with redo pending");
        bounded.redo();
        assertEquals("short", bounded.text(), "rejection does not erase redo");
        bounded.undo();
        bounded.undo();
        assertEquals("", bounded.text(), "rejection adds no undo entry");
        assertTrue(bounded.insert("\r\n".repeat(TextEditorBuffer.MAX_LENGTH)), "capacity measured after normalization");
        assertEquals(TextEditorBuffer.MAX_LENGTH, bounded.text().length(), "normalized exact capacity");

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
