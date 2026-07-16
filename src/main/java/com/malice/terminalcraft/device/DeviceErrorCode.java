package com.malice.terminalcraft.device;

/** Stable machine-readable failures returned by device operations. */
public enum DeviceErrorCode {
    NOT_FOUND,
    REMOVED,
    REPLACED,
    OFFLINE,
    CHUNK_UNLOADED,
    UNSUPPORTED,
    PERMISSION_DENIED,
    BUSY,
    INVALID_ARGUMENT,
    INSUFFICIENT_RESOURCES,
    CAPACITY_EXCEEDED,
    TIMEOUT,
    ADAPTER_ERROR
}
