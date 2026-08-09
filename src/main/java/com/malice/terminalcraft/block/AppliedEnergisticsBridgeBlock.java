package com.malice.terminalcraft.block;

import com.malice.terminalcraft.blockentity.AppliedEnergisticsBridgeBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;

/** Visible, dedicated attachment boundary for an adjacent Applied Energistics 2 grid. */
public final class AppliedEnergisticsBridgeBlock extends BaseEntityBlock {
    public AppliedEnergisticsBridgeBlock() {
        super(BlockBehaviour.Properties.of().mapColor(MapColor.METAL)
                .strength(2.5f, 6.0f).requiresCorrectToolForDrops());
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AppliedEnergisticsBridgeBlockEntity(pos, state);
    }
}
