package com.malice.terminalcraft.device;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Bounded durable correlation ledger for non-idempotent native crafting submissions.
 * A persisted submission without a native task ID is ambiguous and is never automatically retried.
 */
public final class DurableCraftingLedger {
    public static final int FORMAT_VERSION = 2;
    private static final int LEGACY_FORMAT_VERSION = 1;
    public static final int MAX_JOBS = 128;
    private final Map<UUID, Entry> byJob = new LinkedHashMap<>();
    private final Map<OperationKey, UUID> byOperation = new LinkedHashMap<>();

    public ReserveResult reserve(DeviceCallContext caller, GenericCraftingService.Submission request,
                                 long updatedAt) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(request, "request");
        OperationKey key = new OperationKey(caller.principal(), request.operationId());
        UUID existingId = byOperation.get(key);
        if (existingId != null) {
            Entry existing = byJob.get(existingId);
            boolean same = existing.resourceId().equals(request.resourceId())
                    && existing.amount() == request.amount();
            return new ReserveResult(same ? ReserveDisposition.REPLAYED : ReserveDisposition.CONFLICT,
                    existing);
        }
        if (byJob.size() >= MAX_JOBS) return new ReserveResult(ReserveDisposition.FULL, null);
        Entry entry = new Entry(UUID.randomUUID(), request.operationId(), caller.principal(),
                request.resourceId(), request.amount(), null, GenericCraftingService.State.SUBMITTING,
                0, 0, "", "", updatedAt);
        put(entry);
        return new ReserveResult(ReserveDisposition.RESERVED, entry);
    }

    /** Records the authoritative native ID immediately after request/start succeeds. */
    public Entry confirmNative(UUID jobId, UUID nativeTaskId, long updatedAt) {
        Entry current = require(jobId);
        if (current.nativeTaskId() != null && !current.nativeTaskId().equals(nativeTaskId)) {
            throw new IllegalStateException("crafting job is already correlated to another native task");
        }
        return replace(current.withNative(nativeTaskId, GenericCraftingService.State.QUEUED, updatedAt));
    }

    /** Marks the crash window conservatively; callers must reconcile and must not resubmit. */
    public Entry markAmbiguous(UUID jobId, String error, long updatedAt) {
        Entry current = require(jobId);
        return replace(current.withState(GenericCraftingService.State.RECONCILING,
                current.completedWork(), current.totalWork(), "", bounded(error), updatedAt));
    }

    public Entry update(UUID jobId, GenericCraftingService.State state, long completed, long total,
                        String terminalResult, String error, long updatedAt) {
        Entry current = require(jobId);
        if (current.state().terminal()) return current;
        return replace(current.withState(state, completed, total, bounded(terminalResult),
                bounded(error), updatedAt));
    }

    public Optional<Entry> findOwned(UUID jobId, PrincipalIdentity principal) {
        Entry entry = byJob.get(jobId);
        return entry != null && DeviceAuthorization.owns(principal, entry.principal())
                ? Optional.of(entry) : Optional.empty();
    }

    /** Compatibility lookup for legacy callers, interpreted strictly as a player identity. */
    public Optional<Entry> findOwned(UUID jobId, UUID principalId) {
        Entry entry = byJob.get(jobId);
        return entry != null && entry.principal().kind() == PrincipalIdentity.Kind.PLAYER
                && entry.principal().id().equals(principalId) ? Optional.of(entry) : Optional.empty();
    }

    public int size() { return byJob.size(); }

    public CompoundTag save() {
        CompoundTag root = new CompoundTag();
        root.putInt("version", FORMAT_VERSION);
        ListTag jobs = new ListTag();
        for (Entry entry : byJob.values()) jobs.add(saveEntry(entry));
        root.put("jobs", jobs);
        return root;
    }

    public static DurableCraftingLedger load(CompoundTag root) {
        DurableCraftingLedger ledger = new DurableCraftingLedger();
        if (root == null) return ledger;
        int version = root.getInt("version");
        if (version != FORMAT_VERSION && version != LEGACY_FORMAT_VERSION) return ledger;
        ListTag jobs = root.getList("jobs", Tag.TAG_COMPOUND);
        for (int index = 0; index < Math.min(jobs.size(), MAX_JOBS); index++) {
            try {
                Entry entry = loadEntry(jobs.getCompound(index), version);
                // A crash before durable native correlation is ambiguous, never retryable submission work.
                if (entry.state() == GenericCraftingService.State.SUBMITTING && entry.nativeTaskId() == null) {
                    entry = entry.withState(GenericCraftingService.State.RECONCILING, 0, 0, "",
                            "native submission outcome is ambiguous after reload", entry.updatedAt());
                }
                OperationKey key = new OperationKey(entry.principal(), entry.operationId());
                if (!ledger.byOperation.containsKey(key) && !ledger.byJob.containsKey(entry.jobId())) {
                    ledger.put(entry);
                }
            } catch (RuntimeException ignored) {
                // Malformed provider records are isolated; no native work is started during loading.
            }
        }
        return ledger;
    }

    private void put(Entry entry) {
        byJob.put(entry.jobId(), entry);
        byOperation.put(new OperationKey(entry.principal(), entry.operationId()), entry.jobId());
    }

    private Entry replace(Entry entry) { put(entry); return entry; }
    private Entry require(UUID id) {
        Entry entry = byJob.get(Objects.requireNonNull(id, "jobId"));
        if (entry == null) throw new IllegalArgumentException("crafting job not found");
        return entry;
    }

    private static CompoundTag saveEntry(Entry entry) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("job", entry.jobId()); tag.putUUID("operation", entry.operationId());
        tag.putUUID("principal", entry.principal().id());
        tag.putString("principalKind", entry.principal().kind().serializedName());
        tag.putString("principalName", entry.principal().name());
        tag.putString("resource", entry.resourceId());
        tag.putLong("amount", entry.amount());
        if (entry.nativeTaskId() != null) tag.putUUID("native", entry.nativeTaskId());
        tag.putString("state", entry.state().name()); tag.putLong("completed", entry.completedWork());
        tag.putLong("total", entry.totalWork()); tag.putString("result", entry.terminalResult());
        tag.putString("error", entry.lastError()); tag.putLong("updated", entry.updatedAt());
        return tag;
    }

    private static Entry loadEntry(CompoundTag tag, int version) {
        UUID principalId = tag.getUUID("principal");
        PrincipalIdentity principal;
        if (version == LEGACY_FORMAT_VERSION) {
            principal = PrincipalIdentity.player(principalId, "legacy-player");
        } else {
            PrincipalIdentity.Kind kind = PrincipalIdentity.Kind.parse(tag.getString("principalKind"));
            String name = tag.getString("principalName");
            if (name.isBlank()) throw new IllegalArgumentException("missing principal name");
            principal = new PrincipalIdentity(kind, principalId, name);
        }
        UUID nativeId = tag.hasUUID("native") ? tag.getUUID("native") : null;
        return new Entry(tag.getUUID("job"), tag.getUUID("operation"), principal,
                tag.getString("resource"), tag.getLong("amount"), nativeId,
                GenericCraftingService.State.valueOf(tag.getString("state")),
                tag.getLong("completed"), tag.getLong("total"), tag.getString("result"),
                tag.getString("error"), tag.getLong("updated"));
    }

    private static String bounded(String value) {
        value = value == null ? "" : value;
        return value.length() <= GenericCraftingService.MAX_ERROR_LENGTH
                ? value : value.substring(0, GenericCraftingService.MAX_ERROR_LENGTH);
    }

    public enum ReserveDisposition { RESERVED, REPLAYED, CONFLICT, FULL }
    public record ReserveResult(ReserveDisposition disposition, Entry entry) {}
    private record OperationKey(PrincipalIdentity principal, UUID operationId) {}

    public record Entry(UUID jobId, UUID operationId, PrincipalIdentity principal, String resourceId, long amount,
                        UUID nativeTaskId, GenericCraftingService.State state, long completedWork,
                        long totalWork, String terminalResult, String lastError, long updatedAt) {
        public Entry {
            // Reuse the generic contract's strict validation and bounds.
            new GenericCraftingService.Job(jobId, operationId, principal, resourceId, amount, state,
                    completedWork, totalWork, terminalResult, lastError, true, updatedAt);
        }
        public UUID principalId() { return principal.id(); }

        Entry withNative(UUID nativeId, GenericCraftingService.State next, long time) {
            return new Entry(jobId, operationId, principal, resourceId, amount,
                    Objects.requireNonNull(nativeId, "nativeTaskId"), next, completedWork, totalWork,
                    terminalResult, lastError, time);
        }
        Entry withState(GenericCraftingService.State next, long completed, long total,
                        String result, String error, long time) {
            return new Entry(jobId, operationId, principal, resourceId, amount, nativeTaskId,
                    next, completed, total, result, error, time);
        }
        public GenericCraftingService.Job toJob() {
            return new GenericCraftingService.Job(jobId, operationId, principal, resourceId, amount,
                    state, completedWork, totalWork, terminalResult, lastError, true, updatedAt);
        }
    }
}
