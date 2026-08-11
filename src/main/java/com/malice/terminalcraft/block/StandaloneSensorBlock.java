package com.malice.terminalcraft.block;

import com.malice.terminalcraft.blockentity.StandaloneSensorBlockEntity;
import com.malice.terminalcraft.registry.ModRegistries;
import com.malice.terminalcraft.sensor.SensorKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/** One fixed-family sensor that samples the block or world in its facing direction. */
public class StandaloneSensorBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    private final SensorKind kind;

    public StandaloneSensorBlock(SensorKind kind) {
        super(BlockBehaviour.Properties.of()
                .mapColor(mapColor(kind))
                .strength(1.5f, 3.0f)
                .requiresCorrectToolForDrops()
                .noOcclusion()
                .isRedstoneConductor((state, level, pos) -> false));
        this.kind = kind;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public SensorKind kind() { return kind; }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace().getOpposite());
    }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                               net.minecraft.world.phys.shapes.CollisionContext context) {
        return shapeFor(state.getValue(FACING));
    }

    private static VoxelShape shapeFor(Direction facing) {
        return switch (facing) {
            case NORTH -> Shapes.or(
                    box(3, 3, 0, 13, 13, 2), box(4, 4, 2, 12, 12, 4), box(6, 6, 4, 10, 10, 6));
            case SOUTH -> Shapes.or(
                    box(3, 3, 14, 13, 13, 16), box(4, 4, 12, 12, 12, 14), box(6, 6, 10, 10, 10, 12));
            case EAST -> Shapes.or(
                    box(14, 3, 3, 16, 13, 13), box(12, 4, 4, 14, 12, 12), box(10, 6, 6, 12, 10, 10));
            case WEST -> Shapes.or(
                    box(0, 3, 3, 2, 13, 13), box(2, 4, 4, 4, 12, 12), box(4, 6, 6, 6, 10, 10));
            case UP -> Shapes.or(
                    box(3, 14, 3, 13, 16, 13), box(4, 12, 4, 12, 14, 12), box(6, 10, 6, 10, 12, 10));
            case DOWN -> Shapes.or(
                    box(3, 0, 3, 13, 2, 13), box(4, 2, 4, 12, 4, 12), box(6, 4, 6, 10, 6, 10));
        };
    }

    private static VoxelShape box(int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        return Shapes.box(fromX / 16.0, fromY / 16.0, fromZ / 16.0,
                toX / 16.0, toY / 16.0, toZ / 16.0);
    }

    @Nullable
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new StandaloneSensorBlockEntity(pos, state);
    }

    @Nullable
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return createTickerHelper(type, ModRegistries.STANDALONE_SENSOR_BLOCK_ENTITY.get(),
                StandaloneSensorBlockEntity::serverTick);
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof StandaloneSensorBlockEntity sensor) {
            for (String line : sensor.summary()) player.displayClientMessage(Component.literal(line), false);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    @Override @SuppressWarnings("deprecation")
    public boolean isSignalSource(BlockState state) { return true; }

    @Override @SuppressWarnings("deprecation")
    public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        if (!(level.getBlockEntity(pos) instanceof StandaloneSensorBlockEntity sensor)) return 0;
        // A failed/cyclic probe is not a valid redstone level. Redstone output must fail closed.
        return Math.max(0, Math.min(15, sensor.signal()));
    }

    @Override @SuppressWarnings("deprecation")
    public int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        return getSignal(state, level, pos, side);
    }

    @Override @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        if (!state.is(replacement.getBlock()) && level.getBlockEntity(pos) instanceof StandaloneSensorBlockEntity sensor) {
            sensor.setRemoved();
            level.removeBlockEntity(pos);
        }
        super.onRemove(state, level, pos, replacement, moving);
    }

    private static MapColor mapColor(SensorKind kind) {
        return switch (kind) {
            case REDSTONE -> MapColor.COLOR_RED;
            case BLOCK_STATE, MACHINE -> MapColor.COLOR_GRAY;
            case INVENTORY -> MapColor.COLOR_BROWN;
            case FLUID -> MapColor.COLOR_BLUE;
            case ENERGY -> MapColor.COLOR_YELLOW;
            case ENTITY -> MapColor.COLOR_PURPLE;
            case ENVIRONMENT -> MapColor.COLOR_GREEN;
            case NETWORK -> MapColor.COLOR_LIGHT_BLUE;
            case KINETIC -> MapColor.COLOR_ORANGE;
            case CHEMICAL -> MapColor.COLOR_CYAN;
        };
    }
}
