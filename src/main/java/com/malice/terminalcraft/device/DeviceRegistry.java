package com.malice.terminalcraft.device;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Bounded registry for currently discovered endpoints. Lifecycle owners register and invalidate devices. */
public final class DeviceRegistry implements DeviceAccess {
    public static final int MAX_ENUMERATION_RESULTS = 256;
    public static final int MAX_RETAINED_EVENTS = 256;
    public static final int MAX_EVENT_POLL_RESULTS = 64;
    public static final int MAX_EVENTS_PER_SOURCE_PER_TICK = 32;
    public static final int MAX_EVENTS_PER_TICK = 1024;
    public static final int MAX_EVENT_PAYLOAD_NODES = 128;
    public static final int MAX_EVENT_PAYLOAD_TEXT_LENGTH = 4096;
    private static final DeviceCallContext LEGACY_READ_ONLY = DeviceCallContext.readOnly("legacy-shell");
    private final Map<UUID, DeviceEndpoint> endpoints = new LinkedHashMap<>();
    private final Deque<DeviceEvent> events = new ArrayDeque<>();
    private final DeviceEventRuntime eventRuntime = new DeviceEventRuntime();
    private long nextEventSequence = 1;
    private long legacyEventCursor;
    private long publicationTick = Long.MIN_VALUE;
    private int publicationsThisTick;
    private final Map<UUID, Integer> publicationsBySource = new LinkedHashMap<>();

    public synchronized void register(DeviceEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        UUID id = endpoint.descriptor().deviceId();
        if (endpoints.containsKey(id)) throw new IllegalArgumentException("device already registered: " + id);
        endpoints.put(id, endpoint);
    }

    public synchronized boolean invalidate(UUID deviceId) {
        return endpoints.remove(Objects.requireNonNull(deviceId, "deviceId")) != null;
    }

    /** Publishes an event only when its source is live and advertises the event type. */
    public synchronized DeviceResult publishEvent(UUID deviceId, String type, long gameTime,
                                                  DeviceValue.MapValue payload) {
        DeviceEndpoint endpoint = endpoints.get(Objects.requireNonNull(deviceId, "deviceId"));
        if (endpoint == null) return DeviceResult.failure(DeviceErrorCode.NOT_FOUND, "device not found", false);
        if (type == null || type.isBlank()) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT, "event type is required", false);
        }
        DeviceDescriptor descriptor;
        try {
            descriptor = endpoint.descriptor();
        } catch (RuntimeException exception) {
            return DeviceResult.failure(DeviceErrorCode.ADAPTER_ERROR, "device adapter failed", true);
        }
        if (!descriptor.eventTypes().contains(type)) {
            return DeviceResult.failure(DeviceErrorCode.UNSUPPORTED, "event type is unsupported", false);
        }
        if (gameTime < 0 || payload == null) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT,
                    "event game time and payload must be valid", false);
        }
        try {
            DeviceValue.requireWithinBudget(List.of(payload), MAX_EVENT_PAYLOAD_NODES,
                    MAX_EVENT_PAYLOAD_TEXT_LENGTH, "event payload");
        } catch (IllegalArgumentException exception) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT,
                    "event payload exceeds aggregate limits", false);
        }
        if (publicationTick != gameTime) {
            publicationTick = gameTime;
            publicationsThisTick = 0;
            publicationsBySource.clear();
        }
        int sourcePublications = publicationsBySource.getOrDefault(deviceId, 0);
        if (publicationsThisTick >= MAX_EVENTS_PER_TICK
                || sourcePublications >= MAX_EVENTS_PER_SOURCE_PER_TICK) {
            return DeviceResult.failure(DeviceErrorCode.BUSY,
                    "event publication budget exceeded for this tick", true);
        }
        publicationsThisTick++;
        publicationsBySource.put(deviceId, sourcePublications + 1);
        DeviceEvent event = new DeviceEvent(nextEventSequence++, deviceId, type, gameTime, payload);
        events.addLast(event);
        eventRuntime.publish(event);
        while (events.size() > MAX_RETAINED_EVENTS) events.removeFirst();
        return DeviceResult.success();
    }

    /** Creates a typed-principal-owned bounded event subscription. */
    public synchronized DeviceResult subscribeEvents(DeviceCallContext context,
                                                       DeviceEventSubscription subscription) {
        DeviceResult authorization = DeviceAuthorization.require(
                Objects.requireNonNull(context, "context"), DeviceCallContext.READ);
        if (!authorization.isSuccess()) return authorization;
        try {
            UUID id = eventRuntime.subscribe(context.principal(),
                    Objects.requireNonNull(subscription, "subscription"));
            return DeviceResult.success(DeviceValue.of(id.toString()));
        } catch (IllegalArgumentException exception) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT, exception.getMessage(), false);
        } catch (IllegalStateException exception) {
            return DeviceResult.failure(DeviceErrorCode.CAPACITY_EXCEEDED, exception.getMessage(), false);
        }
    }

    public synchronized DeviceEventBatch pollSubscription(DeviceCallContext context,
                                                            UUID subscriptionId, int limit) {
        if (!DeviceAuthorization.allows(context, DeviceAuthorization.Action.DISCOVER)) {
            return new DeviceEventBatch(List.of(), 0);
        }
        return eventRuntime.poll(context.principal(), subscriptionId, limit);
    }

    public synchronized Optional<DeviceEventDiagnostics> eventDiagnostics(DeviceCallContext context,
                                                                           UUID subscriptionId) {
        if (!DeviceAuthorization.allows(context, DeviceAuthorization.Action.DISCOVER)) {
            return Optional.empty();
        }
        return eventRuntime.diagnostics(context.principal(), subscriptionId);
    }

    public synchronized boolean unsubscribeEvents(DeviceCallContext context, UUID subscriptionId) {
        if (!DeviceAuthorization.allows(context, DeviceAuthorization.Action.DISCOVER)) return false;
        return eventRuntime.unsubscribe(context.principal(), subscriptionId);
    }

    /** Returns a caller-bound view; permission checks remain authoritative in this registry. */
    public synchronized DeviceAccess access(DeviceCallContext context) {
        return new BoundAccess(this, Objects.requireNonNull(context, "context"), nextEventSequence - 1);
    }

    @Override
    public DeviceCallContext context() {
        return LEGACY_READ_ONLY;
    }

    @Override
    public DeviceResult call(UUID deviceId, String method, List<DeviceValue> arguments) {
        return call(LEGACY_READ_ONLY, deviceId, method, arguments);
    }

    @Override
    public synchronized DeviceEventBatch pollEvents(int limit) {
        Poll poll = pollAfter(legacyEventCursor, limit);
        legacyEventCursor = poll.cursor();
        return poll.batch();
    }

    public synchronized DeviceResult call(DeviceCallContext context, UUID deviceId, String method,
                                          List<DeviceValue> arguments) {
        Objects.requireNonNull(context, "context");
        DeviceEndpoint endpoint = endpoints.get(Objects.requireNonNull(deviceId, "deviceId"));
        if (endpoint == null) return DeviceResult.failure(DeviceErrorCode.NOT_FOUND, "device not found", false);
        DeviceDescriptor descriptor;
        try {
            descriptor = Objects.requireNonNull(endpoint.descriptor(), "endpoint descriptor");
        } catch (RuntimeException exception) {
            return DeviceResult.failure(DeviceErrorCode.ADAPTER_ERROR, "device adapter failed", true);
        }
        if (!descriptor.loaded()) return DeviceResult.failure(DeviceErrorCode.CHUNK_UNLOADED, "device chunk is unloaded", true);
        if (!descriptor.online()) return DeviceResult.failure(DeviceErrorCode.OFFLINE, "device is offline", true);
        if (method == null || method.isBlank()) return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT, "method is required", false);

        DeviceMethodDescriptor schema = descriptor.methods().stream()
                .filter(candidate -> candidate.name().equals(method))
                .findFirst()
                .orElse(null);
        if (schema == null) return DeviceResult.failure(DeviceErrorCode.UNSUPPORTED, "method is unsupported", false);
        DeviceResult authorization = DeviceAuthorization.require(context, schema.requiredPermission());
        if (!authorization.isSuccess()) return authorization;

        List<DeviceValue> safeArguments;
        try {
            safeArguments = arguments == null ? List.of() : List.copyOf(arguments);
        } catch (RuntimeException exception) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT, "arguments must not contain null values", false);
        }
        DeviceResult validationFailure = validateArguments(schema, safeArguments);
        if (validationFailure != null) return validationFailure;
        try {
            DeviceValue.requireWithinBudget(safeArguments, DeviceValue.MAX_TOTAL_NODES,
                    DeviceValue.MAX_TOTAL_TEXT_LENGTH, "device arguments");
        } catch (IllegalArgumentException exception) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT,
                    "device arguments exceed aggregate limits", false);
        }
        DeviceResult result;
        try {
            result = Objects.requireNonNull(endpoint instanceof ContextualDeviceEndpoint contextual
                    ? contextual.call(context, method, safeArguments)
                    : endpoint.call(method, safeArguments), "endpoint result");
        } catch (IllegalArgumentException exception) {
            String message = exception.getMessage();
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT,
                    message == null || message.isBlank() ? "invalid device argument" : message, false);
        } catch (RuntimeException exception) {
            return DeviceResult.failure(DeviceErrorCode.ADAPTER_ERROR, "device adapter failed", true);
        }
        if (result.isSuccess()) {
            DeviceValue value = result.value().orElse(DeviceValue.nullValue());
            try {
                DeviceValue.requireWithinBudget(List.of(value), DeviceValue.MAX_TOTAL_NODES,
                        DeviceValue.MAX_TOTAL_TEXT_LENGTH, "device result");
            } catch (RuntimeException exception) {
                return DeviceResult.failure(DeviceErrorCode.ADAPTER_ERROR,
                        "device adapter returned an oversized result", false);
            }
            if (value.type() != schema.returnType()) {
                return DeviceResult.failure(DeviceErrorCode.ADAPTER_ERROR,
                        "device adapter returned " + value.type().name().toLowerCase()
                                + " but advertised " + schema.returnType().name().toLowerCase(), false);
            }
        }
        return result;
    }

    private static DeviceResult validateArguments(DeviceMethodDescriptor schema, List<DeviceValue> arguments) {
        int required = 0;
        for (DeviceParameterDescriptor parameter : schema.parameters()) {
            if (parameter.required()) required++;
        }
        if (arguments.size() < required || arguments.size() > schema.parameters().size()) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT,
                    schema.name() + " expects " + arity(required, schema.parameters().size())
                            + " argument(s), got " + arguments.size(), false);
        }
        for (int index = 0; index < arguments.size(); index++) {
            DeviceValue value = arguments.get(index);
            DeviceParameterDescriptor parameter = schema.parameters().get(index);
            if (value.type() != parameter.type()) {
                return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT,
                        "parameter '" + parameter.name() + "' expects "
                                + parameter.type().name().toLowerCase() + ", got "
                                + value.type().name().toLowerCase(), false);
            }
        }
        return null;
    }

    private static String arity(int required, int maximum) {
        return required == maximum ? Integer.toString(required) : required + ".." + maximum;
    }

    @Override
    public synchronized Optional<DeviceDescriptor> descriptor(UUID deviceId) {
        DeviceEndpoint endpoint = endpoints.get(Objects.requireNonNull(deviceId, "deviceId"));
        if (endpoint == null) return Optional.empty();
        try {
            return Optional.of(Objects.requireNonNull(endpoint.descriptor(), "endpoint descriptor"));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    @Override
    public synchronized List<DeviceDescriptor> descriptors(int limit) {
        int bounded = Math.max(0, Math.min(limit, MAX_ENUMERATION_RESULTS));
        List<DeviceDescriptor> result = new ArrayList<>(Math.min(endpoints.size(), bounded));
        for (DeviceEndpoint endpoint : endpoints.values()) {
            try {
                result.add(Objects.requireNonNull(endpoint.descriptor(), "endpoint descriptor"));
            } catch (RuntimeException ignored) {
                // One broken adapter must not abort bounded device discovery.
            }
        }
        result.sort(Comparator.comparing(d -> d.deviceId().toString()));
        return List.copyOf(result.subList(0, Math.min(bounded, result.size())));
    }

    synchronized boolean ownsAddress(String address) {
        Objects.requireNonNull(address, "address");
        for (DeviceEndpoint endpoint : endpoints.values()) {
            try {
                if (address.equals(endpoint.descriptor().address())) return true;
            } catch (RuntimeException ignored) {
                // Broken descriptors are not authoritative for address ownership.
            }
        }
        return false;
    }

    private synchronized Poll pollAfter(long cursor, int limit) {
        int bounded = Math.max(0, Math.min(limit, MAX_EVENT_POLL_RESULTS));
        if (events.isEmpty() || bounded == 0) return new Poll(new DeviceEventBatch(List.of(), 0), cursor);
        long oldest = events.getFirst().sequence();
        long dropped = Math.max(0, oldest - cursor - 1);
        long effectiveCursor = Math.max(cursor, oldest - 1);
        List<DeviceEvent> result = new ArrayList<>(bounded);
        for (DeviceEvent event : events) {
            if (event.sequence() > effectiveCursor) {
                result.add(event);
                if (result.size() == bounded) break;
            }
        }
        long newCursor = result.isEmpty() ? effectiveCursor : result.get(result.size() - 1).sequence();
        return new Poll(new DeviceEventBatch(result, dropped), newCursor);
    }

    private record Poll(DeviceEventBatch batch, long cursor) {}

    private static final class BoundAccess implements DeviceAccess, AddressAwareDeviceAccess,
            DeviceEventSubscriptionAccess {
        private final DeviceRegistry registry;
        private final DeviceCallContext context;
        private long eventCursor;

        private BoundAccess(DeviceRegistry registry, DeviceCallContext context, long eventCursor) {
            this.registry = Objects.requireNonNull(registry, "registry");
            this.context = Objects.requireNonNull(context, "context");
            this.eventCursor = eventCursor;
        }

        @Override public DeviceCallContext context() { return context; }
        @Override public List<DeviceDescriptor> descriptors(int limit) {
            return DeviceAuthorization.allows(context, DeviceAuthorization.Action.DISCOVER) ? registry.descriptors(limit) : List.of();
        }
        @Override public Optional<DeviceDescriptor> descriptor(UUID id) {
            return DeviceAuthorization.allows(context, DeviceAuthorization.Action.DISCOVER) ? registry.descriptor(id) : Optional.empty();
        }
        @Override public DeviceResult call(UUID id, String method, List<DeviceValue> arguments) {
            return registry.call(context, id, method, arguments);
        }
        @Override public boolean ownsAddress(String address) {
            return DeviceAuthorization.allows(context, DeviceAuthorization.Action.DISCOVER) && registry.ownsAddress(address);
        }
        @Override public synchronized DeviceEventBatch pollEvents(int limit) {
            if (!DeviceAuthorization.allows(context, DeviceAuthorization.Action.DISCOVER)) return new DeviceEventBatch(List.of(), 0);
            Poll poll = registry.pollAfter(eventCursor, limit);
            eventCursor = poll.cursor();
            return poll.batch();
        }
        @Override public DeviceResult subscribeEvents(DeviceEventSubscription subscription) {
            return registry.subscribeEvents(context, subscription);
        }
        @Override public DeviceEventBatch pollSubscription(UUID subscriptionId, int limit) {
            return registry.pollSubscription(context, subscriptionId, limit);
        }
        @Override public Optional<DeviceEventDiagnostics> eventDiagnostics(UUID subscriptionId) {
            return registry.eventDiagnostics(context, subscriptionId);
        }
        @Override public boolean unsubscribeEvents(UUID subscriptionId) {
            return registry.unsubscribeEvents(context, subscriptionId);
        }
    }

}
