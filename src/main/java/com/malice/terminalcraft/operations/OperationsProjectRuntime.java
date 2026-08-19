package com.malice.terminalcraft.operations;

import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.ServerDeviceManager;
import net.minecraft.server.MinecraftServer;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Server-side orchestration boundary intended for future GUI packets. Clients receive previews but
 * apply only an opaque plan ID; the authoritative plan, principal, and project revision remain on
 * the server and expire quickly.
 */
public final class OperationsProjectRuntime {
    public static final int MAX_RETAINED_PLANS = 64;
    public static final long PLAN_TTL_TICKS = 1_200;

    private static final Map<MinecraftServer, State> SERVERS = new WeakHashMap<>();

    private OperationsProjectRuntime() {}

    public enum PreviewStatus { READY, INVALID, NOT_FOUND }

    public record PreviewResult(PreviewStatus status, String message,
                                Optional<OperationsDeploymentService.Plan> plan) {
        public PreviewResult {
            status = Objects.requireNonNull(status, "status");
            message = Objects.requireNonNull(message, "message");
            plan = Objects.requireNonNull(plan, "plan");
        }
    }

    public static PreviewResult preview(MinecraftServer server, DeviceCallContext context,
                                        UUID projectId, int discoveryLimit) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(projectId, "projectId");
        OperationsProject project = OperationsProjectSavedData.get(server)
                .project(context, projectId).orElse(null);
        if (project == null) {
            return new PreviewResult(PreviewStatus.NOT_FOUND, "project not found", Optional.empty());
        }
        OperationsDiscoverySnapshot snapshot = OperationsDiscoverySnapshot.capture(
                server, context, discoveryLimit);
        State state = state(server);
        OperationsDeploymentService.Plan plan = state.deployments.preview(project, snapshot);
        if (plan.ready()) state.retain(context.authorityKey(), plan, snapshot.capturedAt());
        return new PreviewResult(plan.ready() ? PreviewStatus.READY : PreviewStatus.INVALID,
                plan.ready() ? "deployment preview is ready" : "deployment preview has validation errors",
                Optional.of(plan));
    }

    /** Applies one server-retained preview and consumes it regardless of the outcome. */
    public static OperationsDeploymentService.Result apply(MinecraftServer server,
                                                           DeviceCallContext context, UUID planId) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(planId, "planId");
        long gameTime = server.overworld().getGameTime();
        State state = state(server);
        OperationsDeploymentService.Plan plan = state.take(context.authorityKey(), planId, gameTime)
                .orElse(null);
        if (plan == null) return rejected("deployment preview is missing, expired, or belongs to another principal");
        OperationsProject project = OperationsProjectSavedData.get(server)
                .project(context, plan.projectId()).orElse(null);
        if (project == null) return rejected("project is no longer available");
        return state.deployments.apply(plan, project, ServerDeviceManager.access(server, context), gameTime);
    }

    public static void clear(MinecraftServer server) {
        if (server == null) return;
        synchronized (SERVERS) { SERVERS.remove(server); }
    }

    private static OperationsDeploymentService.Result rejected(String message) {
        return new OperationsDeploymentService.Result(OperationsDeploymentService.Status.REJECTED,
                message, java.util.List.of(), new OperationsProjectValidator.Report(java.util.List.of()));
    }

    private static State state(MinecraftServer server) {
        synchronized (SERVERS) { return SERVERS.computeIfAbsent(server, ignored -> new State()); }
    }

    private static final class State {
        private final OperationsDeploymentService deployments = new OperationsDeploymentService();
        private final LinkedHashMap<UUID, RetainedPlan> plans = new LinkedHashMap<>();

        private synchronized void retain(String authorityKey, OperationsDeploymentService.Plan plan,
                                         long gameTime) {
            purge(gameTime);
            plans.put(plan.planId(), new RetainedPlan(authorityKey, plan, gameTime));
            while (plans.size() > MAX_RETAINED_PLANS) {
                Iterator<UUID> iterator = plans.keySet().iterator();
                iterator.next();
                iterator.remove();
            }
        }

        private synchronized Optional<OperationsDeploymentService.Plan> take(String authorityKey,
                                                                              UUID planId,
                                                                              long gameTime) {
            purge(gameTime);
            RetainedPlan retained = plans.get(planId);
            if (retained == null || !retained.authorityKey.equals(authorityKey)) return Optional.empty();
            plans.remove(planId);
            return Optional.of(retained.plan);
        }

        private void purge(long gameTime) {
            plans.values().removeIf(retained -> gameTime - retained.createdAt > PLAN_TTL_TICKS);
        }
    }

    private record RetainedPlan(String authorityKey, OperationsDeploymentService.Plan plan,
                                long createdAt) {}
}
