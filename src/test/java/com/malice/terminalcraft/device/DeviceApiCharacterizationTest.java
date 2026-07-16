package com.malice.terminalcraft.device;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Headless contract tests using the same endpoint interface intended for real adapters. */
public final class DeviceApiCharacterizationTest {
    private DeviceApiCharacterizationTest() {}

    public static void main(String[] args) {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        MutableTestEndpoint endpoint = new MutableTestEndpoint(id);
        DeviceRegistry registry = new DeviceRegistry();
        registry.register(endpoint);

        assertEquals(1, registry.descriptors(1000).size(), "bounded enumeration contains endpoint");
        assertEquals(id, registry.descriptors(1).get(0).deviceId(), "stable identity");

        DeviceResult echo = registry.call(id, "echo", List.of(DeviceValue.of("hello")));
        assertTrue(echo.isSuccess(), "echo succeeds");
        assertEquals("hello", ((DeviceValue.StringValue) echo.value().orElseThrow()).value(), "echo value");

        DeviceMethodDescriptor writeMethod = new DeviceMethodDescriptor(
                "write", "Mutates test state", List.of(), DeviceValueType.NULL, DeviceCallContext.WRITE);
        assertEquals(DeviceCallContext.WRITE, writeMethod.requiredPermission(), "write permission schema");

        DeviceResult badArgument = registry.call(id, "echo", List.of());
        assertError(DeviceErrorCode.INVALID_ARGUMENT, badArgument, "argument arity failure");
        assertEquals(1, endpoint.calls, "invalid arity rejected before endpoint");
        assertError(DeviceErrorCode.INVALID_ARGUMENT,
                registry.call(id, "echo", List.of(DeviceValue.of(true))),
                "argument type failure");
        assertEquals(1, endpoint.calls, "invalid type rejected before endpoint");
        int callsBeforeAggregateRejection = endpoint.calls;
        assertError(DeviceErrorCode.INVALID_ARGUMENT,
                registry.call(id, "many", java.util.Collections.nCopies(
                        9, DeviceValue.of("x".repeat(DeviceValue.MAX_STRING_LENGTH)))),
                "combined argument text budget failure");
        assertEquals(callsBeforeAggregateRejection, endpoint.calls,
                "aggregate argument overflow rejected before endpoint");
        assertError(DeviceErrorCode.ADAPTER_ERROR,
                registry.call(id, "wrong_return", List.of()), "return schema failure");
        assertError(DeviceErrorCode.UNSUPPORTED,
                registry.call(id, "missing", List.of()), "unsupported method");
        assertError(DeviceErrorCode.PERMISSION_DENIED,
                registry.access(DeviceCallContext.readOnly("reader")).call(id, "write", List.of()),
                "read-only caller denied");
        DeviceAccess unprivileged = registry.access(new DeviceCallContext(
                UUID.fromString("00000000-0000-0000-0000-000000000098"), "unprivileged", Set.of()));
        assertEquals(0, unprivileged.descriptors(10).size(),
                "enumeration requires read permission");
        assertTrue(unprivileged.descriptor(id).isEmpty(),
                "descriptor lookup requires read permission");
        assertEquals(0, unprivileged.pollEvents(10).events().size(),
                "event polling requires read permission");

        DeviceCallContext writer = new DeviceCallContext(
                UUID.fromString("00000000-0000-0000-0000-000000000099"), "writer",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        assertTrue(registry.access(writer).call(id, "write", List.of()).isSuccess(),
                "write caller allowed");
        assertEquals(1, endpoint.writes, "authorized endpoint invoked once");

        DeviceAccess eventReaderA = registry.access(DeviceCallContext.readOnly("event-reader-a"));
        DeviceAccess eventReaderB = registry.access(DeviceCallContext.readOnly("event-reader-b"));
        DeviceValue.MapValue payload = new DeviceValue.MapValue(Map.of("count", DeviceValue.of(2L)));
        assertTrue(registry.publishEvent(id, "changed", 100L, payload).isSuccess(),
                "advertised event published");
        assertError(DeviceErrorCode.UNSUPPORTED,
                registry.publishEvent(id, "missing", 101L, payload), "unadvertised event rejected");
        DeviceEventBatch firstEvents = eventReaderA.pollEvents(10);
        assertEquals(1, firstEvents.events().size(), "event reader receives event");
        assertEquals(0L, firstEvents.dropped(), "event reader has no drops");
        DeviceEvent changed = firstEvents.events().get(0);
        assertEquals(id, changed.sourceDeviceId(), "event source identity");
        assertEquals("changed", changed.type(), "event type");
        assertEquals(100L, changed.gameTime(), "event game time");
        assertEquals(payload, changed.payload(), "event payload");
        assertEquals(0, eventReaderA.pollEvents(10).events().size(), "poll cursor advances");
        assertEquals(1, eventReaderB.pollEvents(10).events().size(),
                "independent reader does not lose event");

        DeviceCallContext revokedReader = DeviceCallContext.readOnly("revoked-reader");
        DeviceResult revokedSubscriptionResult = registry.subscribeEvents(revokedReader,
                new DeviceEventSubscription(id, Set.of("changed"), 0, false));
        UUID revokedSubscription = UUID.fromString(((DeviceValue.StringValue)
                revokedSubscriptionResult.value().orElseThrow()).value());
        assertTrue(registry.publishEvent(id, "changed", 150L, payload).isSuccess(),
                "subscribed event published before authorization revocation");
        DeviceCallContext revoked = new DeviceCallContext(revokedReader.principal(), Set.of());
        assertEquals(0, registry.pollSubscription(revoked, revokedSubscription, 10).events().size(),
                "authorization revocation denies queued subscription disclosure");
        assertTrue(registry.eventDiagnostics(revoked, revokedSubscription).isEmpty(),
                "authorization revocation conceals subscription diagnostics");
        assertTrue(!registry.unsubscribeEvents(revoked, revokedSubscription),
                "authorization revocation denies subscription removal");

        DeviceAccess slowReader = registry.access(DeviceCallContext.readOnly("slow-reader"));
        for (int event = 0; event <= DeviceRegistry.MAX_RETAINED_EVENTS; event++) {
            assertTrue(registry.publishEvent(id, "changed", 200L + event, payload).isSuccess(),
                    "bounded event published");
        }
        DeviceEventBatch overflow = slowReader.pollEvents(DeviceRegistry.MAX_EVENT_POLL_RESULTS);
        assertEquals(1L, overflow.dropped(), "retention overflow is explicit");
        assertEquals(DeviceRegistry.MAX_EVENT_POLL_RESULTS, overflow.events().size(),
                "event poll result is bounded");

        long quotaTick = 10_000L;
        for (int event = 0; event < DeviceRegistry.MAX_EVENTS_PER_SOURCE_PER_TICK; event++) {
            assertTrue(registry.publishEvent(id, "changed", quotaTick, payload).isSuccess(),
                    "per-source publication within quota succeeds");
        }
        assertError(DeviceErrorCode.BUSY,
                registry.publishEvent(id, "changed", quotaTick, payload),
                "per-source same-tick publication quota is explicit");
        assertTrue(registry.publishEvent(id, "changed", quotaTick + 1, payload).isSuccess(),
                "publication quota resets on the next game tick");

        endpoint.loaded = false;
        assertError(DeviceErrorCode.CHUNK_UNLOADED,
                registry.call(id, "echo", List.of(DeviceValue.of("x"))), "unloaded lifecycle");
        endpoint.loaded = true;
        endpoint.online = false;
        assertError(DeviceErrorCode.OFFLINE,
                registry.call(id, "echo", List.of(DeviceValue.of("x"))), "offline lifecycle");
        endpoint.online = true;
        endpoint.descriptorFails = true;
        assertError(DeviceErrorCode.ADAPTER_ERROR,
                registry.call(id, "echo", List.of(DeviceValue.of("x"))),
                "descriptor adapter failure is structured");
        assertTrue(registry.descriptor(id).isEmpty(),
                "broken descriptor lookup is contained");
        assertEquals(0, registry.descriptors(10).size(),
                "broken descriptor is omitted from enumeration");
        endpoint.descriptorFails = false;

        DeviceRegistry largeRegistry = new DeviceRegistry();
        MutableTestEndpoint lastRegistered = null;
        for (int index = 0; index < DeviceRegistry.MAX_ENUMERATION_RESULTS + 1; index++) {
            lastRegistered = new MutableTestEndpoint(new UUID(1L, index + 1L));
            largeRegistry.register(lastRegistered);
        }
        assertEquals(DeviceRegistry.MAX_ENUMERATION_RESULTS,
                largeRegistry.descriptors(Integer.MAX_VALUE).size(), "enumeration remains bounded");
        assertTrue(largeRegistry.ownsAddress("test:" + lastRegistered.id),
                "address authority lookup is not truncated by enumeration bound");

        DeviceValue.ListValue immutable = (DeviceValue.ListValue) DeviceValue.list(
                List.of(DeviceValue.of("one")));
        assertThrows(() -> immutable.values().add(DeviceValue.of("two")), "list is immutable");
        assertThrows(() -> DeviceValue.of(Double.NaN), "non-finite number rejected");
        assertThrows(DeviceApiCharacterizationTest::excessivelyNestedValue,
                "recursive value depth is bounded");
        assertThrows(DeviceApiCharacterizationTest::excessivelyLargeValueTree,
                "recursive value node count is bounded");
        Map<String, DeviceValue> oversizedProperties = new java.util.LinkedHashMap<>();
        for (int property = 0; property < 9; property++) {
            oversizedProperties.put("property" + property, DeviceValue.of("x".repeat(4096)));
        }
        assertThrows(() -> new DeviceDescriptor(UUID.randomUUID(), "terminalcraft:test", "test_device",
                        "Test Device", "terminalcraft", "test:properties", Set.of(), oversizedProperties,
                        List.of(), Set.of(), Set.of(), true, true),
                "aggregate descriptor property text is bounded");
        assertThrows(() -> new DeviceDescriptor(UUID.randomUUID(), "terminalcraft:test", "test_device",
                        "Test Device", "terminalcraft", "test:properties", Set.of(),
                        Map.of("Invalid Property", DeviceValue.of(1L)), List.of(), Set.of(), Set.of(), true, true),
                "descriptor property names are validated");
        assertThrows(() -> new DeviceMethodDescriptor("invalid", "Invalid ordering", List.of(
                        new DeviceParameterDescriptor("optional", DeviceValueType.STRING, false, "Optional"),
                        new DeviceParameterDescriptor("required", DeviceValueType.STRING, true, "Required")),
                        DeviceValueType.NULL),
                "required parameter after optional rejected");
        assertThrows(() -> new DeviceMethodDescriptor("duplicate", "Duplicate parameter", List.of(
                        new DeviceParameterDescriptor("value", DeviceValueType.STRING, true, "First"),
                        new DeviceParameterDescriptor("value", DeviceValueType.STRING, false, "Second")),
                        DeviceValueType.NULL),
                "duplicate parameter rejected");
        DeviceMethodDescriptor duplicateMethod = new DeviceMethodDescriptor(
                "same", "Duplicate method", List.of(), DeviceValueType.NULL);
        assertThrows(() -> new DeviceDescriptor(UUID.randomUUID(), "terminalcraft:test", "test_device",
                        "Test Device", "terminalcraft", "test:duplicate", Set.of(), Map.of(),
                        List.of(duplicateMethod, duplicateMethod), Set.of(), Set.of(), true, true),
                "duplicate method rejected");
        assertThrows(() -> registry.register(endpoint), "duplicate identity rejected");

        assertTrue(registry.invalidate(id), "invalidation removes endpoint");
        assertError(DeviceErrorCode.NOT_FOUND,
                registry.call(id, "echo", List.of(DeviceValue.of("x"))), "removed lifecycle");
        assertError(DeviceErrorCode.NOT_FOUND,
                registry.publishEvent(id, "changed", quotaTick + 2, payload),
                "invalidated source cannot publish events");

        System.out.println("Device API characterization tests: OK");
    }

    private static final class MutableTestEndpoint implements DeviceEndpoint {
        private final UUID id;
        private boolean online = true;
        private boolean loaded = true;
        private boolean descriptorFails;
        private int writes;
        private int calls;

        private MutableTestEndpoint(UUID id) { this.id = id; }

        @Override
        public DeviceDescriptor descriptor() {
            if (descriptorFails) throw new IllegalStateException("broken descriptor");
            return new DeviceDescriptor(id, "terminalcraft:test", "test_device", "Test Device",
                    "terminalcraft", "test:" + id, Set.of("inventory"),
                    Map.of("count", DeviceValue.of(1L)),
                    List.of(
                            new DeviceMethodDescriptor("echo", "Returns its string argument",
                                    List.of(new DeviceParameterDescriptor("value", DeviceValueType.STRING,
                                            true, "Value to return")), DeviceValueType.STRING),
                            new DeviceMethodDescriptor("write", "Mutates test state", List.of(),
                                    DeviceValueType.NULL, DeviceCallContext.WRITE),
                            new DeviceMethodDescriptor("many", "Accepts enough strings to test aggregate limits",
                                    java.util.stream.IntStream.range(0, 9)
                                            .mapToObj(index -> new DeviceParameterDescriptor("value" + index,
                                                    DeviceValueType.STRING, true, "Value"))
                                            .toList(), DeviceValueType.NULL),
                            new DeviceMethodDescriptor("wrong_return", "Returns the wrong type", List.of(),
                                    DeviceValueType.STRING)),
                    Set.of("changed"), Set.of("device.call"), online, loaded);
        }

        @Override
        public DeviceResult call(String method, List<DeviceValue> arguments) {
            calls++;
            if ("wrong_return".equals(method)) return DeviceResult.success(DeviceValue.of(true));
            if ("write".equals(method)) {
                if (!arguments.isEmpty()) return DeviceResult.failure(
                        DeviceErrorCode.INVALID_ARGUMENT, "write accepts no arguments", false);
                writes++;
                return DeviceResult.success();
            }
            if (!"echo".equals(method)) {
                return DeviceResult.failure(DeviceErrorCode.UNSUPPORTED, "method is unsupported", false);
            }
            if (arguments.size() != 1 || !(arguments.get(0) instanceof DeviceValue.StringValue)) {
                return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT,
                        "echo requires one string", false);
            }
            return DeviceResult.success(arguments.get(0));
        }
    }

    private static void excessivelyNestedValue() {
        DeviceValue value = DeviceValue.nullValue();
        for (int depth = 0; depth < DeviceValue.MAX_NESTING_DEPTH; depth++) {
            value = DeviceValue.list(List.of(value));
        }
    }

    private static void excessivelyLargeValueTree() {
        DeviceValue branch = DeviceValue.list(java.util.Collections.nCopies(
                DeviceValue.MAX_COLLECTION_ENTRIES, DeviceValue.nullValue()));
        DeviceValue.list(List.of(branch, branch, branch, branch));
    }

    private static void assertError(DeviceErrorCode code, DeviceResult result, String message) {
        if (result.isSuccess() || result.error().orElseThrow().code() != code) {
            throw new AssertionError(message + ": expected=" + code + ", actual=" + result.error());
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertThrows(Runnable action, String message) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError(message + ": expected exception");
    }
}
