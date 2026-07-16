package com.malice.terminalcraft.device;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Headless tests for endpoint authority, status mapping, replay, and pre-mutation guards. */
public final class ExactItemTransferServiceTest {
    private static final UUID PRINCIPAL = UUID.fromString("00000000-0000-0000-0000-000000000501");
    private static final UUID SOURCE = UUID.fromString("00000000-0000-0000-0000-000000000502");
    private static final UUID DESTINATION = UUID.fromString("00000000-0000-0000-0000-000000000503");
    private static final UUID OPERATION = UUID.fromString("00000000-0000-0000-0000-000000000504");
    private static final DeviceCallContext WRITER = new DeviceCallContext(PRINCIPAL, "writer",
            Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));

    private ExactItemTransferServiceTest() {}

    public static void main(String[] args) {
        completeAndPartialResultsAreBoundedValues();
        replayIsReturnedWithoutSecondMutation();
        authorityAndInputGuardsPrecedeMutation();
        operationConflictsAndAdapterErrorsAreMapped();
        backingAliasesAndResolutionFailuresAreRejected();
        System.out.println("Exact item transfer service tests: OK");
    }

    private static void completeAndPartialResultsAreBoundedValues() {
        TestPort source = new TestPort(List.of(new Payload("minecraft:iron_ingot", 8)), 64);
        TestPort destination = new TestPort(List.of(), 3);
        ExactItemTransferService<Payload> service = service(source, destination);

        DeviceValue.MapValue value = success(service.transfer(WRITER, OPERATION, SOURCE,
                DESTINATION, "minecraft:iron_ingot", 8));
        assertEquals("partial", string(value, "status"), "partial status");
        assertEquals(8.0, number(value, "requested"), "requested");
        assertEquals(3.0, number(value, "inserted"), "inserted");
        assertEquals(5.0, number(value, "rolled_back"), "rolled back");
        assertEquals(0.0, number(value, "escrowed"), "escrowed");
        assertEquals(false, bool(value, "complete"), "partial complete marker");
        assertEquals(false, bool(value, "replayed"), "initial replay marker");
    }

    private static void replayIsReturnedWithoutSecondMutation() {
        TestPort source = new TestPort(List.of(new Payload("minecraft:coal", 4)), 64);
        TestPort destination = new TestPort(List.of(), 64);
        ExactItemTransferService<Payload> service = service(source, destination);
        success(service.transfer(WRITER, OPERATION, SOURCE, DESTINATION, "minecraft:coal", 4));
        int mutations = source.mutations + destination.mutations;

        DeviceValue.MapValue replay = success(service.transfer(WRITER, OPERATION, SOURCE,
                DESTINATION, "minecraft:coal", 4));
        assertEquals(true, bool(replay, "replayed"), "duplicate marked replayed");
        assertEquals(mutations, source.mutations + destination.mutations,
                "duplicate does not mutate ports");

        ExactItemTransferCoordinator<Payload> durable = new ExactItemTransferCoordinator<>();
        ExactItemTransferService<Payload> first = new ExactItemTransferService<>(durable, id -> {
            if (id.equals(SOURCE)) return found(SOURCE, source);
            if (id.equals(DESTINATION)) return found(DESTINATION, destination);
            return missing();
        });
        UUID offlineReplay = UUID.fromString("00000000-0000-0000-0000-000000000506");
        success(first.transfer(WRITER, offlineReplay, SOURCE, DESTINATION, "minecraft:coal", 1));
        ExactItemTransferService<Payload> afterUnload = new ExactItemTransferService<>(durable, id -> missing());
        DeviceValue.MapValue replayWithoutEndpoints = success(afterUnload.transfer(WRITER, offlineReplay,
                SOURCE, DESTINATION, "minecraft:coal", 1));
        assertEquals(true, bool(replayWithoutEndpoints, "replayed"),
                "authoritative replay does not require live endpoints");
    }

    private static void authorityAndInputGuardsPrecedeMutation() {
        TestPort source = new TestPort(List.of(new Payload("minecraft:coal", 4)), 64);
        TestPort destination = new TestPort(List.of(), 64);
        ExactItemTransferCoordinator<Payload> coordinator = new ExactItemTransferCoordinator<>();
        ExactItemTransferService<Payload> service = new ExactItemTransferService<>(coordinator, id -> {
            if (id.equals(SOURCE)) return found(SOURCE, source);
            return missing();
        });

        assertError(DeviceErrorCode.PERMISSION_DENIED, service.transfer(
                DeviceCallContext.readOnly("reader"), OPERATION, SOURCE, DESTINATION,
                "minecraft:coal", 4), "read-only denied");
        assertError(DeviceErrorCode.INVALID_ARGUMENT, service.transfer(WRITER, OPERATION,
                SOURCE, SOURCE, "minecraft:coal", 4), "same endpoint denied");
        assertError(DeviceErrorCode.INVALID_ARGUMENT, service.transfer(WRITER, OPERATION,
                SOURCE, DESTINATION, "not-an-id", 4), "resource denied");
        assertError(DeviceErrorCode.NOT_FOUND, service.transfer(WRITER, OPERATION,
                SOURCE, DESTINATION, "minecraft:coal", 4), "missing destination denied");
        assertEquals(0, source.mutations + destination.mutations, "guards precede mutation");
    }

    private static void operationConflictsAndAdapterErrorsAreMapped() {
        TestPort source = new TestPort(List.of(new Payload("minecraft:gold_ingot", 6)), 64);
        TestPort destination = new TestPort(List.of(), 64);
        ExactItemTransferService<Payload> service = service(source, destination);
        success(service.transfer(WRITER, OPERATION, SOURCE, DESTINATION,
                "minecraft:gold_ingot", 2));
        assertError(DeviceErrorCode.INVALID_ARGUMENT, service.transfer(WRITER, OPERATION,
                SOURCE, DESTINATION, "minecraft:gold_ingot", 3), "operation conflict mapped");

        TestPort brokenSource = new TestPort(List.of(new Payload("minecraft:diamond", 1)), 64);
        brokenSource.rejectExtracts = true;
        ExactItemTransferService<Payload> broken = service(brokenSource, destination);
        assertError(DeviceErrorCode.ADAPTER_ERROR, broken.transfer(WRITER,
                UUID.fromString("00000000-0000-0000-0000-000000000505"), SOURCE,
                DESTINATION, "minecraft:diamond", 1), "source error mapped");
    }

    private static void backingAliasesAndResolutionFailuresAreRejected() {
        TestPort shared = new TestPort(List.of(new Payload("minecraft:coal", 4)), 64);
        Object backing = new Object();
        ExactItemTransferService<Payload> aliases = new ExactItemTransferService<>(
                new ExactItemTransferCoordinator<>(), id -> {
            if (id.equals(SOURCE) || id.equals(DESTINATION)) {
                return ExactItemTransferService.Resolution.found(
                        new ExactItemTransferService.ResolvedEndpoint<>(id, backing, shared));
            }
            return missing();
        });
        assertError(DeviceErrorCode.INVALID_ARGUMENT, aliases.transfer(WRITER, OPERATION,
                SOURCE, DESTINATION, "minecraft:coal", 1), "backing alias rejected");
        assertEquals(0, shared.mutations, "backing alias rejected before mutation");

        for (ExactItemTransferService.ResolutionStatus status : List.of(
                ExactItemTransferService.ResolutionStatus.NOT_FOUND,
                ExactItemTransferService.ResolutionStatus.CHUNK_UNLOADED,
                ExactItemTransferService.ResolutionStatus.UNSUPPORTED)) {
            ExactItemTransferService<Payload> failed = new ExactItemTransferService<>(
                    new ExactItemTransferCoordinator<>(), id -> id.equals(SOURCE)
                    ? ExactItemTransferService.Resolution.failure(status)
                    : found(DESTINATION, new TestPort(List.of(), 64)));
            DeviceErrorCode expected = switch (status) {
                case NOT_FOUND -> DeviceErrorCode.NOT_FOUND;
                case CHUNK_UNLOADED -> DeviceErrorCode.CHUNK_UNLOADED;
                case UNSUPPORTED -> DeviceErrorCode.UNSUPPORTED;
                case PERMISSION_DENIED -> DeviceErrorCode.PERMISSION_DENIED;
                case FOUND -> throw new AssertionError("unexpected status");
            };
            assertError(expected, failed.transfer(WRITER, UUID.randomUUID(), SOURCE,
                    DESTINATION, "minecraft:coal", 1), "resolution status mapped");
        }
    }

    private static ExactItemTransferService.Resolution<Payload> found(UUID id, TestPort port) {
        return ExactItemTransferService.Resolution.found(
                new ExactItemTransferService.ResolvedEndpoint<>(id, port, port));
    }

    private static ExactItemTransferService.Resolution<Payload> missing() {
        return ExactItemTransferService.Resolution.failure(
                ExactItemTransferService.ResolutionStatus.NOT_FOUND);
    }

    private static ExactItemTransferService<Payload> service(TestPort source, TestPort destination) {
        return new ExactItemTransferService<>(new ExactItemTransferCoordinator<>(), id -> {
            if (id.equals(SOURCE)) return found(SOURCE, source);
            if (id.equals(DESTINATION)) return found(DESTINATION, destination);
            return missing();
        });
    }

    private static final class TestPort implements ExactItemTransferCoordinator.Port<Payload> {
        private final List<Payload> stored = new ArrayList<>();
        private final int capacity;
        private boolean rejectExtracts;
        private int mutations;

        private TestPort(List<Payload> initial, int capacity) {
            stored.addAll(initial);
            this.capacity = capacity;
        }

        @Override public List<Payload> extract(String resourceId, int count, int maxParts) {
            if (rejectExtracts) throw new IllegalStateException("source failed");
            List<Payload> result = new ArrayList<>();
            int remaining = count;
            for (int index = 0; index < stored.size() && remaining > 0 && result.size() < maxParts;) {
                Payload payload = stored.get(index);
                if (!payload.resource().equals(resourceId)) { index++; continue; }
                int taken = Math.min(remaining, payload.amount());
                result.add(new Payload(payload.resource(), taken));
                if (taken == payload.amount()) stored.remove(index);
                else stored.set(index++, new Payload(payload.resource(), payload.amount() - taken));
                remaining -= taken;
                mutations++;
            }
            return result;
        }

        @Override public Payload insert(Payload payload) {
            mutations++;
            int used = stored.stream().mapToInt(Payload::amount).sum();
            int accepted = Math.min(payload.amount(), Math.max(0, capacity - used));
            if (accepted > 0) stored.add(new Payload(payload.resource(), accepted));
            return new Payload(payload.resource(), payload.amount() - accepted);
        }

        @Override public int amount(Payload payload) { return payload.amount(); }
        @Override public boolean sameVariant(Payload left, Payload right) {
            return left.resource().equals(right.resource());
        }
    }

    private record Payload(String resource, int amount) {}

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
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
