package com.malice.terminalcraft.device;

import net.minecraft.nbt.CompoundTag;

import java.util.Objects;
import java.util.UUID;

/** Shared persistence codec for stable TerminalCraft device identities. */
public final class DeviceIdentity {
    public static final String TAG_DEVICE_ID = "DeviceId";

    private DeviceIdentity() {}

    /** Creates a new persistent identity for a newly placed or legacy device. */
    public static UUID create() {
        return UUID.randomUUID();
    }

    /** Writes a device identity using Minecraft's canonical UUID NBT representation. */
    public static void save(CompoundTag tag, UUID deviceId) {
        Objects.requireNonNull(tag, "tag").putUUID(TAG_DEVICE_ID,
                Objects.requireNonNull(deviceId, "deviceId"));
    }

    /**
     * Loads a valid persisted identity or retains the supplied identity for legacy/malformed data.
     * Retaining the fallback lets a block entity generate once at construction and persist on its
     * next save without making malformed world data fatal.
     */
    public static UUID loadOrRetain(CompoundTag tag, UUID fallback) {
        Objects.requireNonNull(fallback, "fallback");
        if (tag == null || !tag.hasUUID(TAG_DEVICE_ID)) {
            return fallback;
        }
        try {
            return tag.getUUID(TAG_DEVICE_ID);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
