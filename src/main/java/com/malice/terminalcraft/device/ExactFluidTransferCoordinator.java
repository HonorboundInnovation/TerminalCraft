package com.malice.terminalcraft.device;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Coordinates exact-identity fluid movement with replay, rollback, and durable-ready escrow.
 * Retention policy: replay records are bounded to the newest 256 entries in insertion order.
 * Escrow custody never expires automatically; it remains durable until authorized recovery.
 * A full escrow rejects new extraction rather than discarding an existing payload.
 */
public final class ExactFluidTransferCoordinator<T> {
    public static final int MAX_TRANSFER_AMOUNT = GenericCapabilityDevice.MAX_TRANSFER_AMOUNT;
    public static final int MAX_REPLAY_RECORDS = 256;
    public static final int MAX_ESCROW_ENTRIES = 256;

    private final LinkedHashMap<ReplayKey, ReplayRecord> replayRecords = new LinkedHashMap<>();
    private final LinkedHashMap<UUID, EscrowEntry<T>> escrow = new LinkedHashMap<>();
    private final Runnable stateChanged;

    public ExactFluidTransferCoordinator() { this(() -> {}); }

    public ExactFluidTransferCoordinator(Runnable stateChanged) {
        this.stateChanged = Objects.requireNonNull(stateChanged, "stateChanged");
    }

    /** Restores bounded durable state. Payloads must already be defensive copies. */
    public ExactFluidTransferCoordinator(Snapshot<T> snapshot, Runnable stateChanged) {
        this(stateChanged);
        Objects.requireNonNull(snapshot, "snapshot");
        for (ReplayEntry entry : snapshot.replays()) {
            ReplayKey key = new ReplayKey(entry.principal(), entry.operationId());
            Request request = new Request(entry.sourceId(), entry.destinationId(),
                    entry.resourceId(), entry.amountMb());
            replayRecords.put(key, new ReplayRecord(request, entry.result()));
        }
        for (EscrowEntry<T> entry : snapshot.escrow()) escrow.put(entry.escrowId(), entry);
    }

    /** A port must preserve complete fluid identity, including any tag/components. */
    public interface Port<T> {
        T drain(String resourceId, int amountMb);
        T fill(T payload);
        int amount(T payload);
        boolean sameVariant(T left, T right);
    }

    public synchronized Optional<TransferResult> replayResult(DeviceCallContext context, UUID operationId,
                                                               UUID sourceId, UUID destinationId,
                                                               String resourceId, int amountMb) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(destinationId, "destinationId");
        requireRequest(resourceId, amountMb);
        ReplayRecord previous = replayRecords.get(new ReplayKey(context.principal(), operationId));
        if (previous == null) return Optional.empty();
        Request request = new Request(sourceId, destinationId, resourceId, amountMb);
        return Optional.of(previous.request().equals(request)
                ? previous.result().asReplay()
                : TransferResult.failure(Status.OPERATION_CONFLICT, amountMb, true));
    }

    public synchronized TransferResult transfer(DeviceCallContext context, UUID operationId,
                                                 UUID sourceId, Port<T> source,
                                                 UUID destinationId, Port<T> destination,
                                                 String resourceId, int amountMb) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(destinationId, "destinationId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        requireRequest(resourceId, amountMb);

        Optional<TransferResult> replay = replayResult(context, operationId, sourceId,
                destinationId, resourceId, amountMb);
        if (replay.isPresent()) return replay.orElseThrow();
        ReplayKey key = new ReplayKey(context.principal(), operationId);
        Request request = new Request(sourceId, destinationId, resourceId, amountMb);
        if (!DeviceAuthorization.allows(context, DeviceAuthorization.Action.MUTATE)) {
            return remember(key, request, TransferResult.failure(Status.PERMISSION_DENIED, amountMb, false));
        }
        if (sourceId.equals(destinationId)) {
            return remember(key, request, TransferResult.failure(Status.SAME_ENDPOINT, amountMb, false));
        }
        if (escrow.size() >= MAX_ESCROW_ENTRIES) {
            return remember(key, request, TransferResult.failure(Status.ESCROW_CAPACITY, amountMb, false));
        }

        T extracted;
        try {
            extracted = Objects.requireNonNull(source.drain(resourceId, amountMb), "drained fluid");
            validatePayload(source, extracted, amountMb, null);
        } catch (RuntimeException exception) {
            return remember(key, request, TransferResult.failure(Status.SOURCE_ERROR, amountMb, false));
        }

        int extractedAmount = source.amount(extracted);
        if (extractedAmount == 0) {
            return remember(key, request, new TransferResult(Status.PARTIAL, amountMb, 0, 0, 0, 0, false));
        }

        T destinationRemainder;
        Status status = Status.COMPLETE;
        try {
            destinationRemainder = checkedRemainder(destination, extracted, destination.fill(extracted));
        } catch (RuntimeException exception) {
            destinationRemainder = extracted;
            status = Status.DESTINATION_ERROR;
        }

        int remainderAmount = destination.amount(destinationRemainder);
        int insertedAmount = extractedAmount - remainderAmount;
        int rolledBackAmount = 0;
        int escrowedAmount = 0;
        if (remainderAmount > 0) {
            try {
                T rollbackRemainder = checkedRemainder(source, destinationRemainder,
                        source.fill(destinationRemainder));
                escrowedAmount = source.amount(rollbackRemainder);
                rolledBackAmount = remainderAmount - escrowedAmount;
                if (escrowedAmount > 0) addEscrow(operationId, sourceId, destinationId, rollbackRemainder);
            } catch (RuntimeException exception) {
                escrowedAmount = remainderAmount;
                addEscrow(operationId, sourceId, destinationId, destinationRemainder);
            }
            if (escrowedAmount > 0) status = Status.ESCROWED;
            else if (status == Status.COMPLETE) status = Status.PARTIAL;
        }
        if (extractedAmount < amountMb && status == Status.COMPLETE) status = Status.PARTIAL;
        if (insertedAmount + rolledBackAmount + escrowedAmount != extractedAmount) {
            throw new IllegalStateException("fluid transfer violated conservation");
        }
        return remember(key, request, new TransferResult(status, amountMb, extractedAmount,
                insertedAmount, rolledBackAmount, escrowedAmount, false));
    }

    public synchronized Map<UUID, EscrowEntry<T>> escrowEntries() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(escrow));
    }

    /** Inserts an escrow payload before removing or shrinking server custody. */
    public synchronized EscrowRecoveryResult recoverEscrow(DeviceCallContext context, UUID escrowId,
                                                            Port<T> destination) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(escrowId, "escrowId");
        Objects.requireNonNull(destination, "destination");
        if (!DeviceAuthorization.allows(context, DeviceAuthorization.Action.ESCROW_ADMIN)) {
            return new EscrowRecoveryResult(EscrowRecoveryStatus.PERMISSION_DENIED, 0, 0);
        }
        EscrowEntry<T> entry = escrow.get(escrowId);
        if (entry == null) return new EscrowRecoveryResult(EscrowRecoveryStatus.NOT_FOUND, 0, 0);

        int originalAmount = destination.amount(entry.payload());
        T remainder;
        try {
            remainder = checkedRemainder(destination, entry.payload(), destination.fill(entry.payload()));
        } catch (RuntimeException exception) {
            return new EscrowRecoveryResult(EscrowRecoveryStatus.DESTINATION_ERROR, 0, originalAmount);
        }
        int remaining = destination.amount(remainder);
        int inserted = originalAmount - remaining;
        if (inserted == 0) {
            return new EscrowRecoveryResult(EscrowRecoveryStatus.NO_CAPACITY, 0, originalAmount);
        }
        if (remaining == 0) escrow.remove(escrowId);
        else escrow.put(escrowId, new EscrowEntry<>(entry.escrowId(), entry.operationId(),
                entry.sourceId(), entry.destinationId(), remainder));
        stateChanged.run();
        return new EscrowRecoveryResult(remaining == 0 ? EscrowRecoveryStatus.COMPLETE
                : EscrowRecoveryStatus.PARTIAL, inserted, remaining);
    }

    public synchronized Snapshot<T> snapshot() {
        List<ReplayEntry> replays = new ArrayList<>(replayRecords.size());
        for (Map.Entry<ReplayKey, ReplayRecord> entry : replayRecords.entrySet()) {
            ReplayKey key = entry.getKey();
            Request request = entry.getValue().request();
            replays.add(new ReplayEntry(key.principal(), key.operationId(), request.sourceId(),
                    request.destinationId(), request.resourceId(), request.amountMb(),
                    entry.getValue().result()));
        }
        return new Snapshot<>(replays, new ArrayList<>(escrow.values()));
    }

    private void addEscrow(UUID operationId, UUID sourceId, UUID destinationId, T payload) {
        if (escrow.size() >= MAX_ESCROW_ENTRIES) throw new IllegalStateException("fluid escrow capacity exhausted");
        UUID escrowId = UUID.randomUUID();
        escrow.put(escrowId, new EscrowEntry<>(escrowId, operationId, sourceId, destinationId, payload));
        stateChanged.run();
    }

    private static <T> void validatePayload(Port<T> port, T payload, int maximum, T expectedVariant) {
        int amount = port.amount(payload);
        if (amount < 0 || amount > maximum) throw new IllegalStateException("port returned invalid fluid amount");
        if (amount > 0 && expectedVariant != null && !port.sameVariant(expectedVariant, payload)) {
            throw new IllegalStateException("port changed exact fluid identity");
        }
    }

    private static <T> T checkedRemainder(Port<T> port, T original, T remainder) {
        Objects.requireNonNull(remainder, "fluid remainder");
        validatePayload(port, remainder, port.amount(original), original);
        return remainder;
    }

    private TransferResult remember(ReplayKey key, Request request, TransferResult result) {
        replayRecords.put(key, new ReplayRecord(request, result));
        while (replayRecords.size() > MAX_REPLAY_RECORDS) {
            replayRecords.remove(replayRecords.keySet().iterator().next());
        }
        stateChanged.run();
        return result;
    }

    private static void requireRequest(String resourceId, int amountMb) {
        if (resourceId == null || !resourceId.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")) {
            throw new IllegalArgumentException("resource must be a namespaced identifier");
        }
        if (amountMb < 1 || amountMb > MAX_TRANSFER_AMOUNT) {
            throw new IllegalArgumentException("amount must be from 1 to " + MAX_TRANSFER_AMOUNT + " mB");
        }
    }

    public enum Status {
        COMPLETE, PARTIAL, ESCROWED, PERMISSION_DENIED, SAME_ENDPOINT,
        OPERATION_CONFLICT, ESCROW_CAPACITY, SOURCE_ERROR, DESTINATION_ERROR
    }

    public record TransferResult(Status status, int requestedMb, int extractedMb, int insertedMb,
                                 int rolledBackMb, int escrowedMb, boolean replayed) {
        public TransferResult {
            Objects.requireNonNull(status, "status");
            if (requestedMb < 0 || extractedMb < 0 || insertedMb < 0 || rolledBackMb < 0
                    || escrowedMb < 0 || extractedMb > requestedMb
                    || insertedMb + rolledBackMb + escrowedMb != extractedMb) {
                throw new IllegalArgumentException("invalid exact fluid transfer result");
            }
        }
        public boolean complete() { return status == Status.COMPLETE && insertedMb == requestedMb; }
        private TransferResult asReplay() {
            return new TransferResult(status, requestedMb, extractedMb, insertedMb,
                    rolledBackMb, escrowedMb, true);
        }
        private static TransferResult failure(Status status, int requestedMb, boolean replayed) {
            return new TransferResult(status, requestedMb, 0, 0, 0, 0, replayed);
        }
    }

    public record ReplayEntry(PrincipalIdentity principal, UUID operationId, UUID sourceId,
                              UUID destinationId, String resourceId, int amountMb,
                              TransferResult result) {
        public ReplayEntry {
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(destinationId, "destinationId");
            requireRequest(resourceId, amountMb);
            Objects.requireNonNull(result, "result");
            if (result.requestedMb() != amountMb) throw new IllegalArgumentException("replay mismatch");
        }
    }

    public record Snapshot<T>(List<ReplayEntry> replays, List<EscrowEntry<T>> escrow) {
        public Snapshot {
            replays = List.copyOf(Objects.requireNonNull(replays, "replays"));
            escrow = List.copyOf(Objects.requireNonNull(escrow, "escrow"));
            if (replays.size() > MAX_REPLAY_RECORDS || escrow.size() > MAX_ESCROW_ENTRIES) {
                throw new IllegalArgumentException("fluid transfer snapshot exceeds bounds");
            }
        }
    }

    public enum EscrowRecoveryStatus {
        COMPLETE, PARTIAL, NO_CAPACITY, PERMISSION_DENIED, NOT_FOUND, DESTINATION_ERROR
    }

    public record EscrowRecoveryResult(EscrowRecoveryStatus status, int insertedMb, int remainingMb) {
        public EscrowRecoveryResult {
            Objects.requireNonNull(status, "status");
            if (insertedMb < 0 || remainingMb < 0) throw new IllegalArgumentException("negative recovery amount");
        }
    }

    public record EscrowEntry<T>(UUID escrowId, UUID operationId, UUID sourceId,
                                 UUID destinationId, T payload) {
        public EscrowEntry {
            Objects.requireNonNull(escrowId, "escrowId");
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(destinationId, "destinationId");
            Objects.requireNonNull(payload, "payload");
        }
    }

    private record ReplayKey(PrincipalIdentity principal, UUID operationId) {}
    private record Request(UUID sourceId, UUID destinationId, String resourceId, int amountMb) {}
    private record ReplayRecord(Request request, TransferResult result) {}
}
