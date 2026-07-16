package com.malice.terminalcraft.network;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import com.malice.terminalcraft.device.DeviceValue;
import com.malice.terminalcraft.device.ServerDeviceManager;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.charset.StandardCharsets;

/**
 * In-world rednet-style message bus for TerminalCraft modems.
 * Server-only; never touches the real network stack.
 */
public final class RednetNetwork {
    public static final int MAX_MESSAGE_LENGTH = 4096;
    private static final Map<RednetRuntimeScope.Endpoint, List<PendingMessage>> QUEUES = new ConcurrentHashMap<>();
    private static final Map<RednetRuntimeScope, List<Subscription>> SUBS = new ConcurrentHashMap<>();
    private static final Map<RednetRuntimeScope, RednetHostDirectory> HOSTS = new ConcurrentHashMap<>();
    private static final Map<RednetRuntimeScope, RednetServiceDirectory> SERVICES = new ConcurrentHashMap<>();
    private static final Map<RednetRuntimeScope, RednetDuplicateFilter> DIRECTED_DUPLICATES =
            new ConcurrentHashMap<>();
    private static final long DIRECTED_DUPLICATE_RETENTION_TICKS = 7_200;
    private static final Map<RednetRuntimeScope, RednetDeliveryRuntime> DELIVERIES = new ConcurrentHashMap<>();
    private static final Map<RednetRuntimeScope, Map<UUID, ReliableRoute>> RELIABLE_ROUTES = new ConcurrentHashMap<>();
    private static final Map<RednetRuntimeScope, Long> LAST_DELIVERY_TICK = new ConcurrentHashMap<>();
    private static final Map<RednetRuntimeScope, RednetTrafficQuota> TRAFFIC_QUOTAS = new ConcurrentHashMap<>();
    private static final Map<RednetRuntimeScope, RednetQueueBudget> QUEUE_BUDGETS = new ConcurrentHashMap<>();
    private static final Map<RednetRuntimeScope, RejectionCounter> REJECTIONS = new ConcurrentHashMap<>();
    private static final Map<RednetRuntimeScope.Endpoint, List<NetworkEnvelope>> ACK_CONTROLS =
            new ConcurrentHashMap<>();
    public static final long DEFAULT_ACK_TIMEOUT_TICKS = 20;
    public static final int DEFAULT_MAX_RETRIES = 2;

    /** Saturating, aggregate rejection diagnostics for one logical RedNet scope. */
    public record RejectionDiagnostics(long malformed, long rateLimited,
                                       long applicationQueueFull, long controlQueueFull) {}

    private static final class RejectionCounter {
        private long malformed;
        private long rateLimited;
        private long applicationQueueFull;
        private long controlQueueFull;

        synchronized void increment(RejectionKind kind) {
            switch (kind) {
                case MALFORMED -> malformed = saturatingIncrement(malformed);
                case RATE_LIMITED -> rateLimited = saturatingIncrement(rateLimited);
                case APPLICATION_QUEUE_FULL -> applicationQueueFull = saturatingIncrement(applicationQueueFull);
                case CONTROL_QUEUE_FULL -> controlQueueFull = saturatingIncrement(controlQueueFull);
            }
        }

        synchronized RejectionDiagnostics snapshot() {
            return new RejectionDiagnostics(malformed, rateLimited, applicationQueueFull, controlQueueFull);
        }
    }

    private enum RejectionKind { MALFORMED, RATE_LIMITED, APPLICATION_QUEUE_FULL, CONTROL_QUEUE_FULL }

    private RednetNetwork() {}

    public static boolean registerHost(Level level, UUID modemId, String hostname) {
        return registerHostDetailed(level, modemId, hostname).accepted();
    }

    /** Registers or renames a host while preserving a machine-readable failure reason. */
    public static RednetRegistrationResult registerHostDetailed(
            Level level, UUID modemId, String hostname) {
        if (level == null || level.isClientSide || modemId == null) {
            return RednetRegistrationResult.of(RednetRegistrationResult.Status.INVALID, "");
        }
        return HOSTS.computeIfAbsent(scope(level), ignored -> new RednetHostDirectory())
                .registerDetailed(modemId, hostname);
    }

    public static void unregisterHost(Level level, UUID modemId) {
        if (level == null || level.isClientSide || modemId == null) return;
        RednetHostDirectory directory = HOSTS.get(scope(level));
        if (directory != null) directory.unregister(modemId);
    }

    public static String hostname(Level level, UUID modemId) {
        if (level == null || level.isClientSide || modemId == null) return "";
        RednetHostDirectory directory = HOSTS.get(scope(level));
        return directory == null ? "" : directory.name(modemId);
    }

    /** Returns the stable logical identity with its current optional directory alias. */
    public static java.util.Optional<RednetAddress> address(Level level, UUID modemId) {
        if (level == null || level.isClientSide || modemId == null) return java.util.Optional.empty();
        return java.util.Optional.of(new RednetAddress(modemId, hostname(level, modemId)));
    }

    public static List<String> hosts(Level level, int maximum) {
        if (level == null || level.isClientSide) return List.of();
        RednetHostDirectory directory = HOSTS.get(scope(level));
        return directory == null ? List.of() : directory.names(maximum);
    }

    public static boolean registerService(Level level, UUID modemId, String serviceName, int port) {
        if (level == null || level.isClientSide || modemId == null) return false;
        return SERVICES.computeIfAbsent(scope(level), ignored -> new RednetServiceDirectory())
                .register(modemId, serviceName, clampChannel(port), RednetServiceDirectory.LEGACY_PROTOCOL);
    }

    /** Registers a typed service; unlike numeric compatibility APIs, invalid ports fail closed. */
    public static boolean registerService(Level level, UUID modemId, String serviceName, int port,
                                          RednetProtocol protocol) {
        return registerServiceDetailed(level, modemId, serviceName, port, protocol).accepted();
    }

    /** Registers a typed service while preserving conflict and capacity failure reasons. */
    public static RednetRegistrationResult registerServiceDetailed(
            Level level, UUID modemId, String serviceName, int port, RednetProtocol protocol) {
        if (level == null || level.isClientSide || modemId == null) {
            return RednetRegistrationResult.of(RednetRegistrationResult.Status.INVALID, "");
        }
        return SERVICES.computeIfAbsent(scope(level), ignored -> new RednetServiceDirectory())
                .registerDetailed(modemId, serviceName, port, protocol);
    }

    /** Resolves a service to stable device identity plus its declared protocol contract. */
    public static java.util.Optional<RednetServiceEndpoint> resolveService(Level level, String serviceName) {
        if (level == null || level.isClientSide) return java.util.Optional.empty();
        RednetServiceDirectory directory = SERVICES.get(scope(level));
        RednetServiceDirectory.Service service = directory == null
                ? null : directory.resolve(serviceName).orElse(null);
        if (service == null) return java.util.Optional.empty();
        return java.util.Optional.of(new RednetServiceEndpoint(service.name(),
                new RednetAddress(service.modemId(), hostname(level, service.modemId())),
                service.port(), service.protocol()));
    }

    public static boolean unregisterService(Level level, UUID modemId, String serviceName) {
        if (level == null || level.isClientSide || modemId == null) return false;
        RednetServiceDirectory directory = SERVICES.get(scope(level));
        return directory != null && directory.unregister(modemId, serviceName);
    }

    public static void unregisterServices(Level level, UUID modemId) {
        if (level == null || level.isClientSide || modemId == null) return;
        RednetServiceDirectory directory = SERVICES.get(scope(level));
        if (directory != null) directory.unregisterAll(modemId);
    }

    /** Lists typed online services whose registered port is reachable from the caller. */
    public static List<RednetServiceEndpoint> reachableServiceEndpoints(
            Level level, UUID senderId, BlockPos senderPos, boolean wireless, int range, int maximum) {
        if (level == null || level.isClientSide || senderId == null || senderPos == null) return List.of();
        RednetServiceDirectory directory = SERVICES.get(scope(level));
        List<Subscription> subscriptions = SUBS.get(scope(level));
        if (directory == null || subscriptions == null) return List.of();
        int limit = Math.max(0, Math.min(maximum, 128));
        List<RednetServiceEndpoint> result = new ArrayList<>();
        synchronized (subscriptions) {
            for (RednetServiceDirectory.Service service : directory.services(RednetServiceDirectory.MAX_SERVICES)) {
                if (service.modemId().equals(senderId)) continue;
                boolean reachable = subscriptions.stream().anyMatch(sub ->
                        sub.modemId.equals(service.modemId()) && sub.channel == service.port()
                                && canReach(level, senderPos, sub.pos, wireless, sub.wireless,
                                Math.min(range, sub.range)));
                if (reachable) result.add(new RednetServiceEndpoint(service.name(),
                        new RednetAddress(service.modemId(), hostname(level, service.modemId())),
                        service.port(), service.protocol()));
                if (result.size() >= limit) break;
            }
        }
        return List.copyOf(result);
    }

    /** Compatibility view containing only service name and port. */
    public static List<String> reachableServices(Level level, UUID senderId, BlockPos senderPos,
                                                  boolean wireless, int range, int maximum) {
        return reachableServiceEndpoints(level, senderId, senderPos, wireless, range, maximum).stream()
                .map(service -> service.name() + " " + service.port()).toList();
    }

    /** Lists only online named hosts reachable through the caller's current transport. */
    public static List<String> reachableHosts(Level level, UUID senderId, BlockPos senderPos,
                                              boolean wireless, int range, int maximum) {
        if (level == null || level.isClientSide || senderId == null || senderPos == null) return List.of();
        RednetHostDirectory directory = HOSTS.get(scope(level));
        List<Subscription> subscriptions = SUBS.get(scope(level));
        if (directory == null || subscriptions == null) return List.of();
        int limit = Math.max(0, Math.min(maximum, 128));
        List<String> result = new ArrayList<>();
        synchronized (subscriptions) {
            for (String name : directory.names(RednetHostDirectory.MAX_HOSTS)) {
                UUID target = directory.resolve(name).orElse(null);
                if (target == null || target.equals(senderId)) continue;
                boolean reachable = subscriptions.stream().anyMatch(sub -> sub.modemId.equals(target)
                        && canReach(level, senderPos, sub.pos, wireless, sub.wireless,
                        Math.min(range, sub.range)));
                if (reachable) result.add(name);
                if (result.size() >= limit) break;
            }
        }
        return List.copyOf(result);
    }

    public static final class PendingMessage {
        public final NetworkEnvelope envelope;
        public final int channel;
        public final int replyChannel;
        public final String message;
        public final String senderId;
        public final BlockPos senderPos;
        public final long gameTime;

        public PendingMessage(NetworkEnvelope envelope, BlockPos senderPos) {
            this.envelope = envelope;
            this.channel = envelope.port();
            this.replyChannel = envelope.replyPort();
            this.message = envelope.payload();
            this.senderId = envelope.source();
            this.senderPos = senderPos.immutable();
            this.gameTime = envelope.gameTime();
        }

        public String format() {
            return "ch=" + channel
                    + " reply=" + replyChannel
                    + " hops=" + (NetworkEnvelope.MAX_HOPS - envelope.hopLimit())
                    + " from=" + senderId
                    + " pos=" + senderPos.getX() + "," + senderPos.getY() + "," + senderPos.getZ()
                    + " msg=" + message;
        }
    }

    /** Returns one immutable interface snapshot per live modem attachment. */
    public static List<RednetInterface> interfaces(Level level, UUID modemId) {
        if (level == null || level.isClientSide || modemId == null) return List.of();
        List<Subscription> subscriptions = SUBS.get(scope(level));
        if (subscriptions == null) return List.of();
        Map<InterfaceKey, List<Integer>> ports = new java.util.LinkedHashMap<>();
        synchronized (subscriptions) {
            for (Subscription subscription : subscriptions) {
                if (!subscription.modemId.equals(modemId)) continue;
                InterfaceKey key = new InterfaceKey(subscription.pos, subscription.wireless,
                        subscription.wireless ? Math.max(0, subscription.range) : 0);
                ports.computeIfAbsent(key, ignored -> new ArrayList<>()).add(subscription.channel);
            }
        }
        RednetAddress address = new RednetAddress(modemId, hostname(level, modemId));
        String dimension = ((ServerLevel) level).dimension().location().toString();
        return ports.entrySet().stream().map(entry -> new RednetInterface(address,
                        entry.getKey().wireless ? RednetInterface.Transport.WIRELESS
                                : RednetInterface.Transport.WIRED,
                        dimension, entry.getKey().pos, entry.getKey().range, entry.getValue()))
                .sorted(java.util.Comparator.comparing((RednetInterface value) -> value.position().getX())
                        .thenComparing(value -> value.position().getY())
                        .thenComparing(value -> value.position().getZ())
                        .thenComparing(value -> value.transport().ordinal()))
                .toList();
    }

    /** Lists bounded, deterministic live routes to named neighboring modems. */
    public static List<RednetRoute> neighbors(Level level, UUID sourceId, int maximum) {
        if (level == null || level.isClientSide || sourceId == null) return List.of();
        RednetHostDirectory directory = HOSTS.get(scope(level));
        if (directory == null) return List.of();
        int limit = Math.max(0, Math.min(maximum, 128));
        if (limit == 0) return List.of();
        List<RednetRoute> result = new ArrayList<>();
        for (String name : directory.names(RednetHostDirectory.MAX_HOSTS)) {
            UUID destinationId = directory.resolve(name).orElse(null);
            if (destinationId == null || destinationId.equals(sourceId)) continue;
            route(level, sourceId, destinationId).ifPresent(result::add);
            if (result.size() >= limit) break;
        }
        return List.copyOf(result);
    }

    /** Resolves a named destination and returns its first deterministic live route. */
    public static java.util.Optional<RednetRoute> route(Level level, UUID sourceId, String destination) {
        if (level == null || level.isClientSide || sourceId == null || destination == null) {
            return java.util.Optional.empty();
        }
        RednetHostDirectory directory = HOSTS.get(scope(level));
        UUID destinationId = directory == null ? null : directory.resolve(destination).orElse(null);
        return destinationId == null ? java.util.Optional.empty() : route(level, sourceId, destinationId);
    }

    /** Resolves the first deterministic live route between two modem identities. */
    public static java.util.Optional<RednetRoute> route(Level level, UUID sourceId, UUID destinationId) {
        if (level == null || level.isClientSide || sourceId == null || destinationId == null
                || sourceId.equals(destinationId)) return java.util.Optional.empty();
        List<RednetInterface> sources = interfaces(level, sourceId);
        List<RednetInterface> destinations = interfaces(level, destinationId);
        for (RednetInterface source : sources) {
            for (RednetInterface destination : destinations) {
                if (source.transport() != destination.transport()) continue;
                if (source.transport() == RednetInterface.Transport.WIRELESS) {
                    int range = Math.min(source.range(), destination.range());
                    if (source.position().closerThan(destination.position(), Math.max(1, range) + 0.5)) {
                        return java.util.Optional.of(new RednetRoute(source, destination, 0, List.of()));
                    }
                } else if (level instanceof ServerLevel serverLevel) {
                    WiredNetworkTopology.Route physical = WiredNetworkTopology.route(serverLevel,
                            source.position(), destination.position());
                    if (physical.reachable() && !physical.truncated()) {
                        return java.util.Optional.of(new RednetRoute(source, destination,
                                physical.routerHops(), physical.routerPasses()));
                    }
                }
            }
        }
        return java.util.Optional.empty();
    }

    private record InterfaceKey(BlockPos pos, boolean wireless, int range) {
        private InterfaceKey { pos = pos.immutable(); }
    }

    private static final class Subscription {
        final UUID modemId;
        final int channel;
        final BlockPos pos;
        final boolean wireless;
        final int range;

        Subscription(UUID modemId, int channel, BlockPos pos, boolean wireless, int range) {
            this.modemId = modemId;
            this.channel = channel;
            this.pos = pos.immutable();
            this.wireless = wireless;
            this.range = range;
        }
    }

    public static void open(Level level, UUID modemId, int channel, BlockPos pos, boolean wireless, int range) {
        if (level == null || level.isClientSide || modemId == null) {
            return;
        }
        final int chOpen = clampChannel(channel);
        RednetRuntimeScope key = scope(level);
        SUBS.computeIfAbsent(key, k -> new ArrayList<>());
        List<Subscription> list = SUBS.get(key);
        synchronized (list) {
            list.removeIf(s -> s.modemId.equals(modemId) && s.channel == chOpen);
            list.add(new Subscription(modemId, chOpen, pos, wireless, range));
        }
        QUEUES.computeIfAbsent(key.endpoint(modemId), id -> new ArrayList<>());
    }

    public static void close(Level level, UUID modemId, int channel) {
        if (level == null || level.isClientSide || modemId == null) {
            return;
        }
        final int chClose = clampChannel(channel);
        List<Subscription> list = SUBS.get(scope(level));
        if (list == null) {
            return;
        }
        synchronized (list) {
            list.removeIf(s -> s.modemId.equals(modemId) && s.channel == chClose);
        }
    }

    public static void closeAll(Level level, UUID modemId) {
        if (level == null || level.isClientSide || modemId == null) {
            return;
        }
        List<Subscription> list = SUBS.get(scope(level));
        if (list != null) {
            synchronized (list) {
                list.removeIf(s -> s.modemId.equals(modemId));
            }
        }
        RednetRuntimeScope runtimeScope = scope(level);
        removeApplicationQueue(runtimeScope.endpoint(modemId));
        removeControlQueue(runtimeScope.endpoint(modemId));
    }

    /** Replaces a mobile modem's subscriptions in its current dimension and position. */
    public static void rebind(Level level, UUID modemId, BlockPos pos, List<Integer> channels,
                              boolean wireless, int range) {
        if (level == null || level.isClientSide || modemId == null) return;
        RednetRuntimeScope currentScope = scope(level);
        Object server = currentScope.serverIdentity();
        for (Map.Entry<RednetRuntimeScope, List<Subscription>> entry : SUBS.entrySet()) {
            if (!entry.getKey().belongsTo(server)) continue;
            List<Subscription> list = entry.getValue();
            synchronized (list) {
                list.removeIf(subscription -> subscription.modemId.equals(modemId));
            }
        }
        HOSTS.forEach((scope, directory) -> {
            if (scope.belongsTo(server) && !scope.equals(currentScope)) directory.unregister(modemId);
        });
        SERVICES.forEach((scope, directory) -> {
            if (scope.belongsTo(server) && !scope.equals(currentScope)) directory.unregisterAll(modemId);
        });
        QUEUES.keySet().stream().filter(endpoint -> endpoint.modemId().equals(modemId)
                && endpoint.scope().belongsTo(server) && !endpoint.scope().equals(currentScope))
                .toList().forEach(RednetNetwork::removeApplicationQueue);
        ACK_CONTROLS.keySet().stream().filter(endpoint -> endpoint.modemId().equals(modemId)
                && endpoint.scope().belongsTo(server) && !endpoint.scope().equals(currentScope))
                .toList().forEach(RednetNetwork::removeControlQueue);
        for (int channel : channels) {
            open(level, modemId, channel, pos, wireless, range);
        }
        if (channels.isEmpty()) QUEUES.computeIfAbsent(currentScope.endpoint(modemId), ignored -> new ArrayList<>());
    }

    public static void updatePosition(Level level, UUID modemId, BlockPos pos) {
        if (level == null || level.isClientSide || modemId == null) {
            return;
        }
        List<Subscription> list = SUBS.get(scope(level));
        if (list == null) {
            return;
        }
        synchronized (list) {
            for (int i = 0; i < list.size(); i++) {
                Subscription s = list.get(i);
                if (s.modemId.equals(modemId)) {
                    list.set(i, new Subscription(s.modemId, s.channel, pos, s.wireless, s.range));
                }
            }
        }
    }

    public static void transmit(Level level, UUID senderId, BlockPos senderPos,
                                int channel, int replyChannel, String message,
                                boolean wireless, int range) {
        if (level == null || level.isClientSide || senderId == null) {
            return;
        }
        if (message != null && message.length() > MAX_MESSAGE_LENGTH) return;
        String admittedMessage = message == null ? "" : message;
        if (!admitTraffic(level, senderId, admittedMessage)) return;
        channel = clampChannel(channel);
        replyChannel = clampChannel(replyChannel);
        List<Subscription> list = SUBS.get(scope(level));
        if (list == null) {
            return;
        }
        long time = level.getGameTime();
        NetworkEnvelope envelope;
        try {
            String source = hostname(level, senderId);
            envelope = NetworkEnvelope.channel(UUID.randomUUID(), source.isEmpty() ? senderId.toString() : source, channel,
                    replyChannel, message == null ? "" : message, time);
        } catch (IllegalArgumentException invalid) {
            return;
        }
        synchronized (list) {
            for (Subscription sub : list) {
                if (sub.channel != channel) {
                    continue;
                }
                if (sub.modemId.equals(senderId)) {
                    continue; // do not echo to self
                }
                Reachability reachability = reachability(level, senderPos, sub.pos, wireless, sub.wireless,
                        Math.min(range, sub.range));
                if (!reachability.reachable()) continue;
                PendingMessage recipientMessage = new PendingMessage(
                        envelope.forwarded(reachability.routerHops()), senderPos);
                RednetRuntimeScope.Endpoint endpoint = scope(level).endpoint(sub.modemId);
                if (enqueueApplication(endpoint, recipientMessage)) {
                        if (level instanceof ServerLevel serverLevel) {
                            ServerDeviceManager.publishEvent(serverLevel.getServer(), sub.modemId,
                                    "message_received", time,
                                    (DeviceValue.MapValue) DeviceValue.map(Map.of(
                                            "channel", DeviceValue.of(channel),
                                            "reply_channel", DeviceValue.of(replyChannel),
                                            "message", DeviceValue.of(recipientMessage.message),
                                            "sender_id", DeviceValue.of(recipientMessage.senderId),
                                            "sender_x", DeviceValue.of(senderPos.getX()),
                                            "sender_y", DeviceValue.of(senderPos.getY()),
                                            "sender_z", DeviceValue.of(senderPos.getZ()),
                                            "router_hops", DeviceValue.of(reachability.routerHops()))));
                        }
                }
            }
        }
    }

    /** Sends one envelope to a named, reachable host listening on the destination port. */
    public static boolean transmitTo(Level level, UUID senderId, BlockPos senderPos, String destination,
                                     int port, int replyPort, String message,
                                     boolean wireless, int range) {
        if (level == null || level.isClientSide) return false;
        if (senderId == null || senderPos == null || destination == null
                || message == null || message.length() > MAX_MESSAGE_LENGTH) {
            reject(scope(level), RejectionKind.MALFORMED);
            return false;
        }
        RednetHostDirectory directory = HOSTS.get(scope(level));
        UUID target = directory == null ? null : directory.resolve(destination).orElse(null);
        if (target == null || target.equals(senderId)) return false;
        return transmitToTarget(level, senderId, senderPos, target,
                RednetHostName.normalize(destination).orElse(""), port, replyPort, message,
                wireless, range, "terminalcraft:rednet-host", NetworkEnvelope.TEXT_PAYLOAD);
    }

    private static boolean transmitToTarget(Level level, UUID senderId, BlockPos senderPos, UUID target,
                                            String destination, int port, int replyPort, String message,
                                            boolean wireless, int range, String protocol, String payloadType) {
        if (level == null || level.isClientSide || senderId == null || senderPos == null || target == null
                || target.equals(senderId) || message == null || message.length() > MAX_MESSAGE_LENGTH) return false;
        if (!admitTraffic(level, senderId, message)) return false;
        int destinationPort = clampChannel(port);
        int responsePort = clampChannel(replyPort);
        List<Subscription> list = SUBS.get(scope(level));
        if (list == null) return false;
        synchronized (list) {
            for (Subscription sub : list) {
                if (!sub.modemId.equals(target) || sub.channel != destinationPort) continue;
                RednetRoute typedRoute = directedRoute(level, senderId, senderPos, wireless, range,
                        target, sub).orElse(null);
                if (typedRoute == null) continue;
                Reachability reachability = new Reachability(true, typedRoute.routerHops());
                String source = hostname(level, senderId);
                NetworkEnvelope envelope;
                try {
                    envelope = new NetworkEnvelope(NetworkEnvelope.CURRENT_VERSION, UUID.randomUUID(),
                            source.isEmpty() ? senderId.toString() : source, destination,
                            destinationPort, responsePort, protocol, payloadType, message, level.getGameTime(),
                            NetworkEnvelope.MAX_HOPS, "", null);
                } catch (IllegalArgumentException invalid) {
                    return false;
                }
                PendingMessage pending = new PendingMessage(
                        envelope.forwarded(reachability.routerHops()), senderPos);
                RednetRuntimeScope runtimeScope = scope(level);
                RednetDuplicateFilter duplicates = DIRECTED_DUPLICATES.computeIfAbsent(
                        runtimeScope, ignored -> new RednetDuplicateFilter());
                RednetRuntimeScope.Endpoint endpoint = runtimeScope.endpoint(target);
                boolean[] duplicate = {false};
                if (!enqueueApplication(endpoint, pending, () -> {
                    duplicate[0] = !duplicates.admit(target, envelope.messageId(), level.getGameTime(),
                            DIRECTED_DUPLICATE_RETENTION_TICKS);
                    return !duplicate[0];
                })) return duplicate[0];
                if (level instanceof ServerLevel serverLevel) {
                    ServerDeviceManager.publishEvent(serverLevel.getServer(), target,
                            "message_received", level.getGameTime(),
                            (DeviceValue.MapValue) DeviceValue.map(Map.of(
                                    "channel", DeviceValue.of(destinationPort),
                                    "reply_channel", DeviceValue.of(responsePort),
                                    "message", DeviceValue.of(message),
                                    "sender_id", DeviceValue.of(pending.senderId),
                                    "sender_x", DeviceValue.of(senderPos.getX()),
                                    "sender_y", DeviceValue.of(senderPos.getY()),
                                    "sender_z", DeviceValue.of(senderPos.getZ()),
                                    "router_hops", DeviceValue.of(reachability.routerHops()))));
                }
                return true;
            }
        }
        return false;
    }

    /** Starts an acknowledged directed delivery without changing legacy best-effort send behavior. */
    public static RednetDeliveryRuntime.Delivery transmitReliableTo(
            Level level, UUID senderId, BlockPos senderPos, String destination,
            int port, int replyPort, String message, boolean wireless, int range,
            long timeoutTicks, int maxRetries) {
        if (level == null || level.isClientSide || senderId == null || senderPos == null
                || message == null || message.length() > MAX_MESSAGE_LENGTH) return null;
        RednetRuntimeScope runtimeScope = scope(level);
        RednetHostDirectory directory = HOSTS.get(runtimeScope);
        UUID target = directory == null ? null : directory.resolve(destination).orElse(null);
        String canonical = RednetHostName.normalize(destination).orElse("");
        if (target == null || target.equals(senderId) || canonical.isEmpty()) return null;
        if (!admitTraffic(level, senderId, message)) return null;
        String source = hostname(level, senderId);
        NetworkEnvelope envelope;
        try {
            envelope = new NetworkEnvelope(NetworkEnvelope.CURRENT_VERSION, UUID.randomUUID(),
                    source.isEmpty() ? senderId.toString() : source, canonical,
                    clampChannel(port), clampChannel(replyPort), "terminalcraft:rednet-reliable",
                    message, level.getGameTime(), NetworkEnvelope.MAX_HOPS, null);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        ReliableRoute route = new ReliableRoute(senderId, senderPos.immutable(), target,
                wireless, range);
        RELIABLE_ROUTES.computeIfAbsent(runtimeScope, ignored -> new ConcurrentHashMap<>())
                .put(envelope.messageId(), route);
        try {
            return DELIVERIES.computeIfAbsent(runtimeScope, ignored -> new RednetDeliveryRuntime())
                    .submit(envelope, level.getGameTime(), timeoutTicks, maxRetries,
                            attempt -> enqueueReliableAttempt(level, route, attempt));
        } catch (IllegalArgumentException | IllegalStateException failure) {
            RELIABLE_ROUTES.getOrDefault(runtimeScope, Map.of()).remove(envelope.messageId());
            return null;
        }
    }

    /** Returns sender-side state for a reliable directed message. */
    public static java.util.Optional<RednetDeliveryRuntime.Delivery> delivery(Level level, UUID messageId) {
        if (level == null || level.isClientSide || messageId == null) return java.util.Optional.empty();
        RednetDeliveryRuntime runtime = DELIVERIES.get(scope(level));
        return runtime == null ? java.util.Optional.empty() : runtime.delivery(messageId);
    }

    /** Returns delivery state only when the requesting modem originally submitted it. */
    public static java.util.Optional<RednetDeliveryRuntime.Delivery> delivery(
            Level level, UUID senderId, UUID messageId) {
        if (level == null || level.isClientSide || senderId == null || messageId == null) {
            return java.util.Optional.empty();
        }
        RednetRuntimeScope runtimeScope = scope(level);
        ReliableRoute route = RELIABLE_ROUTES.getOrDefault(runtimeScope, Map.of()).get(messageId);
        return route == null || !route.senderId().equals(senderId)
                ? java.util.Optional.empty() : delivery(level, messageId);
    }

    /**
     * Application acknowledgement routed back through the recipient's current transport.
     * The caller UUID is authenticated against retained route state and live subscriptions.
     */
    public static boolean acknowledge(Level level, UUID recipientId, PendingMessage received) {
        if (level == null || level.isClientSide || recipientId == null || received == null) return false;
        NetworkEnvelope request = received.envelope;
        if (!"terminalcraft:rednet-reliable".equals(request.protocol())) return false;
        RednetRuntimeScope runtimeScope = scope(level);
        RednetDeliveryRuntime runtime = DELIVERIES.get(runtimeScope);
        ReliableRoute route = RELIABLE_ROUTES.getOrDefault(runtimeScope, Map.of()).get(request.messageId());
        if (runtime == null || route == null || !route.target().equals(recipientId)) return false;
        RednetDeliveryRuntime.Delivery delivery = runtime.delivery(request.messageId()).orElse(null);
        NetworkEnvelope original = delivery == null ? null : delivery.envelope();
        if (original == null || !original.source().equals(request.source())
                || !original.destination().equals(request.destination())
                || original.port() != request.port() || original.replyPort() != request.replyPort()
                || !original.protocol().equals(request.protocol())
                || !original.payloadType().equals(request.payloadType())
                || !original.payload().equals(request.payload())) return false;

        List<Subscription> subscriptions = SUBS.get(runtimeScope);
        if (subscriptions == null) return false;
        NetworkEnvelope ack;
        synchronized (subscriptions) {
            Subscription recipient = subscriptions.stream().filter(sub -> sub.modemId.equals(recipientId)
                    && sub.channel == request.port()).findFirst().orElse(null);
            Subscription sender = subscriptions.stream().filter(sub -> sub.modemId.equals(route.senderId())
                    && sub.channel == request.replyPort()).findFirst().orElse(null);
            if (recipient == null || sender == null) return false;
            RednetRoute reverseRoute = directedRoute(level, recipientId, recipient.pos,
                    recipient.wireless, recipient.range, route.senderId(), sender).orElse(null);
            if (reverseRoute == null) return false;
            Reachability reverse = new Reachability(true, reverseRoute.routerHops());
            try {
                ack = new NetworkEnvelope(NetworkEnvelope.CURRENT_VERSION, UUID.randomUUID(),
                        request.destination(), request.source(), request.replyPort(), request.port(),
                        RednetDeliveryRuntime.ACK_PROTOCOL, NetworkEnvelope.TEXT_PAYLOAD,
                        RednetDeliveryRuntime.ACK_PAYLOAD, level.getGameTime(),
                        NetworkEnvelope.MAX_HOPS, "", request.messageId())
                        .forwarded(reverse.routerHops());
            } catch (IllegalArgumentException invalid) {
                return false;
            }
        }
        return enqueueControl(runtimeScope.endpoint(route.senderId()), ack);
    }

    /** Drives reliable retry deadlines at most once per logical dimension tick. */
    public static int tickDeliveries(Level level) {
        if (level == null || level.isClientSide) return 0;
        RednetRuntimeScope runtimeScope = scope(level);
        long now = level.getGameTime();
        Long previous = LAST_DELIVERY_TICK.put(runtimeScope, now);
        if (previous != null && previous == now) return 0;
        RednetDeliveryRuntime runtime = DELIVERIES.get(runtimeScope);
        Map<UUID, ReliableRoute> routes = RELIABLE_ROUTES.get(runtimeScope);
        if (runtime == null || routes == null) return 0;
        drainAcknowledgements(runtimeScope, runtime, routes, now);
        int attempted = runtime.tick(now, envelope -> {
            ReliableRoute route = routes.get(envelope.messageId());
            return route != null && enqueueReliableAttempt(level, route, envelope);
        });
        // Retain route ownership for terminal deliveries while the bounded runtime retains status.
        // This keeps sender-authorized diagnostics available without an unbounded second ledger.
        java.util.Set<UUID> retained = runtime.deliveries(RednetDeliveryRuntime.MAX_RETAINED).stream()
                .map(RednetDeliveryRuntime.Delivery::messageId)
                .collect(java.util.stream.Collectors.toSet());
        routes.keySet().removeIf(id -> !retained.contains(id));
        return attempted;
    }

    /** Consumes bounded transport controls without exposing them through application receive APIs. */
    private static void drainAcknowledgements(RednetRuntimeScope runtimeScope,
                                               RednetDeliveryRuntime runtime,
                                               Map<UUID, ReliableRoute> routes,
                                               long now) {
        int remaining = RednetDeliveryRuntime.MAX_TICK_ATTEMPTS;
        for (RednetRuntimeScope.Endpoint endpoint : List.copyOf(ACK_CONTROLS.keySet())) {
            if (remaining == 0) break;
            if (!endpoint.scope().equals(runtimeScope)) continue;
            List<NetworkEnvelope> drained = new ArrayList<>();
            int allowance = remaining;
            ACK_CONTROLS.computeIfPresent(endpoint, (ignored, controls) -> {
                Iterator<NetworkEnvelope> iterator = controls.iterator();
                while (iterator.hasNext() && drained.size() < allowance) {
                    NetworkEnvelope acknowledgement = iterator.next();
                    drained.add(acknowledgement);
                    iterator.remove();
                    releaseControlQueue(endpoint, acknowledgement);
                }
                return controls.isEmpty() ? null : controls;
            });
            remaining -= drained.size();
            for (NetworkEnvelope acknowledgement : drained) {
                // Retain sender ownership metadata while the bounded delivery record remains
                // available so terminal acknowledgement state can be queried safely.
                runtime.acknowledge(acknowledgement, now);
            }
        }
    }

    private static boolean enqueueReliableAttempt(Level level, ReliableRoute route, NetworkEnvelope envelope) {
        List<Subscription> list = SUBS.get(scope(level));
        if (list == null) return false;
        synchronized (list) {
            for (Subscription sub : list) {
                if (!sub.modemId.equals(route.target()) || sub.channel != envelope.port()) continue;
                RednetRoute typedRoute = directedRoute(level, route.senderId(), route.senderPos(),
                        route.wireless(), route.range(), route.target(), sub).orElse(null);
                if (typedRoute == null) continue;
                Reachability reachability = new Reachability(true, typedRoute.routerHops());
                RednetRuntimeScope runtimeScope = scope(level);
                RednetDuplicateFilter duplicates = DIRECTED_DUPLICATES.computeIfAbsent(
                        runtimeScope, ignored -> new RednetDuplicateFilter());
                PendingMessage pending = new PendingMessage(
                        envelope.forwarded(reachability.routerHops()), route.senderPos());
                RednetRuntimeScope.Endpoint endpoint = runtimeScope.endpoint(route.target());
                boolean[] duplicate = {false};
                boolean enqueued = enqueueApplication(endpoint, pending, () -> {
                    duplicate[0] = !duplicates.admit(route.target(), envelope.messageId(), level.getGameTime(),
                            DIRECTED_DUPLICATE_RETENTION_TICKS);
                    return !duplicate[0];
                });
                return enqueued || duplicate[0];
            }
        }
        return false;
    }

    private record ReliableRoute(UUID senderId, BlockPos senderPos, UUID target,
                                 boolean wireless, int range) {}

    /** Resolves a named service and sends using its registered protocol metadata. */
    public static boolean transmitService(Level level, UUID senderId, BlockPos senderPos,
                                          String serviceName, int replyPort, String message,
                                          boolean wireless, int range) {
        return transmitService(level, senderId, senderPos, serviceName, replyPort, message,
                wireless, range, null);
    }

    /**
     * Sends only when the live service contract exactly matches the caller's expected contract.
     * A null expectation retains the legacy discovery-and-send behavior.
     */
    public static boolean transmitService(Level level, UUID senderId, BlockPos senderPos,
                                          String serviceName, int replyPort, String message,
                                          boolean wireless, int range, RednetProtocol expectedProtocol) {
        if (level == null || level.isClientSide) return false;
        RednetServiceDirectory directory = SERVICES.get(scope(level));
        RednetServiceDirectory.Service service = directory == null
                ? null : directory.resolve(serviceName).orElse(null);
        if (service == null || service.modemId().equals(senderId)
                || expectedProtocol != null && !expectedProtocol.equals(service.protocol())) return false;
        return transmitToTarget(level, senderId, senderPos, service.modemId(), service.name(),
                service.port(), replyPort, message, wireless, range,
                service.protocol().id(), service.protocol().payloadType());
    }

    public static List<PendingMessage> receive(Level level, UUID modemId, int max) {
        if (level == null || level.isClientSide || modemId == null) return List.of();
        RednetRuntimeScope.Endpoint endpoint = scope(level).endpoint(modemId);
        int n = Math.max(1, Math.min(max, 32));
        List<PendingMessage> out = new ArrayList<>();
        QUEUES.computeIfPresent(endpoint, (ignored, queue) -> {
            Iterator<PendingMessage> iterator = queue.iterator();
            while (iterator.hasNext() && out.size() < n) {
                PendingMessage pending = iterator.next();
                out.add(pending);
                iterator.remove();
                releaseApplicationQueue(endpoint, pending);
            }
            return queue;
        });
        return List.copyOf(out);
    }

    public static int pendingCount(Level level, UUID modemId) {
        if (level == null || level.isClientSide || modemId == null) return 0;
        RednetRuntimeScope.Endpoint endpoint = scope(level).endpoint(modemId);
        int[] count = {0};
        QUEUES.computeIfPresent(endpoint, (ignored, queue) -> {
            count[0] = queue.size();
            return queue;
        });
        return count[0];
    }

    /** Clears only the stopped logical server's ephemeral RedNet state. */
    public static void clear(MinecraftServer server) {
        if (server == null) return;
        SUBS.keySet().removeIf(scope -> scope.belongsTo(server));
        HOSTS.keySet().removeIf(scope -> scope.belongsTo(server));
        SERVICES.keySet().removeIf(scope -> scope.belongsTo(server));
        DIRECTED_DUPLICATES.keySet().removeIf(scope -> scope.belongsTo(server));
        DELIVERIES.keySet().removeIf(scope -> scope.belongsTo(server));
        RELIABLE_ROUTES.keySet().removeIf(scope -> scope.belongsTo(server));
        LAST_DELIVERY_TICK.keySet().removeIf(scope -> scope.belongsTo(server));
        TRAFFIC_QUOTAS.keySet().removeIf(scope -> scope.belongsTo(server));
        REJECTIONS.keySet().removeIf(scope -> scope.belongsTo(server));
        List.copyOf(ACK_CONTROLS.keySet()).stream()
                .filter(endpoint -> endpoint.scope().belongsTo(server))
                .forEach(RednetNetwork::removeControlQueue);
        List.copyOf(QUEUES.keySet()).stream()
                .filter(endpoint -> endpoint.scope().belongsTo(server))
                .forEach(RednetNetwork::removeApplicationQueue);
        // Queue ownership operations release their reservations before aggregate budgets disappear.
        QUEUE_BUDGETS.keySet().removeIf(scope -> scope.belongsTo(server));
    }

    /** Resolves one exact directed subscription through the typed interface and route boundary. */
    private static java.util.Optional<RednetRoute> directedRoute(
            Level level, UUID sourceId, BlockPos sourcePos, boolean sourceWireless, int sourceRange,
            UUID destinationId, Subscription destination) {
        if (!(level instanceof ServerLevel serverLevel) || sourceId == null || destinationId == null
                || sourceId.equals(destinationId) || sourcePos == null || destination == null
                || !destination.modemId.equals(destinationId)) return java.util.Optional.empty();
        String dimension = serverLevel.dimension().location().toString();
        RednetInterface.Transport sourceTransport = sourceWireless
                ? RednetInterface.Transport.WIRELESS : RednetInterface.Transport.WIRED;
        RednetInterface.Transport destinationTransport = destination.wireless
                ? RednetInterface.Transport.WIRELESS : RednetInterface.Transport.WIRED;
        if (sourceTransport != destinationTransport) return java.util.Optional.empty();
        RednetInterface source = new RednetInterface(
                new RednetAddress(sourceId, hostname(level, sourceId)), sourceTransport, dimension,
                sourcePos, sourceWireless ? Math.max(0, sourceRange) : 0, List.of());
        RednetInterface target = new RednetInterface(
                new RednetAddress(destinationId, hostname(level, destinationId)), destinationTransport,
                dimension, destination.pos, destination.wireless ? Math.max(0, destination.range) : 0,
                List.of(destination.channel));
        if (sourceWireless) {
            int effectiveRange = Math.min(source.range(), target.range());
            return source.position().closerThan(target.position(), Math.max(1, effectiveRange) + 0.5)
                    ? java.util.Optional.of(new RednetRoute(source, target, 0, List.of()))
                    : java.util.Optional.empty();
        }
        WiredNetworkTopology.Route physical = WiredNetworkTopology.route(
                serverLevel, source.position(), target.position());
        return physical.reachable() && !physical.truncated()
                ? java.util.Optional.of(new RednetRoute(source, target, physical.routerHops(),
                        physical.routerPasses()))
                : java.util.Optional.empty();
    }

    private record Reachability(boolean reachable, int routerHops) {
        private static final Reachability UNREACHABLE = new Reachability(false, 0);
    }

    private static boolean canReach(Level level, BlockPos a, BlockPos b,
                                    boolean senderWireless, boolean receiverWireless, int range) {
        return reachability(level, a, b, senderWireless, receiverWireless, range).reachable();
    }

    /** Resolves transport reachability and the exact router cost applied to a recipient envelope. */
    private static Reachability reachability(Level level, BlockPos a, BlockPos b,
                                             boolean senderWireless, boolean receiverWireless, int range) {
        if (senderWireless && receiverWireless) {
            int r = Math.max(1, range);
            return a.closerThan(b, r + 0.5) ? new Reachability(true, 0) : Reachability.UNREACHABLE;
        }
        if (senderWireless != receiverWireless || !(level instanceof ServerLevel serverLevel)) {
            return Reachability.UNREACHABLE;
        }
        WiredNetworkTopology.Route route = WiredNetworkTopology.route(serverLevel, a, b);
        return route.reachable() && !route.truncated()
                ? new Reachability(true, route.routerHops()) : Reachability.UNREACHABLE;
    }

    private static boolean admitTraffic(Level level, UUID senderId, String payload) {
        RednetRuntimeScope runtimeScope = scope(level);
        boolean admitted = TRAFFIC_QUOTAS.computeIfAbsent(runtimeScope, ignored -> new RednetTrafficQuota())
                .admit(senderId, payload, level.getGameTime());
        if (!admitted) reject(runtimeScope, RejectionKind.RATE_LIMITED);
        return admitted;
    }

    /** Returns non-disclosing, scope-wide counters; diagnostics never retain payloads or identities. */
    public static RejectionDiagnostics rejectionDiagnostics(Level level) {
        if (level == null || level.isClientSide) return new RejectionDiagnostics(0, 0, 0, 0);
        RejectionCounter counter = REJECTIONS.get(scope(level));
        return counter == null ? new RejectionDiagnostics(0, 0, 0, 0) : counter.snapshot();
    }

    private static void reject(RednetRuntimeScope runtimeScope, RejectionKind kind) {
        REJECTIONS.computeIfAbsent(runtimeScope, ignored -> new RejectionCounter()).increment(kind);
    }

    private static long saturatingIncrement(long value) {
        return value == Long.MAX_VALUE ? value : value + 1;
    }

    private enum QueueKind { APPLICATION, CONTROL }

    private record QueueId(QueueKind kind, RednetRuntimeScope.Endpoint endpoint) {}

    private static int envelopeBytes(NetworkEnvelope envelope) {
        // Fixed-width fields, UUIDs, and framing plus every UTF-8 encoded string field.
        int bytes = 64;
        for (String value : List.of(envelope.source(), envelope.destination(), envelope.protocol(),
                envelope.payloadType(), envelope.payload(), envelope.replyTo())) {
            bytes = Math.addExact(bytes, value.getBytes(StandardCharsets.UTF_8).length);
        }
        return bytes;
    }

    private static boolean admitApplicationQueue(RednetRuntimeScope.Endpoint endpoint, PendingMessage pending) {
        return QUEUE_BUDGETS.computeIfAbsent(endpoint.scope(), ignored -> new RednetQueueBudget())
                .admit(new QueueId(QueueKind.APPLICATION, endpoint), envelopeBytes(pending.envelope));
    }

    private static boolean admitControlQueue(RednetRuntimeScope.Endpoint endpoint, NetworkEnvelope envelope) {
        return QUEUE_BUDGETS.computeIfAbsent(endpoint.scope(), ignored -> new RednetQueueBudget())
                .admit(new QueueId(QueueKind.CONTROL, endpoint), envelopeBytes(envelope));
    }

    private static void releaseApplicationQueue(RednetRuntimeScope.Endpoint endpoint, PendingMessage pending) {
        RednetQueueBudget budget = QUEUE_BUDGETS.get(endpoint.scope());
        if (budget != null) budget.release(new QueueId(QueueKind.APPLICATION, endpoint), envelopeBytes(pending.envelope));
    }

    private static void releaseControlQueue(RednetRuntimeScope.Endpoint endpoint, NetworkEnvelope envelope) {
        RednetQueueBudget budget = QUEUE_BUDGETS.get(endpoint.scope());
        if (budget != null) budget.release(new QueueId(QueueKind.CONTROL, endpoint), envelopeBytes(envelope));
    }

    private static boolean enqueueApplication(RednetRuntimeScope.Endpoint endpoint, PendingMessage pending) {
        return enqueueApplication(endpoint, pending, () -> true);
    }

    private static boolean enqueueApplication(RednetRuntimeScope.Endpoint endpoint, PendingMessage pending,
                                              java.util.function.BooleanSupplier commit) {
        boolean[] accepted = {false};
        QUEUES.compute(endpoint, (ignored, queue) -> {
            List<PendingMessage> live = queue == null ? new ArrayList<>() : queue;
            if (!admitApplicationQueue(endpoint, pending)) {
                reject(endpoint.scope(), RejectionKind.APPLICATION_QUEUE_FULL);
                return live;
            }
            if (!commit.getAsBoolean()) {
                releaseApplicationQueue(endpoint, pending);
                return live;
            }
            live.add(pending);
            accepted[0] = true;
            return live;
        });
        return accepted[0];
    }

    private static boolean enqueueControl(RednetRuntimeScope.Endpoint endpoint, NetworkEnvelope envelope) {
        boolean[] accepted = {false};
        ACK_CONTROLS.compute(endpoint, (ignored, queue) -> {
            List<NetworkEnvelope> live = queue == null ? new ArrayList<>() : queue;
            if (!admitControlQueue(endpoint, envelope)) {
                reject(endpoint.scope(), RejectionKind.CONTROL_QUEUE_FULL);
                return live;
            }
            live.add(envelope);
            accepted[0] = true;
            return live;
        });
        return accepted[0];
    }

    private static void removeApplicationQueue(RednetRuntimeScope.Endpoint endpoint) {
        QUEUES.computeIfPresent(endpoint, (ignored, queue) -> {
            for (PendingMessage pending : queue) releaseApplicationQueue(endpoint, pending);
            queue.clear();
            return null;
        });
    }

    private static void removeControlQueue(RednetRuntimeScope.Endpoint endpoint) {
        ACK_CONTROLS.computeIfPresent(endpoint, (ignored, queue) -> {
            for (NetworkEnvelope envelope : queue) releaseControlQueue(endpoint, envelope);
            queue.clear();
            return null;
        });
    }

    private static int clampChannel(int channel) {
        if (channel < 0) {
            return 0;
        }
        if (channel > 65535) {
            return 65535;
        }
        return channel;
    }

    private static RednetRuntimeScope scope(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            throw new IllegalArgumentException("RedNet runtime state requires a server level");
        }
        return new RednetRuntimeScope(serverLevel.getServer(),
                serverLevel.dimension().location().toString());
    }

}
