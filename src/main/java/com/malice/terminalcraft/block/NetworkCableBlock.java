package com.malice.terminalcraft.block;

import com.malice.terminalcraft.blockentity.NetworkCableBlockEntity;
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

/** Thin multipart surface cable for wired RedNet, with Red-Alloy-style corner routing. */
public class NetworkCableBlock extends BaseEntityBlock implements WiredNetworkNode {
    public static final DirectionProperty FACE = DirectionProperty.create("face");

    public NetworkCableBlock() {
        super(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_CYAN).strength(0.2f)
                .noCollission().noOcclusion());
        registerDefaultState(CableShapeSupport.disconnected(stateDefinition.any().setValue(FACE, Direction.UP)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACE, CableShapeSupport.DOWN, CableShapeSupport.UP, CableShapeSupport.NORTH,
                CableShapeSupport.SOUTH, CableShapeSupport.WEST, CableShapeSupport.EAST);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new NetworkCableBlockEntity(pos, state);
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
        return renderState(level, pos, state.getValue(FACE));
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (!(level.getBlockEntity(pos) instanceof NetworkCableBlockEntity cable)) {
            return faceShape(level, pos, state.getValue(FACE));
        }
        VoxelShape shape = Shapes.empty();
        for (Direction face : cable.faces()) shape = Shapes.or(shape, faceShape(level, pos, face));
        return shape.isEmpty() ? faceShape(level, pos, state.getValue(FACE)) : shape.optimize();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!(level.getBlockEntity(pos) instanceof NetworkCableBlockEntity cable)) return;
        for (Direction face : cable.faces()) {
            if (!canFaceSurvive(level, pos, face)) removeFace(level, pos, face, true);
        }
        if (level.getBlockEntity(pos) instanceof NetworkCableBlockEntity remaining) syncPrimaryState(level, pos, remaining);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState previous, boolean moving) {
        super.onPlace(state, level, pos, previous, moving);
        if (!level.isClientSide && !state.is(previous.getBlock())) level.scheduleTick(pos, this, 1);
    }

    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player,
                                       boolean willHarvest, FluidState fluid) {
        if (level.getBlockEntity(pos) instanceof NetworkCableBlockEntity cable && cable.faceCount() > 1) {
            Direction selected = targetedFace(level, pos, player.getEyePosition(),
                    player.getEyePosition().add(player.getViewVector(1.0F).scale(player.getBlockReach() + 1.0D)));
            if (selected == null || !cable.hasFace(selected)) selected = state.getValue(FACE);
            removeFace(level, pos, selected, willHarvest && !player.isCreative());
            return false;
        }
        return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }

    public static boolean addFace(Level level, BlockPos pos, Direction face) {
        if (level.isClientSide || !canFaceSurvive(level, pos, face)) return false;
        if (!(level.getBlockEntity(pos) instanceof NetworkCableBlockEntity cable)
                || cable.hasFace(face.getOpposite()) || !cable.addFace(face)) return false;
        syncPrimaryState(level, pos, cable);
        notifyTopology(level, pos);
        return true;
    }

    public static boolean removeFace(Level level, BlockPos pos, Direction face, boolean drop) {
        if (!(level.getBlockEntity(pos) instanceof NetworkCableBlockEntity cable) || !cable.removeFace(face)) return false;
        if (!level.isClientSide && drop) {
            popResourceFromFace(level, pos, face, ModRegistries.NETWORK_CABLE_ITEM.get().getDefaultInstance());
        }
        if (cable.faceCount() == 0) level.removeBlock(pos, false);
        else syncPrimaryState(level, pos, cable);
        notifyTopology(level, pos);
        return true;
    }

    public static boolean hasFace(BlockGetter level, BlockPos pos, Direction face) {
        if (level.getBlockEntity(pos) instanceof NetworkCableBlockEntity cable) return cable.hasFace(face);
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof NetworkCableBlock && state.hasProperty(FACE)
                && state.getValue(FACE) == face;
    }

    private static Set<Direction> occupiedFaces(BlockGetter level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof NetworkCableBlockEntity cable) return cable.faces();
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof NetworkCableBlock && state.hasProperty(FACE)
                ? Set.of(state.getValue(FACE)) : Set.of();
    }

    /** Block positions physically joined to any occupied face, including routed external corners. */
    public static Set<BlockPos> networkNeighbors(LevelAccessor level, BlockPos pos) {
        Set<BlockPos> result = new HashSet<>();
        Set<Direction> faces = occupiedFaces(level, pos);
        if (faces.isEmpty()) return result;
        for (Direction face : faces) {
            for (Node node : connectedNodes(level, new Node(pos, face))) {
                if (!node.pos().equals(pos)) result.add(node.pos());
            }
            for (Direction direction : planeDirections(face)) {
                BlockPos adjacent = pos.relative(direction);
                BlockState state = level.getBlockState(adjacent);
                if (!(state.getBlock() instanceof NetworkCableBlock)
                        && (state.getBlock() instanceof WiredNetworkNode || state.getBlock() instanceof ModemBlock)) {
                    result.add(adjacent.immutable());
                }
            }
        }
        return Set.copyOf(result);
    }

    public static BlockState renderState(BlockGetter level, BlockPos pos, Direction face) {
        BlockState state = ModRegistries.NETWORK_CABLE_BLOCK.get().defaultBlockState().setValue(FACE, face);
        if (!(level instanceof LevelAccessor accessor)) return state;
        Node node = new Node(pos, face);
        Set<Node> cableNeighbors = connectedNodes(accessor, node);
        Set<BlockPos> networkNeighbors = networkNeighborsForFace(accessor, node);
        for (Direction direction : Direction.values()) {
            boolean connected = direction.getAxis() != face.getAxis()
                    && (cableNeighbors.stream().anyMatch(next -> armDirection(node, next) == direction)
                    || networkNeighbors.contains(pos.relative(direction)));
            state = state.setValue(CableShapeSupport.property(direction), connected);
        }
        return state;
    }

    public static boolean isConnected(BlockState state, Direction direction) {
        return state.getValue(CableShapeSupport.property(direction));
    }

    @Nullable
    public static Direction targetedFace(BlockGetter level, BlockPos pos, Vec3 start, Vec3 end) {
        if (!(level.getBlockEntity(pos) instanceof NetworkCableBlockEntity cable)) return null;
        Direction nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (Direction face : cable.faces()) {
            BlockHitResult hit = faceShape(level, pos, face).clip(start, end, pos);
            if (hit != null && start.distanceToSqr(hit.getLocation()) < distance) {
                nearest = face;
                distance = start.distanceToSqr(hit.getLocation());
            }
        }
        return nearest;
    }

    private static Set<Node> connectedNodes(LevelAccessor level, Node node) {
        Set<Node> result = new HashSet<>();
        if (!hasFace(level, node.pos(), node.face())) return result;
        if (level.getBlockEntity(node.pos()) instanceof NetworkCableBlockEntity cable) {
            for (Direction other : cable.faces()) {
                if (other != node.face() && other != node.face().getOpposite()) result.add(new Node(node.pos(), other));
            }
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

    private static Set<BlockPos> networkNeighborsForFace(LevelAccessor level, Node node) {
        Set<BlockPos> result = new HashSet<>();
        for (Direction direction : planeDirections(node.face())) {
            BlockPos adjacent = node.pos().relative(direction);
            BlockState state = level.getBlockState(adjacent);
            if (!(state.getBlock() instanceof NetworkCableBlock)
                    && (state.getBlock() instanceof WiredNetworkNode || state.getBlock() instanceof ModemBlock)) {
                result.add(adjacent);
            }
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

    private static void syncPrimaryState(Level level, BlockPos pos, NetworkCableBlockEntity cable) {
        BlockState current = level.getBlockState(pos);
        Direction primary = current.hasProperty(FACE) && cable.hasFace(current.getValue(FACE))
                ? current.getValue(FACE) : cable.faces().iterator().next();
        BlockState rendered = renderState(level, pos, primary);
        if (!current.equals(rendered)) level.setBlock(pos, rendered, Block.UPDATE_CLIENTS);
    }

    private static void notifyTopology(Level level, BlockPos pos) {
        if (level.isClientSide) return;
        level.updateNeighborsAt(pos, ModRegistries.NETWORK_CABLE_BLOCK.get());
        for (Direction direction : Direction.values()) level.updateNeighborsAt(pos.relative(direction),
                ModRegistries.NETWORK_CABLE_BLOCK.get());
    }

    private static boolean canFaceSurvive(LevelReader level, BlockPos pos, Direction face) {
        BlockPos support = pos.relative(face.getOpposite());
        return level.getBlockState(support).isFaceSturdy(level, support, face);
    }

    private static VoxelShape faceShape(BlockGetter level, BlockPos pos, Direction face) {
        BlockState rendered = renderState(level, pos, face);
        VoxelShape shape = faceCoreShape(face);
        for (Direction direction : planeDirections(face)) {
            if (isConnected(rendered, direction)) shape = Shapes.or(shape, faceArmShape(face, direction));
        }
        return shape.optimize();
    }

    private static VoxelShape faceCoreShape(Direction face) {
        return switch (face) {
            case UP -> Block.box(7, 0, 7, 9, 2, 9); case DOWN -> Block.box(7, 14, 7, 9, 16, 9);
            case NORTH -> Block.box(7, 7, 14, 9, 9, 16); case SOUTH -> Block.box(7, 7, 0, 9, 9, 2);
            case WEST -> Block.box(14, 7, 7, 16, 9, 9); case EAST -> Block.box(0, 7, 7, 2, 9, 9);
        };
    }

    private static VoxelShape faceArmShape(Direction face, Direction arm) {
        return switch (face) {
            case UP -> switch (arm) { case NORTH -> Block.box(7,0,0,9,2,7); case SOUTH -> Block.box(7,0,9,9,2,16); case WEST -> Block.box(0,0,7,7,2,9); case EAST -> Block.box(9,0,7,16,2,9); default -> Shapes.empty(); };
            case DOWN -> switch (arm) { case NORTH -> Block.box(7,14,0,9,16,7); case SOUTH -> Block.box(7,14,9,9,16,16); case WEST -> Block.box(0,14,7,7,16,9); case EAST -> Block.box(9,14,7,16,16,9); default -> Shapes.empty(); };
            case NORTH -> switch (arm) { case DOWN -> Block.box(7,0,14,9,7,16); case UP -> Block.box(7,9,14,9,16,16); case WEST -> Block.box(0,7,14,7,9,16); case EAST -> Block.box(9,7,14,16,9,16); default -> Shapes.empty(); };
            case SOUTH -> switch (arm) { case DOWN -> Block.box(7,0,0,9,7,2); case UP -> Block.box(7,9,0,9,16,2); case WEST -> Block.box(0,7,0,7,9,2); case EAST -> Block.box(9,7,0,16,9,2); default -> Shapes.empty(); };
            case WEST -> switch (arm) { case DOWN -> Block.box(14,0,7,16,7,9); case UP -> Block.box(14,9,7,16,16,9); case NORTH -> Block.box(14,7,0,16,9,7); case SOUTH -> Block.box(14,7,9,16,9,16); default -> Shapes.empty(); };
            case EAST -> switch (arm) { case DOWN -> Block.box(0,0,7,2,7,9); case UP -> Block.box(0,9,7,2,16,9); case NORTH -> Block.box(0,7,0,2,9,7); case SOUTH -> Block.box(0,7,9,2,9,16); default -> Shapes.empty(); };
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
