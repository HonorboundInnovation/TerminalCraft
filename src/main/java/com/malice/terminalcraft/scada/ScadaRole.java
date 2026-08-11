package com.malice.terminalcraft.scada;

import java.util.Locale;

/** Plant-level roles layered over per-device authorization. */
public enum ScadaRole {
    VIEWER,
    OPERATOR,
    ENGINEER,
    ADMIN;

    public static ScadaRole parse(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public boolean allows(ScadaAction action) {
        return ordinal() >= action.minimumRole().ordinal();
    }
}
