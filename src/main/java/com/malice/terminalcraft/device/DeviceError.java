package com.malice.terminalcraft.device;

import java.util.Objects;

/** Structured device failure suitable for shell, network, and automation consumers. */
public record DeviceError(DeviceErrorCode code, String message, boolean retryable) {
    public DeviceError {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        if (message.isBlank() || message.length() > DeviceValue.MAX_STRING_LENGTH) {
            throw new IllegalArgumentException("device error message must be non-blank and bounded");
        }
    }
}
