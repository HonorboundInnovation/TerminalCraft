package com.malice.terminalcraft.device;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Bounded, renderer-neutral windows and widgets for terminal-style screens. */
public final class TerminalUiLayout {
    public static final int MAX_WINDOWS = 16;
    public static final int MAX_WIDGETS = 128;
    public static final int MAX_TEXT_LENGTH = 256;

    public enum WidgetKind { LABEL, BUTTON, TEXT_FIELD, PROGRESS }

    public record Bounds(int x, int y, int width, int height) {
        public Bounds {
            if (x < 0 || y < 0 || width <= 0 || height <= 0
                    || x > TerminalViewport.MAX_COORDINATE || y > TerminalViewport.MAX_COORDINATE
                    || width > TerminalViewport.MAX_COORDINATE || height > TerminalViewport.MAX_COORDINATE) {
                throw new IllegalArgumentException("UI bounds are outside bounded limits");
            }
        }

        public boolean contains(int pointX, int pointY) {
            return pointX >= x && pointX < x + width && pointY >= y && pointY < y + height;
        }

        public Bounds intersection(Bounds other) {
            Objects.requireNonNull(other, "other");
            int left = Math.max(x, other.x);
            int top = Math.max(y, other.y);
            int right = Math.min(x + width, other.x + other.width);
            int bottom = Math.min(y + height, other.y + other.height);
            return right <= left || bottom <= top ? null
                    : new Bounds(left, top, right - left, bottom - top);
        }
    }

    public record Window(String id, Bounds bounds, String title, boolean visible) {
        public Window {
            id = requireId(id, "window id");
            bounds = Objects.requireNonNull(bounds, "bounds");
            title = boundedText(title, "window title");
        }
    }

    public record Widget(String id, String windowId, WidgetKind kind, Bounds bounds,
                         String label, boolean enabled) {
        public Widget {
            id = requireId(id, "widget id");
            windowId = requireId(windowId, "widget window id");
            kind = Objects.requireNonNull(kind, "widget kind");
            bounds = Objects.requireNonNull(bounds, "bounds");
            label = boundedText(label, "widget label");
        }
    }

    private final Map<String, Window> windows = new LinkedHashMap<>();
    private final Map<String, Widget> widgets = new LinkedHashMap<>();

    public void addWindow(Window window) {
        Objects.requireNonNull(window, "window");
        if (windows.size() >= MAX_WINDOWS && !windows.containsKey(window.id())) {
            throw new IllegalStateException("terminal window capacity exceeded");
        }
        windows.put(window.id(), window);
    }

    public void removeWindow(String id) {
        windows.remove(id);
        widgets.values().removeIf(widget -> widget.windowId().equals(id));
    }

    public void addWidget(Widget widget) {
        Objects.requireNonNull(widget, "widget");
        if (!windows.containsKey(widget.windowId())) {
            throw new IllegalArgumentException("widget window does not exist");
        }
        if (widgets.size() >= MAX_WIDGETS && !widgets.containsKey(widget.id())) {
            throw new IllegalStateException("terminal widget capacity exceeded");
        }
        widgets.put(widget.id(), widget);
    }

    public void removeWidget(String id) { widgets.remove(id); }

    public List<Window> windows() { return List.copyOf(windows.values()); }

    public List<Widget> widgets() { return List.copyOf(widgets.values()); }

    /** Returns visible windows clipped to a screen viewport, in insertion/z-order order. */
    public List<Window> visibleWindows(TerminalViewport viewport) {
        Objects.requireNonNull(viewport, "viewport");
        Bounds clip = new Bounds(viewport.x(), viewport.y(), viewport.width(), viewport.height());
        List<Window> result = new ArrayList<>();
        for (Window window : windows.values()) {
            if (!window.visible()) continue;
            Bounds visible = window.bounds().intersection(clip);
            if (visible != null) result.add(new Window(window.id(), visible, window.title(), true));
        }
        return List.copyOf(result);
    }

    /** Returns the topmost enabled widget hit by a screen coordinate, if any. */
    public Optional<Widget> hitTest(int x, int y) {
        List<Widget> ordered = new ArrayList<>(widgets.values());
        for (int index = ordered.size() - 1; index >= 0; index--) {
            Widget widget = ordered.get(index);
            Window window = windows.get(widget.windowId());
            if (widget.enabled() && window != null && window.visible()
                    && window.bounds().contains(x, y) && widget.bounds().contains(x, y)) {
                return Optional.of(widget);
            }
        }
        return Optional.empty();
    }

    private static String requireId(String value, String label) {
        Objects.requireNonNull(value, label);
        if (!value.matches("[a-z][a-z0-9_.-]{0,63}")) throw new IllegalArgumentException("invalid " + label);
        return value;
    }

    private static String boundedText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.length() > MAX_TEXT_LENGTH) throw new IllegalArgumentException(label + " exceeds limit");
        return value;
    }
}
