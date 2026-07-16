package com.malice.terminalcraft.device;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Coordinates exact-payload item movement without reducing variants to registry identifiers.
 * Calls must run on the logical server thread; the coordinator intentionally performs no locking.
 * Retention policy: replay records are bounded to the newest 256 entries in insertion order.
 * Escrow custody never expires automatically; it remains durable until authorized recovery.
 * A full escrow rejects new extraction rather than discarding an existing payload.
 */
public final class ExactItemTransferCoordinator<T> {
    public static final int MAX_TRANSFER_AMOUNT = GenericCapabilityDevice.MAX_TRANSFER_AMOUNT;
    public static final int MAX_EXTRACTED_PARTS = GenericCapabilityDevice.MAX_INVENTORY_SLOTS;
    public static final int MAX_REPLAY_RECORDS = 256;
    public static final int MAX_ESCROW_PARTS = 256;

    private final LinkedHashMap<ReplayKey, ReplayRecord> replayRecords = new LinkedHashMap<>();
    private final List<EscrowEntry<T>> escrow = new ArrayList<>();
    private final Runnable stateChanged;

    public ExactItemTransferCoordinator() { this(() -> {}); }
    public ExactItemTransferCoordinator(Runnable stateChanged) {
        this.stateChanged = Objects.requireNonNull(stateChanged, "stateChanged");
    }

    /** Restores bounded durable state. Payloads should already be defensive copies. */
    public ExactItemTransferCoordinator(Snapshot<T> snapshot, Runnable stateChanged) {
        this(stateChanged);
        Objects.requireNonNull(snapshot, "snapshot");
        for (ReplayEntry entry : snapshot.replays()) {
            if (replayRecords.size() == MAX_REPLAY_RECORDS) break;
            ReplayKey key = new ReplayKey(entry.principal(), entry.operationId());
            Request request = new Request(entry.sourceId(), entry.destinationId(), entry.resourceId(), entry.count());
            replayRecords.put(key, new ReplayRecord(request, entry.result()));
        }
        for (EscrowEntry<T> entry : snapshot.escrow()) {
            if (escrow.size() == MAX_ESCROW_PARTS) break;
            escrow.add(entry);
        }
    }

    /** A port preserves the complete payload identity, such as an ItemStack including its tag. */
    public interface Port<T> {
        List<T> extract(String resourceId, int count, int maxParts);
        T insert(T payload);
        int amount(T payload);
        boolean sameVariant(T left, T right);
    }

    /** Returns an authoritative prior result before callers attempt live endpoint resolution. */
    public synchronized Optional<TransferResult> replayResult(DeviceCallContext context, UUID operationId,
                                                               UUID sourceId, UUID destinationId,
                                                               String resourceId, int count) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(destinationId, "destinationId");
        requireRequest(resourceId, count);
        ReplayRecord previous = replayRecords.get(new ReplayKey(context.principal(), operationId));
        if (previous == null) return Optional.empty();
        Request request = new Request(sourceId, destinationId, resourceId, count);
        return Optional.of(previous.request().equals(request)
                ? previous.result().asReplay()
                : TransferResult.failure(Status.OPERATION_CONFLICT, count, true));
    }

    public synchronized TransferResult transfer(DeviceCallContext context, UUID operationId,
                                                UUID sourceId, Port<T> source,
                                                UUID destinationId, Port<T> destination,
                                                String resourceId, int count) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(destinationId, "destinationId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        requireRequest(resourceId, count);

        Optional<TransferResult> replay = replayResult(context, operationId, sourceId,
                destinationId, resourceId, count);
        if (replay.isPresent()) return replay.orElseThrow();
        ReplayKey replayKey = new ReplayKey(context.principal(), operationId);
        Request request = new Request(sourceId, destinationId, resourceId, count);
        if (!DeviceAuthorization.allows(context, DeviceAuthorization.Action.MUTATE)) {
            return remember(replayKey, request, TransferResult.failure(Status.PERMISSION_DENIED, count, false));
        }
        if (sourceId.equals(destinationId)) {
            return remember(replayKey, request, TransferResult.failure(Status.SAME_ENDPOINT, count, false));
        }
        if (MAX_ESCROW_PARTS - escrow.size() < MAX_EXTRACTED_PARTS) {
            return remember(replayKey, request, TransferResult.failure(Status.ESCROW_CAPACITY, count, false));
        }

        List<T> extracted;
        try {
            extracted = List.copyOf(Objects.requireNonNull(
                    source.extract(resourceId, count, MAX_EXTRACTED_PARTS), "extracted payloads"));
            validateExtracted(source, extracted, count);
        } catch (RuntimeException exception) {
            return remember(replayKey, request, TransferResult.failure(Status.SOURCE_ERROR, count, false));
        }

        int extractedAmount = total(source, extracted);
        int insertedAmount = 0;
        int rolledBackAmount = 0;
        int escrowedAmount = 0;
        Status status = Status.COMPLETE;

        for (int index = 0; index < extracted.size(); index++) {
            T payload = extracted.get(index);
            T remainder;
            try {
                remainder = checkedRemainder(destination, payload, destination.insert(payload));
                insertedAmount += source.amount(payload) - destination.amount(remainder);
            } catch (RuntimeException exception) {
                remainder = payload;
                status = Status.DESTINATION_ERROR;
            }
            if (destination.amount(remainder) > 0) {
                Rollback rollback = rollback(source, remainder, operationId, sourceId, destinationId);
                rolledBackAmount += rollback.restored();
                escrowedAmount += rollback.escrowed();
                if (status == Status.COMPLETE) status = rollback.escrowed() == 0 ? Status.PARTIAL : Status.ESCROWED;
            }
            if (status == Status.DESTINATION_ERROR) {
                for (int remaining = index + 1; remaining < extracted.size(); remaining++) {
                    Rollback rollback = rollback(source, extracted.get(remaining), operationId, sourceId, destinationId);
                    rolledBackAmount += rollback.restored();
                    escrowedAmount += rollback.escrowed();
                }
                if (escrowedAmount > 0) status = Status.ESCROWED;
                break;
            }
        }

        if (extractedAmount < count && status == Status.COMPLETE) status = Status.PARTIAL;
        if (insertedAmount + rolledBackAmount + escrowedAmount != extractedAmount)
            throw new IllegalStateException("item transfer violated conservation");
        return remember(replayKey, request, new TransferResult(status, count, extractedAmount,
                insertedAmount, rolledBackAmount, escrowedAmount, false));
    }

    public synchronized List<EscrowEntry<T>> escrowEntries() { return List.copyOf(escrow); }

    /** Inserts an escrow payload before removing or shrinking server custody. */
    public synchronized EscrowRecoveryResult recoverEscrow(DeviceCallContext context, UUID escrowId,
                                                             Port<T> destination) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(escrowId, "escrowId");
        Objects.requireNonNull(destination, "destination");
        if (!DeviceAuthorization.allows(context, DeviceAuthorization.Action.ESCROW_ADMIN)) {
            return new EscrowRecoveryResult(EscrowRecoveryStatus.PERMISSION_DENIED, 0, 0);
        }
        int index = -1;
        for (int candidate = 0; candidate < escrow.size(); candidate++) {
            if (escrow.get(candidate).escrowId().equals(escrowId)) {
                index = candidate;
                break;
            }
        }
        if (index < 0) return new EscrowRecoveryResult(EscrowRecoveryStatus.NOT_FOUND, 0, 0);

        EscrowEntry<T> entry = escrow.get(index);
        int originalAmount = destination.amount(entry.payload());
        T remainder;
        try {
            remainder = checkedRemainder(destination, entry.payload(), destination.insert(entry.payload()));
        } catch (RuntimeException exception) {
            return new EscrowRecoveryResult(EscrowRecoveryStatus.DESTINATION_ERROR, 0, originalAmount);
        }
        int remaining = destination.amount(remainder);
        int inserted = originalAmount - remaining;
        if (inserted == 0) {
            return new EscrowRecoveryResult(EscrowRecoveryStatus.NO_CAPACITY, 0, originalAmount);
        }
        if (remaining == 0) {
            escrow.remove(index);
        } else {
            escrow.set(index, new EscrowEntry<>(entry.escrowId(), entry.operationId(),
                    entry.sourceId(), entry.destinationId(), remainder));
        }
        stateChanged.run();
        return new EscrowRecoveryResult(remaining == 0 ? EscrowRecoveryStatus.COMPLETE
                : EscrowRecoveryStatus.PARTIAL, inserted, remaining);
    }

    public synchronized Snapshot<T> snapshot() {
        List<ReplayEntry> replays = new ArrayList<>(replayRecords.size());
        for (Map.Entry<ReplayKey, ReplayRecord> entry : replayRecords.entrySet()) {
            ReplayKey key = entry.getKey();
            ReplayRecord value = entry.getValue();
            Request request = value.request();
            replays.add(new ReplayEntry(key.principal(), key.operationId(), request.sourceId(),
                    request.destinationId(), request.resourceId(), request.count(), value.result()));
        }
        return new Snapshot<>(replays, escrow);
    }

    private Rollback rollback(Port<T> source, T payload, UUID operationId, UUID sourceId, UUID destinationId) {
        int amount = source.amount(payload);
        if (amount == 0) return new Rollback(0, 0);
        try {
            T remainder = checkedRemainder(source, payload, source.insert(payload));
            int escrowed = source.amount(remainder);
            if (escrowed > 0) {
                escrow.add(new EscrowEntry<>(UUID.randomUUID(), operationId, sourceId, destinationId, remainder));
                stateChanged.run();
            }
            return new Rollback(amount - escrowed, escrowed);
        } catch (RuntimeException exception) {
            escrow.add(new EscrowEntry<>(UUID.randomUUID(), operationId, sourceId, destinationId, payload));
            stateChanged.run();
            return new Rollback(0, amount);
        }
    }

    private T checkedRemainder(Port<T> port, T original, T remainder) {
        Objects.requireNonNull(remainder, "insert remainder");
        int originalAmount = port.amount(original);
        int remainderAmount = port.amount(remainder);
        if (originalAmount < 0 || remainderAmount < 0 || remainderAmount > originalAmount)
            throw new IllegalStateException("port returned an invalid remainder amount");
        if (remainderAmount > 0 && !port.sameVariant(original, remainder))
            throw new IllegalStateException("port changed exact item identity");
        return remainder;
    }

    private static <T> void validateExtracted(Port<T> source, List<T> extracted, int requested) {
        if (extracted.size() > MAX_EXTRACTED_PARTS) throw new IllegalStateException("source returned too many payload parts");
        int total = 0;
        for (T payload : extracted) {
            Objects.requireNonNull(payload, "extracted payload");
            int amount = source.amount(payload);
            if (amount < 1 || total > requested - amount)
                throw new IllegalStateException("source returned an invalid extracted amount");
            total += amount;
        }
    }

    private static <T> int total(Port<T> port, List<T> payloads) {
        int total = 0;
        for (T payload : payloads) total += port.amount(payload);
        return total;
    }

    private TransferResult remember(ReplayKey key, Request request, TransferResult result) {
        replayRecords.put(key, new ReplayRecord(request, result));
        while (replayRecords.size() > MAX_REPLAY_RECORDS)
            replayRecords.remove(replayRecords.keySet().iterator().next());
        stateChanged.run();
        return result;
    }

    private static void requireRequest(String resourceId, int count) {
        requireResource(resourceId);
        if (count < 1 || count > MAX_TRANSFER_AMOUNT)
            throw new IllegalArgumentException("count must be from 1 to " + MAX_TRANSFER_AMOUNT);
    }

    private static void requireResource(String resourceId) {
        if (resourceId == null || !resourceId.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+"))
            throw new IllegalArgumentException("resource must be a namespaced identifier");
    }

    public enum Status {
        COMPLETE, PARTIAL, ESCROWED, PERMISSION_DENIED, SAME_ENDPOINT,
        OPERATION_CONFLICT, ESCROW_CAPACITY, SOURCE_ERROR, DESTINATION_ERROR
    }

    public record TransferResult(Status status, int requested, int extracted, int inserted,
                                 int rolledBack, int escrowed, boolean replayed) {
        public TransferResult {
            Objects.requireNonNull(status, "status");
            if (requested < 0 || extracted < 0 || inserted < 0 || rolledBack < 0 || escrowed < 0
                    || extracted > requested || inserted + rolledBack + escrowed != extracted)
                throw new IllegalArgumentException("invalid exact item transfer result");
        }
        public boolean complete() { return status == Status.COMPLETE && inserted == requested; }
        private TransferResult asReplay() {
            return new TransferResult(status, requested, extracted, inserted, rolledBack, escrowed, true);
        }
        private static TransferResult failure(Status status, int requested, boolean replayed) {
            return new TransferResult(status, requested, 0, 0, 0, 0, replayed);
        }
    }

    public record ReplayEntry(PrincipalIdentity principal, UUID operationId, UUID sourceId,
                              UUID destinationId, String resourceId, int count, TransferResult result) {
        public ReplayEntry {
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(destinationId, "destinationId");
            requireResource(resourceId);
            if (count < 1 || count > MAX_TRANSFER_AMOUNT) throw new IllegalArgumentException("invalid replay count");
            Objects.requireNonNull(result, "result");
            if (result.requested() != count) throw new IllegalArgumentException("replay request/result mismatch");
        }
    }

    public record Snapshot<T>(List<ReplayEntry> replays, List<EscrowEntry<T>> escrow) {
        public Snapshot {
            replays = List.copyOf(Objects.requireNonNull(replays, "replays"));
            escrow = List.copyOf(Objects.requireNonNull(escrow, "escrow"));
            if (replays.size() > MAX_REPLAY_RECORDS || escrow.size() > MAX_ESCROW_PARTS)
                throw new IllegalArgumentException("transfer snapshot exceeds bounds");
        }
    }

    public enum EscrowRecoveryStatus {
        COMPLETE, PARTIAL, NO_CAPACITY, PERMISSION_DENIED, NOT_FOUND, DESTINATION_ERROR
    }

    public record EscrowRecoveryResult(EscrowRecoveryStatus status, int inserted, int remaining) {
        public EscrowRecoveryResult {
            Objects.requireNonNull(status, "status");
            if (inserted < 0 || remaining < 0) throw new IllegalArgumentException("negative recovery amount");
        }
    }

    public record EscrowEntry<T>(UUID escrowId, UUID operationId, UUID sourceId, UUID destinationId, T payload) {
        public EscrowEntry {
            Objects.requireNonNull(escrowId, "escrowId");
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(destinationId, "destinationId");
            Objects.requireNonNull(payload, "payload");
        }
    }

    private record ReplayKey(PrincipalIdentity principal, UUID operationId) {}
    private record Request(UUID sourceId, UUID destinationId, String resourceId, int count) {}
    private record ReplayRecord(Request request, TransferResult result) {}
    private record Rollback(int restored, int escrowed) {}
}
