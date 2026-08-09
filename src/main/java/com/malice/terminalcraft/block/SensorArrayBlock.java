package com.malice.terminalcraft.block;

import com.malice.terminalcraft.blockentity.SensorArrayBlockEntity;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** Placeable universal telemetry hub for vanilla and standard Forge capabilities. */
public class SensorArrayBlock extends BaseEntityBlock {
    public SensorArrayBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_CYAN)
                .strength(2.0f, 4.0f)
                .requiresCorrectToolForDrops()
                .isRedstoneConductor((state, level, pos) -> false));
    }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Nullable
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SensorArrayBlockEntity(pos, state);
    }

    @Nullable
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return createTickerHelper(type, ModRegistries.SENSOR_ARRAY_BLOCK_ENTITY.get(),
                SensorArrayBlockEntity::serverTick);
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof SensorArrayBlockEntity array) {
            for (String line : array.summary()) player.displayClientMessage(Component.literal(line), false);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        if (!state.is(replacement.getBlock()) && level.getBlockEntity(pos) instanceof SensorArrayBlockEntity array) {
            array.setRemoved();
            level.removeBlockEntity(pos);
        }
        super.onRemove(state, level, pos, replacement, moving);
    }
}
