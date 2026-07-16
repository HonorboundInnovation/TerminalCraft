package com.malice.terminalcraft.server;

import net.minecraft.util.StringRepresentable;

/** Module installed in one half-height bay of a server rack. */
public enum RackModuleType implements StringRepresentable {
    EMPTY("empty"),
    SERVER("server"),
    ROUTER("router");

    private final String serializedName;

    RackModuleType(String serializedName) {
        this.serializedName = serializedName;
    }

    @Override
    public String getSerializedName() {
        return serializedName;
    }
}
