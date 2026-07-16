package com.malice.terminalcraft.device;

import java.util.Objects;
import java.util.Optional;

/** Success or failure from a device call; exactly one of value and error is present. */
public final class DeviceResult {
    private final DeviceValue value;
    private final DeviceError error;

    private DeviceResult(DeviceValue value, DeviceError error) {
        this.value = value;
        this.error = error;
    }

    public static DeviceResult success(DeviceValue value) {
        return new DeviceResult(Objects.requireNonNull(value, "value"), null);
    }

    public static DeviceResult success() {
        return success(DeviceValue.nullValue());
    }

    public static DeviceResult failure(DeviceErrorCode code, String message, boolean retryable) {
        return new DeviceResult(null, new DeviceError(code, message, retryable));
    }

    public boolean isSuccess() { return error == null; }
    public Optional<DeviceValue> value() { return Optional.ofNullable(value); }
    public Optional<DeviceError> error() { return Optional.ofNullable(error); }
}
