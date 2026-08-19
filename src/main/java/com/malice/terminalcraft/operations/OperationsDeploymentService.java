package com.malice.terminalcraft.operations;

import com.malice.terminalcraft.device.DeviceAccess;
import com.malice.terminalcraft.device.DeviceError;
import com.malice.terminalcraft.device.DeviceResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Two-phase deployment boundary for GUI and CLI clients. Preview is pure; apply revalidates a
 * fresh discovery snapshot, rejects stale revisions and replays, and compensates attempted writes
 * in reverse order after a failure.
 */
public final class OperationsDeploymentService {
    public static final int MAX_RETAINED_PLAN_IDS = 256;

    private final Deque<UUID> consumedOrder = new ArrayDeque<>();
    private final Set<UUID> consumed = new HashSet<>();

    public enum Status { REJECTED, APPLIED, ROLLED_BACK, PARTIAL_ROLLBACK }
    public enum Phase { APPLY, ROLLBACK }

    public record Plan(UUID planId, UUID projectId, int schemaVersion, long projectRevision,
                       long previewedAt, int discoveryLimit, Set<String> installedModSources,
                       List<OperationsProject.DeploymentStep> steps,
                       OperationsProjectValidator.Report validation) {
        public Plan {
            planId = Objects.requireNonNull(planId, "planId");
            projectId = Objects.requireNonNull(projectId, "projectId");
            if (schemaVersion != OperationsProject.CURRENT_SCHEMA_VERSION) {
                throw new IllegalArgumentException("unsupported plan schema");
            }
            if (projectRevision < 0 || previewedAt < 0) {
                throw new IllegalArgumentException("plan revisions and times must not be negative");
            }
            if (discoveryLimit < 1) throw new IllegalArgumentException("plan discovery limit must be positive");
            installedModSources = Set.copyOf(Objects.requireNonNull(installedModSources, "installedModSources"));
            steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
            validation = Objects.requireNonNull(validation, "validation");
        }

        public boolean ready() { return validation.ready(); }
    }

    public record StepOutcome(UUID stepId, String label, Phase phase, boolean success, String detail) {
        public StepOutcome {
            stepId = Objects.requireNonNull(stepId, "stepId");
            label = Objects.requireNonNull(label, "label");
            phase = Objects.requireNonNull(phase, "phase");
            detail = Objects.requireNonNull(detail, "detail");
        }
    }

    public record Result(Status status, String message, List<StepOutcome> outcomes,
                         OperationsProjectValidator.Report validation) {
        public Result {
            status = Objects.requireNonNull(status, "status");
            message = Objects.requireNonNull(message, "message");
            outcomes = List.copyOf(Objects.requireNonNull(outcomes, "outcomes"));
            validation = Objects.requireNonNull(validation, "validation");
        }

        public boolean success() { return status == Status.APPLIED; }
    }

    public Plan preview(OperationsProject project, OperationsDiscoverySnapshot snapshot) {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(snapshot, "snapshot");
        return new Plan(UUID.randomUUID(), project.projectId(), project.schemaVersion(), project.revision(),
                snapshot.capturedAt(), snapshot.requestedLimit(), snapshot.installedModSources(),
                project.deploymentSteps(), OperationsProjectValidator.validate(project, snapshot));
    }

    public synchronized Result apply(Plan plan, OperationsProject currentProject,
                                     DeviceAccess access, long gameTime) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(currentProject, "currentProject");
        Objects.requireNonNull(access, "access");
        if (gameTime < 0) throw new IllegalArgumentException("deployment time must not be negative");
        if (consumed.contains(plan.planId())) {
            return rejected("deployment plan has already been consumed", plan.validation());
        }
        if (!matches(plan, currentProject)) {
            return rejected("deployment plan is stale or belongs to another project", plan.validation());
        }
        if (!plan.ready()) return rejected("deployment preview contains validation errors", plan.validation());

        OperationsDiscoverySnapshot fresh = OperationsDiscoverySnapshot.capture(access, gameTime,
                plan.discoveryLimit(), plan.installedModSources());
        OperationsProjectValidator.Report validation = OperationsProjectValidator.validate(currentProject, fresh);
        if (!validation.ready()) return rejected("deployment state changed after preview", validation);
        consume(plan.planId());

        List<StepOutcome> outcomes = new ArrayList<>();
        List<OperationsProject.DeploymentStep> attempted = new ArrayList<>();
        for (OperationsProject.DeploymentStep step : currentProject.deploymentSteps()) {
            attempted.add(step);
            Call call = call(access, step.deviceId(), step.method(), step.arguments());
            outcomes.add(new StepOutcome(step.stepId(), step.label(), Phase.APPLY,
                    call.success(), call.detail()));
            if (!call.success()) {
                boolean rollbackComplete = rollback(access, attempted, outcomes);
                return new Result(rollbackComplete ? Status.ROLLED_BACK : Status.PARTIAL_ROLLBACK,
                        rollbackComplete ? "deployment failed and all attempted changes were rolled back"
                                : "deployment failed and one or more rollback actions failed",
                        outcomes, validation);
            }
        }
        return new Result(Status.APPLIED, "deployment applied", outcomes, validation);
    }

    private static boolean matches(Plan plan, OperationsProject project) {
        return plan.projectId().equals(project.projectId())
                && plan.schemaVersion() == project.schemaVersion()
                && plan.projectRevision() == project.revision()
                && plan.steps().equals(project.deploymentSteps());
    }

    private static boolean rollback(DeviceAccess access,
                                    List<OperationsProject.DeploymentStep> attempted,
                                    List<StepOutcome> outcomes) {
        boolean complete = true;
        for (int index = attempted.size() - 1; index >= 0; index--) {
            OperationsProject.DeploymentStep step = attempted.get(index);
            Optional<OperationsProject.Compensation> compensation = step.compensation();
            if (compensation.isEmpty()) continue;
            Call call = call(access, step.deviceId(), compensation.get().method(),
                    compensation.get().arguments());
            outcomes.add(new StepOutcome(step.stepId(), step.label(), Phase.ROLLBACK,
                    call.success(), call.detail()));
            complete &= call.success();
        }
        return complete;
    }

    private static Call call(DeviceAccess access, UUID deviceId, String method,
                             List<com.malice.terminalcraft.device.DeviceValue> arguments) {
        try {
            DeviceResult result = access.call(deviceId, method, arguments);
            if (result == null) return new Call(false, "device access returned no result");
            if (result.isSuccess()) return new Call(true, "ok");
            DeviceError error = result.error().orElse(null);
            return error == null ? new Call(false, "device call failed")
                    : new Call(false, error.code().name().toLowerCase(java.util.Locale.ROOT)
                    + ": " + error.message());
        } catch (RuntimeException exception) {
            String detail = exception.getMessage();
            return new Call(false, detail == null || detail.isBlank()
                    ? "device access failed" : "device access failed: " + detail);
        }
    }

    private void consume(UUID planId) {
        consumed.add(planId);
        consumedOrder.addLast(planId);
        while (consumedOrder.size() > MAX_RETAINED_PLAN_IDS) {
            consumed.remove(consumedOrder.removeFirst());
        }
    }

    private static Result rejected(String message, OperationsProjectValidator.Report validation) {
        return new Result(Status.REJECTED, message, List.of(), validation);
    }

    private record Call(boolean success, String detail) {}
}
