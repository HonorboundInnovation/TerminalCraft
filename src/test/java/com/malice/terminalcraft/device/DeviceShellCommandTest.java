package com.malice.terminalcraft.device;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Headless tests for device shell parsing, schema conversion, formatting, lookup, and calls. */
public final class DeviceShellCommandTest {
    private DeviceShellCommandTest() {}

    public static void main(String[] args) {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000010");
        DeviceRegistry registry = new DeviceRegistry();
        TestEndpoint endpoint = new TestEndpoint(id);
        registry.register(endpoint);

        assertOutcome(DeviceShellCommand.execute(registry, List.of("list")), 0,
                List.of(id + "  test_device  online  test:10  Test Device"), "list");
        registry.publishEvent(id, "touch", 42, (DeviceValue.MapValue) DeviceValue.map(Map.of(
                "x", DeviceValue.of(3), "y", DeviceValue.of(4))));
        DeviceShellCommand.Outcome events = DeviceShellCommand.execute(registry, List.of("events", "1"));
        assertEquals(0, events.exitCode(), "events exit");
        assertTrue(events.lines().size() == 1 && events.lines().get(0).contains(" touch ")
                && events.lines().get(0).contains("\"x\": 3"), "touch event formatting");
        callerOwnedEventSubscriptions(registry, id);

        DeviceShellCommand.Outcome info = DeviceShellCommand.execute(registry, List.of("info", id.toString()));
        assertEquals(0, info.exitCode(), "info exit");
        assertTrue(info.lines().contains("id: " + id), "info identity");
        assertTrue(info.lines().contains("methods: echo(value:string)->string [device.read], typed(count:number,enabled:boolean?)->list [device.read], values()->map [device.read]"),
                "sorted methods and optional schema");

        assertOutcome(DeviceShellCommand.execute(registry,
                        List.of("call", id.toString(), "echo", "hello world")),
                0, List.of("\"hello world\""), "string call");
        assertOutcome(DeviceShellCommand.execute(registry,
                        List.of("call", id.toString(), "echo", "12")),
                0, List.of("\"12\""), "numeric-looking string follows schema");

        assertOutcome(DeviceShellCommand.execute(registry,
                        List.of("call", id.toString(), "typed", "12.5")),
                0, List.of("[12.5]"), "required number with omitted optional argument");
        assertOutcome(DeviceShellCommand.execute(registry,
                        List.of("call", id.toString(), "typed", "12", "TRUE")),
                0, List.of("[12, true]"), "typed number and boolean conversion");
        assertOutcome(DeviceShellCommand.execute(registry,
                        List.of("call", id.toString(), "typed", "twelve")),
                1, List.of("device: invalid_argument: parameter 'count' expects number, got \"twelve\""),
                "invalid number rejected before adapter");
        assertOutcome(DeviceShellCommand.execute(registry,
                        List.of("call", id.toString(), "typed", "12", "yes")),
                1, List.of("device: invalid_argument: parameter 'enabled' expects boolean, got \"yes\""),
                "invalid boolean rejected before adapter");
        assertOutcome(DeviceShellCommand.execute(registry,
                        List.of("call", id.toString(), "typed")),
                1, List.of("device: invalid_argument: typed expects 1..2 argument(s), got 0"),
                "missing required argument");
        assertOutcome(DeviceShellCommand.execute(registry,
                        List.of("call", id.toString(), "typed", "1", "true", "extra")),
                1, List.of("device: invalid_argument: typed expects 1..2 argument(s), got 3"),
                "excess argument");

        assertOutcome(DeviceShellCommand.execute(registry,
                        List.of("call", id.toString(), "values")),
                0, List.of("{\"enabled\": true, \"items\": [1, \"two\"], \"nothing\": null}"),
                "structured formatting");
        assertOutcome(DeviceShellCommand.execute(registry,
                        List.of("call", id.toString(), "missing")),
                1, List.of("device: unsupported: method is unsupported: missing"), "schema rejects unknown method");
        assertOutcome(DeviceShellCommand.execute(registry,
                        List.of("call", id.toString(), "values", "extra")),
                1, List.of("device: invalid_argument: values expects 0 argument(s), got 1"),
                "zero-argument method arity");
        assertOutcome(DeviceShellCommand.execute(registry, List.of("info", "bad")),
                1, List.of("device: invalid UUID: bad"), "invalid UUID");
        assertOutcome(DeviceShellCommand.execute(registry,
                        List.of("call", "00000000-0000-0000-0000-000000000099", "echo", "x")),
                1, List.of("device: device not found: 00000000-0000-0000-0000-000000000099"),
                "missing device before call");
        assertOutcome(DeviceShellCommand.execute(null, List.of("list")),
                1, List.of("device: server device registry unavailable"), "unavailable registry");

        assertOutcome(DeviceShellCommand.execute(registry, List.of("transfer", id.toString(),
                        id.toString(), id.toString(), "minecraft:stone", "1")),
                1, List.of("device: unsupported: exact item transfer is unavailable"),
                "transfer capability required");

        TransferAccess transfers = new TransferAccess();
        UUID operation = UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID source = UUID.fromString("00000000-0000-0000-0000-000000000012");
        UUID destination = UUID.fromString("00000000-0000-0000-0000-000000000013");
        assertOutcome(DeviceShellCommand.execute(transfers, List.of("transfer", operation.toString(),
                        source.toString(), destination.toString(), "minecraft:stone", "7")),
                0, List.of("{\"inserted\": 7, \"status\": \"complete\"}"),
                "exact transfer command");
        assertEquals(operation, transfers.operationId, "operation forwarded");
        assertEquals(source, transfers.sourceId, "source forwarded");
        assertEquals(destination, transfers.destinationId, "destination forwarded");
        assertEquals("minecraft:stone", transfers.resourceId, "resource forwarded");
        assertEquals(7, transfers.count, "count forwarded");

        assertOutcome(DeviceShellCommand.execute(transfers, List.of("transfer", "bad",
                        source.toString(), destination.toString(), "minecraft:stone", "7")),
                1, List.of("device: invalid operation UUID: bad"), "invalid operation UUID");
        assertOutcome(DeviceShellCommand.execute(transfers, List.of("transfer", operation.toString(),
                        source.toString(), destination.toString(), "minecraft:stone", "many")),
                1, List.of("device: invalid_argument: transfer count must be an integer"),
                "invalid transfer count");
        assertEquals(1, transfers.calls, "invalid syntax never reaches transfer access");

        assertOutcome(DeviceShellCommand.execute(registry, List.of("escrow", "list")),
                1, List.of("device: unsupported: item escrow administration is unavailable"),
                "escrow capability required");
        EscrowAccess escrow = new EscrowAccess();
        UUID escrowId = UUID.fromString("00000000-0000-0000-0000-000000000014");
        assertOutcome(DeviceShellCommand.execute(escrow, List.of("escrow", "list")),
                0, List.of("[]"), "escrow list");
        assertOutcome(DeviceShellCommand.execute(escrow, List.of("escrow", "recover",
                        escrowId.toString(), destination.toString())),
                0, List.of("{\"inserted\": 1, \"remaining\": 0, \"status\": \"complete\"}"),
                "escrow recovery");
        assertEquals(escrowId, escrow.escrowId, "escrow ID forwarded");
        assertEquals(destination, escrow.destinationId, "escrow destination forwarded");
        assertOutcome(DeviceShellCommand.execute(escrow, List.of("escrow", "recover",
                        "bad", destination.toString())),
                1, List.of("device: invalid escrow UUID: bad"), "invalid escrow UUID");
        assertEquals(1, escrow.recoveries, "invalid escrow syntax does not invoke access");

        FluidTransferAccess fluidTransfers = new FluidTransferAccess();
        assertOutcome(DeviceShellCommand.execute(fluidTransfers, List.of("fluid-transfer",
                        operation.toString(), source.toString(), destination.toString(),
                        "minecraft:water", "750")),
                0, List.of("{\"inserted\": 750, \"status\": \"complete\", \"unit\": \"mB\"}"),
                "exact fluid transfer command");
        assertEquals(750, fluidTransfers.amountMb, "fluid amount forwarded");
        assertOutcome(DeviceShellCommand.execute(fluidTransfers, List.of("fluid-transfer",
                        operation.toString(), source.toString(), destination.toString(),
                        "minecraft:water", "many")),
                1, List.of("device: invalid_argument: fluid amount must be an integer in mB"),
                "invalid fluid amount");
        assertEquals(1, fluidTransfers.calls, "invalid fluid syntax never reaches access");

        FluidEscrowAccess fluidEscrow = new FluidEscrowAccess();
        assertOutcome(DeviceShellCommand.execute(fluidEscrow, List.of("fluid-escrow", "list")),
                0, List.of("[]"), "fluid escrow list");
        assertOutcome(DeviceShellCommand.execute(fluidEscrow, List.of("fluid-escrow", "recover",
                        escrowId.toString(), destination.toString())),
                0, List.of("{\"inserted\": 250, \"remaining\": 0, \"status\": \"complete\", \"unit\": \"mB\"}"),
                "fluid escrow recovery");
        assertEquals(1, fluidEscrow.recoveries, "fluid escrow recovery forwarded once");

        System.out.println("Device shell command tests: OK");
    }

    private static void callerOwnedEventSubscriptions(DeviceRegistry registry, UUID source) {
        UUID ownerId = UUID.randomUUID();
        DeviceCallContext player = new DeviceCallContext(ownerId, "alice", Set.of(DeviceCallContext.READ));
        DeviceAccess access = registry.access(player);
        DeviceShellCommand.Outcome subscribed = DeviceShellCommand.execute(access,
                List.of("events", "subscribe", source.toString(), "touch", "0", "true"));
        assertEquals(0, subscribed.exitCode(), "subscription creation exit");
        UUID subscriptionId = UUID.fromString(subscribed.lines().get(0).replace("\"", ""));

        registry.publishEvent(source, "touch", 43, (DeviceValue.MapValue) DeviceValue.map(Map.of(
                "x", DeviceValue.of(5))));
        DeviceShellCommand.Outcome poll = DeviceShellCommand.execute(access,
                List.of("events", "poll", subscriptionId.toString(), "1"));
        assertTrue(poll.exitCode() == 0 && poll.lines().get(0).contains(" touch "),
                "owned subscription polls matching event");
        assertOutcome(DeviceShellCommand.execute(access,
                        List.of("events", "diagnostics", subscriptionId.toString())),
                0, List.of("queued=0 delivered=1 dropped=0 debounced=0 coalesced=0"),
                "subscription diagnostics");

        DeviceAccess sameUuidService = registry.access(DeviceCallContext.service(ownerId, "alice-service",
                Set.of(DeviceCallContext.READ)));
        assertOutcome(DeviceShellCommand.execute(sameUuidService,
                        List.of("events", "diagnostics", subscriptionId.toString())),
                1, List.of("device: subscription not found"),
                "same-UUID service cannot inspect player subscription");
        assertOutcome(DeviceShellCommand.execute(sameUuidService,
                        List.of("events", "unsubscribe", subscriptionId.toString())),
                1, List.of("device: subscription not found"),
                "same-UUID service cannot remove player subscription");
        assertOutcome(DeviceShellCommand.execute(access,
                        List.of("events", "unsubscribe", subscriptionId.toString())),
                0, List.of("unsubscribed=" + subscriptionId), "owner unsubscribe");
        assertOutcome(DeviceShellCommand.execute(access,
                        List.of("events", "subscribe", "*", "touch", "bad")),
                1, List.of("device: invalid_argument: debounce must be an integer tick count"),
                "invalid debounce rejected before runtime");
        assertOutcome(DeviceShellCommand.execute(access,
                        List.of("events", "subscribe", "not-a-uuid", "touch")),
                1, List.of("device: invalid source UUID: not-a-uuid"),
                "invalid source rejected before runtime");
        assertOutcome(DeviceShellCommand.execute(access,
                        List.of("events", "subscribe", "*", "touch,,changed")),
                1, List.of("device: invalid_argument: invalid event type: "),
                "empty event type rejected");
        assertOutcome(DeviceShellCommand.execute(access,
                        List.of("events", "subscribe", "*", "Touch")),
                1, List.of("device: invalid_argument: invalid event type: Touch"),
                "malformed event identifier rejected");
        assertOutcome(DeviceShellCommand.execute(access,
                        List.of("events", "subscribe", "*", "touch", "-1")),
                1, List.of("device: invalid_argument: event debounce is outside the supported range"),
                "negative debounce rejected");
        assertOutcome(DeviceShellCommand.execute(access,
                        List.of("events", "subscribe", "*", "touch",
                                Long.toString(DeviceEventSubscription.MAX_DEBOUNCE_TICKS + 1))),
                1, List.of("device: invalid_argument: event debounce is outside the supported range"),
                "oversized debounce rejected");
        assertOutcome(DeviceShellCommand.execute(access,
                        List.of("events", "subscribe", "*", "touch", "0", "yes")),
                1, List.of("device: invalid_argument: coalesce must be true or false"),
                "invalid coalesce rejected");
        assertOutcome(DeviceShellCommand.execute(access,
                        List.of("events", "poll", UUID.randomUUID().toString(), "0")),
                1, List.of("device: invalid_argument: event limit must be from 1 to "
                        + DeviceEventRuntime.MAX_POLL_RESULTS),
                "zero subscription poll limit rejected");
        assertOutcome(DeviceShellCommand.execute(access,
                        List.of("events", "diagnostics", "bad")),
                1, List.of("device: invalid subscription UUID: bad"),
                "invalid diagnostics UUID rejected");
    }

    private static final class TransferAccess implements DeviceAccess, ExactItemTransferAccess {
        private UUID operationId;
        private UUID sourceId;
        private UUID destinationId;
        private String resourceId;
        private int count;
        private int calls;

        @Override public DeviceCallContext context() { return DeviceCallContext.readOnly("test"); }
        @Override public List<DeviceDescriptor> descriptors(int limit) { return List.of(); }
        @Override public java.util.Optional<DeviceDescriptor> descriptor(UUID deviceId) {
            return java.util.Optional.empty();
        }
        @Override public DeviceResult call(UUID deviceId, String method, List<DeviceValue> arguments) {
            throw new AssertionError("ordinary call not expected");
        }
        @Override public DeviceEventBatch pollEvents(int limit) {
            return new DeviceEventBatch(List.of(), 0);
        }
        @Override public DeviceResult transferExactItems(UUID operationId, UUID sourceId,
                                                         UUID destinationId, String resourceId, int count) {
            this.operationId = operationId;
            this.sourceId = sourceId;
            this.destinationId = destinationId;
            this.resourceId = resourceId;
            this.count = count;
            calls++;
            return DeviceResult.success(DeviceValue.map(Map.of(
                    "status", DeviceValue.of("complete"),
                    "inserted", DeviceValue.of(count))));
        }
    }

    private static final class EscrowAccess implements DeviceAccess, ExactItemEscrowAccess {
        private UUID escrowId;
        private UUID destinationId;
        private int recoveries;

        @Override public DeviceCallContext context() { return DeviceCallContext.readOnly("test"); }
        @Override public List<DeviceDescriptor> descriptors(int limit) { return List.of(); }
        @Override public java.util.Optional<DeviceDescriptor> descriptor(UUID deviceId) {
            return java.util.Optional.empty();
        }
        @Override public DeviceResult call(UUID deviceId, String method, List<DeviceValue> arguments) {
            throw new AssertionError("ordinary call not expected");
        }
        @Override public DeviceEventBatch pollEvents(int limit) { return new DeviceEventBatch(List.of(), 0); }
        @Override public DeviceResult listItemEscrow(int limit) {
            return DeviceResult.success(DeviceValue.list(List.of()));
        }
        @Override public DeviceResult recoverItemEscrow(UUID escrowId, UUID destinationId) {
            this.escrowId = escrowId;
            this.destinationId = destinationId;
            recoveries++;
            return DeviceResult.success(DeviceValue.map(Map.of(
                    "status", DeviceValue.of("complete"),
                    "inserted", DeviceValue.of(1),
                    "remaining", DeviceValue.of(0))));
        }
    }

    private static final class FluidTransferAccess implements DeviceAccess, ExactFluidTransferAccess {
        private int amountMb;
        private int calls;
        @Override public DeviceCallContext context() { return DeviceCallContext.readOnly("test"); }
        @Override public List<DeviceDescriptor> descriptors(int limit) { return List.of(); }
        @Override public java.util.Optional<DeviceDescriptor> descriptor(UUID deviceId) { return java.util.Optional.empty(); }
        @Override public DeviceResult call(UUID deviceId, String method, List<DeviceValue> arguments) { throw new AssertionError(); }
        @Override public DeviceEventBatch pollEvents(int limit) { return new DeviceEventBatch(List.of(), 0); }
        @Override public DeviceResult transferExactFluid(UUID operationId, UUID sourceId, UUID destinationId,
                                                         String resourceId, int amountMb) {
            this.amountMb = amountMb;
            calls++;
            return DeviceResult.success(DeviceValue.map(Map.of(
                    "status", DeviceValue.of("complete"), "inserted", DeviceValue.of(amountMb),
                    "unit", DeviceValue.of("mB"))));
        }
    }

    private static final class FluidEscrowAccess implements DeviceAccess, ExactFluidEscrowAccess {
        private int recoveries;
        @Override public DeviceCallContext context() { return DeviceCallContext.readOnly("test"); }
        @Override public List<DeviceDescriptor> descriptors(int limit) { return List.of(); }
        @Override public java.util.Optional<DeviceDescriptor> descriptor(UUID deviceId) { return java.util.Optional.empty(); }
        @Override public DeviceResult call(UUID deviceId, String method, List<DeviceValue> arguments) { throw new AssertionError(); }
        @Override public DeviceEventBatch pollEvents(int limit) { return new DeviceEventBatch(List.of(), 0); }
        @Override public DeviceResult listFluidEscrow(int limit) { return DeviceResult.success(DeviceValue.list(List.of())); }
        @Override public DeviceResult recoverFluidEscrow(UUID escrowId, UUID destinationId) {
            recoveries++;
            return DeviceResult.success(DeviceValue.map(Map.of(
                    "status", DeviceValue.of("complete"), "inserted", DeviceValue.of(250),
                    "remaining", DeviceValue.of(0), "unit", DeviceValue.of("mB"))));
        }
    }

    private static final class TestEndpoint implements DeviceEndpoint {
        private final UUID id;

        private TestEndpoint(UUID id) {
            this.id = id;
        }

        @Override
        public DeviceDescriptor descriptor() {
            return new DeviceDescriptor(id, "terminalcraft:test", "test_device", "Test Device",
                    "terminalcraft", "test:10", Set.of("test"),
                    Map.of("label", DeviceValue.of("test")),
                    List.of(
                            new DeviceMethodDescriptor("values", "Returns values", List.of(), DeviceValueType.MAP),
                            new DeviceMethodDescriptor("echo", "Echoes a value",
                                    List.of(new DeviceParameterDescriptor("value", DeviceValueType.STRING,
                                            true, "Value")), DeviceValueType.STRING),
                            new DeviceMethodDescriptor("typed", "Returns converted arguments",
                                    List.of(
                                            new DeviceParameterDescriptor("count", DeviceValueType.NUMBER,
                                                    true, "Count"),
                                            new DeviceParameterDescriptor("enabled", DeviceValueType.BOOLEAN,
                                                    false, "Optional flag")), DeviceValueType.LIST)),
                    Set.of("touch"), Set.of("device.read"), true, true);
        }

        @Override
        public DeviceResult call(String method, List<DeviceValue> arguments) {
            return switch (method) {
                case "echo" -> DeviceResult.success(arguments.get(0));
                case "typed" -> DeviceResult.success(DeviceValue.list(arguments));
                case "values" -> DeviceResult.success(DeviceValue.map(Map.of(
                        "nothing", DeviceValue.nullValue(),
                        "items", DeviceValue.list(List.of(DeviceValue.of(1L), DeviceValue.of("two"))),
                        "enabled", DeviceValue.of(true))));
                default -> throw new AssertionError("schema validation should reject unknown method");
            };
        }
    }

    private static void assertOutcome(DeviceShellCommand.Outcome actual, int exitCode,
                                      List<String> lines, String message) {
        assertEquals(exitCode, actual.exitCode(), message + " exit");
        assertEquals(lines, actual.lines(), message + " output");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
