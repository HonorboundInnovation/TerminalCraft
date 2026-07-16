package com.malice.terminalcraft.device;

import java.util.Set;
import java.util.UUID;

/** Headless exact-fluid identity, conservation, rollback, escrow, permission, and replay tests. */
public final class ExactFluidTransferCoordinatorTest {
    private static final UUID SOURCE = new UUID(0, 301);
    private static final UUID DESTINATION = new UUID(0, 302);
    private static final DeviceCallContext WRITER = new DeviceCallContext(new UUID(0, 303), "writer",
            Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));

    private ExactFluidTransferCoordinatorTest() {}

    public static void main(String[] args) {
        completeTransferPreservesExactVariant();
        partialDestinationRollsBackRemainder();
        failedRollbackEscrowsExactFluid();
        destinationFailureRollsBackWithoutLoss();
        permissionAndReplayGuardsPrecedeMutation();
        invalidRemainderIdentityCannotBreakConservation();
        malformedDrainIsRejectedWithoutDestinationMutation();
        partialEscrowRecoveryPreservesExactVariant();
        replayRetentionEvictsOldestRecordAtBound();
        escrowCapacityRejectsBeforeDrainWithoutEviction();
        System.out.println("Exact fluid transfer coordinator tests: OK");
    }

    private static void completeTransferPreservesExactVariant() {
        ExactFluidTransferCoordinator<Fluid> coordinator = new ExactFluidTransferCoordinator<>();
        TestPort source = new TestPort(new Fluid("minecraft:water", "temperature=warm", 1_000), 1_000);
        TestPort destination = new TestPort(Fluid.empty(), 1_000);
        var result = coordinator.transfer(WRITER, operation(1), SOURCE, source, DESTINATION,
                destination, "minecraft:water", 750);
        assertEquals(ExactFluidTransferCoordinator.Status.COMPLETE, result.status(), "complete status");
        assertEquals(new Fluid("minecraft:water", "temperature=warm", 750), destination.stored,
                "exact fluid variant reaches destination");
        assertConserved(result);
    }

    private static void partialDestinationRollsBackRemainder() {
        ExactFluidTransferCoordinator<Fluid> coordinator = new ExactFluidTransferCoordinator<>();
        TestPort source = new TestPort(new Fluid("minecraft:water", "plain", 1_000), 1_000);
        TestPort destination = new TestPort(Fluid.empty(), 250);
        var result = coordinator.transfer(WRITER, operation(2), SOURCE, source, DESTINATION,
                destination, "minecraft:water", 600);
        assertEquals(ExactFluidTransferCoordinator.Status.PARTIAL, result.status(), "partial status");
        assertEquals(250, result.insertedMb(), "destination accepted bounded amount");
        assertEquals(350, result.rolledBackMb(), "remainder returned to source");
        assertEquals(750, source.stored.amountMb(), "source retains unrequested plus rollback fluid");
        assertConserved(result);
    }

    private static void failedRollbackEscrowsExactFluid() {
        ExactFluidTransferCoordinator<Fluid> coordinator = new ExactFluidTransferCoordinator<>();
        TestPort source = new TestPort(new Fluid("minecraft:lava", "hot=true", 800), 800);
        source.rejectFills = true;
        TestPort destination = new TestPort(Fluid.empty(), 300);
        var result = coordinator.transfer(WRITER, operation(3), SOURCE, source, DESTINATION,
                destination, "minecraft:lava", 800);
        assertEquals(ExactFluidTransferCoordinator.Status.ESCROWED, result.status(), "escrow status");
        assertEquals(500, result.escrowedMb(), "rejected rollback enters escrow");
        Fluid escrowed = coordinator.escrowEntries().values().iterator().next().payload();
        assertEquals(new Fluid("minecraft:lava", "hot=true", 500), escrowed,
                "escrow preserves exact fluid identity");
        assertConserved(result);
    }

    private static void destinationFailureRollsBackWithoutLoss() {
        ExactFluidTransferCoordinator<Fluid> coordinator = new ExactFluidTransferCoordinator<>();
        TestPort source = new TestPort(new Fluid("minecraft:water", "mineral=true", 500), 500);
        TestPort destination = new TestPort(Fluid.empty(), 500);
        destination.rejectFills = true;
        var result = coordinator.transfer(WRITER, operation(9), SOURCE, source, DESTINATION,
                destination, "minecraft:water", 300);
        assertEquals(ExactFluidTransferCoordinator.Status.DESTINATION_ERROR, result.status(),
                "destination exception is explicit");
        assertEquals(0, result.insertedMb(), "failed destination inserts nothing");
        assertEquals(300, result.rolledBackMb(), "entire payload is rolled back");
        assertEquals(new Fluid("minecraft:water", "mineral=true", 500), source.stored,
                "source is restored exactly");
        assertTrue(coordinator.escrowEntries().isEmpty(), "successful rollback needs no escrow");
        assertConserved(result);
    }

    private static void permissionAndReplayGuardsPrecedeMutation() {
        ExactFluidTransferCoordinator<Fluid> coordinator = new ExactFluidTransferCoordinator<>();
        TestPort source = new TestPort(new Fluid("minecraft:water", "plain", 1_000), 1_000);
        TestPort destination = new TestPort(Fluid.empty(), 1_000);
        var denied = coordinator.transfer(DeviceCallContext.readOnly("reader"), operation(4), SOURCE,
                source, DESTINATION, destination, "minecraft:water", 100);
        assertEquals(ExactFluidTransferCoordinator.Status.PERMISSION_DENIED, denied.status(), "permission denied");
        assertEquals(0, source.mutations + destination.mutations, "denial does not mutate");

        UUID operation = operation(5);
        var first = coordinator.transfer(WRITER, operation, SOURCE, source, DESTINATION,
                destination, "minecraft:water", 100);
        int mutations = source.mutations + destination.mutations;
        var replay = coordinator.transfer(WRITER, operation, SOURCE, source, DESTINATION,
                destination, "minecraft:water", 100);
        assertTrue(replay.replayed(), "same operation is replayed");
        assertEquals(first.insertedMb(), replay.insertedMb(), "replay returns authoritative result");
        assertEquals(mutations, source.mutations + destination.mutations, "replay does not mutate");
        var conflict = coordinator.transfer(WRITER, operation, SOURCE, source, DESTINATION,
                destination, "minecraft:water", 101);
        assertEquals(ExactFluidTransferCoordinator.Status.OPERATION_CONFLICT, conflict.status(),
                "changed request conflicts");
        assertEquals(mutations, source.mutations + destination.mutations, "conflict does not mutate");
        DeviceCallContext service = DeviceCallContext.service(WRITER.principalId(), "writer-service",
                java.util.Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        var separate = coordinator.transfer(service, operation, SOURCE, source, DESTINATION,
                destination, "minecraft:water", 1);
        assertTrue(!separate.replayed(), "same UUID operation remains distinct across principal kinds");
        assertEquals(ExactFluidTransferCoordinator.Status.COMPLETE, separate.status(),
                "cross-kind operation executes independently");
    }

    private static void invalidRemainderIdentityCannotBreakConservation() {
        ExactFluidTransferCoordinator<Fluid> coordinator = new ExactFluidTransferCoordinator<>();
        TestPort source = new TestPort(new Fluid("minecraft:water", "plain", 400), 400);
        source.rejectFills = true;
        TestPort destination = new TestPort(Fluid.empty(), 0);
        destination.corruptRemainder = true;
        var result = coordinator.transfer(WRITER, operation(6), SOURCE, source, DESTINATION,
                destination, "minecraft:water", 400);
        assertEquals(ExactFluidTransferCoordinator.Status.ESCROWED, result.status(),
                "invalid destination behavior is escrowed");
        assertEquals(400, result.escrowedMb(), "full exact source payload retained");
        assertConserved(result);
    }

    private static void malformedDrainIsRejectedWithoutDestinationMutation() {
        ExactFluidTransferCoordinator<Fluid> coordinator = new ExactFluidTransferCoordinator<>();
        TestPort destination = new TestPort(Fluid.empty(), 1_000);
        ExactFluidTransferCoordinator.Port<Fluid> malformed = new ExactFluidTransferCoordinator.Port<>() {
            @Override public Fluid drain(String resourceId, int amountMb) {
                return new Fluid(resourceId, "oversized", amountMb + 1);
            }
            @Override public Fluid fill(Fluid payload) { return Fluid.empty(); }
            @Override public int amount(Fluid payload) { return payload.amountMb(); }
            @Override public boolean sameVariant(Fluid left, Fluid right) { return left.equals(right); }
        };
        var result = coordinator.transfer(WRITER, operation(7), SOURCE, malformed, DESTINATION,
                destination, "minecraft:water", 100);
        assertEquals(ExactFluidTransferCoordinator.Status.SOURCE_ERROR, result.status(),
                "oversized drain is a source error");
        assertEquals(0, destination.mutations, "malformed drain never reaches destination");
        assertConserved(result);
    }

    private static void partialEscrowRecoveryPreservesExactVariant() {
        ExactFluidTransferCoordinator<Fluid> coordinator = new ExactFluidTransferCoordinator<>();
        TestPort source = new TestPort(new Fluid("minecraft:lava", "tag=retained", 600), 600);
        source.rejectFills = true;
        TestPort firstDestination = new TestPort(Fluid.empty(), 100);
        var transfer = coordinator.transfer(WRITER, operation(8), SOURCE, source, DESTINATION,
                firstDestination, "minecraft:lava", 600);
        assertEquals(500, transfer.escrowedMb(), "remainder enters escrow");
        UUID escrowId = coordinator.escrowEntries().keySet().iterator().next();

        DeviceCallContext admin = new DeviceCallContext(new UUID(0, 304), "admin",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE, DeviceCallContext.ESCROW_ADMIN));
        TestPort recoveryDestination = new TestPort(Fluid.empty(), 200);
        var denied = coordinator.recoverEscrow(WRITER, escrowId, recoveryDestination);
        assertEquals(ExactFluidTransferCoordinator.EscrowRecoveryStatus.PERMISSION_DENIED,
                denied.status(), "write authority does not imply fluid escrow administration");
        assertEquals(0, recoveryDestination.mutations, "denied recovery does not mutate destination");
        assertEquals(500, coordinator.escrowEntries().get(escrowId).payload().amountMb(),
                "denied recovery preserves escrow custody");
        var recovery = coordinator.recoverEscrow(admin, escrowId, recoveryDestination);
        assertEquals(ExactFluidTransferCoordinator.EscrowRecoveryStatus.PARTIAL, recovery.status(),
                "bounded destination produces partial recovery");
        assertEquals(new Fluid("minecraft:lava", "tag=retained", 200), recoveryDestination.stored,
                "recovered fluid preserves exact variant");
        assertEquals(new Fluid("minecraft:lava", "tag=retained", 300),
                coordinator.escrowEntries().get(escrowId).payload(),
                "remaining custody preserves exact variant");
    }

    private static void replayRetentionEvictsOldestRecordAtBound() {
        ExactFluidTransferCoordinator<Fluid> coordinator = new ExactFluidTransferCoordinator<>();
        TestPort source = new TestPort(Fluid.empty(), 0);
        TestPort destination = new TestPort(Fluid.empty(), 0);
        for (int index = 0; index <= ExactFluidTransferCoordinator.MAX_REPLAY_RECORDS; index++) {
            coordinator.transfer(WRITER, operation(1_000 + index), SOURCE, source, DESTINATION,
                    destination, "minecraft:water", 1);
        }
        assertEquals(ExactFluidTransferCoordinator.MAX_REPLAY_RECORDS,
                coordinator.snapshot().replays().size(), "replay retention remains bounded");
        assertTrue(coordinator.replayResult(WRITER, operation(1_000), SOURCE, DESTINATION,
                "minecraft:water", 1).isEmpty(), "oldest replay record is evicted first");
        assertTrue(coordinator.replayResult(WRITER,
                operation(1_000 + ExactFluidTransferCoordinator.MAX_REPLAY_RECORDS),
                SOURCE, DESTINATION, "minecraft:water", 1).isPresent(),
                "newest replay record remains available");
    }

    private static void escrowCapacityRejectsBeforeDrainWithoutEviction() {
        java.util.List<ExactFluidTransferCoordinator.EscrowEntry<Fluid>> escrow = new java.util.ArrayList<>();
        for (int index = 0; index < ExactFluidTransferCoordinator.MAX_ESCROW_ENTRIES; index++) {
            UUID id = operation(5_000L + index);
            escrow.add(new ExactFluidTransferCoordinator.EscrowEntry<>(id,
                    operation(6_000L + index), SOURCE, DESTINATION,
                    new Fluid("minecraft:water", "retained-" + index, 1)));
        }
        ExactFluidTransferCoordinator<Fluid> coordinator = new ExactFluidTransferCoordinator<>(
                new ExactFluidTransferCoordinator.Snapshot<>(java.util.List.of(), escrow), () -> {});
        TestPort source = new TestPort(new Fluid("minecraft:water", "new", 100), 100);
        TestPort destination = new TestPort(Fluid.empty(), 100);
        var result = coordinator.transfer(WRITER, operation(7_000L), SOURCE, source,
                DESTINATION, destination, "minecraft:water", 100);
        assertEquals(ExactFluidTransferCoordinator.Status.ESCROW_CAPACITY, result.status(),
                "full escrow rejects transfer");
        assertEquals(0, source.mutations + destination.mutations,
                "escrow capacity is checked before source drain");
        assertEquals(ExactFluidTransferCoordinator.MAX_ESCROW_ENTRIES,
                coordinator.escrowEntries().size(), "existing custody is never evicted");
    }

    private static final class TestPort implements ExactFluidTransferCoordinator.Port<Fluid> {
        private Fluid stored;
        private final int capacity;
        private boolean rejectFills;
        private boolean corruptRemainder;
        private int mutations;

        private TestPort(Fluid stored, int capacity) { this.stored = stored; this.capacity = capacity; }

        @Override public Fluid drain(String resourceId, int amountMb) {
            mutations++;
            if (stored.amountMb() == 0 || !stored.resource().equals(resourceId)) return Fluid.empty();
            int drained = Math.min(amountMb, stored.amountMb());
            Fluid result = stored.withAmount(drained);
            stored = stored.withAmount(stored.amountMb() - drained);
            return result;
        }

        @Override public Fluid fill(Fluid payload) {
            mutations++;
            if (rejectFills) throw new IllegalStateException("fill rejected");
            int accepted = Math.min(payload.amountMb(), Math.max(0, capacity - stored.amountMb()));
            if (accepted > 0) {
                if (stored.amountMb() > 0 && !sameVariant(stored, payload)) accepted = 0;
                else stored = payload.withAmount(stored.amountMb() + accepted);
            }
            String variant = corruptRemainder ? payload.variant() + "-corrupt" : payload.variant();
            return new Fluid(payload.resource(), variant, payload.amountMb() - accepted);
        }

        @Override public int amount(Fluid payload) { return payload.amountMb(); }
        @Override public boolean sameVariant(Fluid left, Fluid right) {
            return left.resource().equals(right.resource()) && left.variant().equals(right.variant());
        }
    }

    private record Fluid(String resource, String variant, int amountMb) {
        private Fluid { if (amountMb < 0) throw new IllegalArgumentException("negative fluid amount"); }
        private static Fluid empty() { return new Fluid("minecraft:empty", "", 0); }
        private Fluid withAmount(int amountMb) { return new Fluid(resource, variant, amountMb); }
    }

    private static UUID operation(long value) { return new UUID(0, value); }
    private static void assertConserved(ExactFluidTransferCoordinator.TransferResult result) {
        assertEquals(result.extractedMb(), result.insertedMb() + result.rolledBackMb() + result.escrowedMb(),
                "fluid transfer conserves extracted amount");
    }
    private static void assertTrue(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
