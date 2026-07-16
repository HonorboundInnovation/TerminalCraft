package com.malice.terminalcraft.device;

/** Single-media drive surface exposed independently from Minecraft item/NBT types. */
public interface DiskDriveDevice {
    boolean mediaPresent();
    String mediaLabel();
    void setMediaLabel(String label);
}
