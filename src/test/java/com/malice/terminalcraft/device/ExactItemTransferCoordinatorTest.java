package com.malice.terminalcraft.device;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Headless conservation, rollback, escrow, authorization, and replay tests. */
public final class ExactItemTransferCoordinatorTest {
    private static final UUID SOURCE = UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID DESTINATION = UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final DeviceCallContext WRITER = new DeviceCallContext(
            UUID.fromString("00000000-0000-0000-0000-000000000203"), "writer",
            Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));

    private ExactItemTransferCoordinatorTest() {}

    public static void main(String[] args) {
        completeTransferPreservesVariant();
        partialTransferRollsBackExactRemainder();
        failedRollbackMovesExactRemainderToEscrow();
        replayIsIdempotentAndConflictsAreRejected();
        permissionAndEndpointGuardsRunBeforeMutation();
        invalidPortBehaviorCannotBreakConservationSilently();
        escrowRecoveryRequiresAdminAndPreservesRemainder();
        replayRetentionEvictsOldestDeterministically();
        escrowCapacityRejectsBeforeExtraction();
        System.out.println("Exact item transfer coordinator tests: OK");
    }

    private static void completeTransferPreservesVariant() {
        ExactItemTransferCoordinator<Payload> coordinator = new ExactItemTransferCoordinator<>();
        TestPort source = new TestPort(List.of(new Payload("minecraft:diamond_sword", "enchanted", 1)), 64);
        TestPort destination = new TestPort(List.of(), 64);
        ExactItemTransferCoordinator.TransferResult result = coordinator.transfer(WRITER, operation(1),
                SOURCE, source, DESTINATION, destination, "minecraft:diamond_sword", 1);
        assertEquals(ExactItemTransferCoordinator.Status.COMPLETE, result.status(), "complete status");
        assertTrue(result.complete(), "complete result");
        assertEquals(List.of(new Payload("minecraft:diamond_sword", "enchanted", 1)), destination.stored,
                "exact variant reaches destination");
        assertConserved(result);
    }

    private static void partialTransferRollsBackExactRemainder() {
        ExactItemTransferCoordinator<Payload> coordinator = new ExactItemTransferCoordinator<>();
        TestPort source = new TestPort(List.of(new Payload("minecraft:book", "signed-by-malice", 10)), 64);
        TestPort destination = new TestPort(List.of(), 4);
        ExactItemTransferCoordinator.TransferResult result = coordinator.transfer(WRITER, operation(2),
                SOURCE, source, DESTINATION, destination, "minecraft:book", 10);
        assertEquals(ExactItemTransferCoordinator.Status.PARTIAL, result.status(), "partial status");
        assertEquals(4, result.inserted(), "destination accepted bounded amount");
        assertEquals(6, result.rolledBack(), "remainder returned to source");
        assertEquals("signed-by-malice", source.stored.get(0).variant(), "rollback preserves metadata");
        assertConserved(result);
    }

    private static void failedRollbackMovesExactRemainderToEscrow() {
        ExactItemTransferCoordinator<Payload> coordinator = new ExactItemTransferCoordinator<>();
        TestPort source = new TestPort(List.of(new Payload("minecraft:potion", "healing", 8)), 64);
        source.rejectInserts = true;
        TestPort destination = new TestPort(List.of(), 3);
        ExactItemTransferCoordinator.TransferResult result = coordinator.transfer(WRITER, operation(3),
                SOURCE, source, DESTINATION, destination, "minecraft:potion", 8);
        assertEquals(ExactItemTransferCoordinator.Status.ESCROWED, result.status(), "escrow status");
        assertEquals(5, result.escrowed(), "unrestored payload escrowed");
        assertEquals(new Payload("minecraft:potion", "healing", 5),
                coordinator.escrowEntries().get(0).payload(), "escrow preserves exact payload");
        UUID escrowId = coordinator.escrowEntries().get(0).escrowId();
        TestPort recovery = new TestPort(List.of(), 64);
        DeviceCallContext admin = new DeviceCallContext(WRITER.principalId(), "admin",
                Set.of(DeviceCallContext.READ, DeviceCallContext.ESCROW_ADMIN));
        ExactItemTransferCoordinator.EscrowRecoveryResult recovered =
                coordinator.recoverEscrow(admin, escrowId, recovery);
        assertEquals(ExactItemTransferCoordinator.EscrowRecoveryStatus.COMPLETE, recovered.status(),
                "escrow recovery completes");
        assertEquals(5, recovered.inserted(), "escrow recovery inserts payload");
        assertEquals(0, coordinator.escrowEntries().size(), "escrow recovery frees capacity");
        assertConserved(result);
    }

    private static void replayIsIdempotentAndConflictsAreRejected() {
        ExactItemTransferCoordinator<Payload> coordinator = new ExactItemTransferCoordinator<>();
        TestPort source = new TestPort(List.of(new Payload("minecraft:iron_ingot", "plain", 20)), 64);
        TestPort destination = new TestPort(List.of(), 64);
        UUID operation = operation(4);
        ExactItemTransferCoordinator.TransferResult first = coordinator.transfer(WRITER, operation,
                SOURCE, source, DESTINATION, destination, "minecraft:iron_ingot", 7);
        int mutations = source.mutations + destination.mutations;
        ExactItemTransferCoordinator.TransferResult replay = coordinator.transfer(WRITER, operation,
                SOURCE, source, DESTINATION, destination, "minecraft:iron_ingot", 7);
        assertTrue(replay.replayed(), "duplicate request returns replay marker");
        assertEquals(first.inserted(), replay.inserted(), "duplicate returns original result");
        assertEquals(mutations, source.mutations + destination.mutations, "duplicate does not mutate");
        ExactItemTransferCoordinator.TransferResult conflict = coordinator.transfer(WRITER, operation,
                SOURCE, source, DESTINATION, destination, "minecraft:iron_ingot", 8);
        assertEquals(ExactItemTransferCoordinator.Status.OPERATION_CONFLICT, conflict.status(), "operation conflict");
        assertEquals(mutations, source.mutations + destination.mutations, "conflict does not mutate");
        DeviceCallContext service = DeviceCallContext.service(WRITER.principalId(), "writer-service",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        ExactItemTransferCoordinator.TransferResult separate = coordinator.transfer(service, operation,
                SOURCE, source, DESTINATION, destination, "minecraft:iron_ingot", 1);
        assertTrue(!separate.replayed(), "same UUID operation remains distinct across principal kinds");
        assertEquals(ExactItemTransferCoordinator.Status.COMPLETE, separate.status(),
                "cross-kind operation executes independently");
    }

    private static void permissionAndEndpointGuardsRunBeforeMutation() {
        ExactItemTransferCoordinator<Payload> coordinator = new ExactItemTransferCoordinator<>();
        TestPort source = new TestPort(List.of(new Payload("minecraft:coal", "plain", 4)), 64);
        TestPort destination = new TestPort(List.of(), 64);
        ExactItemTransferCoordinator.TransferResult denied = coordinator.transfer(
                DeviceCallContext.readOnly("reader"), operation(5), SOURCE, source,
                DESTINATION, destination, "minecraft:coal", 4);
        assertEquals(ExactItemTransferCoordinator.Status.PERMISSION_DENIED, denied.status(), "permission denied");
        ExactItemTransferCoordinator.TransferResult same = coordinator.transfer(WRITER, operation(6),
                SOURCE, source, SOURCE, destination, "minecraft:coal", 4);
        assertEquals(ExactItemTransferCoordinator.Status.SAME_ENDPOINT, same.status(), "same endpoint rejected");
        assertEquals(0, source.mutations + destination.mutations, "guards precede mutation");
    }

    private static void invalidPortBehaviorCannotBreakConservationSilently() {
        ExactItemTransferCoordinator<Payload> coordinator = new ExactItemTransferCoordinator<>();
        TestPort source = new TestPort(List.of(new Payload("minecraft:gold_ingot", "plain", 4)), 64);
        TestPort destination = new TestPort(List.of(), 0);
        destination.changeVariant = true;
        source.rejectInserts = true;
        ExactItemTransferCoordinator.TransferResult result = coordinator.transfer(WRITER, operation(7),
                SOURCE, source, DESTINATION, destination, "minecraft:gold_ingot", 4);
        assertEquals(ExactItemTransferCoordinator.Status.ESCROWED, result.status(), "invalid destination is escrowed");
        assertEquals(4, result.escrowed(), "full invalid payload retained");
        assertConserved(result);
    }


    private static void escrowRecoveryRequiresAdminAndPreservesRemainder() {
        ExactItemTransferCoordinator<Payload> coordinator = new ExactItemTransferCoordinator<>();
        TestPort source = new TestPort(List.of(new Payload("minecraft:book", "signed", 6)), 64);
        source.rejectInserts = true;
        TestPort blocked = new TestPort(List.of(), 0);
        coordinator.transfer(WRITER, operation(8), SOURCE, source, DESTINATION, blocked,
                "minecraft:book", 6);
        UUID escrowId = coordinator.escrowEntries().get(0).escrowId();
        TestPort recovery = new TestPort(List.of(), 2);
        assertEquals(ExactItemTransferCoordinator.EscrowRecoveryStatus.PERMISSION_DENIED,
                coordinator.recoverEscrow(WRITER, escrowId, recovery).status(), "write is not escrow admin");
        assertEquals(0, recovery.mutations, "denied recovery does not insert");
        DeviceCallContext admin = new DeviceCallContext(WRITER.principalId(), "admin",
                Set.of(DeviceCallContext.ESCROW_ADMIN));
        ExactItemTransferCoordinator.EscrowRecoveryResult partial =
                coordinator.recoverEscrow(admin, escrowId, recovery);
        assertEquals(ExactItemTransferCoordinator.EscrowRecoveryStatus.PARTIAL, partial.status(),
                "partial recovery status");
        assertEquals(2, partial.inserted(), "partial recovery inserted");
        assertEquals(4, partial.remaining(), "partial recovery retained");
        assertEquals(4, coordinator.escrowEntries().get(0).payload().amount(),
                "exact remainder stays in escrow");
    }

    private static void replayRetentionEvictsOldestDeterministically() {
        ExactItemTransferCoordinator<Payload> coordinator = new ExactItemTransferCoordinator<>();
        TestPort source = new TestPort(List.of(), 64);
        TestPort destination = new TestPort(List.of(), 64);
        DeviceCallContext reader = DeviceCallContext.readOnly("retention-reader");
        for (int index = 0; index <= ExactItemTransferCoordinator.MAX_REPLAY_RECORDS; index++) {
            ExactItemTransferCoordinator.TransferResult result = coordinator.transfer(reader,
                    operation(1_000L + index), SOURCE, source, DESTINATION, destination,
                    "minecraft:stone", 1);
            assertEquals(ExactItemTransferCoordinator.Status.PERMISSION_DENIED, result.status(),
                    "retention seed result");
        }
        assertEquals(ExactItemTransferCoordinator.MAX_REPLAY_RECORDS,
                coordinator.snapshot().replays().size(), "replay records stay bounded");
        assertTrue(coordinator.replayResult(reader, operation(1_000L), SOURCE, DESTINATION,
                "minecraft:stone", 1).isEmpty(), "oldest replay is evicted first");
        assertTrue(coordinator.replayResult(reader, operation(1_001L), SOURCE, DESTINATION,
                "minecraft:stone", 1).isPresent(), "next replay remains retained");
        assertEquals(0, source.mutations + destination.mutations,
                "retention exercise never reaches ports");
    }

    private static void escrowCapacityRejectsBeforeExtraction() {
        List<ExactItemTransferCoordinator.EscrowEntry<Payload>> escrow = new ArrayList<>();
        int occupied = ExactItemTransferCoordinator.MAX_ESCROW_PARTS
                - ExactItemTransferCoordinator.MAX_EXTRACTED_PARTS + 1;
        for (int index = 0; index < occupied; index++) {
            escrow.add(new ExactItemTransferCoordinator.EscrowEntry<>(operation(2_000L + index),
                    operation(3_000L + index), SOURCE, DESTINATION,
                    new Payload("minecraft:stone", "retained-" + index, 1)));
        }
        ExactItemTransferCoordinator<Payload> coordinator = new ExactItemTransferCoordinator<>(
                new ExactItemTransferCoordinator.Snapshot<>(List.of(), escrow), () -> {});
        TestPort source = new TestPort(
                List.of(new Payload("minecraft:stone", "new", 1)), 64);
        TestPort destination = new TestPort(List.of(), 64);
        ExactItemTransferCoordinator.TransferResult result = coordinator.transfer(WRITER,
                operation(4_000L), SOURCE, source, DESTINATION, destination,
                "minecraft:stone", 1);
        assertEquals(ExactItemTransferCoordinator.Status.ESCROW_CAPACITY, result.status(),
                "insufficient worst-case escrow capacity rejects transfer");
        assertEquals(0, source.mutations + destination.mutations,
                "escrow capacity is checked before extraction");
        assertEquals(occupied, coordinator.escrowEntries().size(),
                "existing custody is never evicted");
    }

    private static final class TestPort implements ExactItemTransferCoordinator.Port<Payload> {
        private final List<Payload> stored = new ArrayList<>();
        private final int capacity;
        private boolean rejectInserts;
        private boolean changeVariant;
        private int mutations;

        private TestPort(List<Payload> initial, int capacity) { this.stored.addAll(initial); this.capacity = capacity; }
        @Override public List<Payload> extract(String resourceId, int count, int maxParts) {
            List<Payload> result = new ArrayList<>();
            int remaining = count;
            for (int index = 0; index < stored.size() && remaining > 0 && result.size() < maxParts;) {
                Payload payload = stored.get(index);
                if (!payload.resource().equals(resourceId)) { index++; continue; }
                int taken = Math.min(remaining, payload.amount());
                result.add(payload.withAmount(taken));
                if (taken == payload.amount()) stored.remove(index);
                else { stored.set(index, payload.withAmount(payload.amount() - taken)); index++; }
                remaining -= taken;
                mutations++;
            }
            return result;
        }
        @Override public Payload insert(Payload payload) {
            mutations++;
            if (rejectInserts) throw new IllegalStateException("insertion rejected");
            int used = stored.stream().mapToInt(Payload::amount).sum();
            int accepted = Math.min(payload.amount(), Math.max(0, capacity - used));
            if (accepted > 0) stored.add(payload.withAmount(accepted));
            String variant = changeVariant ? payload.variant() + "-corrupted" : payload.variant();
            return new Payload(payload.resource(), variant, payload.amount() - accepted);
        }
        @Override public int amount(Payload payload) { return payload.amount(); }
        @Override public boolean sameVariant(Payload left, Payload right) {
            return left.resource().equals(right.resource()) && left.variant().equals(right.variant());
        }
    }

    private record Payload(String resource, String variant, int amount) {
        private Payload { if (amount < 0) throw new IllegalArgumentException("negative payload"); }
        private Payload withAmount(int amount) { return new Payload(resource, variant, amount); }
    }

    private static UUID operation(long value) { return new UUID(0L, value); }
    private static void assertConserved(ExactItemTransferCoordinator.TransferResult result) {
        assertEquals(result.extracted(), result.inserted() + result.rolledBack() + result.escrowed(),
                "transfer conserves extracted items");
    }
    private static void assertTrue(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual))
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
    }
}
