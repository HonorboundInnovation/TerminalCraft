package com.malice.terminalcraft.integration.refinedstorage;

import com.malice.terminalcraft.blockentity.RefinedStorageBridgeBlockEntity;
import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.GenericCraftingService;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.Set;
import java.util.UUID;

/** Deterministic fault and ordering coverage for the native Refined Storage crafting boundary. */
public final class RefinedStorageCraftingServiceTest {
    private RefinedStorageCraftingServiceTest() {}

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        DeviceCallContext alice = new DeviceCallContext(UUID.randomUUID(), "alice",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));

        Fixture successful = fixture();
        UUID operation = UUID.randomUUID();
        GenericCraftingService.Submission submission = submission(operation);
        successful.session.beforeRequest = () -> require(successful.bridge.craftingJobCount() == 1,
                "durable reservation precedes native request");
        GenericCraftingService.Response accepted = successful.service.submit(alice, submission);
        require(accepted.job().state() == GenericCraftingService.State.RUNNING, "native task starts running");
        require(successful.session.requests == 1, "one native request is issued");

        GenericCraftingService.Response replayed = successful.service.submit(alice, submission);
        require(replayed.disposition() == GenericCraftingService.Disposition.REPLAYED,
                "same operation is replayed");
        require(successful.session.requests == 1, "replay never issues another native request");

        Fixture denied = fixture();
        denied.environment.authorization = RefinedStorageCraftingService.Authorization.failure(
                GenericCraftingService.Response.failure(GenericCraftingService.FailureCode.PERMISSION_DENIED,
                        "denied", false));
        require(denied.service.submit(alice, submission(UUID.randomUUID())).failure().code()
                        == GenericCraftingService.FailureCode.PERMISSION_DENIED,
                "authorization failure is preserved");
        require(denied.bridge.craftingJobCount() == 0 && denied.session.requests == 0,
                "denial occurs before durable or native work");

        Fixture invalid = fixture();
        invalid.environment.validResource = false;
        require(invalid.service.submit(alice, submission(UUID.randomUUID())).failure().code()
                        == GenericCraftingService.FailureCode.INVALID_ARGUMENT,
                "unknown resource is rejected");
        require(invalid.bridge.craftingJobCount() == 0, "invalid resource is not reserved");

        Fixture unavailable = fixture();
        unavailable.session.returnNull = true;
        GenericCraftingService.Response failed = unavailable.service.submit(alice, submission(UUID.randomUUID()));
        require(failed.job().state() == GenericCraftingService.State.FAILED,
                "null native task becomes a durable failed job");

        Fixture ambiguous = fixture();
        ambiguous.session.throwRequest = true;
        GenericCraftingService.Response uncertain = ambiguous.service.submit(alice, submission(UUID.randomUUID()));
        require(uncertain.job().state() == GenericCraftingService.State.RECONCILING,
                "native submission exception becomes reconciling");

        UUID jobId = accepted.job().jobId();
        RefinedStorageCraftingService.Authorization savedAuthorization =
                successful.environment.authorization;
        successful.environment.authorization = RefinedStorageCraftingService.Authorization.failure(
                GenericCraftingService.Response.failure(
                        GenericCraftingService.FailureCode.PERMISSION_DENIED,
                        "native permission revoked", false));
        int lookupsBeforeDenial = successful.session.lookups;
        GenericCraftingService.Response revokedStatus = successful.service.status(alice, jobId);
        require(revokedStatus.failure().code() == GenericCraftingService.FailureCode.PERMISSION_DENIED,
                "current native authorization gates owner-visible cached status");
        require(successful.session.lookups == lookupsBeforeDenial,
                "native denial occurs before task lookup");
        successful.environment.authorization = savedAuthorization;
        successful.session.throwLookup = true;
        GenericCraftingService.Response statusFailure = successful.service.status(alice, jobId);
        require(statusFailure.failure().code() == GenericCraftingService.FailureCode.ADAPTER_ERROR
                        && statusFailure.failure().retryable(),
                "native status exception is bounded and retryable");

        successful.session.throwLookup = false;
        successful.session.throwCancel = true;
        GenericCraftingService.Response cancelFailure = successful.service.cancel(alice, jobId);
        require(cancelFailure.failure().code() == GenericCraftingService.FailureCode.ADAPTER_ERROR
                        && !cancelFailure.failure().retryable(),
                "ambiguous native cancellation is not automatically retryable");

        Fixture missing = fixture();
        GenericCraftingService.Response missingAccepted = missing.service.submit(
                alice, submission(UUID.randomUUID()));
        missing.session.percentage = null;
        GenericCraftingService.Response missingStatus = missing.service.status(
                alice, missingAccepted.job().jobId());
        require(missingStatus.job().state() == GenericCraftingService.State.UNKNOWN,
                "missing native task becomes unknown without inventing completion");

        Fixture clampedLow = fixture();
        clampedLow.session.percentage = -25;
        GenericCraftingService.Response low = clampedLow.service.submit(
                alice, submission(UUID.randomUUID()));
        require(low.job().state() == GenericCraftingService.State.RUNNING
                        && low.job().completedWork() == 0,
                "negative native progress is clamped to zero");

        Fixture clampedHigh = fixture();
        clampedHigh.session.percentage = 250;
        GenericCraftingService.Response high = clampedHigh.service.submit(
                alice, submission(UUID.randomUUID()));
        require(high.job().state() == GenericCraftingService.State.COMPLETED
                        && high.job().completedWork() == high.job().totalWork(),
                "native progress above one hundred is clamped to completion");

        Fixture cancellable = fixture();
        GenericCraftingService.Response cancellableAccepted = cancellable.service.submit(
                alice, submission(UUID.randomUUID()));
        UUID cancellableJob = cancellableAccepted.job().jobId();
        GenericCraftingService.Response cancelled = cancellable.service.cancel(alice, cancellableJob);
        require(cancelled.disposition() == GenericCraftingService.Disposition.CANCELLED
                        && cancelled.job().state() == GenericCraftingService.State.CANCELLED,
                "successful native cancellation persists a cancelled terminal job");
        require(cancellable.session.cancels == 1, "native cancellation is issued exactly once");
        GenericCraftingService.Response repeatedCancel = cancellable.service.cancel(alice, cancellableJob);
        require(repeatedCancel.failure().code() == GenericCraftingService.FailureCode.CONFLICT,
                "repeated cancellation conflicts after terminal state");
        require(cancellable.session.cancels == 1,
                "repeated cancellation cannot issue another native cancellation");

        System.out.println("Refined Storage crafting service tests: OK");
    }

    private static GenericCraftingService.Submission submission(UUID operation) {
        return new GenericCraftingService.Submission(operation, "minecraft:diamond", 4);
    }

    private static Fixture fixture() {
        BlockEntityType<?> type = BlockEntityType.Builder.of(
                (position, state) -> { throw new UnsupportedOperationException("test-only type"); },
                Blocks.STONE).build(null);
        RefinedStorageBridgeBlockEntity bridge = new RefinedStorageBridgeBlockEntity(
                type, BlockPos.ZERO, Blocks.STONE.defaultBlockState());
        FakeSession session = new FakeSession();
        FakeEnvironment environment = new FakeEnvironment(session);
        return new Fixture(bridge, session, environment,
                new RefinedStorageCraftingService(bridge, environment));
    }

    private record Fixture(RefinedStorageBridgeBlockEntity bridge, FakeSession session,
                           FakeEnvironment environment, RefinedStorageCraftingService service) {}

    private static final class FakeEnvironment implements RefinedStorageCraftingService.Environment {
        private final FakeSession session;
        private RefinedStorageCraftingService.Authorization authorization;
        private boolean validResource = true;
        private long clock = 1;

        private FakeEnvironment(FakeSession session) {
            this.session = session;
            this.authorization = RefinedStorageCraftingService.Authorization.success(session);
        }
        @Override public RefinedStorageCraftingService.Authorization authorize(DeviceCallContext caller) {
            return authorization;
        }
        @Override public boolean validResource(String resourceId) { return validResource; }
        @Override public long now() { return clock++; }
    }

    private static final class FakeSession implements RefinedStorageCraftingService.NativeSession {
        private final UUID taskId = UUID.randomUUID();
        private int requests;
        private Runnable beforeRequest = () -> {};
        private boolean returnNull;
        private boolean throwRequest;
        private boolean throwLookup;
        private int lookups;
        private boolean throwCancel;
        private Integer percentage = 25;
        private int cancels;

        @Override public UUID requestAndStart(String resourceId, int amount) {
            beforeRequest.run();
            requests++;
            if (throwRequest) throw new IllegalStateException("request fault");
            return returnNull ? null : taskId;
        }
        @Override public Integer completionPercentage(UUID nativeTaskId) {
            lookups++;
            if (throwLookup) throw new IllegalStateException("lookup fault");
            return percentage;
        }
        @Override public void cancel(UUID nativeTaskId) {
            if (throwCancel) throw new IllegalStateException("cancel fault");
            cancels++;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
