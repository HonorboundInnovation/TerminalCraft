package com.malice.terminalcraft.block;

import com.malice.terminalcraft.blockentity.RefinedStorageBridgeBlockEntity;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;

/** Visible, dedicated attachment boundary for an adjacent Refined Storage network node. */
public final class RefinedStorageBridgeBlock extends BaseEntityBlock {
    public RefinedStorageBridgeBlock() {
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
        return new RefinedStorageBridgeBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return createTickerHelper(type, ModRegistries.REFINED_STORAGE_BRIDGE_BLOCK_ENTITY.get(),
                RefinedStorageBridgeBlockEntity::serverTick);
    }
}
