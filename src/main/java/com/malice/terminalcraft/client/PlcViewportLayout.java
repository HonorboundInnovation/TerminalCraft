package com.malice.terminalcraft.client;

/** Reserves the wide programmer's live-watch sidebar from source editing. */
final class PlcViewportLayout {
    private PlcViewportLayout() {}
    static int editorRightOffset(int panelWidth, boolean ladderMode) {
        return panelWidth >= 560 && !ladderMode ? 430 : panelWidth - 8;
    }
}
