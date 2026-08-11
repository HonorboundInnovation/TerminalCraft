package com.malice.terminalcraft.scada;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Persistent multi-page advanced-HMI definition bound to one monitor wall. */
public record ScadaHmiDashboard(String name, UUID monitorId, String title, int refreshTicks,
                                String activePage, List<ScadaHmiPage> pages) {
    public static final int MAX_NAME_CHARS = 64;
    public static final int MAX_TITLE_CHARS = 40;

    public ScadaHmiDashboard {
        name = canonicalName(name);
        monitorId = Objects.requireNonNull(monitorId, "monitorId");
        title = boundedTitle(title == null || title.isBlank() ? name : title);
        if (refreshTicks < 2 || refreshTicks > 20 * 60) throw new IllegalArgumentException("invalid HMI refresh interval");
        pages = List.copyOf(Objects.requireNonNullElse(pages, List.of()));
        if (pages.isEmpty()) pages = List.of(new ScadaHmiPage("overview", "Overview", List.of()));
        if (pages.size() > ScadaHmiPage.MAX_PAGES_PER_DASHBOARD) {
            throw new IllegalArgumentException("HMI dashboard page capacity exceeded");
        }
        java.util.HashSet<String> names = new java.util.HashSet<>();
        for (ScadaHmiPage page : pages) {
            if (!names.add(Objects.requireNonNull(page, "page").name())) {
                throw new IllegalArgumentException("duplicate HMI page: " + page.name());
            }
        }
        activePage = activePage == null || activePage.isBlank()
                ? pages.get(0).name() : ScadaHmiPage.canonicalName(activePage);
        String selected = activePage;
        if (pages.stream().noneMatch(page -> page.name().equals(selected))) {
            throw new IllegalArgumentException("active HMI page is not defined: " + activePage);
        }
    }

    public static String canonicalName(String requested) {
        String value = Objects.requireNonNullElse(requested, "").trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || value.length() > MAX_NAME_CHARS || !value.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("invalid HMI dashboard name");
        }
        return value;
    }

    public ScadaHmiPage selectedPage() { return page(activePage); }

    public ScadaHmiPage page(String requestedName) {
        String pageName = ScadaHmiPage.canonicalName(requestedName);
        return pages.stream().filter(page -> page.name().equals(pageName)).findFirst().orElse(null);
    }

    public ScadaHmiDashboard withPage(ScadaHmiPage page) {
        Objects.requireNonNull(page, "page");
        List<ScadaHmiPage> next = new ArrayList<>(pages);
        int existing = -1;
        for (int index = 0; index < next.size(); index++) if (next.get(index).name().equals(page.name())) existing = index;
        if (existing >= 0) next.set(existing, page);
        else {
            if (next.size() >= ScadaHmiPage.MAX_PAGES_PER_DASHBOARD) {
                throw new IllegalArgumentException("HMI dashboard page capacity exceeded");
            }
            next.add(page);
        }
        return new ScadaHmiDashboard(name, monitorId, title, refreshTicks, activePage, next);
    }

    public ScadaHmiDashboard withoutPage(String requestedName) {
        String pageName = ScadaHmiPage.canonicalName(requestedName);
        if (pages.size() <= 1) throw new IllegalArgumentException("HMI dashboard must retain one page");
        List<ScadaHmiPage> next = pages.stream().filter(page -> !page.name().equals(pageName)).toList();
        if (next.size() == pages.size()) throw new IllegalArgumentException("HMI page not found: " + pageName);
        String selected = activePage.equals(pageName) ? next.get(0).name() : activePage;
        return new ScadaHmiDashboard(name, monitorId, title, refreshTicks, selected, next);
    }

    public ScadaHmiDashboard withActivePage(String requestedName) {
        String pageName = ScadaHmiPage.canonicalName(requestedName);
        if (page(pageName) == null) throw new IllegalArgumentException("HMI page not found: " + pageName);
        return new ScadaHmiDashboard(name, monitorId, title, refreshTicks, pageName, pages);
    }

    private static String boundedTitle(String requested) {
        String value = Objects.requireNonNullElse(requested, "").trim().replace('\n', ' ').replace('\r', ' ');
        if (value.isEmpty() || value.length() > MAX_TITLE_CHARS) throw new IllegalArgumentException("invalid HMI dashboard title");
        return value;
    }
}
