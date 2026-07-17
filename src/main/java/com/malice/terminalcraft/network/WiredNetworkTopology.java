package com.malice.terminalcraft.network;

import com.malice.terminalcraft.block.WiredNetworkNode;
import com.malice.terminalcraft.block.NetworkCableBlock;
import com.malice.terminalcraft.blockentity.ModemBlockEntity;
import com.malice.terminalcraft.blockentity.NetworkRouterBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.WeakHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Bounded on-demand topology, physical-subnet, and minimum-router-hop traversal for wired RedNet. */
public final class WiredNetworkTopology {
    public static final int MAX_VISITED_NODES = 4096;

    public record Component(int nodeCount, int modemCount, boolean truncated) {}

    /** Deterministic identity for one loaded non-router forwarding segment. */
    public record SubnetId(String dimension, BlockPos anchor) {
        public SubnetId {
            if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("dimension is required");
            if (anchor == null) throw new IllegalArgumentException("anchor is required");
            anchor = anchor.immutable();
        }

        public String displayName() {
            return dimension + "@" + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ();
        }
    }

    /** Description of one physical subnet. Router nodes are boundaries and are not members. */
    public record Subnet(SubnetId id, int nodeCount, int modemCount, boolean truncated) {}

    /** One logical interface from a contiguous router group to a physical subnet. */
    public record RouterInterface(SubnetId subnet, int attachmentCount) {}

    /** One face-specific attachment from a routing node to a physical subnet. */
    public record RouterAttachment(BlockPos router, Direction face, SubnetId subnet, String networkName,
                                   boolean enabled) {
        public RouterAttachment {
            router = router.immutable();
            networkName = networkName == null ? "" : networkName;
        }
    }

    /** Bounded interface inventory for one contiguous standalone-router or cabinet-router group. */
    public record RouterView(int routerNodeCount, List<RouterInterface> interfaces, boolean truncated) {
        public RouterView {
            interfaces = List.copyOf(interfaces);
        }
    }

    /** One actual ingress/egress traversal through a contiguous router group on the selected path. */
    public record RouterPass(BlockPos ingressRouter, Direction ingressFace,
                             BlockPos egressRouter, Direction egressFace,
                             List<BlockPos> traversedRouters) {
        public RouterPass {
            if (ingressRouter == null || ingressFace == null || egressRouter == null || egressFace == null) {
                throw new IllegalArgumentException("router pass endpoints are required");
            }
            ingressRouter = ingressRouter.immutable();
            egressRouter = egressRouter.immutable();
            if (traversedRouters == null || traversedRouters.isEmpty()) {
                throw new IllegalArgumentException("router pass path is required");
            }
            traversedRouters = traversedRouters.stream().map(BlockPos::immutable).toList();
            if (!traversedRouters.get(0).equals(ingressRouter)
                    || !traversedRouters.get(traversedRouters.size() - 1).equals(egressRouter)) {
                throw new IllegalArgumentException("router pass endpoints must match the traversed path");
            }
        }
    }

    public record Route(boolean reachable, int routerHops, int visitedNodes, boolean truncated,
                        List<RouterPass> routerPasses) {
        public Route {
            if (routerPasses == null) throw new IllegalArgumentException("router passes are required");
            routerPasses = List.copyOf(routerPasses);
            if (reachable && routerHops != routerPasses.size()) {
                throw new IllegalArgumentException("router hop count must match path transitions");
            }
            if (!reachable && !routerPasses.isEmpty()) {
                throw new IllegalArgumentException("unreachable routes cannot expose a partial path");
            }
        }

        static Route unreachable(int visitedNodes, boolean truncated) {
            return new Route(false, -1, visitedNodes, truncated, List.of());
        }
    }

    /** Bounded cache diagnostics for tests and administrator tooling. */
    public record CacheDiagnostics(long revision, long computations, long hits, int entries) {}

    private record RouteKey(BlockPos source, BlockPos destination) {
        private RouteKey { source = source.immutable(); destination = destination.immutable(); }
    }

    private static final class RouteCache {
        private static final int MAX_ENTRIES = 1024;
        private final LinkedHashMap<RouteKey, Route> routes = new LinkedHashMap<>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<RouteKey, Route> eldest) {
                return size() > MAX_ENTRIES;
            }
        };
        private long revision;
        private long computations;
        private long hits;

        synchronized Route get(RouteKey key) {
            Route route = routes.get(key);
            if (route != null) hits++;
            return route;
        }
        synchronized Route put(RouteKey key, Route route) {
            computations++;
            routes.put(key, route);
            return route;
        }
        synchronized void invalidate() { revision++; routes.clear(); }
        synchronized CacheDiagnostics diagnostics() {
            return new CacheDiagnostics(revision, computations, hits, routes.size());
        }
    }

    private static final Map<ServerLevel, RouteCache> ROUTE_CACHES =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private record Step(BlockPos pos, int routerHops) {}
    private record SegmentScan(Set<BlockPos> nodes, Set<BlockPos> modems, BlockPos anchor, boolean truncated) {}
    private record SegmentPolicy(boolean valid, boolean truncated) {}

    /** Per-route cache for fail-closed logical-network classification of physical segments. */
    private static final class LogicalPolicy {
        private final ServerLevel level;
        private final Map<BlockPos, SegmentPolicy> byNode = new HashMap<>();

        private LogicalPolicy(ServerLevel level) {
            this.level = level;
        }

        boolean allowsNode(BlockPos pos) {
            return !isSegmentNode(level, pos) || segment(pos).valid();
        }

        boolean truncated(BlockPos pos) {
            return isSegmentNode(level, pos) && segment(pos).truncated();
        }

        boolean anyTruncated() {
            return byNode.values().stream().anyMatch(SegmentPolicy::truncated);
        }

        boolean allowsModemAttachment(BlockPos modemPos, BlockPos nodePos) {
            if (isSegmentNode(level, nodePos)) return segment(nodePos).valid();
            if (!isRoutingNode(level, nodePos)) return false;
            String modemNetwork = modemNetwork(level, modemPos);
            Direction face = directionFromTo(nodePos, modemPos);
            if (face != null && level.getBlockEntity(nodePos) instanceof NetworkRouterBlockEntity router) {
                return router.isInterfaceEnabled(face)
                        && compatible(modemNetwork, router.getInterfaceNetwork(face));
            }
            return compatible(modemNetwork, "");
        }

        private SegmentPolicy segment(BlockPos start) {
            SegmentPolicy cached = byNode.get(start);
            if (cached != null) return cached;
            SegmentScan scan = scanSegment(level, start);
            Set<String> explicitNetworks = new HashSet<>();
            for (BlockPos modem : scan.modems()) {
                String network = modemNetwork(level, modem);
                if (!network.isEmpty()) explicitNetworks.add(network);
            }
            for (BlockPos node : scan.nodes()) {
                for (Direction direction : Direction.values()) {
                    BlockPos routerPos = node.relative(direction);
                    if (!(level.getBlockEntity(routerPos) instanceof NetworkRouterBlockEntity router)
                            || !hasPhysicalEdge(level, node, routerPos) || !isRoutingNode(level, routerPos)
                            || !router.isInterfaceEnabled(direction.getOpposite())) continue;
                    String network = router.getInterfaceNetwork(direction.getOpposite());
                    if (!network.isEmpty()) explicitNetworks.add(network);
                }
            }
            SegmentPolicy policy = new SegmentPolicy(!scan.truncated() && explicitNetworks.size() <= 1,
                    scan.truncated());
            for (BlockPos node : scan.nodes()) byNode.put(node, policy);
            return policy;
        }
    }

    private WiredNetworkTopology() {}

    /** Invalidates bounded route snapshots after a local topology or configuration mutation. */
    public static void invalidate(ServerLevel level, BlockPos changedPosition) {
        if (level == null || changedPosition == null) return;
        cache(level).invalidate();
    }

    /** Chunk load/unload invalidation; no route query scans unloaded chunks to discover changes. */
    public static void invalidateChunk(ServerLevel level, net.minecraft.world.level.ChunkPos chunk) {
        if (level == null || chunk == null) return;
        cache(level).invalidate();
    }

    /** Releases every route snapshot owned by one stopped logical server. */
    public static void clear(net.minecraft.server.MinecraftServer server) {
        if (server == null) return;
        synchronized (ROUTE_CACHES) {
            ROUTE_CACHES.keySet().removeIf(level -> level.getServer() == server);
        }
    }

    public static CacheDiagnostics cacheDiagnostics(ServerLevel level) {
        return level == null ? new CacheDiagnostics(0, 0, 0, 0) : cache(level).diagnostics();
    }

    private static RouteCache cache(ServerLevel level) {
        return ROUTE_CACHES.computeIfAbsent(level, ignored -> new RouteCache());
    }

    /** Compatibility wrapper for callers that only need physical reachability. */
    public static boolean connected(ServerLevel level, BlockPos firstModem, BlockPos secondModem) {
        Route route = route(level, firstModem, secondModem);
        return route.reachable() && !route.truncated();
    }

    /**
     * Finds the loaded physical route using the fewest router transitions. Entering one contiguous
     * group of router nodes costs one hop; ordinary cable/backplane traversal costs zero hops.
     */
    public static Route route(ServerLevel level, BlockPos firstModem, BlockPos secondModem) {
        if (level == null || firstModem == null || secondModem == null || firstModem.equals(secondModem)) {
            return Route.unreachable(0, false);
        }
        RouteKey key = new RouteKey(firstModem, secondModem);
        Route cached = cache(level).get(key);
        if (cached != null) return cached;
        return cache(level).put(key, computeRoute(level, firstModem, secondModem));
    }

    private static Route computeRoute(ServerLevel level, BlockPos firstModem, BlockPos secondModem) {
        if (!isWiredModem(level, firstModem) || !isWiredModem(level, secondModem)) {
            return Route.unreachable(0, false);
        }
        LogicalPolicy policy = new LogicalPolicy(level);
        Set<BlockPos> targets = adjacentNetworkNodes(level, secondModem).stream()
                .filter(node -> policy.allowsNode(node) && policy.allowsModemAttachment(secondModem, node))
                .collect(java.util.stream.Collectors.toSet());
        if (targets.isEmpty()) return Route.unreachable(0, policy.anyTruncated());

        ArrayDeque<Step> pending = new ArrayDeque<>();
        Map<BlockPos, Integer> best = new HashMap<>();
        Map<BlockPos, BlockPos> predecessor = new HashMap<>();
        Set<BlockPos> settled = new HashSet<>();
        for (BlockPos start : adjacentNetworkNodes(level, firstModem)) {
            if (!policy.allowsNode(start) || !policy.allowsModemAttachment(firstModem, start)) continue;
            int hops = isRoutingNode(level, start) ? 1 : 0;
            if (hops <= NetworkEnvelope.MAX_HOPS) {
                best.put(start, hops);
                pending.addLast(new Step(start, hops));
            }
        }

        while (!pending.isEmpty() && settled.size() < MAX_VISITED_NODES) {
            Step step = pending.removeFirst();
            if (step.routerHops() != best.getOrDefault(step.pos(), Integer.MAX_VALUE)
                    || !settled.add(step.pos())) continue;
            if (targets.contains(step.pos())) {
                List<RouterPass> passes = describeRouterPasses(level, firstModem, secondModem,
                        step.pos(), predecessor);
                return new Route(true, step.routerHops(), settled.size(), false, passes);
            }

            boolean currentRouter = isRoutingNode(level, step.pos());
            for (BlockPos next : forwardingNeighbors(level, step.pos())) {
                if (!policy.allowsNode(next) || !allowsEdge(level, step.pos(), next)) continue;
                boolean nextRouter = isRoutingNode(level, next);
                int nextHops = step.routerHops() + (!currentRouter && nextRouter ? 1 : 0);
                if (nextHops > NetworkEnvelope.MAX_HOPS
                        || nextHops >= best.getOrDefault(next, Integer.MAX_VALUE)) continue;
                BlockPos immutable = next.immutable();
                best.put(immutable, nextHops);
                predecessor.put(immutable, step.pos());
                Step nextStep = new Step(immutable, nextHops);
                if (nextHops == step.routerHops()) pending.addFirst(nextStep);
                else pending.addLast(nextStep);
            }
        }
        return Route.unreachable(settled.size(), !pending.isEmpty() || policy.anyTruncated());
    }

    /** Reconstructs immutable router ingress/egress transitions from the selected minimum-hop path. */
    private static List<RouterPass> describeRouterPasses(ServerLevel level, BlockPos firstModem,
                                                          BlockPos secondModem, BlockPos target,
                                                          Map<BlockPos, BlockPos> predecessor) {
        List<BlockPos> reversed = new ArrayList<>();
        BlockPos current = target;
        while (current != null) {
            reversed.add(current.immutable());
            current = predecessor.get(current);
        }
        java.util.Collections.reverse(reversed);

        List<BlockPos> path = new ArrayList<>(reversed.size() + 2);
        path.add(firstModem.immutable());
        path.addAll(reversed);
        path.add(secondModem.immutable());

        List<RouterPass> passes = new ArrayList<>();
        int index = 1;
        while (index < path.size() - 1) {
            if (!isRoutingNode(level, path.get(index))) {
                index++;
                continue;
            }
            int start = index;
            while (index + 1 < path.size() - 1 && isRoutingNode(level, path.get(index + 1))) index++;
            int end = index;
            Direction ingress = directionFromTo(path.get(start), path.get(start - 1));
            Direction egress = directionFromTo(path.get(end), path.get(end + 1));
            if (ingress == null || egress == null) {
                throw new IllegalStateException("selected router path contains a non-adjacent transition");
            }
            passes.add(new RouterPass(path.get(start), ingress, path.get(end), egress,
                    path.subList(start, end + 1)));
            index++;
        }
        return List.copyOf(passes);
    }

    /** Resolves the physical subnet containing a non-router forwarding node. */
    public static Optional<Subnet> subnet(ServerLevel level, BlockPos networkNode) {
        if (level == null || networkNode == null || !isSegmentNode(level, networkNode)) return Optional.empty();
        return Optional.of(describeSubnet(level, scanSegment(level, networkNode)));
    }

    /** Lists the distinct physical subnets directly available to a modem. */
    public static List<Subnet> modemSubnets(ServerLevel level, BlockPos modemPos) {
        if (level == null || modemPos == null || !isWiredModem(level, modemPos)) return List.of();
        Map<SubnetId, Subnet> found = new HashMap<>();
        Set<BlockPos> classified = new HashSet<>();
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = modemPos.relative(direction);
            if (!isSegmentNode(level, adjacent) || !hasPhysicalEdge(level, modemPos, adjacent)
                    || classified.contains(adjacent)) continue;
            SegmentScan scan = scanSegment(level, adjacent);
            classified.addAll(scan.nodes());
            Subnet subnet = describeSubnet(level, scan);
            found.put(subnet.id(), subnet);
        }
        return found.values().stream().sorted(Comparator.comparing(subnet -> subnet.id().displayName())).toList();
    }

    /** Lists every face-specific physical attachment of one contiguous routing-node group. */
    public static List<RouterAttachment> routerAttachments(ServerLevel level, BlockPos routerNode) {
        if (level == null || routerNode == null || !isRoutingNode(level, routerNode)) return List.of();
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        Set<BlockPos> routers = new HashSet<>();
        pending.add(routerNode.immutable());
        while (!pending.isEmpty() && routers.size() < MAX_VISITED_NODES) {
            BlockPos current = pending.removeFirst();
            if (!routers.add(current)) continue;
            for (Direction direction : Direction.values()) {
                BlockPos next = current.relative(direction);
                if (isRoutingNode(level, next) && allowsEdge(level, current, next)
                        && !routers.contains(next)) pending.addLast(next.immutable());
            }
        }

        Map<BlockPos, SubnetId> subnetByNode = new HashMap<>();
        List<RouterAttachment> result = new ArrayList<>();
        for (BlockPos router : routers) {
            for (Direction face : Direction.values()) {
                BlockPos adjacent = router.relative(face);
                if (!isSegmentNode(level, adjacent) || !hasPhysicalEdge(level, router, adjacent)) continue;
                SubnetId subnet = subnetByNode.get(adjacent);
                if (subnet == null) {
                    SegmentScan scan = scanSegment(level, adjacent);
                    subnet = describeSubnet(level, scan).id();
                    for (BlockPos node : scan.nodes()) subnetByNode.put(node, subnet);
                }
                boolean enabled = !(level.getBlockEntity(router) instanceof NetworkRouterBlockEntity blockEntity)
                        || blockEntity.isInterfaceEnabled(face);
                String networkName = level.getBlockEntity(router) instanceof NetworkRouterBlockEntity blockEntity
                        ? blockEntity.getInterfaceNetwork(face) : "";
                result.add(new RouterAttachment(router, face, subnet, networkName, enabled));
            }
        }
        result.sort(Comparator
                .comparingInt((RouterAttachment attachment) -> attachment.router().getX())
                .thenComparingInt(attachment -> attachment.router().getY())
                .thenComparingInt(attachment -> attachment.router().getZ())
                .thenComparingInt(attachment -> attachment.face().ordinal()));
        return List.copyOf(result);
    }

    /**
     * Resolves the interfaces of one contiguous routing-node group. Multiple physical attachment
     * edges to the same subnet are represented by one interface with an attachment count.
     */
    public static RouterView routerInterfaces(ServerLevel level, BlockPos routerNode) {
        if (level == null || routerNode == null || !isRoutingNode(level, routerNode)) {
            return new RouterView(0, List.of(), false);
        }
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        Set<BlockPos> routers = new HashSet<>();
        pending.add(routerNode.immutable());
        while (!pending.isEmpty() && routers.size() < MAX_VISITED_NODES) {
            BlockPos current = pending.removeFirst();
            if (!routers.add(current)) continue;
            for (Direction direction : Direction.values()) {
                BlockPos next = current.relative(direction);
                if (isRoutingNode(level, next) && allowsEdge(level, current, next)
                        && !routers.contains(next)) pending.addLast(next.immutable());
            }
        }
        boolean truncated = !pending.isEmpty();

        Map<BlockPos, SubnetId> subnetByNode = new HashMap<>();
        Map<SubnetId, Integer> attachments = new HashMap<>();
        for (BlockPos router : routers) {
            for (Direction direction : Direction.values()) {
                BlockPos adjacent = router.relative(direction);
                if (!isSegmentNode(level, adjacent) || !hasPhysicalEdge(level, router, adjacent)) continue;
                SubnetId id = subnetByNode.get(adjacent);
                if (id == null) {
                    SegmentScan scan = scanSegment(level, adjacent);
                    Subnet subnet = describeSubnet(level, scan);
                    id = subnet.id();
                    for (BlockPos node : scan.nodes()) subnetByNode.put(node, id);
                    truncated |= scan.truncated();
                }
                attachments.merge(id, 1, Integer::sum);
            }
        }

        List<RouterInterface> interfaces = new ArrayList<>();
        attachments.forEach((subnet, count) -> interfaces.add(new RouterInterface(subnet, count)));
        interfaces.sort(Comparator.comparing(routerInterface -> routerInterface.subnet().displayName()));
        return new RouterView(routers.size(), interfaces, truncated);
    }

    /** Inspects one connected component for router diagnostics. */
    public static Component inspect(ServerLevel level, BlockPos networkNode) {
        if (level == null || networkNode == null || !isForwardingNode(level, networkNode)) {
            return new Component(0, 0, false);
        }
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        Set<BlockPos> modems = new HashSet<>();
        pending.add(networkNode.immutable());
        while (!pending.isEmpty() && visited.size() < MAX_VISITED_NODES) {
            BlockPos current = pending.removeFirst();
            if (!visited.add(current)) continue;
            for (BlockPos next : physicalNeighbors(level, current)) {
                if (!allowsEdge(level, current, next)) continue;
                if (isForwardingNode(level, next)) {
                    if (!visited.contains(next)) pending.addLast(next.immutable());
                } else if (isWiredModem(level, next)) {
                    modems.add(next.immutable());
                }
            }
        }
        return new Component(visited.size(), modems.size(), !pending.isEmpty());
    }

    private static SegmentScan scanSegment(ServerLevel level, BlockPos start) {
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        Set<BlockPos> modems = new HashSet<>();
        BlockPos anchor = start.immutable();
        pending.add(anchor);
        while (!pending.isEmpty() && visited.size() < MAX_VISITED_NODES) {
            BlockPos current = pending.removeFirst();
            if (!visited.add(current)) continue;
            if (comparePosition(current, anchor) < 0) anchor = current;
            for (BlockPos next : physicalNeighbors(level, current)) {
                if (isSegmentNode(level, next)) {
                    if (!visited.contains(next)) pending.addLast(next.immutable());
                } else if (isWiredModem(level, next)) {
                    modems.add(next.immutable());
                }
            }
        }
        return new SegmentScan(Set.copyOf(visited), Set.copyOf(modems), anchor, !pending.isEmpty());
    }

    private static Subnet describeSubnet(ServerLevel level, SegmentScan scan) {
        SubnetId id = new SubnetId(level.dimension().location().toString(), scan.anchor());
        return new Subnet(id, scan.nodes().size(), scan.modems().size(), scan.truncated());
    }

    private static int comparePosition(BlockPos first, BlockPos second) {
        int x = Integer.compare(first.getX(), second.getX());
        if (x != 0) return x;
        int y = Integer.compare(first.getY(), second.getY());
        return y != 0 ? y : Integer.compare(first.getZ(), second.getZ());
    }


    /** A disabled standalone-router face removes that physical edge from live route traversal. */
    private static boolean allowsEdge(ServerLevel level, BlockPos from, BlockPos to) {
        if (!hasPhysicalEdge(level, from, to)) return false;
        Direction direction = directionFromTo(from, to);
        if (direction == null) return true;
        if (level.getBlockEntity(from) instanceof NetworkRouterBlockEntity source
                && !source.isInterfaceEnabled(direction)) return false;
        return !(level.getBlockEntity(to) instanceof NetworkRouterBlockEntity destination)
                || destination.isInterfaceEnabled(direction.getOpposite());
    }

    private static String modemNetwork(ServerLevel level, BlockPos modemPos) {
        return level.getBlockEntity(modemPos) instanceof ModemBlockEntity modem && !modem.isWireless()
                ? modem.getNetworkName() : "";
    }

    private static boolean compatible(String first, String second) {
        return first == null || first.isEmpty() || second == null || second.isEmpty() || first.equals(second);
    }

    private static Direction directionFromTo(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        for (Direction direction : Direction.values()) {
            if (direction.getStepX() == dx && direction.getStepY() == dy && direction.getStepZ() == dz) {
                return direction;
            }
        }
        return null;
    }

    private static Set<BlockPos> adjacentNetworkNodes(ServerLevel level, BlockPos modem) {
        Set<BlockPos> nodes = new HashSet<>();
        for (BlockPos candidate : physicalNeighbors(level, modem)) {
            if (isForwardingNode(level, candidate)) nodes.add(candidate.immutable());
        }
        return nodes;
    }

    /** Physical graph neighbors, including surface-cable external corners but excluding false face contacts. */
    private static Set<BlockPos> physicalNeighbors(ServerLevel level, BlockPos pos) {
        Set<BlockPos> result = new HashSet<>();
        if (level.getBlockState(pos).getBlock() instanceof NetworkCableBlock) {
            result.addAll(NetworkCableBlock.networkNeighbors(level, pos));
        }
        for (Direction direction : Direction.values()) {
            BlockPos candidate = pos.relative(direction);
            if (hasPhysicalEdge(level, pos, candidate)) result.add(candidate.immutable());
        }
        return result;
    }

    private static Set<BlockPos> forwardingNeighbors(ServerLevel level, BlockPos pos) {
        return physicalNeighbors(level, pos).stream().filter(next -> isForwardingNode(level, next))
                .collect(java.util.stream.Collectors.toSet());
    }

    private static boolean hasPhysicalEdge(ServerLevel level, BlockPos first, BlockPos second) {
        if (level.getBlockState(first).getBlock() instanceof NetworkCableBlock) {
            return NetworkCableBlock.networkNeighbors(level, first).contains(second);
        }
        if (level.getBlockState(second).getBlock() instanceof NetworkCableBlock) {
            return NetworkCableBlock.networkNeighbors(level, second).contains(first);
        }
        return directionFromTo(first, second) != null;
    }

    private static boolean isWiredModem(ServerLevel level, BlockPos pos) {
        return level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof ModemBlockEntity modem
                && !modem.isWireless();
    }

    private static boolean isSegmentNode(ServerLevel level, BlockPos pos) {
        return isForwardingNode(level, pos) && !isRoutingNode(level, pos);
    }

    private static boolean isRoutingNode(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return false;
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof WiredNetworkNode node
                && node.forwardsWiredTraffic(level, pos, state)
                && node.routesWiredTraffic(level, pos, state);
    }

    private static boolean isForwardingNode(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return false;
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof WiredNetworkNode node && node.forwardsWiredTraffic(level, pos, state);
    }
}
