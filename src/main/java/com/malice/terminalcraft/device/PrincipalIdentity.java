package com.malice.terminalcraft.device;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Stable identity for an actor that can own work or invoke TerminalCraft services.
 *
 * <p>The kind is part of the security identity: a player, device, service and process with the
 * same UUID are distinct principals. Display names are diagnostic only and never authoritative.</p>
 */
public record PrincipalIdentity(Kind kind, UUID id, String name) {
    public static final int MAX_NAME_LENGTH = 64;

    public enum Kind {
        PLAYER, DEVICE, SERVICE, PROCESS;

        public String serializedName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Kind parse(String value) {
            if (value == null) throw new IllegalArgumentException("principal kind is required");
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("unknown principal kind: " + value, invalid);
            }
        }
    }

    public PrincipalIdentity {
        kind = Objects.requireNonNull(kind, "kind");
        id = Objects.requireNonNull(id, "id");
        name = Objects.requireNonNull(name, "name").trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("principal name must be non-blank and bounded");
        }
    }

    public static PrincipalIdentity player(UUID id, String name) {
        return new PrincipalIdentity(Kind.PLAYER, id, name);
    }

    public static PrincipalIdentity device(UUID id, String name) {
        return new PrincipalIdentity(Kind.DEVICE, id, name);
    }

    public static PrincipalIdentity service(UUID id, String name) {
        return new PrincipalIdentity(Kind.SERVICE, id, name);
    }

    public static PrincipalIdentity process(UUID id, String name) {
        return new PrincipalIdentity(Kind.PROCESS, id, name);
    }

    /** Stable namespaced key for maps, quotas, replay records and future authorization decisions. */
    public String authorityKey() {
        return kind.serializedName() + ":" + id;
    }
}
