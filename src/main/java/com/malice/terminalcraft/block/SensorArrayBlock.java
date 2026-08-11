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
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/** Placeable universal telemetry hub for vanilla and standard Forge capabilities. */
public class SensorArrayBlock extends BaseEntityBlock {
    public SensorArrayBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_CYAN)
                .strength(2.0f, 4.0f)
                .requiresCorrectToolForDrops()
                .noOcclusion()
                .isRedstoneConductor((state, level, pos) -> false));
    }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level,
                               BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext context) {
        return Shapes.or(
                box(3, 0, 3, 13, 2, 13),
                box(4, 2, 4, 12, 4, 12),
                box(6, 4, 6, 10, 8, 10),
                box(4, 6, 7, 6, 8, 9),
                box(10, 6, 7, 12, 8, 9),
                box(7, 7, 4, 9, 9, 6));
    }

    private static VoxelShape box(int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        return Shapes.box(fromX / 16.0, fromY / 16.0, fromZ / 16.0,
                toX / 16.0, toY / 16.0, toZ / 16.0);
    }

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
