package com.malice.terminalcraft.block;

import com.malice.terminalcraft.blockentity.NetworkCableBlockEntity;
import com.malice.terminalcraft.item.NetworkCableItem;
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
import net.minecraft.world.level.chunk.LevelChunk;
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

/** One centered, automatically routed colored data/control cable per block face. */
public class NetworkCableBlock extends BaseEntityBlock implements WiredNetworkNode {
    public static final DirectionProperty FACE = DirectionProperty.create("face");
    public static final IntegerProperty COLOR = IntegerProperty.create("color", 0, 15);

    public record Target(Direction face, int lane) {}
    private record Node(BlockPos pos, Direction face, int lane, int color) {
        private Node { pos = pos.immutable(); }
    }

    public NetworkCableBlock() {
        super(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_CYAN).strength(0.2f)
                .noCollission().noOcclusion());
        registerDefaultState(CableShapeSupport.disconnected(stateDefinition.any()
                .setValue(FACE, Direction.UP).setValue(COLOR, DyeColor.CYAN.getId())));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACE, COLOR, CableShapeSupport.DOWN, CableShapeSupport.UP,
                CableShapeSupport.NORTH, CableShapeSupport.SOUTH,
                CableShapeSupport.WEST, CableShapeSupport.EAST);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new NetworkCableBlockEntity(pos, state);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        BlockPos pos = context.getClickedPos();
        int color = NetworkCableItem.color(context.getItemInHand()).getId();
        return canFaceSurvive(context.getLevel(), pos, face)
                ? defaultBlockState().setValue(FACE, face).setValue(COLOR, color) : null;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !(level.getBlockEntity(pos) instanceof NetworkCableBlockEntity cable)) return;
        Direction face = state.getValue(FACE);
        cable.setRoute(face, 0, SurfaceCableRouting.planeMask(face));
        syncPrimaryState(level, pos, cable);
        notifyTopology(level, pos);
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
        if (level instanceof Level realLevel && !realLevel.isClientSide) {
            realLevel.scheduleTick(pos, this, 1);
            if (realLevel instanceof ServerLevel serverLevel) {
                com.malice.terminalcraft.network.WiredNetworkTopology.invalidate(serverLevel, pos);
            }
        }
        return state;
    }

    @Override
    @SuppressWarnings("deprecation")
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (!(level.getBlockEntity(pos) instanceof NetworkCableBlockEntity cable)) {
            return SurfaceCableSupport.faceHalfShape(state.getValue(FACE));
        }
        VoxelShape shape = Shapes.empty();
        for (Direction face : cable.faces()) {
            shape = Shapes.or(shape, SurfaceCableSupport.faceHalfShape(face));
        }
        return shape.isEmpty() ? SurfaceCableSupport.faceHalfShape(state.getValue(FACE)) : shape.optimize();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!(level.getBlockEntity(pos) instanceof NetworkCableBlockEntity cable)) return;
        for (Direction face : Set.copyOf(cable.faces())) {
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
        if (level.getBlockEntity(pos) instanceof NetworkCableBlockEntity cable) {
            Target selected = targetedRun(level, pos, player.getEyePosition(),
                    player.getEyePosition().add(player.getViewVector(1.0F).scale(player.getBlockReach() + 1.0D)));
            if (selected == null || !cable.hasRun(selected.face(), selected.lane())) {
                NetworkCableBlockEntity.Run first = cable.runs().stream().findFirst().orElse(null);
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

    public static int firstFreeLane(BlockGetter level, BlockPos pos, Direction face, int preferred) {
        return level.getBlockEntity(pos) instanceof NetworkCableBlockEntity cable
                ? cable.firstFreeLane(face, preferred) : -1;
    }

    /** Compatibility route accessor; ordinary faces now contain only the centered run. */
    public static int faceBankRoute(BlockGetter level, BlockPos pos, Direction face) {
        if (!(level.getBlockEntity(pos) instanceof NetworkCableBlockEntity cable)) return 0;
        return cable.runs().stream().filter(run -> run.face() == face)
                .sorted(java.util.Comparator.comparingInt(NetworkCableBlockEntity.Run::lane))
                .mapToInt(NetworkCableBlockEntity.Run::ports).findFirst().orElse(0);
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
        return level.getBlockEntity(pos) instanceof NetworkCableBlockEntity local
                && local.hasFace(face) ? -1 : 0;
    }

    public static boolean addRun(Level level, BlockPos pos, Direction face, int lane, int color) {
        return addRun(level, pos, face, 0, color, SurfaceCableRouting.planeMask(face));
    }

    public static boolean addRun(Level level, BlockPos pos, Direction face, int lane, int color, int route) {
        if (level.isClientSide || !canFaceSurvive(level, pos, face)) return false;
        if (!(level.getBlockEntity(pos) instanceof NetworkCableBlockEntity cable)
                || cable.hasFace(face) || !cable.addRun(face, 0, color, SurfaceCableRouting.planeMask(face))) return false;
        syncPrimaryState(level, pos, cable);
        notifyTopology(level, pos);
        return true;
    }

    /** Compatibility helper inserts one centered cyan cable on the face. */
    public static boolean addFace(Level level, BlockPos pos, Direction face) {
        return addRun(level, pos, face, 0, DyeColor.CYAN.getId(), SurfaceCableRouting.planeMask(face));
    }

    public static boolean removeRun(Level level, BlockPos pos, Direction face, int lane, boolean drop) {
        if (!(level.getBlockEntity(pos) instanceof NetworkCableBlockEntity cable) || !cable.hasRun(face, lane)) {
            return false;
        }
        int color = cable.color(face, lane);
        cable.removeRun(face, lane);
        if (!level.isClientSide && drop) popResourceFromFace(level, pos, face, cableDrop(level, pos, color));
        if (cable.runCount() == 0) level.removeBlock(pos, false);
        else syncPrimaryState(level, pos, cable);
        notifyTopology(level, pos);
        return true;
    }

    public static boolean removeFace(Level level, BlockPos pos, Direction face, boolean drop) {
        if (!(level.getBlockEntity(pos) instanceof NetworkCableBlockEntity cable) || !cable.hasFace(face)) return false;
        for (NetworkCableBlockEntity.Run run : Set.copyOf(cable.runs())) {
            if (run.face() == face) removeRun(level, pos, face, run.lane(), drop);
            if (!(level.getBlockEntity(pos) instanceof NetworkCableBlockEntity)) break;
        }
        return true;
    }

    public static boolean hasFace(BlockGetter level, BlockPos pos, Direction face) {
        if (availableBlockEntity(level, pos) instanceof NetworkCableBlockEntity cable) return cable.hasFace(face);
        BlockState state = availableState(level, pos);
        if (state == null) return false;
        return state.getBlock() instanceof NetworkCableBlock && state.getValue(FACE) == face;
    }

    public static boolean hasRun(BlockGetter level, BlockPos pos, Direction face, int lane, int color) {
        return availableBlockEntity(level, pos) instanceof NetworkCableBlockEntity cable
                && cable.hasRun(face, lane, color);
    }

    /** Block positions physically joined by at least one matching lane/color or a bundled trunk. */
    public static Set<BlockPos> networkNeighbors(LevelAccessor level, BlockPos pos) {
        Set<BlockPos> result = new HashSet<>();
        if (!(availableBlockEntity(level, pos) instanceof NetworkCableBlockEntity cable)) return Set.of();
        for (NetworkCableBlockEntity.Run run : cable.runs()) {
            Node node = new Node(pos, run.face(), run.lane(), run.color());
            for (Node connected : connectedNodes(level, node)) {
                if (!connected.pos().equals(pos)) result.add(connected.pos());
            }
            result.addAll(networkNeighborsForRun(level, node));
        }
        return Set.copyOf(result);
    }

    public static Set<Integer> channelsOnFace(BlockGetter level, BlockPos pos, Direction face) {
        if (!(level.getBlockEntity(pos) instanceof NetworkCableBlockEntity cable)) return Set.of();
        return cable.runs().stream().filter(run -> run.face() == face).map(NetworkCableBlockEntity.Run::channel)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Default channels physically presented to one adjacent modem or TerminalCraft endpoint. */
    public static Set<Integer> attachedChannels(LevelAccessor level, BlockPos endpoint) {
        Set<Integer> result = new HashSet<>();
        for (Direction towardCable : Direction.values()) {
            BlockPos cablePos = endpoint.relative(towardCable);
            BlockState state = level.getBlockState(cablePos);
            Direction towardEndpoint = towardCable.getOpposite();
            if (state.getBlock() instanceof BundledNetworkCableBlock
                    && level.getBlockEntity(cablePos) instanceof NetworkCableBlockEntity cable
                    && cable.faces().stream().anyMatch(face -> face.getAxis() != towardEndpoint.getAxis())) {
                for (int channel = 0; channel < 16; channel++) result.add(channel);
            } else if (level.getBlockEntity(cablePos) instanceof NetworkCableBlockEntity cable) {
                for (NetworkCableBlockEntity.Run run : cable.runs()) {
                    if (run.face().getAxis() != towardEndpoint.getAxis()) result.add(run.channel());
                }
            }
        }
        return Set.copyOf(result);
    }

    public static BlockState renderState(BlockGetter level, BlockPos pos, Direction face, int lane, int color) {
        Block currentBlock = level.getBlockState(pos).getBlock();
        BlockState state = (currentBlock instanceof NetworkCableBlock
                ? currentBlock : ModRegistries.NETWORK_CABLE_BLOCK.get()).defaultBlockState()
                .setValue(FACE, face).setValue(COLOR, color);
        int route = visibleRoute(level, pos, face, lane, color);
        for (Direction direction : Direction.values()) {
            if (direction.getAxis() == face.getAxis()) continue;
            state = state.setValue(CableShapeSupport.property(direction),
                    SurfaceCableRouting.hasPort(route, direction));
        }
        return state;
    }

    public static BlockState renderState(BlockGetter level, BlockPos pos, Direction face) {
        if (level.getBlockEntity(pos) instanceof NetworkCableBlockEntity cable) {
            NetworkCableBlockEntity.Run run = cable.runs().stream().filter(value -> value.face() == face)
                    .findFirst().orElse(null);
            if (run != null) return renderState(level, pos, face, run.lane(), run.color());
        }
        return ModRegistries.NETWORK_CABLE_BLOCK.get().defaultBlockState().setValue(FACE, face);
    }

    /** Compatibility hook: ordinary cables now route automatically in the whole face plane. */
    public static int proposedRoute(LevelAccessor level, BlockPos pos, Direction face, int lane,
                                    int color, Direction desired) {
        return SurfaceCableRouting.planeMask(face);
    }

    public static int incomingPorts(LevelAccessor level, BlockPos pos, Direction face, int lane, int color) {
        int incoming = 0;
        if (level.getBlockEntity(pos) instanceof NetworkCableBlockEntity local) {
            for (Direction other : local.faces()) {
                if (other == face || other == face.getOpposite()
                        || !local.hasRun(other, lane, color)) continue;
                if (local.hasPort(other, lane, face.getOpposite())) {
                    incoming |= SurfaceCableRouting.port(other.getOpposite());
                }
            }
        }
        for (Direction direction : SurfaceCableSupport.planeDirections(face)) {
            BlockPos direct = pos.relative(direction);
            if (level.getBlockEntity(direct) instanceof NetworkCableBlockEntity neighbor
                    && !(level.getBlockState(direct).getBlock() instanceof BundledNetworkCableBlock)
                    && neighbor.hasRun(face, lane, color)
                    && neighbor.hasPort(face, lane, direction.getOpposite())) {
                incoming |= SurfaceCableRouting.port(direction);
                continue;
            }
            BlockState bend = level.getBlockState(direct);
            if (!bend.isAir() && !bend.getFluidState().is(FluidTags.WATER)) continue;
            BlockPos around = direct.relative(face.getOpposite());
            if (level.getBlockEntity(around) instanceof NetworkCableBlockEntity neighbor
                    && !(level.getBlockState(around).getBlock() instanceof BundledNetworkCableBlock)
                    && neighbor.hasRun(direction, lane, color)
                    && neighbor.hasPort(direction, lane, face)) {
                incoming |= SurfaceCableRouting.port(direction);
            }
        }
        return SurfaceCableRouting.sanitize(face, incoming);
    }

    public static boolean isConnected(BlockState state, Direction direction) {
        return state.getValue(CableShapeSupport.property(direction));
    }

    @Nullable
    public static Target targetedRun(BlockGetter level, BlockPos pos, Vec3 start, Vec3 end) {
        if (!(level.getBlockEntity(pos) instanceof NetworkCableBlockEntity cable)) return null;
        Target nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (NetworkCableBlockEntity.Run run : cable.runs()) {
            BlockHitResult hit = runShape(level, pos, run.face(), run.lane(), run.color())
                    .clip(start, end, pos);
            if (hit != null && start.distanceToSqr(hit.getLocation()) < distance) {
                nearest = new Target(run.face(), run.lane());
                distance = start.distanceToSqr(hit.getLocation());
            }
        }
        return nearest;
    }

    @Nullable
    public static Direction targetedFace(BlockGetter level, BlockPos pos, Vec3 start, Vec3 end) {
        Target target = targetedRun(level, pos, start, end);
        if (target != null) return target.face();
        if (!(level.getBlockEntity(pos) instanceof NetworkCableBlockEntity cable)) return null;
        Direction nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        for (Direction face : cable.faces()) {
            BlockHitResult hit = faceSelectionShape(face).clip(start, end, pos);
            if (hit != null && start.distanceToSqr(hit.getLocation()) < distance) {
                nearest = face;
                distance = start.distanceToSqr(hit.getLocation());
            }
        }
        return nearest;
    }

    /** Ports with real reciprocal cable, bundled-trunk, modem, or wired-device connections. */
    public static int visibleRoute(BlockGetter level, BlockPos pos, Direction face, int lane, int color) {
        if (!(level.getBlockEntity(pos) instanceof NetworkCableBlockEntity cable)
                || !cable.hasRun(face, lane, color)) return 0;
        if (!(level instanceof LevelAccessor accessor)) return 0;
        Node node = new Node(pos, face, lane, color);
        BlockState currentState = availableState(level, pos);
        if (currentState != null && currentState.getBlock() instanceof BundledNetworkCableBlock) {
            return bundledVisibleRoute(accessor, node, cable);
        }
        int visible = 0;
        for (Node connected : connectedNodes(accessor, node)) {
            Direction arm = armDirection(node, connected);
            if (arm.getAxis() != face.getAxis()) visible |= SurfaceCableRouting.port(arm);
        }
        for (BlockPos neighbor : networkNeighborsForRun(accessor, node)) {
            for (Direction direction : SurfaceCableSupport.planeDirections(face)) {
                BlockPos direct = pos.relative(direction);
                BlockPos around = direct.relative(face.getOpposite());
                if (direct.equals(neighbor) || around.equals(neighbor)) {
                    visible |= SurfaceCableRouting.port(direction);
                }
            }
        }
        return SurfaceCableRouting.sanitize(face, visible);
    }

    /**
     * Match bundled Red Alloy's visual routing: internal perpendicular faces, coplanar neighbors,
     * direct breakouts/devices, and unobstructed external corners. Breakouts do not bend around an
     * external corner; the bundled trunk must occupy both sides of that bend.
     */
    private static int bundledVisibleRoute(LevelAccessor level, Node node,
                                           NetworkCableBlockEntity cable) {
        int visible = 0;
        for (Direction other : cable.faces()) {
            if (other != node.face() && other != node.face().getOpposite()) {
                visible |= SurfaceCableRouting.port(other.getOpposite());
            }
        }
        for (Direction direction : SurfaceCableSupport.planeDirections(node.face())) {
            BlockPos direct = node.pos().relative(direction);
            BlockState directState = availableState(level, direct);
            if (directState == null) continue;
            boolean connected = directState.getBlock() instanceof BundledNetworkCableBlock
                    && hasFace(level, direct, node.face());
            if (!connected && directState.getBlock() instanceof NetworkCableBlock
                    && !(directState.getBlock() instanceof BundledNetworkCableBlock)
                    && availableBlockEntity(level, direct) instanceof NetworkCableBlockEntity breakout) {
                connected = breakout.runs().stream().anyMatch(run -> run.face() == node.face());
            }
            if (!connected && !(directState.getBlock() instanceof NetworkCableBlock)) {
                connected = isDirectNetworkDevice(directState);
            }
            if (!connected && (directState.isAir() || directState.getFluidState().is(FluidTags.WATER))) {
                BlockPos around = direct.relative(node.face().getOpposite());
                BlockState aroundState = availableState(level, around);
                connected = aroundState != null
                        && aroundState.getBlock() instanceof BundledNetworkCableBlock
                        && hasFace(level, around, direction);
            }
            if (connected) visible |= SurfaceCableRouting.port(direction);
        }
        return SurfaceCableRouting.sanitize(node.face(), visible);
    }

    public static String diagnostic(BlockGetter level, BlockPos pos, @Nullable Target target) {
        return String.join(" | ", diagnosticLines(level, pos, target));
    }

    public static java.util.List<String> diagnosticLines(BlockGetter level, BlockPos pos, @Nullable Target target) {
        if (!(level.getBlockEntity(pos) instanceof NetworkCableBlockEntity cable) || cable.runCount() == 0) {
            return java.util.List.of("Network Cable", "State: unavailable");
        }
        NetworkCableBlockEntity.Run run = target == null ? cable.runs().get(0) : cable.runs().stream()
                .filter(candidate -> candidate.face() == target.face() && candidate.lane() == target.lane())
                .findFirst().orElse(cable.runs().get(0));
        if (level.getBlockState(pos).getBlock() instanceof BundledNetworkCableBlock) {
            String faces = cable.faces().stream().map(Direction::getName).sorted()
                    .collect(java.util.stream.Collectors.joining(","));
            Set<Integer> breakouts = new java.util.TreeSet<>();
            if (level instanceof LevelAccessor accessor) {
                for (Direction face : cable.faces()) {
                    for (Direction direction : SurfaceCableSupport.planeDirections(face)) {
                        BlockPos adjacent = pos.relative(direction);
                        if (accessor.getBlockState(adjacent).getBlock() instanceof BundledNetworkCableBlock
                                || !(accessor.getBlockEntity(adjacent) instanceof NetworkCableBlockEntity neighbor)) continue;
                        neighbor.runs().stream().filter(candidate -> candidate.face() == face)
                                .map(NetworkCableBlockEntity.Run::channel).forEach(breakouts::add);
                    }
                }
            }
            return java.util.List.of(
                    "Bundled Network Cable",
                    "Target: face=" + run.face().getName() + "  mounted-faces=" + cable.faceCount(),
                    "Transport: data/control  channels=0-15 (all isolated)",
                    "Mounted faces: " + (faces.isEmpty() ? "none" : faces),
                    "Colored breakouts: " + (breakouts.isEmpty() ? "none" : breakouts));
        }
        Node node = new Node(pos, run.face(), run.lane(), run.color());
        Set<Node> connected = level instanceof LevelAccessor accessor ? connectedNodes(accessor, node) : Set.of();
        String links = connected.stream().map(next -> armDirection(node, next).getName()).distinct().sorted()
                .collect(java.util.stream.Collectors.joining(","));
        boolean trunk = false;
        int topologyNeighbors = 0;
        if (level instanceof LevelAccessor accessor) {
            Set<BlockPos> neighbors = networkNeighborsForRun(accessor, node);
            topologyNeighbors = neighbors.size();
            trunk = neighbors.stream().anyMatch(neighbor ->
                    accessor.getBlockState(neighbor).getBlock() instanceof BundledNetworkCableBlock);
        }
        return java.util.List.of(
                "Network Cable",
                "Target: face=" + run.face().getName() + "  centered cable",
                "Circuit: color=" + SurfaceCableSupport.dyeColor(run.color()).getName()
                        + "  default-channel=" + run.channel(),
                "Routing: automatic RedPower-style links; one cable per face",
                "Links: " + (links.isEmpty() ? "none" : links) + "  trunk=" + (trunk ? "yes" : "no")
                        + "  topology-neighbors=" + topologyNeighbors);
    }

    private static Set<Node> connectedNodes(LevelAccessor level, Node node) {
        Set<Node> result = new HashSet<>();
        if (!hasRun(level, node.pos(), node.face(), node.lane(), node.color())) return result;
        if (availableBlockEntity(level, node.pos()) instanceof NetworkCableBlockEntity cable) {
            for (NetworkCableBlockEntity.Run candidate : cable.runs()) {
                if (candidate.color() == node.color() && candidate.face() != node.face()
                        && candidate.face() != node.face().getOpposite()) {
                    result.add(new Node(node.pos(), candidate.face(), candidate.lane(), candidate.color()));
                }
            }
        }
        for (Direction direction : SurfaceCableSupport.planeDirections(node.face())) {
            BlockPos direct = node.pos().relative(direction);
            BlockState directState = availableState(level, direct);
            if (availableBlockEntity(level, direct) instanceof NetworkCableBlockEntity neighbor
                    && directState != null
                    && !(directState.getBlock() instanceof BundledNetworkCableBlock)) {
                for (NetworkCableBlockEntity.Run candidate : neighbor.runs()) {
                    if (candidate.face() == node.face() && candidate.color() == node.color()) {
                        result.add(new Node(direct, candidate.face(), candidate.lane(), candidate.color()));
                    }
                }
            }
            BlockState bend = directState;
            if (bend == null) continue;
            if (!bend.isAir() && !bend.getFluidState().is(FluidTags.WATER)) continue;
            BlockPos around = direct.relative(node.face().getOpposite());
            BlockState aroundState = availableState(level, around);
            if (availableBlockEntity(level, around) instanceof NetworkCableBlockEntity corner
                    && aroundState != null
                    && !(aroundState.getBlock() instanceof BundledNetworkCableBlock)) {
                for (NetworkCableBlockEntity.Run candidate : corner.runs()) {
                    if (candidate.face() == direction && candidate.color() == node.color()) {
                        result.add(new Node(around, candidate.face(), candidate.lane(), candidate.color()));
                    }
                }
            }
        }
        return result;
    }

    private static Set<BlockPos> networkNeighborsForRun(LevelAccessor level, Node node) {
        Set<BlockPos> result = new HashSet<>();
        BlockState currentState = availableState(level, node.pos());
        if (currentState == null) return result;
        boolean currentBundle = currentState.getBlock() instanceof BundledNetworkCableBlock;
        for (Direction direction : SurfaceCableSupport.planeDirections(node.face())) {
            BlockPos adjacent = node.pos().relative(direction);
            BlockState state = availableState(level, adjacent);
            if (state == null) continue;
            boolean bundle = state.getBlock() instanceof BundledNetworkCableBlock
                    && hasFace(level, adjacent, node.face());
            boolean breakout = currentBundle && state.getBlock() instanceof NetworkCableBlock
                    && !(state.getBlock() instanceof BundledNetworkCableBlock)
                    && availableBlockEntity(level, adjacent) instanceof NetworkCableBlockEntity neighbor
                    && neighbor.runs().stream().anyMatch(candidate -> candidate.face() == node.face());
            boolean device = isDirectNetworkDevice(state);
            if (bundle || breakout || device) result.add(adjacent.immutable());

            if (!state.isAir() && !state.getFluidState().is(FluidTags.WATER)) continue;
            BlockPos around = adjacent.relative(node.face().getOpposite());
            BlockState cornerState = availableState(level, around);
            if (cornerState == null) continue;
            boolean cornerBundle = currentBundle
                    && cornerState.getBlock() instanceof BundledNetworkCableBlock
                    && hasFace(level, around, direction);
            if (cornerBundle) result.add(around.immutable());
        }
        return result;
    }

    /** Keep rendering and physical topology aligned with TerminalCraft's complete wired endpoint set. */
    private static boolean isDirectNetworkDevice(BlockState state) {
        if (state.getBlock() instanceof NetworkCableBlock) return false;
        return state.getBlock() instanceof WiredNetworkNode
                || state.getBlock() instanceof ModemBlock
                || state.getBlock() instanceof TerminalBlock
                || state.getBlock() instanceof ProgrammableLogicControllerBlock
                || state.getBlock() instanceof StandaloneSensorBlock
                || state.getBlock() instanceof SensorArrayBlock;
    }

    /** Avoid recursively requesting a chunk while the wired-topology load callback is indexing it. */
    @Nullable
    private static BlockState availableState(BlockGetter level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) {
            LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            return chunk == null ? null : chunk.getBlockState(pos);
        }
        return level.getBlockState(pos);
    }

    /** Avoid recursively requesting a chunk while the wired-topology load callback is indexing it. */
    @Nullable
    private static BlockEntity availableBlockEntity(BlockGetter level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) {
            LevelChunk chunk = serverLevel.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            return chunk == null ? null : chunk.getBlockEntity(pos);
        }
        return level.getBlockEntity(pos);
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

    private static String routeShape(int ports) {
        if (SurfaceCableRouting.isStraight(ports)) return "straight";
        if (SurfaceCableRouting.isTurn(ports)) return "turn";
        return Integer.bitCount(ports) > 2 ? "junction" : "endpoint";
    }

    private static void syncPrimaryState(Level level, BlockPos pos, NetworkCableBlockEntity cable) {
        NetworkCableBlockEntity.Run primary = cable.runs().stream().findFirst().orElse(null);
        if (primary == null) return;
        BlockState rendered = renderState(level, pos, primary.face(), primary.lane(), primary.color());
        if (!level.getBlockState(pos).equals(rendered)) level.setBlock(pos, rendered, Block.UPDATE_CLIENTS);
    }

    private static void notifyTopology(Level level, BlockPos pos) {
        if (level.isClientSide) return;
        if (level instanceof ServerLevel serverLevel) {
            com.malice.terminalcraft.network.WiredNetworkTopology.invalidate(serverLevel, pos);
        }
        level.updateNeighborsAt(pos, ModRegistries.NETWORK_CABLE_BLOCK.get());
        for (Direction direction : Direction.values()) {
            level.updateNeighborsAt(pos.relative(direction), ModRegistries.NETWORK_CABLE_BLOCK.get());
        }
    }

    public static boolean canFaceSurvive(LevelReader level, BlockPos pos, Direction face) {
        BlockPos support = pos.relative(face.getOpposite());
        return level.getBlockState(support).isFaceSturdy(level, support, face);
    }

    /** One centered RedPower-style data cable with arms for live automatic connections. */
    public static VoxelShape renderedRunShape(BlockGetter level, BlockPos pos, Direction face,
                                              int lane, int color) {
        boolean bundled = level.getBlockState(pos).getBlock() instanceof BundledNetworkCableBlock;
        int route = visibleRoute(level, pos, face, lane, color);
        if (bundled) return SurfaceCableSupport.continuousRunShape(face, lane, route);
        return SurfaceCableSupport.centeredRunShape(face, route);
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

    private static ItemStack cableDrop(BlockGetter level, BlockPos pos, int color) {
        if (level.getBlockState(pos).getBlock() instanceof BundledNetworkCableBlock) {
            return ModRegistries.BUNDLED_NETWORK_CABLE_ITEM.get().getDefaultInstance();
        }
        return NetworkCableItem.colored(ModRegistries.NETWORK_CABLE_ITEM.get().getDefaultInstance(),
                SurfaceCableSupport.dyeColor(color));
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        super.onRemove(state, level, pos, newState, moving);
        if (state.getBlock() != newState.getBlock() && level instanceof ServerLevel serverLevel) {
            com.malice.terminalcraft.network.WiredNetworkTopology.invalidate(serverLevel, pos);
        }
    }
}
