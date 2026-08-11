package com.malice.terminalcraft.block;

import com.malice.terminalcraft.blockentity.RedAlloyWireBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
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

import java.util.ArrayList;
import java.util.List;

/** Compact six-sided, straight-through redstone regenerator for attenuating Red Alloy runs. */
public final class RedAlloyCapacitorBlock extends Block {
    private static final BooleanProperty DOWN = CableShapeSupport.DOWN;
    private static final BooleanProperty UP = CableShapeSupport.UP;
    private static final BooleanProperty NORTH = CableShapeSupport.NORTH;
    private static final BooleanProperty SOUTH = CableShapeSupport.SOUTH;
    private static final BooleanProperty WEST = CableShapeSupport.WEST;
    private static final BooleanProperty EAST = CableShapeSupport.EAST;
    private static final VoxelShape SHAPE = Shapes.or(
            box(3, 3, 3, 13, 13, 13),
            box(6, 6, 0, 10, 10, 3), box(6, 6, 13, 10, 10, 16),
            box(0, 6, 6, 3, 10, 10), box(13, 6, 6, 16, 10, 10),
            box(6, 0, 6, 10, 3, 10), box(6, 13, 6, 10, 16, 10));

    public RedAlloyCapacitorBlock() {
        super(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(1.0F, 2.0F)
                .noOcclusion().isRedstoneConductor((state, level, pos) -> false));
        BlockState state = stateDefinition.any();
        for (Direction direction : Direction.values()) state = state.setValue(property(direction), false);
        registerDefaultState(state);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(DOWN, UP, NORTH, SOUTH, WEST, EAST);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState previous, boolean moving) {
        super.onPlace(state, level, pos, previous, moving);
        if (!level.isClientSide && !state.is(previous.getBlock())) level.scheduleTick(pos, this, 1);
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (level instanceof Level realLevel && !realLevel.isClientSide) realLevel.scheduleTick(pos, this, 1);
        return state;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        BlockState updated = state;
        for (Direction output : Direction.values()) {
            Direction input = outputForInput(output);
            boolean active = restoredStrength(inputSignal(level, pos, input)) > 0;
            updated = updated.setValue(property(output), active);
        }
        if (updated.equals(state)) return;
        level.setBlock(pos, updated, Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS);
        for (Direction output : Direction.values()) {
            if (state.getValue(property(output)) != updated.getValue(property(output))) {
                level.updateNeighborsAt(pos.relative(output), this);
            }
        }
    }

    /** Reads one input face; callers map it to the directly opposing output face. */
    public static int inputSignal(Level level, BlockPos pos, Direction input) {
        BlockPos source = pos.relative(input);
        if (level.getBlockEntity(source) instanceof RedAlloyWireBlockEntity wire) {
            int maximum = 0;
            for (RedAlloyWireBlockEntity.Run run : wire.runs()) {
                if (run.face().getAxis() == input.getAxis()) continue;
                maximum = Math.max(maximum, RedAlloyWireBlock.nativePowerAt(level, source,
                        run.face(), run.lane(), run.color(), pos));
            }
            return maximum;
        }
        return Math.max(0, Math.min(15, level.getSignal(source, input.getOpposite())));
    }

    /** The capacitor is symmetric: an input and its output always lie on the same straight axis. */
    public static Direction outputForInput(Direction input) {
        if (input == null) throw new IllegalArgumentException("input side is required");
        return input.getOpposite();
    }

    public static int restoredStrength(int inputStrength) {
        return inputStrength > 0 ? 15 : 0;
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        return side != null && state.getValue(property(side)) ? 15 : 0;
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        return getSignal(state, level, pos, side);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable BlockGetter level, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable("item.terminalcraft.red_alloy_capacitor.tooltip"));
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (!WireInteractionSupport.isWrench(player.getItemInHand(hand))) return InteractionResult.PASS;
        if (!level.isClientSide) {
            List<String> paths = new ArrayList<>();
            for (Direction output : Direction.values()) {
                if (state.getValue(property(output))) {
                    paths.add(output.getOpposite().getName() + "->" + output.getName());
                }
            }
            player.displayClientMessage(Component.literal("Red Alloy Capacitor"), false);
            player.displayClientMessage(Component.literal("Mode: any input -> strength 15 on opposite face"), false);
            player.displayClientMessage(Component.literal("Active paths: "
                    + (paths.isEmpty() ? "none" : String.join(", ", paths))), false);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    private static BooleanProperty property(Direction direction) {
        return switch (direction) {
            case DOWN -> DOWN;
            case UP -> UP;
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            case EAST -> EAST;
        };
    }
}
