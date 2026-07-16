package com.malice.terminalcraft.block;

import com.malice.terminalcraft.blockentity.RedAlloyWireBlockEntity;
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

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Multipart red-alloy wire: up to six supported face parts may share one block space. */
public class RedAlloyWireBlock extends BaseEntityBlock {
    public static final DirectionProperty FACE = DirectionProperty.create("face");
    public static final IntegerProperty POWER = IntegerProperty.create("power", 0, 15);
    public static final int MAX_COMPONENT_SIZE = 4096;
    private static final ThreadLocal<Boolean> UPDATING = ThreadLocal.withInitial(() -> false);

    public RedAlloyWireBlock() {
        super(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(0.2f)
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
        return new RedAlloyWireBlockEntity(pos, state);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        BlockState state = defaultBlockState().setValue(FACE, face);
        return canFaceSurvive(context.getLevel(), context.getClickedPos(), face) ? state : null;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        // The primary face uses baked block models; additional faces are rendered by the block entity renderer.
        return RenderShape.MODEL;
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return canFaceSurvive(level, pos, state.getValue(FACE));
    }

    @Override
    @SuppressWarnings("deprecation")
    public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        // Defer support removal to the server tick so it uses the same drop-producing multipart
        // removal path as targeted harvesting. Mutating the block entity here silently lost parts.
        if (level instanceof Level realLevel && !realLevel.isClientSide) realLevel.scheduleTick(pos, this, 1);
        return state;
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (!(level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire)) {
            return faceShape(level, pos, state.getValue(FACE));
        }
        VoxelShape shape = Shapes.empty();
        for (Direction face : wire.faces()) shape = Shapes.or(shape, faceShape(level, pos, face));
        return shape.isEmpty() ? faceShape(level, pos, state.getValue(FACE)) : shape.optimize();
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        if (UPDATING.get()) return 0;
        return level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire
                ? wire.maximumPower() : state.getValue(POWER);
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        if (UPDATING.get()) return 0;
        if (level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire) return wire.power(direction);
        return direction == state.getValue(FACE) ? state.getValue(POWER) : 0;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                BlockPos neighborPos, boolean moving) {
        if (!level.isClientSide) level.scheduleTick(pos, this, 1);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        removeUnsupportedFaces(level, pos);
        if (level.getBlockState(pos).getBlock() instanceof RedAlloyWireBlock) recomputeAt(level, pos);
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
        if (level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire && wire.faceCount() > 1) {
            Direction selected = targetedFace(level, pos, player.getEyePosition(),
                    player.getEyePosition().add(player.getViewVector(1.0F).scale(player.getBlockReach() + 1.0D)));
            if (selected == null || !wire.hasFace(selected)) selected = state.getValue(FACE);
            removeFace(level, pos, selected, willHarvest && !player.isCreative());
            // The multipart container remains while another face is occupied.
            return false;
        }
        return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }

    /** Finds the first occupied face slab intersected by a world-space ray. */
    @Nullable
    public static Direction targetedFace(BlockGetter level, BlockPos pos, Vec3 start, Vec3 end) {
        if (!(level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire)) return null;
        Direction nearestFace = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (Direction face : wire.faces()) {
            BlockHitResult hit = faceShape(level, pos, face).clip(start, end, pos);
            if (hit == null) continue;
            double distance = start.distanceToSqr(hit.getLocation());
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestFace = face;
            }
        }
        return nearestFace;
    }

    /** Removes exactly one occupied face and optionally drops its item. */
    public static boolean removeFace(Level level, BlockPos pos, Direction face, boolean drop) {
        if (!(level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire) || !wire.hasFace(face)) return false;
        Set<Node> formerNeighbors = connectedNodes(level, new Node(pos, face));
        wire.removeFace(face);
        if (!level.isClientSide && drop) {
            popResourceFromFace(level, pos, face, ModRegistries.RED_ALLOY_WIRE_ITEM.get().getDefaultInstance());
        }
        if (wire.faceCount() == 0) {
            level.removeBlock(pos, false);
        } else {
            syncPrimaryState(level, pos, wire);
            recomputeAt(level, pos);
        }
        if (!level.isClientSide) {
            for (Node neighbor : formerNeighbors) {
                if (!neighbor.pos().equals(pos) && hasFace(level, neighbor.pos(), neighbor.face())) {
                    recomputeAt(level, neighbor.pos());
                }
            }
        }
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        Set<Node> former = connectedNodes(level, new Node(pos, state.getValue(FACE)));
        super.onRemove(state, level, pos, replacement, moving);
        if (!level.isClientSide && !state.is(replacement.getBlock())) {
            for (Node node : former) recomputeAt(level, node.pos());
        }
    }

    /** Adds another supported face part to an existing wire space. */
    public static boolean addFace(Level level, BlockPos pos, Direction face) {
        if (level.isClientSide || !canFaceSurvive(level, pos, face)) return false;
        if (!(level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire)
                || wire.hasFace(face.getOpposite()) || !wire.addFace(face)) return false;
        syncPrimaryState(level, pos, wire);
        recomputeAt(level, pos);
        return true;
    }

    public static boolean hasFace(BlockGetter level, BlockPos pos, Direction face) {
        return level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire && wire.hasFace(face);
    }

    public static int power(BlockGetter level, BlockPos pos, Direction face) {
        return level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire ? wire.power(face) : 0;
    }

    /** State used by the renderer for one occupied face, including that face's four connection arms. */
    public static BlockState renderState(BlockGetter level, BlockPos pos, Direction face) {
        BlockState state = ModRegistries.RED_ALLOY_WIRE_BLOCK.get().defaultBlockState()
                .setValue(FACE, face).setValue(POWER, power(level, pos, face));
        if (!(level instanceof LevelAccessor accessor)) return state;
        Node node = new Node(pos, face);
        Set<Node> neighbors = connectedNodes(accessor, node);
        for (Direction direction : Direction.values()) {
            boolean connected = direction.getAxis() != face.getAxis()
                    && neighbors.stream().anyMatch(next -> armDirection(node, next) == direction);
            state = state.setValue(CableShapeSupport.property(direction), connected);
        }
        return state;
    }

    public static boolean isConnected(BlockState state, Direction direction) {
        return state.getValue(CableShapeSupport.property(direction));
    }

    /** Recomputes a bounded graph whose nodes are individual occupied faces, not whole blocks. */
    public static void recomputeAt(Level level, BlockPos start) {
        if (level == null || level.isClientSide || UPDATING.get()
                || !(level.getBlockEntity(start) instanceof RedAlloyWireBlockEntity startWire)) return;
        Direction startFace = startWire.faces().stream().findFirst().orElse(null);
        if (startFace == null) return;

        UPDATING.set(true);
        try {
            Set<Node> component = collectComponent(level, new Node(start, startFace));
            Map<Node, Integer> powers = new HashMap<>();
            ArrayDeque<Node> pending = new ArrayDeque<>();
            for (Node node : component) {
                int source = externalPower(level, node, component);
                if (source > 0) {
                    powers.put(node, source);
                    pending.addLast(node);
                }
            }
            while (!pending.isEmpty()) {
                Node current = pending.removeFirst();
                int nextPower = powers.getOrDefault(current, 0) - 1;
                if (nextPower <= 0) continue;
                for (Node next : connectedNodes(level, current)) {
                    if (!component.contains(next) || nextPower <= powers.getOrDefault(next, 0)) continue;
                    powers.put(next, nextPower);
                    pending.addLast(next);
                }
            }
            Set<BlockPos> changedPositions = new HashSet<>();
            for (Node node : component) {
                if (level.getBlockEntity(node.pos()) instanceof RedAlloyWireBlockEntity wire) {
                    wire.setPower(node.face(), powers.getOrDefault(node, 0));
                    changedPositions.add(node.pos());
                }
            }
            for (BlockPos pos : changedPositions) {
                if (level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire) syncPrimaryState(level, pos, wire);
                level.updateNeighborsAt(pos, ModRegistries.RED_ALLOY_WIRE_BLOCK.get());
            }
        } finally {
            UPDATING.set(false);
        }
    }

    private static Set<Node> collectComponent(LevelAccessor level, Node start) {
        Set<Node> found = new HashSet<>();
        ArrayDeque<Node> pending = new ArrayDeque<>();
        pending.add(start);
        while (!pending.isEmpty() && found.size() < MAX_COMPONENT_SIZE) {
            Node current = pending.removeFirst();
            if (!found.add(current) || !hasFace(level, current.pos(), current.face())) continue;
            for (Node next : connectedNodes(level, current)) if (!found.contains(next)) pending.addLast(next);
        }
        return found;
    }

    private static Set<Node> connectedNodes(LevelAccessor level, Node node) {
        Set<Node> result = new HashSet<>();
        if (!hasFace(level, node.pos(), node.face())) return result;

        // Perpendicular faces in the same space meet at an internal/concave corner.
        if (level.getBlockEntity(node.pos()) instanceof RedAlloyWireBlockEntity wire) {
            for (Direction other : wire.faces()) {
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

    private static Direction armDirection(Node from, Node to) {
        if (from.pos().equals(to.pos())) return to.face().getOpposite();
        BlockPos delta = to.pos().subtract(from.pos());
        for (Direction direction : Direction.values()) {
            if (delta.getX() == direction.getStepX() && delta.getY() == direction.getStepY()
                    && delta.getZ() == direction.getStepZ()) return direction;
        }
        // External corners are diagonal; the in-plane part is the direction excluding support depth.
        for (Direction direction : planeDirections(from.face())) {
            BlockPos expected = from.pos().relative(direction).relative(from.face().getOpposite());
            if (expected.equals(to.pos())) return direction;
        }
        return from.face().getOpposite();
    }

    private static int externalPower(Level level, Node node, Set<Node> component) {
        int maximum = 0;
        Set<BlockPos> componentPositions = new HashSet<>();
        for (Node member : component) componentPositions.add(member.pos());
        // A surface wire accepts vanilla power from every adjacent position, including its support.
        // Excluding the support made normal arrangements such as wire on a redstone block or on a
        // lever-powered solid block read zero. Wire positions remain excluded to prevent feedback.
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = node.pos().relative(direction);
            if (componentPositions.contains(neighbor)
                    || level.getBlockState(neighbor).getBlock() instanceof RedAlloyWireBlock) continue;
            maximum = Math.max(maximum, level.getSignal(neighbor, direction.getOpposite()));
        }
        return Math.min(15, maximum);
    }

    private static void removeUnsupportedFaces(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire)) return;
        for (Direction face : wire.faces()) {
            if (!canFaceSurvive(level, pos, face)) removeFace(level, pos, face, true);
        }
    }

    private static void syncPrimaryState(Level level, BlockPos pos, RedAlloyWireBlockEntity wire) {
        Direction primary = wire.hasFace(level.getBlockState(pos).getValue(FACE))
                ? level.getBlockState(pos).getValue(FACE) : wire.faces().iterator().next();
        BlockState rendered = renderState(level, pos, primary).setValue(POWER, wire.maximumPower());
        if (level.getBlockState(pos) != rendered) level.setBlock(pos, rendered, Block.UPDATE_CLIENTS);
    }

    private static boolean canFaceSurvive(LevelReader level, BlockPos pos, Direction face) {
        BlockPos support = pos.relative(face.getOpposite());
        return level.getBlockState(support).isFaceSturdy(level, support, face);
    }

    /** Thin 2x2-pixel RedPower-style profile, expanded only along connected arms. */
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
            case UP -> Block.box(7, 0, 7, 9, 2, 9);
            case DOWN -> Block.box(7, 14, 7, 9, 16, 9);
            case NORTH -> Block.box(7, 7, 14, 9, 9, 16);
            case SOUTH -> Block.box(7, 7, 0, 9, 9, 2);
            case WEST -> Block.box(14, 7, 7, 16, 9, 9);
            case EAST -> Block.box(0, 7, 7, 2, 9, 9);
        };
    }

    private static VoxelShape faceArmShape(Direction face, Direction arm) {
        return switch (face) {
            case UP -> switch (arm) {
                case NORTH -> Block.box(7, 0, 0, 9, 2, 7);
                case SOUTH -> Block.box(7, 0, 9, 9, 2, 16);
                case WEST -> Block.box(0, 0, 7, 7, 2, 9);
                case EAST -> Block.box(9, 0, 7, 16, 2, 9);
                default -> Shapes.empty();
            };
            case DOWN -> switch (arm) {
                case NORTH -> Block.box(7, 14, 0, 9, 16, 7);
                case SOUTH -> Block.box(7, 14, 9, 9, 16, 16);
                case WEST -> Block.box(0, 14, 7, 7, 16, 9);
                case EAST -> Block.box(9, 14, 7, 16, 16, 9);
                default -> Shapes.empty();
            };
            case NORTH -> switch (arm) {
                case DOWN -> Block.box(7, 0, 14, 9, 7, 16);
                case UP -> Block.box(7, 9, 14, 9, 16, 16);
                case WEST -> Block.box(0, 7, 14, 7, 9, 16);
                case EAST -> Block.box(9, 7, 14, 16, 9, 16);
                default -> Shapes.empty();
            };
            case SOUTH -> switch (arm) {
                case DOWN -> Block.box(7, 0, 0, 9, 7, 2);
                case UP -> Block.box(7, 9, 0, 9, 16, 2);
                case WEST -> Block.box(0, 7, 0, 7, 9, 2);
                case EAST -> Block.box(9, 7, 0, 16, 9, 2);
                default -> Shapes.empty();
            };
            case WEST -> switch (arm) {
                case DOWN -> Block.box(14, 0, 7, 16, 7, 9);
                case UP -> Block.box(14, 9, 7, 16, 16, 9);
                case NORTH -> Block.box(14, 7, 0, 16, 9, 7);
                case SOUTH -> Block.box(14, 7, 9, 16, 9, 16);
                default -> Shapes.empty();
            };
            case EAST -> switch (arm) {
                case DOWN -> Block.box(0, 0, 7, 2, 7, 9);
                case UP -> Block.box(0, 9, 7, 2, 16, 9);
                case NORTH -> Block.box(0, 7, 0, 2, 9, 7);
                case SOUTH -> Block.box(0, 7, 9, 2, 9, 16);
                default -> Shapes.empty();
            };
        };
    }

    private static Direction[] planeDirections(Direction face) {
        return switch (face.getAxis()) {
            case X -> new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH};
            case Y -> new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
            case Z -> new Direction[]{Direction.DOWN, Direction.UP, Direction.WEST, Direction.EAST};
        };
    }

    private record Node(BlockPos pos, Direction face) {
        private Node { pos = pos.immutable(); }
    }
}
