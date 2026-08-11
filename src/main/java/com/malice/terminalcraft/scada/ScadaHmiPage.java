package com.malice.terminalcraft.scada;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** One named advanced-HMI page containing a bounded ordered widget layout. */
public record ScadaHmiPage(String name, String title, List<ScadaHmiWidget> widgets) {
    public static final int MAX_PAGES_PER_DASHBOARD = 8;
    public static final int MAX_WIDGETS_PER_PAGE = 32;
    public static final int MAX_NAME_CHARS = 32;
    public static final int MAX_TITLE_CHARS = 40;

    public ScadaHmiPage {
        name = canonicalName(name);
        title = boundedTitle(title == null || title.isBlank() ? name : title);
        widgets = List.copyOf(Objects.requireNonNullElse(widgets, List.of()));
        if (widgets.size() > MAX_WIDGETS_PER_PAGE) throw new IllegalArgumentException("HMI page widget capacity exceeded");
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (ScadaHmiWidget widget : widgets) {
            if (!ids.add(Objects.requireNonNull(widget, "widget").id())) {
                throw new IllegalArgumentException("duplicate HMI widget id: " + widget.id());
            }
        }
    }

    public static String canonicalName(String requested) {
        String value = Objects.requireNonNullElse(requested, "").trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || value.length() > MAX_NAME_CHARS || !value.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException("HMI page name must use letters, numbers, '_' or '-'");
        }
        return value;
    }

    public ScadaHmiPage withWidget(ScadaHmiWidget widget) {
        Objects.requireNonNull(widget, "widget");
        List<ScadaHmiWidget> next = new ArrayList<>(widgets);
        int existing = -1;
        for (int index = 0; index < next.size(); index++) if (next.get(index).id().equals(widget.id())) existing = index;
        if (existing >= 0) next.set(existing, widget);
        else {
            if (next.size() >= MAX_WIDGETS_PER_PAGE) throw new IllegalArgumentException("HMI page widget capacity exceeded");
            next.add(widget);
        }
        return new ScadaHmiPage(name, title, next);
    }

    public ScadaHmiPage withoutWidget(String requestedId) {
        String id = ScadaHmiWidget.canonicalId(requestedId);
        List<ScadaHmiWidget> next = widgets.stream().filter(widget -> !widget.id().equals(id)).toList();
        if (next.size() == widgets.size()) throw new IllegalArgumentException("HMI widget not found: " + id);
        return new ScadaHmiPage(name, title, next);
    }

    public ScadaHmiWidget widget(String requestedId) {
        String id = ScadaHmiWidget.canonicalId(requestedId);
        return widgets.stream().filter(widget -> widget.id().equals(id)).findFirst().orElse(null);
    }

    private static String boundedTitle(String requested) {
        String value = Objects.requireNonNullElse(requested, "").trim().replace('\n', ' ').replace('\r', ' ');
        if (value.isEmpty() || value.length() > MAX_TITLE_CHARS) throw new IllegalArgumentException("invalid HMI page title");
        return value;
    }
}
