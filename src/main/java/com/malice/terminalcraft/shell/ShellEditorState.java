package com.malice.terminalcraft.shell;

import java.util.ArrayList;
import java.util.List;

/** Mutable state for one interactive editor session; behavior remains orchestrated by BashShell. */
final class ShellEditorState {
    boolean active;
    String path;
    final List<String> lines = new ArrayList<>();
    int cursor;
    boolean dirty;

    void close() {
        active = false;
        path = null;
        lines.clear();
        cursor = 0;
        dirty = false;
    }

    void normalizeAfterLoad() {
        if (active && lines.isEmpty()) {
            lines.add("");
        }
        if (cursor < 0 || cursor >= Math.max(1, lines.size())) {
            cursor = Math.max(0, lines.size() - 1);
        }
        if (!active) {
            close();
        }
    }
}
