package com.malice.terminalcraft.item;

import java.util.Locale;

/** Bounded capacity tiers for portable TerminalCraft NAS media. */
public enum SolidStateDriveTier {
    BASIC("basic", 16_384, 64_000, 16, 4),
    ADVANCED("advanced", 65_536, 256_000, 64, 8),
    QUANTUM("quantum", 262_144, 1_024_000, 128, 16);

    private final String id;
    private final int itemCapacity;
    private final int fluidCapacityMb;
    private final int itemEntries;
    private final int fluidEntries;

    SolidStateDriveTier(String id, int itemCapacity, int fluidCapacityMb, int itemEntries, int fluidEntries) {
        this.id = id;
        this.itemCapacity = itemCapacity;
        this.fluidCapacityMb = fluidCapacityMb;
        this.itemEntries = itemEntries;
        this.fluidEntries = fluidEntries;
    }

    public String id() { return id; }
    public int itemCapacity() { return itemCapacity; }
    public int fluidCapacityMb() { return fluidCapacityMb; }
    public int itemEntries() { return itemEntries; }
    public int fluidEntries() { return fluidEntries; }

    public static SolidStateDriveTier parse(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (SolidStateDriveTier tier : values()) if (tier.name().equals(normalized) || tier.id.equalsIgnoreCase(value.trim())) return tier;
        return null;
    }
}
