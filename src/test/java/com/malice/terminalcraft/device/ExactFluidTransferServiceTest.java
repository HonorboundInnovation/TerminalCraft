package com.malice.terminalcraft.device;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Headless tests for fluid endpoint authority, alias rejection, replay, and result mapping. */
public final class ExactFluidTransferServiceTest {
    private static final UUID SOURCE = new UUID(0, 601);
    private static final UUID DESTINATION = new UUID(0, 602);
    private static final UUID OPERATION = new UUID(0, 603);
    private static final DeviceCallContext WRITER = new DeviceCallContext(new UUID(0, 604), "writer",
            Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));

    private ExactFluidTransferServiceTest() {}

    public static void main(String[] args) {
        partialResultUsesExplicitMillibucketUnit();
        replayDoesNotRequireLiveEndpoints();
        guardsAndBackingAliasesPrecedeMutation();
        resolutionAndAdapterFailuresAreMapped();
        System.out.println("Exact fluid transfer service tests: OK");
    }

    private static void partialResultUsesExplicitMillibucketUnit() {
        TestPort source = new TestPort(new Fluid("minecraft:water", 800), 800);
        TestPort destination = new TestPort(Fluid.empty(), 300);
        DeviceValue.MapValue value = success(service(source, destination).transfer(WRITER, OPERATION,
                SOURCE, DESTINATION, "minecraft:water", 700));
        assertEquals("partial", string(value, "status"), "partial status");
        assertEquals("mB", string(value, "unit"), "explicit unit");
        assertEquals(300.0, number(value, "inserted"), "inserted amount");
        assertEquals(400.0, number(value, "rolled_back"), "rollback amount");
    }

    private static void replayDoesNotRequireLiveEndpoints() {
        TestPort source = new TestPort(new Fluid("minecraft:water", 500), 500);
        TestPort destination = new TestPort(Fluid.empty(), 500);
        ExactFluidTransferCoordinator<Fluid> coordinator = new ExactFluidTransferCoordinator<>();
        ExactFluidTransferService<Fluid> online = new ExactFluidTransferService<>(coordinator,
                id -> id.equals(SOURCE) ? found(SOURCE, source)
                        : id.equals(DESTINATION) ? found(DESTINATION, destination) : missing());
        success(online.transfer(WRITER, OPERATION, SOURCE, DESTINATION, "minecraft:water", 100));
        int mutations = source.mutations + destination.mutations;
        ExactFluidTransferService<Fluid> offline = new ExactFluidTransferService<>(coordinator, id -> missing());
        DeviceValue.MapValue replay = success(offline.transfer(WRITER, OPERATION, SOURCE,
                DESTINATION, "minecraft:water", 100));
        assertEquals(true, bool(replay, "replayed"), "offline replay marker");
        assertEquals(mutations, source.mutations + destination.mutations, "replay does not mutate");
    }

    private static void guardsAndBackingAliasesPrecedeMutation() {
        TestPort shared = new TestPort(new Fluid("minecraft:water", 500), 500);
        Object backing = new Object();
        ExactFluidTransferService<Fluid> aliases = new ExactFluidTransferService<>(
                new ExactFluidTransferCoordinator<>(), id -> ExactFluidTransferService.Resolution.found(
                new ExactFluidTransferService.ResolvedEndpoint<>(id, backing, shared)));
        assertError(DeviceErrorCode.INVALID_ARGUMENT, aliases.transfer(WRITER, OPERATION, SOURCE,
                DESTINATION, "minecraft:water", 100), "backing alias rejected");
        assertError(DeviceErrorCode.PERMISSION_DENIED, aliases.transfer(DeviceCallContext.readOnly("reader"),
                new UUID(0, 605), SOURCE, DESTINATION, "minecraft:water", 100), "read-only rejected");
        assertError(DeviceErrorCode.INVALID_ARGUMENT, aliases.transfer(WRITER, new UUID(0, 606), SOURCE,
                DESTINATION, "invalid", 100), "invalid resource rejected");
        assertEquals(0, shared.mutations, "guards precede mutation");
    }

    private static void resolutionAndAdapterFailuresAreMapped() {
        for (ExactFluidTransferService.ResolutionStatus status : java.util.List.of(
                ExactFluidTransferService.ResolutionStatus.NOT_FOUND,
                ExactFluidTransferService.ResolutionStatus.CHUNK_UNLOADED,
                ExactFluidTransferService.ResolutionStatus.UNSUPPORTED)) {
            ExactFluidTransferService<Fluid> service = new ExactFluidTransferService<>(
                    new ExactFluidTransferCoordinator<>(), id -> id.equals(SOURCE)
                    ? ExactFluidTransferService.Resolution.failure(status)
                    : found(DESTINATION, new TestPort(Fluid.empty(), 100)));
            DeviceErrorCode expected = switch (status) {
                case NOT_FOUND -> DeviceErrorCode.NOT_FOUND;
                case CHUNK_UNLOADED -> DeviceErrorCode.CHUNK_UNLOADED;
                case UNSUPPORTED -> DeviceErrorCode.UNSUPPORTED;
                case PERMISSION_DENIED -> DeviceErrorCode.PERMISSION_DENIED;
                case FOUND -> throw new AssertionError("unexpected status");
            };
            assertError(expected, service.transfer(WRITER, UUID.randomUUID(), SOURCE,
                    DESTINATION, "minecraft:water", 1), "resolution status mapped");
        }
        TestPort broken = new TestPort(new Fluid("minecraft:water", 100), 100);
        broken.rejectDrain = true;
        assertError(DeviceErrorCode.ADAPTER_ERROR, service(broken, new TestPort(Fluid.empty(), 100))
                .transfer(WRITER, new UUID(0, 607), SOURCE, DESTINATION, "minecraft:water", 10),
                "source adapter failure mapped");
    }

    private static ExactFluidTransferService<Fluid> service(TestPort source, TestPort destination) {
        return new ExactFluidTransferService<>(new ExactFluidTransferCoordinator<>(), id ->
                id.equals(SOURCE) ? found(SOURCE, source)
                        : id.equals(DESTINATION) ? found(DESTINATION, destination) : missing());
    }
    private static ExactFluidTransferService.Resolution<Fluid> found(UUID id, TestPort port) {
        return ExactFluidTransferService.Resolution.found(
                new ExactFluidTransferService.ResolvedEndpoint<>(id, port, port));
    }
    private static ExactFluidTransferService.Resolution<Fluid> missing() {
        return ExactFluidTransferService.Resolution.failure(ExactFluidTransferService.ResolutionStatus.NOT_FOUND);
    }

    private static final class TestPort implements ExactFluidTransferCoordinator.Port<Fluid> {
        private Fluid stored;
        private final int capacity;
        private boolean rejectDrain;
        private int mutations;
        private TestPort(Fluid stored, int capacity) { this.stored = stored; this.capacity = capacity; }
        @Override public Fluid drain(String resourceId, int amountMb) {
            if (rejectDrain) throw new IllegalStateException("drain failed");
            mutations++;
            if (!stored.resource().equals(resourceId)) return Fluid.empty();
            int amount = Math.min(amountMb, stored.amount());
            stored = new Fluid(stored.resource(), stored.amount() - amount);
            return new Fluid(resourceId, amount);
        }
        @Override public Fluid fill(Fluid payload) {
            mutations++;
            int accepted = Math.min(payload.amount(), Math.max(0, capacity - stored.amount()));
            if (accepted > 0) stored = new Fluid(payload.resource(), stored.amount() + accepted);
            return new Fluid(payload.resource(), payload.amount() - accepted);
        }
        @Override public int amount(Fluid payload) { return payload.amount(); }
        @Override public boolean sameVariant(Fluid left, Fluid right) {
            return left.resource().equals(right.resource());
        }
    }
    private record Fluid(String resource, int amount) {
        private static Fluid empty() { return new Fluid("minecraft:empty", 0); }
    }

    private static DeviceValue.MapValue success(DeviceResult result) {
        if (!result.isSuccess()) throw new AssertionError("expected success: " + result.error());
        return (DeviceValue.MapValue) result.value().orElseThrow();
    }
    private static String string(DeviceValue.MapValue value, String key) {
        return ((DeviceValue.StringValue) value.values().get(key)).value();
    }
    private static double number(DeviceValue.MapValue value, String key) {
        return ((DeviceValue.NumberValue) value.values().get(key)).value();
    }
    private static boolean bool(DeviceValue.MapValue value, String key) {
        return ((DeviceValue.BooleanValue) value.values().get(key)).value();
    }
    private static void assertError(DeviceErrorCode code, DeviceResult result, String message) {
        if (result.isSuccess() || result.error().orElseThrow().code() != code) {
            throw new AssertionError(message + ": expected=" + code + ", actual=" + result.error());
        }
    }
    private static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
