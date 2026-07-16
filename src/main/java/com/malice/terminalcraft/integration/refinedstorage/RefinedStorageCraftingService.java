package com.malice.terminalcraft.integration.refinedstorage;

import com.malice.terminalcraft.blockentity.RefinedStorageBridgeBlockEntity;
import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.DurableCraftingLedger;
import com.malice.terminalcraft.device.GenericCraftingService;
import com.malice.terminalcraft.device.PrincipalIdentity;
import com.refinedmods.refinedstorage.api.autocrafting.ICraftingManager;
import com.refinedmods.refinedstorage.api.autocrafting.task.ICraftingTask;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.UUID;

/** Native Refined Storage crafting adapter with principal checks and durable replay correlation. */
final class RefinedStorageCraftingService implements GenericCraftingService {
    private final RefinedStorageBridgeBlockEntity bridge;
    private final Environment environment;

    RefinedStorageCraftingService(RefinedStorageBridgeBlockEntity bridge) {
        this(bridge, new LiveEnvironment(bridge));
    }

    RefinedStorageCraftingService(RefinedStorageBridgeBlockEntity bridge, Environment environment) {
        this.bridge = java.util.Objects.requireNonNull(bridge, "bridge");
        this.environment = java.util.Objects.requireNonNull(environment, "environment");
    }

    @Override
    public Metadata metadata() {
        return new Metadata("refined_storage", true, DurableCraftingLedger.MAX_JOBS);
    }

    @Override
    public Response submit(DeviceCallContext caller, Submission submission) {
        Authorization authorization = environment.authorize(caller);
        if (authorization.failure != null) return authorization.failure;

        if (submission.amount() > Integer.MAX_VALUE) {
            return Response.failure(FailureCode.INVALID_ARGUMENT,
                    "crafting amount exceeds Refined Storage native limit", false);
        }

        if (!environment.validResource(submission.resourceId())) {
            return Response.failure(FailureCode.INVALID_ARGUMENT, "unknown item resource", false);
        }

        long now = environment.now();
        DurableCraftingLedger.ReserveResult reservation =
                bridge.reserveCrafting(caller, submission, now);
        switch (reservation.disposition()) {
            case FULL:
                return Response.failure(FailureCode.CAPACITY_EXCEEDED,
                        "Refined Storage crafting ledger is full", false);
            case CONFLICT:
                return Response.failure(FailureCode.CONFLICT,
                        "operation ID was already used for a different request", false);
            case REPLAYED:
                return replay(reservation.entry(), authorization.session);
            case RESERVED:
                break;
        }

        DurableCraftingLedger.Entry entry = reservation.entry();
        try {
            UUID nativeTaskId = authorization.session.requestAndStart(
                    submission.resourceId(), (int) submission.amount());
            if (nativeTaskId == null) {
                DurableCraftingLedger.Entry failed = bridge.updateCrafting(entry.jobId(), State.FAILED,
                        0, submission.amount(), "failed", "Refined Storage could not create crafting task", environment.now());
                return Response.success(Disposition.ACCEPTED, failed.toJob());
            }
            DurableCraftingLedger.Entry confirmed =
                    bridge.confirmNativeCrafting(entry.jobId(), nativeTaskId, environment.now());
            return Response.success(Disposition.ACCEPTED, reconcile(confirmed, authorization.session).toJob());
        } catch (RuntimeException exception) {
            DurableCraftingLedger.Entry ambiguous = bridge.markCraftingAmbiguous(entry.jobId(),
                    "native submission outcome is ambiguous; automatic retry is disabled", environment.now());
            return Response.success(Disposition.ACCEPTED, ambiguous.toJob());
        }
    }

    @Override
    public Response status(DeviceCallContext caller, UUID jobId) {
        Optional<DurableCraftingLedger.Entry> owned =
                bridge.findOwnedCrafting(jobId, caller.principal());
        if (owned.isEmpty()) {
            return Response.failure(FailureCode.NOT_FOUND, "crafting job not found", false);
        }
        Authorization authorization = environment.authorize(caller);
        if (authorization.failure != null) return authorization.failure;
        DurableCraftingLedger.Entry entry = owned.orElseThrow();
        try {
            return Response.success(Disposition.FOUND,
                    reconcile(entry, authorization.session).toJob());
        } catch (RuntimeException exception) {
            bridge.markCraftingAmbiguous(jobId,
                    "native status lookup failed; task state is unknown", environment.now());
            return Response.failure(FailureCode.ADAPTER_ERROR,
                    "native crafting status is temporarily unavailable", true);
        }
    }

    @Override
    public Response cancel(DeviceCallContext caller, UUID jobId) {
        Optional<DurableCraftingLedger.Entry> owned =
                bridge.findOwnedCrafting(jobId, caller.principal());
        if (owned.isEmpty()) {
            return Response.failure(FailureCode.NOT_FOUND, "crafting job not found", false);
        }
        Authorization authorization = environment.authorize(caller);
        if (authorization.failure != null) return authorization.failure;
        DurableCraftingLedger.Entry entry = owned.orElseThrow();
        if (entry.state().terminal()) {
            return Response.failure(FailureCode.CONFLICT, "crafting job is already terminal", false);
        }
        if (entry.nativeTaskId() == null) {
            return Response.failure(FailureCode.CONFLICT,
                    "crafting job has no safely correlated native task", false);
        }
        try {
            authorization.session.cancel(entry.nativeTaskId());
            DurableCraftingLedger.Entry cancelled = bridge.updateCrafting(jobId, State.CANCELLED,
                    entry.completedWork(), entry.totalWork(), "cancelled", "", environment.now());
            return Response.success(Disposition.CANCELLED, cancelled.toJob());
        } catch (RuntimeException exception) {
            bridge.markCraftingAmbiguous(jobId,
                    "native cancellation outcome is ambiguous", environment.now());
            return Response.failure(FailureCode.ADAPTER_ERROR,
                    "native cancellation outcome is ambiguous", false);
        }
    }

    /** A replay is read-only native work: lookup failure is retryable and can never resubmit. */
    private Response replay(DurableCraftingLedger.Entry entry, NativeSession session) {
        try {
            return Response.success(Disposition.REPLAYED, reconcile(entry, session).toJob());
        } catch (RuntimeException exception) {
            bridge.markCraftingAmbiguous(entry.jobId(),
                    "native replay status lookup failed; task state is unknown", environment.now());
            return Response.failure(FailureCode.ADAPTER_ERROR,
                    "native crafting replay status is temporarily unavailable", true);
        }
    }

    private DurableCraftingLedger.Entry reconcile(DurableCraftingLedger.Entry entry, NativeSession session) {
        if (entry.state().terminal() || entry.nativeTaskId() == null) return entry;
        Integer percentageValue = session.completionPercentage(entry.nativeTaskId());
        if (percentageValue == null) {
            return bridge.updateCrafting(entry.jobId(), State.UNKNOWN, entry.completedWork(),
                    entry.totalWork(), "", "native task is no longer visible", environment.now());
        }
        long total = Math.max(1, entry.amount());
        int percentage = Math.max(0, Math.min(100, percentageValue));
        long completed = Math.min(total, total * percentage / 100);
        State state = percentage >= 100 ? State.COMPLETED : State.RUNNING;
        return bridge.updateCrafting(entry.jobId(), state, completed, total,
                state == State.COMPLETED ? "completed" : "", "", environment.now());
    }

    interface Environment {
        Authorization authorize(DeviceCallContext caller);
        boolean validResource(String resourceId);
        long now();
    }

    interface NativeSession {
        UUID requestAndStart(String resourceId, int amount);
        Integer completionPercentage(UUID nativeTaskId);
        void cancel(UUID nativeTaskId);
    }

    record Authorization(NativeSession session, Response failure) {
        static Authorization success(NativeSession session) {
            return new Authorization(java.util.Objects.requireNonNull(session, "session"), null);
        }
        static Authorization failure(Response failure) { return new Authorization(null, failure); }
    }

    private static final class LiveEnvironment implements Environment {
        private final RefinedStorageBridgeBlockEntity bridge;

        private LiveEnvironment(RefinedStorageBridgeBlockEntity bridge) { this.bridge = bridge; }

        @Override
        public Authorization authorize(DeviceCallContext caller) {
            if (caller == null || caller.principalKind() != PrincipalIdentity.Kind.PLAYER) {
                return Authorization.failure(Response.failure(FailureCode.PERMISSION_DENIED,
                        "Refined Storage crafting requires an authenticated player principal", false));
            }
            if (!(bridge.getLevel() instanceof ServerLevel level)) {
                return Authorization.failure(Response.failure(FailureCode.UNAVAILABLE,
                        "bridge level is unavailable", true));
            }
            INetwork network = RefinedStorageIntegration.attachedNetwork(bridge).orElse(null);
            if (network == null || !network.canRun()) {
                return Authorization.failure(Response.failure(FailureCode.OFFLINE,
                        "Refined Storage network is detached, ambiguous, or offline", true));
            }
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(caller.principalId());
            if (player == null) {
                return Authorization.failure(Response.failure(FailureCode.PERMISSION_DENIED,
                        "crafting requires the authenticated principal to be online", false));
            }
            if (!network.getSecurityManager().hasPermission(Permission.AUTOCRAFTING, player)) {
                return Authorization.failure(Response.failure(FailureCode.PERMISSION_DENIED,
                        "Refined Storage denied AUTOCRAFTING for this principal", false));
            }
            return Authorization.success(new LiveSession(network, player));
        }

        @Override public boolean validResource(String resourceId) {
            ResourceLocation id = ResourceLocation.tryParse(resourceId);
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return false;
            Item item = BuiltInRegistries.ITEM.get(id);
            return !new ItemStack(item).isEmpty();
        }

        @Override public long now() {
            return bridge.getLevel() == null ? 0 : Math.max(0, bridge.getLevel().getGameTime());
        }
    }

    private record LiveSession(INetwork network, ServerPlayer player) implements NativeSession {
        @Override public UUID requestAndStart(String resourceId, int amount) {
            ResourceLocation id = ResourceLocation.tryParse(resourceId);
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(id));
            ICraftingManager manager = network.getCraftingManager();
            ICraftingTask task = manager.request(player, stack, amount);
            if (task == null) return null;
            manager.start(task);
            return task.getId();
        }
        @Override public Integer completionPercentage(UUID nativeTaskId) {
            ICraftingTask task = network.getCraftingManager().getTask(nativeTaskId);
            return task == null ? null : task.getCompletionPercentage();
        }
        @Override public void cancel(UUID nativeTaskId) {
            network.getCraftingManager().cancel(nativeTaskId);
        }
    }

}
