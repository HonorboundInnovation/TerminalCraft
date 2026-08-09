package com.malice.terminalcraft.shell;

import com.malice.terminalcraft.device.TerminalBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Common surface for placeable computers that host a {@link BashShell}.
 * Implemented by Terminal and Turtle block entities.
 */
public interface ShellComputer extends TerminalHost {
    BashShell getShell();

    /** Shared bounded character-cell view used by passive terminal-screen clients. */
    default TerminalBuffer terminalSurface() { return getShell().terminalSurface(); }

    void markShellChanged();

    Level getLevel();

    BlockPos getBlockPos();

    BlockState getBlockState();
}
