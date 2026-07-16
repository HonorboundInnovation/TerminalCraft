package com.malice.terminalcraft.device;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Implementation-neutral contract for an external crafting service.
 *
 * <p>Native adapters remain authoritative for recipe availability, resources, power, security,
 * CPUs and cancellation. TerminalCraft supplies bounded requests, caller ownership and stable
 * correlation fields without pretending that different storage mods share native job objects.</p>
 */
public interface GenericCraftingService {
    int CONTRACT_VERSION = 1;
    long MAX_REQUEST_AMOUNT = 1_000_000L;
    int MAX_RESOURCE_LENGTH = 128;
    int MAX_RESULT_LENGTH = 256;
    int MAX_ERROR_LENGTH = 256;

    Metadata metadata();

    Response submit(DeviceCallContext caller, Submission submission);

    Response status(DeviceCallContext caller, UUID jobId);

    Response cancel(DeviceCallContext caller, UUID jobId);

    record Metadata(String serviceKind, boolean cancellationSupported, int maxActiveJobs) {
        public Metadata {
            serviceKind = identifier(serviceKind, "service kind");
            if (maxActiveJobs < 1 || maxActiveJobs > 4096) {
                throw new IllegalArgumentException("max active jobs must be between 1 and 4096");
            }
        }
    }

    /** The operation ID is client-generated and must be replayed rather than submitted twice. */
    record Submission(UUID operationId, String resourceId, long amount) {
        public Submission {
            Objects.requireNonNull(operationId, "operationId");
            resourceId = namespacedResource(resourceId);
            if (amount < 1 || amount > MAX_REQUEST_AMOUNT) {
                throw new IllegalArgumentException("crafting amount must be between 1 and "
                        + MAX_REQUEST_AMOUNT);
            }
        }
    }

    enum State {
        SUBMITTING, QUEUED, RUNNING, RECONCILING, COMPLETED, FAILED, CANCELLED, UNKNOWN;

        public boolean terminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED;
        }
    }

    /** Immutable generic projection of an authoritative native crafting job. */
    record Job(UUID jobId, UUID operationId, PrincipalIdentity principal, String resourceId, long amount,
               State state, long completedWork, long totalWork, String terminalResult,
               String lastError, boolean cancellationSupported, long updatedAt) {
        public Job {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(principal, "principal");
            resourceId = namespacedResource(resourceId);
            if (amount < 1 || amount > MAX_REQUEST_AMOUNT) {
                throw new IllegalArgumentException("invalid crafting job amount");
            }
            Objects.requireNonNull(state, "state");
            if (completedWork < 0 || totalWork < 0 || (totalWork > 0 && completedWork > totalWork)) {
                throw new IllegalArgumentException("invalid crafting progress");
            }
            terminalResult = boundedText(terminalResult, MAX_RESULT_LENGTH, "terminal result");
            lastError = boundedText(lastError, MAX_ERROR_LENGTH, "last error");
            if (!state.terminal() && !terminalResult.isEmpty()) {
                throw new IllegalArgumentException("non-terminal job cannot have a terminal result");
            }
            if (state == State.COMPLETED && terminalResult.isEmpty()) {
                throw new IllegalArgumentException("completed job requires a terminal result");
            }
            if (updatedAt < 0) throw new IllegalArgumentException("updated time must not be negative");
        }

        public UUID principalId() { return principal.id(); }

        /** Compatibility constructor for legacy UUID-only player-owned jobs. */
        public Job(UUID jobId, UUID operationId, UUID principalId, String resourceId, long amount,
                   State state, long completedWork, long totalWork, String terminalResult,
                   String lastError, boolean cancellationSupported, long updatedAt) {
            this(jobId, operationId, PrincipalIdentity.player(principalId, "legacy-player"),
                    resourceId, amount, state, completedWork, totalWork, terminalResult, lastError,
                    cancellationSupported, updatedAt);
        }

        public boolean ownedBy(DeviceCallContext caller) {
            return DeviceAuthorization.owns(caller, principal);
        }
    }

    enum Disposition {
        ACCEPTED, REPLAYED, FOUND, CANCELLED
    }

    enum FailureCode {
        INVALID_ARGUMENT, NOT_FOUND, PERMISSION_DENIED, UNSUPPORTED, OFFLINE,
        UNAVAILABLE, CONFLICT, CAPACITY_EXCEEDED, ADAPTER_ERROR
    }

    record Failure(FailureCode code, String message, boolean retryable) {
        public Failure {
            Objects.requireNonNull(code, "code");
            message = boundedText(message, MAX_ERROR_LENGTH, "failure message");
            if (message.isEmpty()) throw new IllegalArgumentException("failure message is required");
        }
    }

    /** Exactly one of job and failure is present. */
    record Response(Disposition disposition, Job job, Failure failure) {
        public Response {
            if ((job == null) == (failure == null)) {
                throw new IllegalArgumentException("response must contain exactly one of job or failure");
            }
            if (job != null) Objects.requireNonNull(disposition, "disposition");
            if (failure != null && disposition != null) {
                throw new IllegalArgumentException("failed response cannot have a disposition");
            }
        }

        public static Response success(Disposition disposition, Job job) {
            return new Response(Objects.requireNonNull(disposition, "disposition"),
                    Objects.requireNonNull(job, "job"), null);
        }

        public static Response failure(FailureCode code, String message, boolean retryable) {
            return new Response(null, null, new Failure(code, message, retryable));
        }

        public Optional<Job> jobOptional() { return Optional.ofNullable(job); }
        public Optional<Failure> failureOptional() { return Optional.ofNullable(failure); }
    }

    private static String namespacedResource(String value) {
        Objects.requireNonNull(value, "resourceId");
        if (value.length() > MAX_RESOURCE_LENGTH
                || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("invalid namespaced crafting resource");
        }
        return value;
    }

    private static String identifier(String value, String label) {
        Objects.requireNonNull(value, label);
        if (!value.matches("[a-z][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return value;
    }

    private static String boundedText(String value, int limit, String label) {
        value = value == null ? "" : value;
        if (value.length() > limit) throw new IllegalArgumentException(label + " exceeds limit");
        return value;
    }
}
