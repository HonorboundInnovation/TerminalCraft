package com.malice.terminalcraft.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Shared schema marker for TerminalCraft-owned NBT roots.
 *
 * <p>Unversioned data produced before this class is treated as legacy version {@value #LEGACY_VERSION}.
 * Version markers are additive: readers must continue to load known fields best-effort so a future
 * version does not make otherwise compatible state unusable.</p>
 */
public final class PersistedDataVersions {
    public static final String TAG_DATA_VERSION = "DataVersion";
    public static final int LEGACY_VERSION = 0;
    public static final int CURRENT_VERSION = 1;

    public enum Schema {
        LEGACY, CURRENT, FUTURE
    }

    private PersistedDataVersions() {}

    /** Writes the current TerminalCraft schema version to an owned NBT root. */
    public static void stampCurrent(CompoundTag tag) {
        tag.putInt(TAG_DATA_VERSION, CURRENT_VERSION);
    }

    /**
     * Reads a schema version defensively. Missing, mistyped, or negative values are legacy data.
     */
    public static int read(CompoundTag tag) {
        if (tag == null || !tag.contains(TAG_DATA_VERSION, Tag.TAG_INT)) {
            return LEGACY_VERSION;
        }
        return Math.max(LEGACY_VERSION, tag.getInt(TAG_DATA_VERSION));
    }

    /** Classifies roots for explicit local migration and additive future-version handling. */
    public static Schema classify(CompoundTag tag) {
        int version = read(tag);
        if (version <= LEGACY_VERSION) return Schema.LEGACY;
        if (version == CURRENT_VERSION) return Schema.CURRENT;
        return Schema.FUTURE;
    }
}
