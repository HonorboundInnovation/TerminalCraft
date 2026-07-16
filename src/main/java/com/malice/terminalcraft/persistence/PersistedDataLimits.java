package com.malice.terminalcraft.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.List;
import java.util.TreeSet;

/**
 * Hard limits and defensive readers for TerminalCraft-owned persisted NBT.
 *
 * <p>String limits are measured in Java UTF-16 code units. These limits apply after Minecraft has
 * decoded NBT; they prevent corrupt or hostile saves from expanding into unbounded runtime
 * collections and ensure repaired state is canonical on its next normal save.</p>
 */
public final class PersistedDataLimits {
    public static final int MAX_LABEL_CHARS = 64;
    public static final int MAX_PATH_CHARS = 256;
    public static final int MAX_ENV_ENTRIES = 128;
    public static final int MAX_ENV_KEY_CHARS = 64;
    public static final int MAX_ENV_VALUE_CHARS = 4096;
    public static final int MAX_OUTPUT_LINES = 2000;
    public static final int MAX_OUTPUT_LINE_CHARS = 4096;
    public static final int MAX_HISTORY_ENTRIES = 50;
    public static final int MAX_HISTORY_LINE_CHARS = 200;
    public static final int MAX_VFS_NODES = 4096;
    public static final int MAX_VFS_FILE_CHARS = 65_536;
    public static final int MAX_VFS_TOTAL_CHARS = 4 * 1024 * 1024;

    private PersistedDataLimits() {}

    /** Returns a string only when the stored tag has the expected type, then truncates it safely. */
    public static String readString(CompoundTag tag, String key, int maxChars, String fallback) {
        if (tag == null || !tag.contains(key, Tag.TAG_STRING)) {
            return fallback;
        }
        return truncate(tag.getString(key), maxChars);
    }

    public static String truncate(String value, int maxChars) {
        if (value == null) return "";
        int bounded = Math.max(0, maxChars);
        return value.length() <= bounded ? value : value.substring(0, bounded);
    }

    /** Reads, clamps, de-duplicates, sorts, and caps an integer array. */
    public static List<Integer> readBoundedIntArray(CompoundTag tag, String key, int min, int max,
                                                     int maxEntries) {
        if (tag == null || !tag.contains(key, Tag.TAG_INT_ARRAY) || maxEntries <= 0) {
            return List.of();
        }
        return java.util.Arrays.stream(tag.getIntArray(key))
                .map(value -> Math.max(min, Math.min(max, value)))
                .distinct().sorted().limit(maxEntries).boxed().toList();
    }

    /**
     * Returns the lexically first {@code maxEntries} keys while allocating only bounded selection
     * state. Iterating the source key view is unavoidable, but oversized compounds are not copied.
     */
    public static List<String> boundedSortedKeys(CompoundTag tag, int maxEntries) {
        if (tag == null || maxEntries <= 0) return List.of();
        TreeSet<String> selected = new TreeSet<>();
        for (String key : tag.getAllKeys()) {
            selected.add(key);
            if (selected.size() > maxEntries) selected.pollLast();
        }
        return List.copyOf(selected);
    }
}
