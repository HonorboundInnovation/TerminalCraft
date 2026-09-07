package com.malice.terminalcraft.client;

/** Keeps chapter navigation within the guide's content column. */
final class GuideViewportLayout {
    private GuideViewportLayout() {}

    static int navigationWidth(int panelWidth, int sidebarWidth) {
        return Math.max(1, Math.min(80, (panelWidth - sidebarWidth - 29) / 2));
    }
}
