package com.malice.terminalcraft.sensor;

import java.util.Locale;

/** Bounded, implementation-neutral families of signals a Sensor Array can sample. */
public enum SensorKind {
    REDSTONE,
    BLOCK_STATE,
    INVENTORY,
    FLUID,
    ENERGY,
    ENTITY,
    MACHINE,
    ENVIRONMENT,
    NETWORK,
    KINETIC,
    CHEMICAL;

    public static SensorKind parse(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "RS", "REDSTONE_INPUT" -> REDSTONE;
            case "BLOCK", "STATE" -> BLOCK_STATE;
            case "ITEM", "ITEMS", "STORAGE" -> INVENTORY;
            case "TANK", "TANKS", "LIQUID", "FLUIDS" -> FLUID;
            case "FE", "POWER", "ELECTRIC" -> ENERGY;
            case "MOBS", "ENTITIES" -> ENTITY;
            case "MACHINES", "PROCESSOR" -> MACHINE;
            case "ENV", "WORLD" -> ENVIRONMENT;
            case "REDNET", "NETWORKING" -> NETWORK;
            case "CREATE", "ROTATION" -> KINETIC;
            case "CHEM", "CHEMICALS" -> CHEMICAL;
            default -> {
                try { yield valueOf(normalized); }
                catch (IllegalArgumentException ignored) { yield null; }
            }
        };
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
