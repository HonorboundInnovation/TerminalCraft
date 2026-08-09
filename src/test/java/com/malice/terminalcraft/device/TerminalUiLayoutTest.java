package com.malice.terminalcraft.device;

import java.util.List;

/** Headless coverage for clipped terminal views and bounded reusable UI layout. */
public final class TerminalUiLayoutTest {
    private TerminalUiLayoutTest() {}

    public static void main(String[] args) {
        TerminalBuffer surface = new TerminalBuffer(8, 4);
        surface.setLine(0, "ABCDEFGH");
        TerminalViewport viewport = new TerminalViewport(6, 2, 8, 8);
        List<TerminalBuffer.CellDelta> visible = viewport.cells(surface);
        require(visible.size() == 4, "viewport clips to the surface edge");
        require(visible.get(0).x() == 6 && visible.get(0).y() == 2,
                "clipped cells retain authoritative surface coordinates");

        TerminalUiLayout layout = new TerminalUiLayout();
        layout.addWindow(new TerminalUiLayout.Window("main",
                new TerminalUiLayout.Bounds(1, 1, 6, 3), "Main", true));
        layout.addWindow(new TerminalUiLayout.Window("overlay",
                new TerminalUiLayout.Bounds(2, 2, 4, 2), "Overlay", true));
        layout.addWidget(new TerminalUiLayout.Widget("button", "main",
                TerminalUiLayout.WidgetKind.BUTTON,
                new TerminalUiLayout.Bounds(1, 1, 4, 1), "Run", true));
        layout.addWidget(new TerminalUiLayout.Widget("overlay-button", "overlay",
                TerminalUiLayout.WidgetKind.BUTTON,
                new TerminalUiLayout.Bounds(2, 2, 3, 1), "Top", true));
        require(layout.visibleWindows(new TerminalViewport(0, 0, 4, 3)).size() == 2,
                "windows are independently clipped to the viewport");
        require(layout.hitTest(2, 2).orElseThrow().id().equals("overlay-button"),
                "later widgets receive topmost hit priority");
        layout.removeWindow("overlay");
        require(layout.widgets().size() == 1, "removing a window removes its child widgets");
        requireThrows(() -> layout.addWidget(new TerminalUiLayout.Widget("orphan", "missing",
                        TerminalUiLayout.WidgetKind.LABEL, new TerminalUiLayout.Bounds(0, 0, 1, 1), "x", true)),
                "widgets cannot target missing windows");
        requireThrows(() -> new TerminalViewport(0, 0, 65, 65),
                "viewport cell budget is bounded");
        System.out.println("Terminal UI layout tests: OK");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void requireThrows(Runnable action, String message) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError(message);
    }
}
