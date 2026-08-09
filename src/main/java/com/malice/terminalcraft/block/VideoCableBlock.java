package com.malice.terminalcraft.block;

import com.malice.terminalcraft.blockentity.MonitorBlockEntity;
import com.malice.terminalcraft.blockentity.VideoCableBlockEntity;
import com.malice.terminalcraft.shell.ShellComputer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkHooks;

/** Routed six-direction video cable for direct terminal/PLC-to-monitor links. */
public class VideoCableBlock extends BaseEntityBlock {
    public static final BooleanProperty NORTH = BooleanProperty.create("north");
    public static final BooleanProperty SOUTH = BooleanProperty.create("south");
    public static final BooleanProperty EAST = BooleanProperty.create("east");
    public static final BooleanProperty WEST = BooleanProperty.create("west");
    public static final BooleanProperty UP = BooleanProperty.create("up");
    public static final BooleanProperty DOWN = BooleanProperty.create("down");

    public VideoCableBlock() {
        super(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLUE).strength(0.25f)
                .noCollission().noOcclusion().isRedstoneConductor((state, level, pos) -> false));
        registerDefaultState(stateDefinition.any().setValue(NORTH, false).setValue(SOUTH, false)
                .setValue(EAST, false).setValue(WEST, false).setValue(UP, false).setValue(DOWN, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, SOUTH, EAST, WEST, UP, DOWN);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return connectionState(context.getLevel(), context.getClickedPos(), defaultBlockState());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VideoCableBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) { return null; }

    @Override
    @SuppressWarnings("deprecation")
    public net.minecraft.world.InteractionResult use(BlockState state, Level level, BlockPos pos,
                                                      net.minecraft.world.entity.player.Player player,
                                                      net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return net.minecraft.world.InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof VideoCableBlockEntity cable && player instanceof ServerPlayer serverPlayer) {
            NetworkHooks.openScreen(serverPlayer, cable, buffer -> {
                buffer.writeByte(com.malice.terminalcraft.menu.DisplayDiagnosticsMenu.TYPE_CABLE);
                buffer.writeBlockPos(pos);
            });
            return net.minecraft.world.InteractionResult.CONSUME;
        }
        return net.minecraft.world.InteractionResult.PASS;
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return connectionState(level, pos, state);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block,
                                BlockPos fromPos, boolean moving) {
        BlockState next = connectionState(level, pos, state);
        if (!next.equals(state)) level.setBlock(pos, next, Block.UPDATE_CLIENTS);
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape shape = Block.box(6, 6, 6, 10, 10, 10);
        if (state.getValue(NORTH)) shape = Shapes.or(shape, Block.box(6, 6, 0, 10, 10, 6));
        if (state.getValue(SOUTH)) shape = Shapes.or(shape, Block.box(6, 6, 10, 10, 10, 16));
        if (state.getValue(EAST)) shape = Shapes.or(shape, Block.box(10, 6, 6, 16, 10, 10));
        if (state.getValue(WEST)) shape = Shapes.or(shape, Block.box(0, 6, 6, 6, 10, 10));
        if (state.getValue(UP)) shape = Shapes.or(shape, Block.box(6, 10, 6, 10, 16, 10));
        if (state.getValue(DOWN)) shape = Shapes.or(shape, Block.box(6, 0, 6, 10, 6, 10));
        return shape.optimize();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        super.onRemove(state, level, pos, replacement, moving);
        if (!state.is(replacement.getBlock())) {
            for (Direction direction : Direction.values()) level.updateNeighborsAt(pos.relative(direction), this);
        }
    }

    private static BlockState connectionState(BlockGetter level, BlockPos pos, BlockState state) {
        return state.setValue(NORTH, connects(level, pos, Direction.NORTH))
                .setValue(SOUTH, connects(level, pos, Direction.SOUTH))
                .setValue(EAST, connects(level, pos, Direction.EAST))
                .setValue(WEST, connects(level, pos, Direction.WEST))
                .setValue(UP, connects(level, pos, Direction.UP))
                .setValue(DOWN, connects(level, pos, Direction.DOWN));
    }

    private static boolean connects(BlockGetter level, BlockPos pos, Direction direction) {
        BlockPos adjacent = pos.relative(direction);
        if (level.getBlockState(adjacent).getBlock() instanceof VideoCableBlock) return true;
        BlockEntity entity = level.getBlockEntity(adjacent);
        return entity instanceof ShellComputer || entity instanceof MonitorBlockEntity;
    }

    public static BooleanProperty property(Direction direction) {
        return switch (direction) {
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case EAST -> EAST;
            case WEST -> WEST;
            case UP -> UP;
            case DOWN -> DOWN;
        };
    }
}
