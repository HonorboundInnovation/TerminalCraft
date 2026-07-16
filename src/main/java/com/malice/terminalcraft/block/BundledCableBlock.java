package com.malice.terminalcraft.block;

import com.malice.terminalcraft.blockentity.BundledCableBlockEntity;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/** Sixteen-channel RedPower-style surface cable; channel zero interoperates with vanilla redstone. */
public class BundledCableBlock extends BaseEntityBlock {
    public static final DirectionProperty FACE = DirectionProperty.create("face");
    public static final IntegerProperty POWER = IntegerProperty.create("power", 0, 15);

    public BundledCableBlock() {
        super(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(0.2f)
                .noCollission().noOcclusion());
        registerDefaultState(CableShapeSupport.disconnected(stateDefinition.any()
                .setValue(FACE, Direction.UP).setValue(POWER, 0)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACE, POWER, CableShapeSupport.DOWN, CableShapeSupport.UP,
                CableShapeSupport.NORTH, CableShapeSupport.SOUTH,
                CableShapeSupport.WEST, CableShapeSupport.EAST);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BundledCableBlockEntity(pos, state);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        return canFaceSurvive(context.getLevel(), context.getClickedPos(), face)
                ? defaultBlockState().setValue(FACE, face) : null;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override
    @SuppressWarnings("deprecation")
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return canFaceSurvive(level, pos, state.getValue(FACE));
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (level instanceof Level realLevel && !realLevel.isClientSide) realLevel.scheduleTick(pos, this, 1);
        return renderState(level, pos, state.getValue(FACE)).setValue(POWER, state.getValue(POWER));
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Set<Direction> faces = occupiedFaces(level, pos);
        if (faces.isEmpty()) return faceShape(level, pos, state.getValue(FACE));
        VoxelShape shape = Shapes.empty();
        for (Direction face : faces) shape = Shapes.or(shape, faceShape(level, pos, face));
        return shape.optimize();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!(level.getBlockEntity(pos) instanceof BundledCableBlockEntity cable)) return;
        for (Direction face : cable.faces()) {
            if (!canFaceSurvive(level, pos, face)) removeFace(level, pos, face, true);
        }
        if (level.getBlockEntity(pos) instanceof BundledCableBlockEntity remaining) {
            syncPrimaryState(level, pos, remaining);
            remaining.refreshVanillaInput();
            remaining.recomputeComponent();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState previous, boolean moving) {
        super.onPlace(state, level, pos, previous, moving);
        if (!level.isClientSide && !state.is(previous.getBlock())) level.scheduleTick(pos, this, 1);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                BlockPos neighborPos, boolean moving) {
        if (!level.isClientSide) level.scheduleTick(pos, this, 1);
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean isSignalSource(BlockState state) { return true; }

    @Override
    @SuppressWarnings("deprecation")
    public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(POWER);
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(POWER);
    }

    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player,
                                       boolean willHarvest, FluidState fluid) {
        if (level.getBlockEntity(pos) instanceof BundledCableBlockEntity cable && cable.faceCount() > 1) {
            Direction selected = targetedFace(level, pos, player.getEyePosition(),
                    player.getEyePosition().add(player.getViewVector(1.0F).scale(player.getBlockReach() + 1.0D)));
            if (selected == null || !cable.hasFace(selected)) selected = state.getValue(FACE);
            removeFace(level, pos, selected, willHarvest && !player.isCreative());
            return false;
        }
        return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        Set<BlockPos> formerNeighbors = connectedCablePositions(level, pos);
        super.onRemove(state, level, pos, replacement, moving);
        if (!level.isClientSide && !state.is(replacement.getBlock())) recomputePositions(level, formerNeighbors);
    }

    public static boolean addFace(Level level, BlockPos pos, Direction face) {
        if (level.isClientSide || !canFaceSurvive(level, pos, face)) return false;
        if (!(level.getBlockEntity(pos) instanceof BundledCableBlockEntity cable)
                || cable.hasFace(face.getOpposite()) || !cable.addFace(face)) return false;
        syncPrimaryState(level, pos, cable);
        cable.refreshVanillaInput();
        cable.recomputeComponent();
        notifyNeighbors(level, pos);
        return true;
    }

    public static boolean removeFace(Level level, BlockPos pos, Direction face, boolean drop) {
        if (!(level.getBlockEntity(pos) instanceof BundledCableBlockEntity cable) || !cable.hasFace(face)) return false;
        Set<BlockPos> formerNeighbors = connectedCablePositions(level, pos);
        cable.removeFace(face);
        if (!level.isClientSide && drop) {
            popResourceFromFace(level, pos, face, ModRegistries.BUNDLED_CABLE_ITEM.get().getDefaultInstance());
        }
        if (cable.faceCount() == 0) level.removeBlock(pos, false);
        else {
            syncPrimaryState(level, pos, cable);
            cable.refreshVanillaInput();
            cable.recomputeComponent();
        }
        recomputePositions(level, formerNeighbors);
        notifyNeighbors(level, pos);
        return true;
    }

    public static boolean hasFace(BlockGetter level, BlockPos pos, Direction face) {
        if (level.getBlockEntity(pos) instanceof BundledCableBlockEntity cable) return cable.hasFace(face);
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof BundledCableBlock && state.hasProperty(FACE)
                && state.getValue(FACE) == face;
    }

    private static Set<Direction> occupiedFaces(BlockGetter level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof BundledCableBlockEntity cable) return cable.faces();
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof BundledCableBlock && state.hasProperty(FACE)
                ? Set.of(state.getValue(FACE)) : Set.of();
    }

    /** Returns cable block positions connected through coplanar, internal, or external corners. */
    public static Set<BlockPos> connectedCablePositions(LevelAccessor level, BlockPos pos) {
        Set<BlockPos> result = new HashSet<>();
        for (Direction face : occupiedFaces(level, pos)) {
            for (Node neighbor : connectedNodes(level, new Node(pos, face))) {
                if (!neighbor.pos().equals(pos)) result.add(neighbor.pos());
            }
        }
        return Set.copyOf(result);
    }

    public static BlockState renderState(BlockGetter level, BlockPos pos, Direction face) {
        int power = level.getBlockState(pos).hasProperty(POWER) ? level.getBlockState(pos).getValue(POWER) : 0;
        BlockState state = ModRegistries.BUNDLED_CABLE_BLOCK.get().defaultBlockState()
                .setValue(FACE, face).setValue(POWER, power);
        if (!(level instanceof LevelAccessor accessor)) return state;
        Node node = new Node(pos, face);
        Set<Node> cableNeighbors = connectedNodes(accessor, node);
        for (Direction direction : planeDirections(face)) {
            boolean device = canAttachDevice(accessor.getBlockState(pos.relative(direction)));
            boolean connected = device || cableNeighbors.stream().anyMatch(next -> armDirection(node, next) == direction);
            state = state.setValue(CableShapeSupport.property(direction), connected);
        }
        return state;
    }

    public static boolean isConnected(BlockState state, Direction direction) {
        return state.getValue(CableShapeSupport.property(direction));
    }

    @Nullable
    public static Direction targetedFace(BlockGetter level, BlockPos pos, Vec3 start, Vec3 end) {
        Direction nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (Direction face : occupiedFaces(level, pos)) {
            BlockHitResult hit = faceShape(level, pos, face).clip(start, end, pos);
            if (hit == null) continue;
            double distance = start.distanceToSqr(hit.getLocation());
            if (distance < nearestDistance) {
                nearest = face;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private static Set<Node> connectedNodes(LevelAccessor level, Node node) {
        Set<Node> result = new HashSet<>();
        if (!hasFace(level, node.pos(), node.face())) return result;
        for (Direction other : occupiedFaces(level, node.pos())) {
            if (other != node.face() && other != node.face().getOpposite()) result.add(new Node(node.pos(), other));
        }
        for (Direction direction : planeDirections(node.face())) {
            BlockPos direct = node.pos().relative(direction);
            if (hasFace(level, direct, node.face())) {
                result.add(new Node(direct, node.face()));
                continue;
            }
            BlockState bend = level.getBlockState(direct);
            if (!bend.isAir() && !bend.getFluidState().is(FluidTags.WATER)) continue;
            BlockPos around = direct.relative(node.face().getOpposite());
            if (hasFace(level, around, direction)) result.add(new Node(around, direction));
        }
        return result;
    }

    private static Direction armDirection(Node from, Node to) {
        if (from.pos().equals(to.pos())) return to.face().getOpposite();
        BlockPos delta = to.pos().subtract(from.pos());
        for (Direction direction : Direction.values()) {
            if (delta.getX() == direction.getStepX() && delta.getY() == direction.getStepY()
                    && delta.getZ() == direction.getStepZ()) return direction;
        }
        for (Direction direction : planeDirections(from.face())) {
            if (from.pos().relative(direction).relative(from.face().getOpposite()).equals(to.pos())) return direction;
        }
        return from.face().getOpposite();
    }

    private static void syncPrimaryState(Level level, BlockPos pos, BundledCableBlockEntity cable) {
        BlockState current = level.getBlockState(pos);
        Direction primary = current.hasProperty(FACE) && cable.hasFace(current.getValue(FACE))
                ? current.getValue(FACE) : cable.faces().iterator().next();
        BlockState rendered = renderState(level, pos, primary).setValue(POWER, cable.getSignal(0));
        if (!current.equals(rendered)) level.setBlock(pos, rendered, Block.UPDATE_CLIENTS);
    }

    private static void recomputePositions(Level level, Set<BlockPos> positions) {
        if (level.isClientSide) return;
        for (BlockPos candidate : positions) {
            if (level.getBlockEntity(candidate) instanceof BundledCableBlockEntity cable) {
                cable.refreshVanillaInput();
                cable.recomputeComponent();
            }
        }
    }

    private static void notifyNeighbors(Level level, BlockPos pos) {
        if (level.isClientSide) return;
        level.updateNeighborsAt(pos, ModRegistries.BUNDLED_CABLE_BLOCK.get());
        for (Direction direction : Direction.values()) {
            level.updateNeighborsAt(pos.relative(direction), ModRegistries.BUNDLED_CABLE_BLOCK.get());
        }
    }

    private static boolean canFaceSurvive(LevelReader level, BlockPos pos, Direction face) {
        BlockPos support = pos.relative(face.getOpposite());
        return level.getBlockState(support).isFaceSturdy(level, support, face);
    }

    private static boolean canAttachDevice(BlockState state) {
        return state.getBlock() instanceof TerminalBlock || state.getBlock() instanceof TurtleBlock;
    }

    private static VoxelShape faceShape(BlockGetter level, BlockPos pos, Direction face) {
        BlockState rendered = renderState(level, pos, face);
        VoxelShape shape = faceCoreShape(face);
        for (Direction direction : planeDirections(face)) {
            if (isConnected(rendered, direction)) shape = Shapes.or(shape, faceArmShape(face, direction));
        }
        return shape.optimize();
    }

    /** RedPower bundled-wire proportions: six pixels wide and four pixels thick. */
    private static VoxelShape faceCoreShape(Direction face) {
        return switch (face) {
            case UP -> Block.box(5, 0, 5, 11, 4, 11); case DOWN -> Block.box(5, 12, 5, 11, 16, 11);
            case NORTH -> Block.box(5, 5, 12, 11, 11, 16); case SOUTH -> Block.box(5, 5, 0, 11, 11, 4);
            case WEST -> Block.box(12, 5, 5, 16, 11, 11); case EAST -> Block.box(0, 5, 5, 4, 11, 11);
        };
    }

    private static VoxelShape faceArmShape(Direction face, Direction arm) {
        return switch (face) {
            case UP -> switch (arm) { case NORTH -> Block.box(5,0,0,11,4,5); case SOUTH -> Block.box(5,0,11,11,4,16); case WEST -> Block.box(0,0,5,5,4,11); case EAST -> Block.box(11,0,5,16,4,11); default -> Shapes.empty(); };
            case DOWN -> switch (arm) { case NORTH -> Block.box(5,12,0,11,16,5); case SOUTH -> Block.box(5,12,11,11,16,16); case WEST -> Block.box(0,12,5,5,16,11); case EAST -> Block.box(11,12,5,16,16,11); default -> Shapes.empty(); };
            case NORTH -> switch (arm) { case DOWN -> Block.box(5,0,12,11,5,16); case UP -> Block.box(5,11,12,11,16,16); case WEST -> Block.box(0,5,12,5,11,16); case EAST -> Block.box(11,5,12,16,11,16); default -> Shapes.empty(); };
            case SOUTH -> switch (arm) { case DOWN -> Block.box(5,0,0,11,5,4); case UP -> Block.box(5,11,0,11,16,4); case WEST -> Block.box(0,5,0,5,11,4); case EAST -> Block.box(11,5,0,16,11,4); default -> Shapes.empty(); };
            case WEST -> switch (arm) { case DOWN -> Block.box(12,0,5,16,5,11); case UP -> Block.box(12,11,5,16,16,11); case NORTH -> Block.box(12,5,0,16,11,5); case SOUTH -> Block.box(12,5,11,16,11,16); default -> Shapes.empty(); };
            case EAST -> switch (arm) { case DOWN -> Block.box(0,0,5,4,5,11); case UP -> Block.box(0,11,5,4,16,11); case NORTH -> Block.box(0,5,0,4,11,5); case SOUTH -> Block.box(0,5,11,4,11,16); default -> Shapes.empty(); };
        };
    }

    private static Direction[] planeDirections(Direction face) {
        return switch (face.getAxis()) {
            case X -> new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH};
            case Y -> new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
            case Z -> new Direction[]{Direction.DOWN, Direction.UP, Direction.WEST, Direction.EAST};
        };
    }

    private record Node(BlockPos pos, Direction face) { private Node { pos = pos.immutable(); } }
}
