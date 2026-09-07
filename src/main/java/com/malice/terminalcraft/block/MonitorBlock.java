package com.malice.terminalcraft.block;

import com.malice.terminalcraft.blockentity.MonitorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;
import com.malice.terminalcraft.registry.ModRegistries;

/**
 * External multi-line text display. Written by adjacent terminals via `monitor` shell command.
 * Right-click shows current lines in chat.
 */
public class MonitorBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    private static final net.minecraft.world.phys.shapes.VoxelShape NORTH_SHAPE =
            net.minecraft.world.phys.shapes.Shapes.or(Block.box(0, 0, 0, 16, 16, 3), Block.box(1, 1, 3, 15, 15, 4));
    private static final net.minecraft.world.phys.shapes.VoxelShape SOUTH_SHAPE =
            net.minecraft.world.phys.shapes.Shapes.or(Block.box(0, 0, 13, 16, 16, 16), Block.box(1, 1, 12, 15, 15, 13));
    private static final net.minecraft.world.phys.shapes.VoxelShape EAST_SHAPE =
            net.minecraft.world.phys.shapes.Shapes.or(Block.box(13, 0, 0, 16, 16, 16), Block.box(12, 1, 1, 13, 15, 15));
    private static final net.minecraft.world.phys.shapes.VoxelShape WEST_SHAPE =
            net.minecraft.world.phys.shapes.Shapes.or(Block.box(0, 0, 0, 3, 16, 16), Block.box(3, 1, 1, 4, 15, 15));

    public MonitorBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_BLACK)
                .strength(2.0f, 4.0f)
                .noOcclusion());
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState state,
            net.minecraft.world.level.BlockGetter level, BlockPos pos,
            net.minecraft.world.phys.shapes.CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SOUTH_SHAPE;
            case EAST -> EAST_SHAPE;
            case WEST -> WEST_SHAPE;
            default -> NORTH_SHAPE;
        };
    }

    @Override
    public BlockState rotate(BlockState state, net.minecraft.world.level.block.Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    public BlockState mirror(BlockState state, net.minecraft.world.level.block.Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MonitorBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                   BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return createTickerHelper(type, ModRegistries.MONITOR_BLOCK_ENTITY.get(), MonitorBlockEntity::serverTick);
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                  InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof MonitorBlockEntity monitor) {
            boolean configure = WireInteractionSupport.isWrench(player.getItemInHand(hand))
                    || player.isShiftKeyDown() && player.getItemInHand(hand).isEmpty();
            if (configure
                    && player instanceof ServerPlayer serverPlayer) {
                NetworkHooks.openScreen(serverPlayer, monitor, buffer -> {
                    buffer.writeByte(com.malice.terminalcraft.menu.DisplayDiagnosticsMenu.TYPE_MONITOR);
                    buffer.writeBlockPos(pos);
                });
                return InteractionResult.CONSUME;
            }
            if (player.isShiftKeyDown()) {
                player.displayClientMessage(monitor.asChatComponent(), false);
            } else {
                monitor.publishTouch(hit.getLocation(), player);
            }
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            level.removeBlockEntity(pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}
