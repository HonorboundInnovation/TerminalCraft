package com.malice.terminalcraft.client;

import com.malice.terminalcraft.menu.TerminalMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;

/**
 * Client-only handlers for shell networking.
 * Kept separate so dedicated servers never need to resolve Minecraft client classes
 * from the common network packet path.
 */
public final class ClientShellPackets {
    private ClientShellPackets() {}

    public static void applyShell(int containerId, CompoundTag shellTag) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.containerMenu instanceof TerminalMenu menu
                && menu.containerId == containerId) {
            menu.getShell().load(shellTag);
        }
    }

    public static void applyEditorResult(int containerId, boolean success, boolean closed, String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.containerMenu instanceof TerminalMenu menu
                && menu.containerId == containerId && mc.screen instanceof TerminalScreen screen) {
            screen.applyEditorResult(success, closed, message);
        }
    }
}
