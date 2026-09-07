package com.malice.terminalcraft.client;

/** Logical GUI-pixel sizing, independent of Minecraft rendering for focused tests. */
final class TerminalViewportLayout {
    private TerminalViewportLayout() {}
    static int panelWidth(int width) { return Math.min(440, Math.max(1, width - 16)); }
    static int panelHeight(int height) { return Math.min(270, Math.max(1, height - 16)); }
    static int editorWidth(int width) { return Math.min(620, Math.max(1, width - 24)); }
    static int editorHeight(int height) { return Math.min(390, Math.max(1, height - 24)); }
    static boolean showStatusRail(int panelWidth, int panelHeight) {
        return panelWidth >= 400 && panelHeight >= 260;
    }
    static int outputRows(int panelHeight) { return Math.max(1, (panelHeight - 63) / 11); }
    static int pageStep(int rows) { return Math.max(1, rows - 1); }
}
