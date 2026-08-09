package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Dedicated Applied Energistics 2 attachment with no local storage or mutation authority. */
public final class AppliedEnergisticsBridgeBlockEntity extends BlockEntity {
    public AppliedEnergisticsBridgeBlockEntity(BlockPos position, BlockState state) {
        this(ModRegistries.APPLIED_ENERGISTICS_BRIDGE_BLOCK_ENTITY.get(), position, state);
    }

    /** Package-private construction seam for deterministic block-entity tests. */
    public AppliedEnergisticsBridgeBlockEntity(BlockEntityType<?> type, BlockPos position, BlockState state) {
        super(type, position, state);
    }
}
