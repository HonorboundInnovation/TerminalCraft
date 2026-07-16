package com.malice.terminalcraft.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

/** Contract for blocks that can carry TerminalCraft wired data traffic. */
public interface WiredNetworkNode {
    /**
     * Allows stateful nodes such as module racks to disable forwarding. Stateless cables and
     * routers use the default implementation.
     */
    default boolean forwardsWiredTraffic(LevelReader level, BlockPos pos, BlockState state) {
        return true;
    }

    /** Whether entering this forwarding node consumes one RedNet router hop. */
    default boolean routesWiredTraffic(LevelReader level, BlockPos pos, BlockState state) {
        return false;
    }
}
