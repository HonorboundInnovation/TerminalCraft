package com.malice.terminalcraft.block;

import com.malice.terminalcraft.blockentity.NetworkCableBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Sixteen-channel data/control trunk; colored network cables are channel-specific breakouts. */
public final class BundledNetworkCableBlock extends NetworkCableBlock {
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        return state == null ? null : state.setValue(COLOR, DyeColor.WHITE.getId());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new NetworkCableBlockEntity(pos, state);
    }

    /** Bundled network cable uses the same six-pixel model geometry as bundled Red Alloy cable. */
    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }
}
