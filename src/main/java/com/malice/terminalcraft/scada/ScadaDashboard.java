package com.malice.terminalcraft.scada;

import java.util.Objects;
import java.util.UUID;

/** Persistent monitor binding for an automatically refreshed SCADA overview. */
public record ScadaDashboard(UUID monitorId, String tagPrefix, String title, int refreshTicks) {
    public static final int MAX_TITLE_CHARS = 32;

    public ScadaDashboard {
        monitorId = Objects.requireNonNull(monitorId, "monitorId");
        tagPrefix = Objects.requireNonNullElse(tagPrefix, "").trim().toLowerCase(java.util.Locale.ROOT);
        if ("*".equals(tagPrefix) || "-".equals(tagPrefix)) tagPrefix = "";
        if (!tagPrefix.isEmpty()) tagPrefix = ScadaTag.canonicalName(tagPrefix);
        title = Objects.requireNonNullElse(title, "SCADA OVERVIEW").trim();
        if (title.isEmpty() || title.length() > MAX_TITLE_CHARS) throw new IllegalArgumentException("invalid dashboard title");
        if (refreshTicks < 10 || refreshTicks > 20 * 60) throw new IllegalArgumentException("invalid dashboard refresh interval");
    }
}
