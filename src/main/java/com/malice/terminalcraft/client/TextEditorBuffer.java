package com.malice.terminalcraft.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Pure text-editing model used by the in-game script editor and its headless tests. */
public final class TextEditorBuffer {
    public static final int MAX_LENGTH = 64 * 1024;
    private static final int MAX_UNDO = 100;

    private final StringBuilder text = new StringBuilder();
    private final ArrayDeque<State> undo = new ArrayDeque<>();
    private final ArrayDeque<State> redo = new ArrayDeque<>();
    private int cursor;
    private int anchor;
    private boolean dirty;

    public TextEditorBuffer(String initial) {
        setInitialText(initial);
    }

    public String text() { return text.toString(); }
    public int cursor() { return cursor; }
    public boolean dirty() { return dirty; }
    public boolean hasSelection() { return cursor != anchor; }
    public int selectionStart() { return Math.min(cursor, anchor); }
    public int selectionEnd() { return Math.max(cursor, anchor); }
    public String selectedText() { return text.substring(selectionStart(), selectionEnd()); }

    public void setInitialText(String value) {
        text.setLength(0);
        String safe = normalize(value);
        text.append(safe, 0, Math.min(safe.length(), MAX_LENGTH));
        cursor = anchor = 0;
        dirty = false;
        undo.clear();
        redo.clear();
    }

    public void markSaved() { dirty = false; }

    public void selectAll() { anchor = 0; cursor = text.length(); }

    public void setCursor(int offset, boolean selecting) {
        cursor = clamp(offset);
        if (!selecting) anchor = cursor;
    }

    public void insert(String value) {
        String safe = normalize(value);
        if (safe.isEmpty() && !hasSelection()) return;
        remember();
        int start = selectionStart();
        int end = selectionEnd();
        int room = MAX_LENGTH - (text.length() - (end - start));
        if (safe.length() > room) safe = safe.substring(0, Math.max(0, room));
        text.replace(start, end, safe);
        cursor = anchor = start + safe.length();
        dirty = true;
    }

    public void backspace() {
        if (hasSelection()) { insert(""); return; }
        if (cursor == 0) return;
        remember();
        text.deleteCharAt(cursor - 1);
        cursor--;
        anchor = cursor;
        dirty = true;
    }

    public void delete() {
        if (hasSelection()) { insert(""); return; }
        if (cursor >= text.length()) return;
        remember();
        text.deleteCharAt(cursor);
        anchor = cursor;
        dirty = true;
    }

    public void moveHorizontal(int amount, boolean selecting, boolean byWord) {
        int target = cursor;
        if (!selecting && hasSelection() && !byWord) {
            target = amount < 0 ? selectionStart() : selectionEnd();
        } else if (byWord) {
            target = amount < 0 ? previousWord(cursor) : nextWord(cursor);
        } else {
            target += amount;
        }
        setCursor(target, selecting);
    }

    public void moveLineBoundary(boolean end, boolean selecting, boolean document) {
        if (document) {
            setCursor(end ? text.length() : 0, selecting);
            return;
        }
        int start = lineStart(cursor);
        int finish = lineEnd(cursor);
        setCursor(end ? finish : start, selecting);
    }

    public void moveVertical(int rows, boolean selecting) {
        Position position = position(cursor);
        int targetLine = Math.max(0, Math.min(lineCount() - 1, position.line() + rows));
        setCursor(offsetForLineColumn(targetLine, position.column()), selecting);
    }

    public void undo() {
        if (undo.isEmpty()) return;
        redo.push(state());
        restore(undo.pop());
        dirty = true;
    }

    public void redo() {
        if (redo.isEmpty()) return;
        undo.push(state());
        restore(redo.pop());
        dirty = true;
    }

    public int lineCount() {
        int count = 1;
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') count++;
        return count;
    }

    public List<String> lines() {
        List<String> result = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '\n') {
                result.add(text.substring(start, i));
                start = i + 1;
            }
        }
        return result;
    }

    public Position position(int offset) {
        int bounded = clamp(offset);
        int line = 0;
        int column = 0;
        for (int i = 0; i < bounded; i++) {
            if (text.charAt(i) == '\n') { line++; column = 0; }
            else column++;
        }
        return new Position(line, column);
    }

    public int offsetForLineColumn(int targetLine, int targetColumn) {
        int line = 0;
        int offset = 0;
        while (line < targetLine && offset < text.length()) {
            if (text.charAt(offset++) == '\n') line++;
        }
        int end = offset;
        while (end < text.length() && text.charAt(end) != '\n') end++;
        return Math.min(end, offset + Math.max(0, targetColumn));
    }

    private int lineStart(int offset) {
        int i = clamp(offset);
        while (i > 0 && text.charAt(i - 1) != '\n') i--;
        return i;
    }

    private int lineEnd(int offset) {
        int i = clamp(offset);
        while (i < text.length() && text.charAt(i) != '\n') i++;
        return i;
    }

    private int previousWord(int offset) {
        int i = clamp(offset);
        while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) i--;
        while (i > 0 && !Character.isWhitespace(text.charAt(i - 1))) i--;
        return i;
    }

    private int nextWord(int offset) {
        int i = clamp(offset);
        while (i < text.length() && !Character.isWhitespace(text.charAt(i))) i++;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        return i;
    }

    private void remember() {
        undo.push(state());
        while (undo.size() > MAX_UNDO) undo.removeLast();
        redo.clear();
    }

    private State state() { return new State(text.toString(), cursor, anchor); }
    private void restore(State state) {
        text.setLength(0);
        text.append(state.text());
        cursor = clamp(state.cursor());
        anchor = clamp(state.anchor());
    }
    private int clamp(int value) { return Math.max(0, Math.min(text.length(), value)); }
    private static String normalize(String value) { return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n'); }

    public record Position(int line, int column) {}
    private record State(String text, int cursor, int anchor) {}
}
