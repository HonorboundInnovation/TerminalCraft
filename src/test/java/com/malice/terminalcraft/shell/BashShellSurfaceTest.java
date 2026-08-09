package com.malice.terminalcraft.shell;

import com.malice.terminalcraft.device.TerminalBuffer;
import net.minecraft.nbt.CompoundTag;

/** Headless coverage for the shell’s shared terminal-surface snapshot. */
public final class BashShellSurfaceTest {
    private BashShellSurfaceTest() {}

    public static void main(String[] args) {
        BashShell shell = new BashShell();
        require(shell.terminalSurface().width() == 40 && shell.terminalSurface().height() == 16,
                "shell surface dimensions remain bounded");
        shell.executeForResult("echo surface-ready");
        require(containsLine(shell.terminalSurface(), "surface-ready"),
                "visible shell output updates the character-cell surface");

        CompoundTag saved = shell.save();
        BashShell restored = new BashShell();
        restored.load(saved);
        require(containsLine(restored.terminalSurface(), "surface-ready"),
                "shell surface survives persistence and client sync snapshots");

        CompoundTag legacy = shell.save();
        legacy.remove("Surface");
        BashShell legacyRestored = new BashShell();
        legacyRestored.load(legacy);
        require(containsLine(legacyRestored.terminalSurface(), "surface-ready"),
                "legacy shell snapshots reconstruct the visible surface");
        System.out.println("Bash shell surface tests: OK");
    }

    private static boolean containsLine(TerminalBuffer surface, String expected) {
        for (String line : surface.lines()) if (line.contains(expected)) return true;
        return false;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
