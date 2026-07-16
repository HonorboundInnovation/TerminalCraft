package com.malice.terminalcraft.shell;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Common surface for placeable computers that host a {@link BashShell}.
 * Implemented by Terminal and Turtle block entities.
 */
public interface ShellComputer extends TerminalHost {
    BashShell getShell();

    void markShellChanged();

    Level getLevel();

    BlockPos getBlockPos();

    BlockState getBlockState();
}
