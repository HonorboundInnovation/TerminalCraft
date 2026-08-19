package com.malice.terminalcraft.block;

import com.malice.terminalcraft.blockentity.BundledCableBlockEntity;
import com.malice.terminalcraft.blockentity.RedAlloyWireBlockEntity;
import com.malice.terminalcraft.item.RedAlloyWireItem;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
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

/** One centered, automatically routed unshielded or color-shielded redstone cable per block face. */
public class RedAlloyWireBlock extends BaseEntityBlock {
    public static final DirectionProperty FACE = DirectionProperty.create("face");
    public static final IntegerProperty COLOR = IntegerProperty.create("color", 0, 15);
    public static final IntegerProperty POWER = IntegerProperty.create("power", 0, 15);
    public static final int MAX_COMPONENT_SIZE = 4096;
    private static final ThreadLocal<Boolean> UPDATING = ThreadLocal.withInitial(() -> false);

    public record Target(Direction face, int lane) {}
    private record Node(BlockPos pos, Direction face, int lane, int color) {
        private Node { pos = pos.immutable(); }
    }

    public RedAlloyWireBlock() {
        super(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(0.2f)
                .noCollission().noOcclusion());
        registerDefaultState(CableShapeSupport.disconnected(stateDefinition.any()
                .setValue(FACE, Direction.UP).setValue(COLOR, DyeColor.RED.getId())
                .setValue(POWER, 0)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACE, COLOR, POWER, CableShapeSupport.DOWN, CableShapeSupport.UP,
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
        BlockPos pos = context.getClickedPos();
        int color = RedAlloyWireItem.color(context.getItemInHand()).getId();
        BlockState state = defaultBlockState().setValue(FACE, face).setValue(COLOR, color);
        return canFaceSurvive(context.getLevel(), pos, face) ? state : null;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !(level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire)) return;
        Direction face = state.getValue(FACE);
        wire.setShielded(face, 0, RedAlloyWireItem.isShielded(stack));
        wire.setRoute(face, 0, SurfaceCableRouting.planeMask(face));
        syncPrimaryState(level, pos, wire);
        recomputeAt(level, pos);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        // The primary face uses the baked multipart model. The block-entity renderer adds only
        // secondary mounted faces, matching bundled cable rendering without overlapping geometry.
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
        if (level instanceof Level realLevel && !realLevel.isClientSide) realLevel.scheduleTick(pos, this, 1);
        return state;
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (!(level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire)) {
            return SurfaceCableSupport.faceHalfShape(state.getValue(FACE));
        }
        VoxelShape shape = Shapes.empty();
        for (Direction face : wire.faces()) {
            shape = Shapes.or(shape, SurfaceCableSupport.faceHalfShape(face));
        }
        return shape.isEmpty() ? SurfaceCableSupport.faceHalfShape(state.getValue(FACE)) : shape.optimize();
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean isSignalSource(BlockState state) { return true; }

    @Override
    @SuppressWarnings("deprecation")
    public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return UPDATING.get() ? 0 : outputPower(level, pos, state, direction);
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return UPDATING.get() ? 0 : outputPower(level, pos, state, direction);
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
        if (level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire) {
            Target selected = targetedRun(level, pos, player.getEyePosition(),
                    player.getEyePosition().add(player.getViewVector(1.0F).scale(player.getBlockReach() + 1.0D)));
            if (selected == null || !wire.hasRun(selected.face(), selected.lane())) {
                RedAlloyWireBlockEntity.Run first = wire.runs().stream().findFirst().orElse(null);
                if (first != null) selected = new Target(first.face(), first.lane());
            }
            if (selected != null) {
                removeRun(level, pos, selected.face(), selected.lane(), willHarvest && !player.isCreative());
                return false;
            }
        }
        return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (!WireInteractionSupport.isWrench(player.getItemInHand(hand))) return InteractionResult.PASS;
        if (!level.isClientSide) {
            Target target = targetedRun(level, pos, player.getEyePosition(), hit.getLocation());
            CableDiagnosticDisplay.show(player, diagnosticLines(level, pos, target));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        Set<Node> former = allConnectedNeighbors(level, pos);
        super.onRemove(state, level, pos, replacement, moving);
        if (!level.isClientSide && !state.is(replacement.getBlock())) {
            for (Node node : former) recomputeAt(level, node.pos());
        }
    }

    public static int firstFreeLane(BlockGetter level, BlockPos pos, Direction face, int preferred) {
        return level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire
                ? wire.firstFreeLane(face, preferred) : -1;
    }

    /** Compatibility route accessor; ordinary faces now contain only the centered run. */
    public static int faceBankRoute(BlockGetter level, BlockPos pos, Direction face) {
        if (!(level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire)) return 0;
        return wire.runs().stream().filter(run -> run.face() == face)
                .sorted(java.util.Comparator.comparingInt(RedAlloyWireBlockEntity.Run::lane))
                .mapToInt(RedAlloyWireBlockEntity.Run::ports).findFirst().orElse(0);
    }

    public static Direction placementDirection(BlockGetter level, BlockPos pos, Direction face,
                                               Direction fallback) {
        return SurfaceCableRouting.bankDirection(face, faceBankRoute(level, pos, face), fallback);
    }

    /** Resolve the mounted face of the thin run that was clicked instead of its incidental side. */
    public static Direction placementFace(BlockGetter level, BlockPos pos, @Nullable Player player,
                                          Vec3 hit, Direction fallback) {
        if (player == null) return fallback;
        Vec3 end = hit.add(player.getViewVector(1.0F).scale(0.125D));
        Target target = targetedRun(level, pos, player.getEyePosition(), end);
        return target == null ? fallback : target.face();
    }

    /** Ordinary cables expose one centered run per face; the aimed legacy point is ignored. */
    public static int preferredLane(LevelAccessor level, BlockPos pos, Direction face, int color, int aimed) {
        return level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity local
                && local.hasFace(face) ? -1 : 0;
    }

    public static boolean addRun(Level level, BlockPos pos, Direction face, int lane, int color) {
        return addRun(level, pos, face, 0, color, true);
    }

    public static boolean addRun(Level level, BlockPos pos, Direction face, int lane, int color,
                                 boolean shielded) {
        return addRun(level, pos, face, 0, color, shielded, SurfaceCableRouting.planeMask(face));
    }

    public static boolean addRun(Level level, BlockPos pos, Direction face, int lane, int color, int route) {
        return addRun(level, pos, face, lane, color, true, route);
    }

    public static boolean addRun(Level level, BlockPos pos, Direction face, int lane, int color,
                                 boolean shielded, int route) {
        if (level.isClientSide || !canFaceSurvive(level, pos, face)) return false;
        if (!(level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire)
                || wire.hasFace(face) || !wire.addRun(face, 0, color, shielded,
                SurfaceCableRouting.planeMask(face))) return false;
        syncPrimaryState(level, pos, wire);
        recomputeAt(level, pos);
        return true;
    }

    /** Compatibility helper inserts one centered red cable on the face. */
    public static boolean addFace(Level level, BlockPos pos, Direction face) {
        if (hasFace(level, pos, face.getOpposite())) return false;
        return addRun(level, pos, face, 0, DyeColor.RED.getId(), SurfaceCableRouting.planeMask(face));
    }

    public static boolean removeRun(Level level, BlockPos pos, Direction face, int lane, boolean drop) {
        if (!(level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire) || !wire.hasRun(face, lane)) {
            return false;
        }
        int color = wire.color(face, lane);
        boolean shielded = wire.shielded(face, lane);
        Set<Node> formerNeighbors = connectedNodes(level, new Node(pos, face, lane, color));
        wire.removeRun(face, lane);
        if (!level.isClientSide && drop) {
            popResourceFromFace(level, pos, face, shielded
                    ? coloredDrop(color) : ModRegistries.RED_ALLOY_WIRE_ITEM.get().getDefaultInstance());
        }
        if (wire.runCount() == 0) level.removeBlock(pos, false);
        else {
            syncPrimaryState(level, pos, wire);
            recomputeAt(level, pos);
        }
        if (!level.isClientSide) {
            for (Node neighbor : formerNeighbors) {
                if (hasRun(level, neighbor.pos(), neighbor.face(), neighbor.lane(), neighbor.color())) {
                    recomputeAt(level, neighbor.pos());
                }
            }
        }
        return true;
    }

    /** Removes and drops the centered cable from one no-longer-supported face. */
    public static boolean removeFace(Level level, BlockPos pos, Direction face, boolean drop) {
        if (!(level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire) || !wire.hasFace(face)) return false;
        for (RedAlloyWireBlockEntity.Run run : Set.copyOf(wire.runs())) {
            if (run.face() == face) removeRun(level, pos, face, run.lane(), drop);
            if (!(level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity)) break;
        }
        return true;
    }

    public static boolean hasFace(BlockGetter level, BlockPos pos, Direction face) {
        return level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire && wire.hasFace(face);
    }

    public static boolean hasRun(BlockGetter level, BlockPos pos, Direction face, int lane, int color) {
        return level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire
                && wire.hasRun(face, lane, color);
    }

    public static int power(BlockGetter level, BlockPos pos, Direction face) {
        if (!(level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire)) return 0;
        int maximum = 0;
        for (RedAlloyWireBlockEntity.Run run : wire.runs()) {
            if (run.face() == face) maximum = Math.max(maximum, run.power());
        }
        return maximum;
    }

    public static int power(BlockGetter level, BlockPos pos, Direction face, int lane) {
        return level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire ? wire.power(face, lane) : 0;
    }

    private static int outputPower(BlockGetter level, BlockPos pos, BlockState state, Direction direction) {
        if (direction == null) return 0;
        if (level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire) {
            int maximum = 0;
            for (RedAlloyWireBlockEntity.Run run : wire.runs()) {
                // Vanilla passes the direction from the receiver toward this source; route ports
                // are inferred dynamically from the run's mounting plane.
                if (run.face().getAxis() != direction.getAxis()) {
                    maximum = Math.max(maximum, run.power());
                }
            }
            return maximum;
        }
        return isOutputDirection(state.getValue(FACE), direction) ? state.getValue(POWER) : 0;
    }

    private static boolean isOutputDirection(Direction face, Direction direction) {
        return face != null && direction != null && face.getAxis() != direction.getAxis();
    }

    public static String diagnostic(BlockGetter level, BlockPos pos, @Nullable Target target) {
        return String.join(" | ", diagnosticLines(level, pos, target));
    }

    public static java.util.List<String> diagnosticLines(BlockGetter level, BlockPos pos, @Nullable Target target) {
        if (!(level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire) || wire.runCount() == 0) {
            return java.util.List.of("Red Alloy Wire", "State: unavailable");
        }
        RedAlloyWireBlockEntity.Run run = target == null ? wire.runs().get(0) : wire.runs().stream()
                .filter(candidate -> candidate.face() == target.face() && candidate.lane() == target.lane())
                .findFirst().orElse(wire.runs().get(0));
        Node node = new Node(pos, run.face(), run.lane(), run.color());
        Set<Node> connected = level instanceof LevelAccessor accessor ? connectedNodes(accessor, node) : Set.of();
        String connections = connected.stream()
                .map(next -> armDirection(node, next).getName())
                .distinct().sorted().collect(java.util.stream.Collectors.joining(","));
        if (connections.isEmpty()) connections = "none";
        boolean bundled = false;
        if (level instanceof LevelAccessor accessor) {
            for (Direction direction : SurfaceCableSupport.planeDirections(run.face())) {
                if (connectsToBundle(accessor, node, direction)) bundled = true;
            }
        }
        String family = run.shielded() ? "Shielded Red Alloy Wire" : "Unshielded Red Alloy Wire";
        String circuit = run.shielded()
                ? "Circuit: color=" + SurfaceCableSupport.dyeColor(run.color()).getName()
                + "  bundle-channel=" + run.color() + "  signal=" + run.power() + "/15"
                : "Circuit: unshielded  bundle-channel=none  signal=" + run.power() + "/15";
        return java.util.List.of(
                family,
                "Target: face=" + run.face().getName() + "  centered cable",
                circuit,
                "Routing: automatic RedPower-style links; one cable per face",
                "Links: " + connections + "  bundled=" + (bundled ? "yes" : "no")
                        + "  mounted-faces=" + wire.faceCount());
    }

    /** Compatibility diagnostic selects the first run on the requested face. */
    public static String diagnostic(BlockGetter level, BlockPos pos, Direction face) {
        if (!(level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire)) return "Red Alloy Wire: unavailable";
        return wire.runs().stream().filter(run -> run.face() == face).findFirst()
                .map(run -> diagnostic(level, pos, new Target(face, run.lane())))
                .orElse("Red Alloy Wire: unavailable");
    }

    public static BlockState renderState(BlockGetter level, BlockPos pos, Direction face, int lane, int color) {
        BlockState state = ModRegistries.RED_ALLOY_WIRE_BLOCK.get().defaultBlockState()
                .setValue(FACE, face).setValue(COLOR, color)
                .setValue(POWER, power(level, pos, face, lane));
        if (!(level instanceof LevelAccessor accessor)) return state;
        int route = visibleRoute(level, pos, face, lane, color);
        for (Direction direction : Direction.values()) {
            if (direction.getAxis() == face.getAxis()) continue;
            boolean routed = SurfaceCableRouting.hasPort(route, direction);
            state = state.setValue(CableShapeSupport.property(direction), routed);
        }
        return state;
    }

    /** Compatibility hook: ordinary cables now route automatically in the whole face plane. */
    public static int proposedRoute(LevelAccessor level, BlockPos pos, Direction face, int lane,
                                    int color, Direction desired) {
        return SurfaceCableRouting.planeMask(face);
    }

    public static int incomingPorts(LevelAccessor level, BlockPos pos, Direction face, int lane, int color) {
        int incoming = 0;
        if (level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity local) {
            for (Direction other : local.faces()) {
                if (other == face || other == face.getOpposite()
                        || !local.hasRun(other, lane)) continue;
                RedAlloyWireBlockEntity.Run otherRun = local.runs().stream()
                        .filter(candidate -> candidate.face() == other && candidate.lane() == lane)
                        .findFirst().orElse(null);
                if (otherRun == null || !colorsConnect(level, pos, face, lane, color,
                        pos, otherRun.face(), otherRun.lane(), otherRun.color())) continue;
                if (local.hasPort(other, lane, face.getOpposite())) {
                    incoming |= SurfaceCableRouting.port(other.getOpposite());
                }
            }
        }
        for (Direction direction : SurfaceCableSupport.planeDirections(face)) {
            BlockPos direct = pos.relative(direction);
            if (level.getBlockEntity(direct) instanceof RedAlloyWireBlockEntity neighbor) {
                RedAlloyWireBlockEntity.Run matching = neighbor.runs().stream()
                        .filter(candidate -> candidate.face() == face
                                && colorsConnect(level, pos, face, lane, color, direct,
                                candidate.face(), candidate.lane(), candidate.color()))
                        .findFirst().orElse(null);
                if (matching != null && neighbor.hasPort(matching.face(), matching.lane(),
                        direction.getOpposite())) {
                    incoming |= SurfaceCableRouting.port(direction);
                    continue;
                }
            }
            BlockState bend = level.getBlockState(direct);
            if (!bend.isAir() && !bend.getFluidState().is(FluidTags.WATER)) continue;
            BlockPos around = direct.relative(face.getOpposite());
            if (level.getBlockEntity(around) instanceof RedAlloyWireBlockEntity neighbor) {
                RedAlloyWireBlockEntity.Run matching = neighbor.runs().stream()
                        .filter(candidate -> candidate.face() == direction
                                && colorsConnect(level, pos, face, lane, color, around,
                                candidate.face(), candidate.lane(), candidate.color()))
                        .findFirst().orElse(null);
                if (matching != null && neighbor.hasPort(matching.face(), matching.lane(), face)) {
                    incoming |= SurfaceCableRouting.port(direction);
                }
            }
        }
        return SurfaceCableRouting.sanitize(face, incoming);
    }

    /** Legacy renderer state selects the first lane on the face. */
    public static BlockState renderState(BlockGetter level, BlockPos pos, Direction face) {
        if (level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire) {
            RedAlloyWireBlockEntity.Run run = wire.runs().stream().filter(value -> value.face() == face)
                    .findFirst().orElse(null);
            if (run != null) return renderState(level, pos, face, run.lane(), run.color());
        }
        return ModRegistries.RED_ALLOY_WIRE_BLOCK.get().defaultBlockState().setValue(FACE, face);
    }

    public static boolean isConnected(BlockState state, Direction direction) {
        return state.getValue(CableShapeSupport.property(direction));
    }

    /** Recomputes every electrically distinct run component that occupies the selected block. */
    public static void recomputeAt(Level level, BlockPos start) {
        if (level == null || level.isClientSide || UPDATING.get()
                || !(level.getBlockEntity(start) instanceof RedAlloyWireBlockEntity startWire)) return;
        Set<Node> processed = new HashSet<>();
        Set<BlockPos> visitedPositions = new HashSet<>();
        Set<BlockPos> powerChangedPositions = new HashSet<>();
        UPDATING.set(true);
        try {
            for (RedAlloyWireBlockEntity.Run startRun : startWire.runs()) {
                Node startNode = new Node(start, startRun.face(), startRun.lane(), startRun.color());
                if (processed.contains(startNode)) continue;
                Set<Node> component = collectComponent(level, startNode);
                processed.addAll(component);
                Map<Node, Integer> powers = propagate(level, component, true, null);
                for (Node node : component) {
                    if (level.getBlockEntity(node.pos()) instanceof RedAlloyWireBlockEntity wire) {
                        if (wire.setPower(node.face(), node.lane(), powers.getOrDefault(node, 0))) {
                            powerChangedPositions.add(node.pos());
                        }
                        visitedPositions.add(node.pos());
                    }
                }
            }
            for (BlockPos pos : visitedPositions) {
                if (level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire) syncPrimaryState(level, pos, wire);
            }
        } finally {
            UPDATING.set(false);
        }
        Block sourceBlock = ModRegistries.RED_ALLOY_WIRE_BLOCK.get();
        for (BlockPos pos : visitedPositions) level.updateNeighborsAt(pos, sourceBlock);
        // Exact analogue changes must also wake devices attached to a solid block powered by the wire.
        // Direct neighbors are covered above; this second ring mirrors vanilla strong-power propagation.
        for (BlockPos pos : powerChangedPositions) {
            for (Direction direction : Direction.values()) {
                level.updateNeighborsAt(pos.relative(direction), sourceBlock);
            }
        }
    }

    /** Signal reaching a bundled breakout from ordinary redstone sources, excluding bundle feedback. */
    public static int nativePowerAt(Level level, BlockPos pos, Direction face, int lane, int color) {
        return nativePowerAt(level, pos, face, lane, color, null);
    }

    /** Native component power while ignoring one adjacent regenerator, preventing output echo. */
    public static int nativePowerAt(Level level, BlockPos pos, Direction face, int lane, int color,
                                    @Nullable BlockPos excludedSource) {
        Node start = new Node(pos, face, lane, color);
        if (!hasRun(level, pos, face, lane, color)) return 0;
        Set<Node> component = collectComponent(level, start);
        // Bundled-cable input sampling happens outside recomputeAt(). Suppress the wire's cached
        // output during that sample as well, otherwise a solid neighbor can conduct the previous
        // value straight back into the same breakout and permanently latch the bundled channel.
        boolean wasUpdating = UPDATING.get();
        UPDATING.set(true);
        try {
            return propagate(level, component, false, excludedSource).getOrDefault(start, 0);
        } finally {
            UPDATING.set(wasUpdating);
        }
    }

    private static Map<Node, Integer> propagate(Level level, Set<Node> component, boolean includeBundles,
                                                @Nullable BlockPos excludedSource) {
        Map<Node, Integer> powers = new HashMap<>();
        ArrayDeque<Node> pending = new ArrayDeque<>();
        for (Node node : component) {
            int source = externalPower(level, node, component, includeBundles, excludedSource);
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
        return powers;
    }

    private static Set<Node> collectComponent(LevelAccessor level, Node start) {
        Set<Node> found = new HashSet<>();
        ArrayDeque<Node> pending = new ArrayDeque<>();
        pending.add(start);
        while (!pending.isEmpty() && found.size() < MAX_COMPONENT_SIZE) {
            Node current = pending.removeFirst();
            if (!found.add(current) || !hasRun(level, current.pos(), current.face(), current.lane(), current.color())) continue;
            for (Node next : connectedNodes(level, current)) if (!found.contains(next)) pending.addLast(next);
        }
        return found;
    }

    private static Set<Node> connectedNodes(LevelAccessor level, Node node) {
        Set<Node> result = new HashSet<>();
        if (!hasRun(level, node.pos(), node.face(), node.lane(), node.color())) return result;
        if (level.getBlockEntity(node.pos()) instanceof RedAlloyWireBlockEntity wire) {
            for (RedAlloyWireBlockEntity.Run candidate : wire.runs()) {
                if (colorsConnect(level, node.pos(), node.face(), node.lane(), node.color(),
                        node.pos(), candidate.face(), candidate.lane(), candidate.color())
                        && candidate.face() != node.face()
                        && candidate.face() != node.face().getOpposite()) {
                    result.add(new Node(node.pos(), candidate.face(), candidate.lane(), candidate.color()));
                }
            }
        }
        for (Direction direction : SurfaceCableSupport.planeDirections(node.face())) {
            BlockPos direct = node.pos().relative(direction);
            if (level.getBlockEntity(direct) instanceof RedAlloyWireBlockEntity neighbor) {
                for (RedAlloyWireBlockEntity.Run candidate : neighbor.runs()) {
                    if (candidate.face() == node.face()
                            && colorsConnect(level, node.pos(), node.face(), node.lane(), node.color(),
                            direct, candidate.face(), candidate.lane(), candidate.color())) {
                        result.add(new Node(direct, candidate.face(), candidate.lane(), candidate.color()));
                    }
                }
            }
            BlockState bend = level.getBlockState(direct);
            if (!bend.isAir() && !bend.getFluidState().is(FluidTags.WATER)) continue;
            BlockPos around = direct.relative(node.face().getOpposite());
            if (level.getBlockEntity(around) instanceof RedAlloyWireBlockEntity corner) {
                for (RedAlloyWireBlockEntity.Run candidate : corner.runs()) {
                    if (candidate.face() == direction
                            && colorsConnect(level, node.pos(), node.face(), node.lane(), node.color(),
                            around, candidate.face(), candidate.lane(), candidate.color())) {
                        result.add(new Node(around, candidate.face(), candidate.lane(), candidate.color()));
                    }
                }
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
        for (Direction direction : SurfaceCableSupport.planeDirections(from.face())) {
            if (from.pos().relative(direction).relative(from.face().getOpposite()).equals(to.pos())) return direction;
        }
        return from.face().getOpposite();
    }

    private static int externalPower(Level level, Node node, Set<Node> component, boolean includeBundles,
                                     @Nullable BlockPos excludedSource) {
        int maximum = 0;
        Set<BlockPos> componentPositions = new HashSet<>();
        for (Node member : component) componentPositions.add(member.pos());
        // A surface conductor routes visually only in its mounting plane, but can be energized by
        // a redstone source touching either exposed side or the supporting block behind it.
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = node.pos().relative(direction);
            BlockState neighborState = level.getBlockState(neighbor);
            if (neighbor.equals(excludedSource) || componentPositions.contains(neighbor)
                    || neighborState.getBlock() instanceof RedAlloyWireBlock
                    || neighborState.getBlock() instanceof BundledCableBlock) continue;
            // TerminalCraft's side-addressed outputs translate Minecraft's receiver-to-source
            // query into a physical output face. Vanilla directional sources already expose the
            // physical face directly, so query those using the opposite direction.
            Direction query = neighborState.getBlock() instanceof TerminalBlock
                    || neighborState.getBlock() instanceof TurtleBlock
                    || neighborState.getBlock() instanceof ProgrammableLogicControllerBlock
                    ? direction : direction.getOpposite();
            maximum = Math.max(maximum, level.getSignal(neighbor, query));
            if (direction == node.face().getOpposite()) {
                maximum = Math.max(maximum, level.getDirectSignalTo(neighbor));
            }
        }
        if (includeBundles && isShielded(level, node)) {
            for (Direction direction : SurfaceCableSupport.planeDirections(node.face())) {
                BlockPos neighbor = node.pos().relative(direction);
                if (level.getBlockState(neighbor).getBlock() instanceof BundledCableBlock
                        && BundledCableBlock.hasFace(level, neighbor, node.face())
                        && level.getBlockEntity(neighbor) instanceof BundledCableBlockEntity bundle) {
                    maximum = Math.max(maximum, bundle.getSignal(node.color()));
                }
            }
        }
        return Math.min(15, maximum);
    }

    private static boolean connectsToBundle(LevelAccessor level, Node node, Direction direction) {
        if (!isShielded(level, node)) return false;
        BlockPos adjacent = node.pos().relative(direction);
        return level.getBlockState(adjacent).getBlock() instanceof BundledCableBlock
                && BundledCableBlock.hasFace(level, adjacent, node.face());
    }

    /** Shielded colors stay isolated; the original bare conductor intentionally joins any color. */
    private static boolean colorsConnect(BlockGetter level,
                                         BlockPos firstPos, Direction firstFace, int firstLane, int firstColor,
                                         BlockPos secondPos, Direction secondFace, int secondLane, int secondColor) {
        boolean firstShielded = level.getBlockEntity(firstPos) instanceof RedAlloyWireBlockEntity first
                && first.shielded(firstFace, firstLane);
        boolean secondShielded = level.getBlockEntity(secondPos) instanceof RedAlloyWireBlockEntity second
                && second.shielded(secondFace, secondLane);
        return !firstShielded || !secondShielded || firstColor == secondColor;
    }

    private static boolean isShielded(BlockGetter level, Node node) {
        return level.getBlockEntity(node.pos()) instanceof RedAlloyWireBlockEntity wire
                && wire.shielded(node.face(), node.lane());
    }

    private static String routeShape(int ports) {
        if (SurfaceCableRouting.isStraight(ports)) return "straight";
        if (SurfaceCableRouting.isTurn(ports)) return "turn";
        return Integer.bitCount(ports) > 2 ? "junction" : "endpoint";
    }

    private static Set<Node> allConnectedNeighbors(LevelAccessor level, BlockPos pos) {
        Set<Node> result = new HashSet<>();
        if (level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire) {
            for (RedAlloyWireBlockEntity.Run run : wire.runs()) {
                result.addAll(connectedNodes(level, new Node(pos, run.face(), run.lane(), run.color())));
            }
        }
        return result;
    }

    private static void removeUnsupportedFaces(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire)) return;
        for (Direction face : Set.copyOf(wire.faces())) {
            if (!canFaceSurvive(level, pos, face)) removeFace(level, pos, face, true);
        }
    }

    private static void syncPrimaryState(Level level, BlockPos pos, RedAlloyWireBlockEntity wire) {
        RedAlloyWireBlockEntity.Run primary = wire.runs().stream().findFirst().orElse(null);
        if (primary == null) return;
        BlockState rendered = renderState(level, pos, primary.face(), primary.lane(), primary.color())
                .setValue(POWER, wire.maximumPower());
        if (!level.getBlockState(pos).equals(rendered)) level.setBlock(pos, rendered, Block.UPDATE_CLIENTS);
    }

    public static boolean canFaceSurvive(LevelReader level, BlockPos pos, Direction face) {
        BlockPos support = pos.relative(face.getOpposite());
        return level.getBlockState(support).isFaceSturdy(level, support, face);
    }

    @Nullable
    public static Target targetedRun(BlockGetter level, BlockPos pos, Vec3 start, Vec3 end) {
        if (!(level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire)) return null;
        Target nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (RedAlloyWireBlockEntity.Run run : wire.runs()) {
            BlockHitResult hit = runShape(level, pos, run.face(), run.lane(), run.color())
                    .clip(start, end, pos);
            if (hit == null) continue;
            double distance = start.distanceToSqr(hit.getLocation());
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = new Target(run.face(), run.lane());
            }
        }
        return nearest;
    }

    @Nullable
    public static Direction targetedFace(BlockGetter level, BlockPos pos, Vec3 start, Vec3 end) {
        Target target = targetedRun(level, pos, start, end);
        if (target != null) return target.face();
        if (!(level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire)) return null;
        Direction nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (Direction face : wire.faces()) {
            BlockHitResult hit = faceSelectionShape(face).clip(start, end, pos);
            if (hit != null && start.distanceToSqr(hit.getLocation()) < distance) {
                nearest = face;
                distance = start.distanceToSqr(hit.getLocation());
            }
        }
        return nearest;
    }

    /** Ports with real reciprocal wire, bundled breakout, or redstone-device connections. */
    public static int visibleRoute(BlockGetter level, BlockPos pos, Direction face, int lane, int color) {
        if (!(level.getBlockEntity(pos) instanceof RedAlloyWireBlockEntity wire)
                || !wire.hasRun(face, lane, color)) return 0;
        if (!(level instanceof LevelAccessor accessor)) return 0;
        int visible = 0;
        Node node = new Node(pos, face, lane, color);
        for (Node connected : connectedNodes(accessor, node)) {
            Direction arm = armDirection(node, connected);
            if (arm.getAxis() != face.getAxis()) visible |= SurfaceCableRouting.port(arm);
        }
        for (Direction direction : SurfaceCableSupport.planeDirections(face)) {
            if (connectsToBundle(accessor, node, direction)) {
                visible |= SurfaceCableRouting.port(direction);
                continue;
            }
            if (connectsToRedstoneDevice(accessor, node, direction)) {
                visible |= SurfaceCableRouting.port(direction);
            }
        }
        return SurfaceCableRouting.sanitize(face, visible);
    }

    /** Use Forge's redstone-connectivity contract instead of mistaking every solid block for a device. */
    private static boolean connectsToRedstoneDevice(LevelAccessor level, Node node, Direction direction) {
        BlockPos adjacentPos = node.pos().relative(direction);
        BlockState adjacent = level.getBlockState(adjacentPos);
        if (adjacent.getBlock() instanceof RedAlloyWireBlock
                || adjacent.getBlock() instanceof BundledCableBlock
                || adjacent.isAir() || adjacent.getFluidState().is(FluidTags.WATER)) return false;
        return adjacent.canRedstoneConnectTo(level, adjacentPos, direction);
    }

    /** One centered RedPower-style cable with arms for live automatic connections. */
    public static VoxelShape renderedRunShape(BlockGetter level, BlockPos pos, Direction face,
                                              int lane, int color) {
        return SurfaceCableSupport.centeredRunShape(face, visibleRoute(level, pos, face, lane, color));
    }

    private static VoxelShape runShape(BlockGetter level, BlockPos pos, Direction face, int lane, int color) {
        return renderedRunShape(level, pos, face, lane, color);
    }

    private static VoxelShape faceCoreShape(Direction face) {
        return switch (face) {
            case UP -> Block.box(7,0,7,9,2,9); case DOWN -> Block.box(7,14,7,9,16,9);
            case NORTH -> Block.box(7,7,14,9,9,16); case SOUTH -> Block.box(7,7,0,9,9,2);
            case WEST -> Block.box(14,7,7,16,9,9); case EAST -> Block.box(0,7,7,2,9,9);
        };
    }

    private static VoxelShape faceSelectionShape(Direction face) {
        return SurfaceCableSupport.faceHalfShape(face);
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

    private static ItemStack coloredDrop(int color) {
        return RedAlloyWireItem.colored(ModRegistries.RED_ALLOY_WIRE_ITEM.get().getDefaultInstance(),
                SurfaceCableSupport.dyeColor(color));
    }
}
