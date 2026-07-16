package com.malice.terminalcraft.server;

import com.malice.terminalcraft.device.DeviceAuthorization;
import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.PrincipalIdentity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntFunction;
import java.util.function.Function;

/**
 * Persistent, cooperative job queue used by server racks. The scheduler performs no work on
 * native threads: the owning block entity gives it a small budget from the logical server tick.
 */
public final class ServerJobScheduler {
    public static final int MAX_JOBS = 128;
    public static final int MAX_COMMAND_LENGTH = 1024;
    public static final int MAX_RETAINED_FINISHED = 64;
    public static final int MAX_ACTIVE_JOBS_PER_OWNER = 32;
    public static final int MAX_TICK_BUDGET = 64;
    public static final long MAX_DELAY_TICKS = 20L * 60 * 60 * 24 * 7;
    public static final int MAX_ERROR_LENGTH = 256;

    public enum State {
        QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED;

        static State parse(String value) {
            try {
                return State.valueOf(value);
            } catch (IllegalArgumentException | NullPointerException ignored) {
                return FAILED;
            }
        }
    }

    /** The authenticated context is retained so deferred calls never gain or silently lose grants. */
    public record Job(UUID id, PrincipalIdentity process, DeviceCallContext context, String command,
                      State state, int exitCode, String lastError, long submittedAt, long eligibleAt,
                      long startedAt, long finishedAt, int continuationVersion, String continuation) {
        public Job {
            if (id == null) throw new IllegalArgumentException("job id is required");
            if (process == null || process.kind() != PrincipalIdentity.Kind.PROCESS) {
                throw new IllegalArgumentException("job process identity is required");
            }
            if (context == null) throw new IllegalArgumentException("job context is required");
            command = command == null ? "" : command;
            state = state == null ? State.FAILED : state;
            lastError = lastError == null ? "" : lastError;
            continuation = continuation == null ? "" : continuation;
        }

        public String owner() {
            return context.principalName();
        }

        Job withState(State next, int code, String error, long started, long finished) {
            return new Job(id, process, context, command, next, code, error, submittedAt, eligibleAt,
                    started, finished, continuationVersion, continuation);
        }
    }

    public record Diagnostics(long tick, int budget, int executed, int eligible, int deferred,
                              int queued, int running, int retainedFinished) {}

    public enum StepDisposition { YIELD, WAIT_UNTIL, COMPLETED, FAILED }

    /** One bounded cooperative slice result. Continuations are opaque, versioned scheduler data. */
    public record StepResult(StepDisposition disposition, int exitCode, String error,
                             long eligibleAt, int continuationVersion, String continuation) {
        public StepResult {
            if (disposition == null) throw new IllegalArgumentException("step disposition is required");
            error = bound(error, MAX_ERROR_LENGTH);
            continuation = bound(continuation, MAX_COMMAND_LENGTH);
            if (continuationVersion < 0) throw new IllegalArgumentException("continuation version must not be negative");
        }
        public static StepResult completed(int exitCode) {
            return exitCode == 0 ? new StepResult(StepDisposition.COMPLETED, 0, "", 0, 0, "")
                    : failed(exitCode, "command exited with status " + exitCode);
        }
        public static StepResult failed(int exitCode, String error) {
            return new StepResult(StepDisposition.FAILED, exitCode, error, 0, 0, "");
        }
        public static StepResult yield(int version, String continuation) {
            return new StepResult(StepDisposition.YIELD, -1, "", 0, version, continuation);
        }
        public static StepResult waitUntil(long eligibleAt, int version, String continuation) {
            return new StepResult(StepDisposition.WAIT_UNTIL, -1, "", eligibleAt, version, continuation);
        }
    }

    private final LinkedHashMap<UUID, Job> jobs = new LinkedHashMap<>();
    private PrincipalIdentity lastServedOwner;
    private Diagnostics diagnostics = new Diagnostics(-1, 0, 0, 0, 0, 0, 0, 0);

    public synchronized Job submit(DeviceCallContext context, String command, long gameTime) {
        return schedule(context, command, gameTime, 0);
    }

    /** Schedules a command against logical game time; no wall clock or background thread is used. */
    public synchronized Job schedule(DeviceCallContext context, String command, long gameTime,
                                     long delayTicks) {
        if (context == null) throw new IllegalArgumentException("job context is required");
        String normalized = command == null ? "" : command.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("command must not be empty");
        if (normalized.length() > MAX_COMMAND_LENGTH) {
            throw new IllegalArgumentException("command exceeds " + MAX_COMMAND_LENGTH + " characters");
        }
        if (gameTime < 0) throw new IllegalArgumentException("game time must not be negative");
        if (delayTicks < 0 || delayTicks > MAX_DELAY_TICKS || gameTime > Long.MAX_VALUE - delayTicks) {
            throw new IllegalArgumentException("delay must be from 0 to " + MAX_DELAY_TICKS + " ticks");
        }
        pruneFinished();
        long active = jobs.values().stream()
                .filter(job -> job.state == State.QUEUED || job.state == State.RUNNING)
                .count();
        if (active >= MAX_JOBS) throw new IllegalStateException("server job queue is full");
        long ownedActive = jobs.values().stream()
                .filter(job -> (job.state == State.QUEUED || job.state == State.RUNNING)
                        && job.context.principal().equals(context.principal()))
                .count();
        if (ownedActive >= MAX_ACTIVE_JOBS_PER_OWNER) {
            throw new IllegalStateException("owner server job quota exceeded");
        }
        UUID jobId = UUID.randomUUID();
        PrincipalIdentity process = PrincipalIdentity.process(jobId, "job-" + jobId.toString().substring(0, 8));
        Job job = new Job(jobId, process, context, normalized, State.QUEUED, -1, "",
                gameTime, gameTime + delayTicks, -1, -1, 0, "");
        jobs.put(job.id, job);
        return job;
    }

    public synchronized boolean cancel(UUID id, DeviceCallContext caller, long gameTime) {
        Job job = jobs.get(id);
        if (job == null || !DeviceAuthorization.owns(caller, job.context.principal())
                || (job.state != State.QUEUED && job.state != State.RUNNING)) return false;
        jobs.put(id, job.withState(State.CANCELLED, 130, "cancelled", job.startedAt, gameTime));
        return true;
    }

    public synchronized Job get(UUID id) {
        return jobs.get(id);
    }

    public synchronized List<Job> list() {
        return List.copyOf(jobs.values());
    }

    public synchronized int queuedCount() {
        return (int) jobs.values().stream().filter(job -> job.state == State.QUEUED).count();
    }

    /** Compatibility executor: one command is one completed cooperative slice. */
    public int tick(long gameTime, int budget, ToIntFunction<Job> executor) {
        if (executor == null) return tickSteps(gameTime, budget, null);
        return tickSteps(gameTime, budget, job -> StepResult.completed(executor.applyAsInt(job)));
    }

    /** Executes callbacks outside the scheduler monitor and commits only if cancellation did not win. */
    public int tickSteps(long gameTime, int budget, Function<Job, StepResult> executor) {
        int boundedBudget = Math.max(0, Math.min(budget, MAX_TICK_BUDGET));
        int executed = 0;
        while (executed < boundedBudget && executor != null) {
            Job running;
            synchronized (this) {
                Job selected = selectNext(gameTime);
                if (selected == null) break;
                long started = selected.startedAt < 0 ? gameTime : selected.startedAt;
                running = selected.withState(State.RUNNING, -1, "", started, -1);
                jobs.put(running.id, running);
                lastServedOwner = running.context.principal();
            }
            StepResult result;
            try {
                result = executor.apply(running);
                if (result == null) result = StepResult.failed(1, "executor returned no step result");
            } catch (RuntimeException failure) {
                String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                result = StepResult.failed(1, message);
            }
            synchronized (this) {
                Job current = jobs.get(running.id);
                if (current != null && current.state == State.RUNNING) commitStep(current, result, gameTime);
            }
            executed++;
        }
        synchronized (this) {
            pruneFinished();
            updateDiagnostics(gameTime, boundedBudget, executed);
        }
        return executed;
    }

    private Job selectNext(long gameTime) {
        List<PrincipalIdentity> owners = jobs.values().stream()
                .filter(job -> job.state == State.QUEUED && job.eligibleAt <= gameTime)
                .map(job -> job.context.principal()).distinct().toList();
        if (owners.isEmpty()) return null;
        int start = 0;
        if (lastServedOwner != null) {
            int previous = owners.indexOf(lastServedOwner);
            if (previous >= 0) start = (previous + 1) % owners.size();
        }
        for (int offset = 0; offset < owners.size(); offset++) {
            PrincipalIdentity owner = owners.get((start + offset) % owners.size());
            Job job = jobs.values().stream().filter(candidate -> candidate.state == State.QUEUED
                    && candidate.eligibleAt <= gameTime && candidate.context.principal().equals(owner))
                    .findFirst().orElse(null);
            if (job != null) return job;
        }
        return null;
    }

    private void commitStep(Job job, StepResult result, long gameTime) {
        switch (result.disposition) {
            case COMPLETED -> jobs.put(job.id, new Job(job.id, job.process, job.context, job.command,
                    State.COMPLETED, result.exitCode, "", job.submittedAt, job.eligibleAt,
                    job.startedAt, gameTime, 0, ""));
            case FAILED -> jobs.put(job.id, new Job(job.id, job.process, job.context, job.command,
                    State.FAILED, result.exitCode, result.error, job.submittedAt, job.eligibleAt,
                    job.startedAt, gameTime, 0, ""));
            case YIELD -> jobs.put(job.id, new Job(job.id, job.process, job.context, job.command,
                    State.QUEUED, -1, "", job.submittedAt, gameTime + 1,
                    job.startedAt, -1, result.continuationVersion, result.continuation));
            case WAIT_UNTIL -> {
                if (result.eligibleAt < gameTime || result.eligibleAt - gameTime > MAX_DELAY_TICKS) {
                    jobs.put(job.id, job.withState(State.FAILED, 1, "invalid cooperative wake deadline",
                            job.startedAt, gameTime));
                } else {
                    jobs.put(job.id, new Job(job.id, job.process, job.context, job.command,
                            State.QUEUED, -1, "", job.submittedAt, result.eligibleAt,
                            job.startedAt, -1, result.continuationVersion, result.continuation));
                }
            }
        }
    }

    public synchronized Diagnostics diagnostics() { return diagnostics; }

    private void updateDiagnostics(long gameTime, int budget, int executed) {
        int eligible = 0;
        int deferred = 0;
        int queued = 0;
        int running = 0;
        int finished = 0;
        for (Job job : jobs.values()) {
            if (job.state == State.QUEUED) {
                queued++;
                if (job.eligibleAt <= gameTime) eligible++; else deferred++;
            } else if (job.state == State.RUNNING) running++;
            else finished++;
        }
        diagnostics = new Diagnostics(gameTime, budget, executed, eligible, deferred, queued, running, finished);
    }

    public synchronized CompoundTag save() {
        CompoundTag root = new CompoundTag();
        ListTag entries = new ListTag();
        for (Job job : jobs.values()) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Id", job.id);
            tag.putUUID("ProcessId", job.process.id());
            tag.putString("ProcessName", job.process.name());
            tag.putUUID("PrincipalId", job.context.principalId());
            tag.putString("PrincipalKind", job.context.principalKind().serializedName());
            tag.putString("Owner", job.owner());
            ListTag permissions = new ListTag();
            job.context.permissions().stream().sorted().map(StringTag::valueOf).forEach(permissions::add);
            tag.put("Permissions", permissions);
            tag.putString("Command", job.command);
            tag.putString("State", job.state.name());
            tag.putInt("ExitCode", job.exitCode);
            tag.putString("LastError", job.lastError);
            tag.putLong("SubmittedAt", job.submittedAt);
            tag.putLong("EligibleAt", job.eligibleAt);
            tag.putLong("StartedAt", job.startedAt);
            tag.putLong("FinishedAt", job.finishedAt);
            tag.putInt("ContinuationVersion", job.continuationVersion);
            tag.putString("Continuation", job.continuation);
            entries.add(tag);
        }
        root.put("Jobs", entries);
        return root;
    }

    public synchronized void load(CompoundTag root) {
        jobs.clear();
        ListTag entries = root.getList("Jobs", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size() && jobs.size() < MAX_JOBS + MAX_RETAINED_FINISHED; i++) {
            CompoundTag tag = entries.getCompound(i);
            if (!tag.hasUUID("Id")) continue;
            String command = tag.getString("Command");
            if (command.isBlank() || command.length() > MAX_COMMAND_LENGTH) continue;
            State state = State.parse(tag.getString("State"));
            // A rack only saves at tick-safe points. A RUNNING job found after a crash is retried
            // from the queue; external mutations should still use idempotency keys where offered.
            if (state == State.RUNNING) state = State.QUEUED;
            try {
                DeviceCallContext context = loadContext(tag);
                UUID jobId = tag.getUUID("Id");
                UUID processId = tag.hasUUID("ProcessId") ? tag.getUUID("ProcessId") : jobId;
                String processName = tag.getString("ProcessName");
                if (processName.isBlank()) processName = "job-" + jobId.toString().substring(0, 8);
                PrincipalIdentity process = PrincipalIdentity.process(processId, processName);
                long submittedAt = tag.getLong("SubmittedAt");
                long eligibleAt = tag.contains("EligibleAt", Tag.TAG_LONG)
                        ? tag.getLong("EligibleAt") : submittedAt;
                if (submittedAt < 0 || eligibleAt < submittedAt
                        || eligibleAt - submittedAt > MAX_DELAY_TICKS) continue;
                Job job = new Job(jobId, process, context, command, state,
                        tag.getInt("ExitCode"), tag.getString("LastError"), submittedAt, eligibleAt,
                        tag.getLong("StartedAt"), tag.getLong("FinishedAt"),
                        tag.getInt("ContinuationVersion"), tag.getString("Continuation"));
                jobs.put(job.id, job);
            } catch (IllegalArgumentException invalidContext) {
                // Corrupt or untrusted persisted principals are skipped rather than elevated.
            }
        }
        pruneFinished();
    }

    private static DeviceCallContext loadContext(CompoundTag tag) {
        UUID principalId = tag.hasUUID("PrincipalId") ? tag.getUUID("PrincipalId") : new UUID(0L, 0L);
        String owner = tag.getString("Owner");
        if (owner.isBlank()) owner = "unknown";
        ListTag stored = tag.getList("Permissions", Tag.TAG_STRING);
        Set<String> permissions;
        if (stored.isEmpty()) {
            // Compatibility with the first unreleased rack format: never infer write access.
            permissions = Set.of(DeviceCallContext.READ);
        } else {
            permissions = stored.stream().map(Tag::getAsString).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        PrincipalIdentity.Kind kind = tag.contains("PrincipalKind", Tag.TAG_STRING)
                ? PrincipalIdentity.Kind.parse(tag.getString("PrincipalKind"))
                : PrincipalIdentity.Kind.PLAYER;
        return new DeviceCallContext(new PrincipalIdentity(kind, principalId, owner),
                permissions);
    }

    private static String bound(String value, int maximum) {
        String safe = value == null ? "" : value;
        return safe.length() <= maximum ? safe : safe.substring(0, maximum);
    }

    private void pruneFinished() {
        List<Job> finished = jobs.values().stream()
                .filter(job -> job.state == State.COMPLETED
                        || job.state == State.FAILED || job.state == State.CANCELLED)
                .sorted(Comparator.comparingLong(job -> job.finishedAt))
                .toList();
        int remove = Math.max(0, finished.size() - MAX_RETAINED_FINISHED);
        for (int i = 0; i < remove; i++) jobs.remove(finished.get(i).id);
    }
}
