package com.malice.terminalcraft.network;

import java.util.Objects;

/** Structured, non-disclosing outcome for RedNet host and service directory mutations. */
public record RednetRegistrationResult(Status status, String name) {
    public enum Status {
        CREATED,
        UPDATED,
        UNCHANGED,
        INVALID,
        NAME_CONFLICT,
        DIRECTORY_FULL,
        OWNER_LIMIT
    }

    public RednetRegistrationResult {
        Objects.requireNonNull(status, "status");
        name = name == null ? "" : name;
    }

    public boolean accepted() {
        return status == Status.CREATED || status == Status.UPDATED || status == Status.UNCHANGED;
    }

    static RednetRegistrationResult of(Status status, String name) {
        return new RednetRegistrationResult(status, name);
    }
}
