package com.malice.terminalcraft.network;

import java.util.Locale;
import java.util.Optional;

/** Validation and canonicalization rules for human-facing RedNet host names. */
public final class RednetHostName {
    public static final int MAX_LENGTH = 63;

    private RednetHostName() {}

    public static Optional<String> normalize(String value) {
        if (value == null) return Optional.empty();
        String name = value.trim().toLowerCase(Locale.ROOT);
        if (name.isEmpty() || name.length() > MAX_LENGTH || name.startsWith("-") || name.endsWith("-")) {
            return Optional.empty();
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9') && c != '-') {
                return Optional.empty();
            }
        }
        return Optional.of(name);
    }
}
