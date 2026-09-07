package com.malice.terminalcraft.persistence;

import com.malice.terminalcraft.shell.BashShell;
import net.minecraft.nbt.CompoundTag;

/** Saved shells must have a supported filesystem; client views use a separate load route. */
public final class ComputerShellPersistence {
    private ComputerShellPersistence() {}

    public static void load(BashShell shell, CompoundTag saved) {
        try {
            PocketStorage.validateWritableShell(saved);
        } catch (java.io.IOException unsupported) {
            shell.preserveUnreadableSnapshot(saved);
            return;
        }
        shell.load(saved);
    }
}
