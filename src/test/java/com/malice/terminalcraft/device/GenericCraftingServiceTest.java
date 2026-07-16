package com.malice.terminalcraft.device;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Headless contract coverage for generic crafting submission, replay, status and cancellation. */
public final class GenericCraftingServiceTest {
    private GenericCraftingServiceTest() {}

    public static void main(String[] args) {
        FakeService service = new FakeService();
        UUID endpointId = UUID.randomUUID();
        GenericCraftingServiceEndpoint endpoint = new GenericCraftingServiceEndpoint(
                endpointId, "terminalcraft:test_crafting", "Test Crafting", "terminalcraft",
                "test:crafting", service, () -> true, () -> true);
        DeviceRegistry registry = new DeviceRegistry();
        registry.register(endpoint);

        DeviceDescriptor descriptor = registry.descriptor(endpointId).orElseThrow();
        require(descriptor.capabilities().contains("crafting_service"), "crafting capability advertised");
        require(descriptor.methods().stream().anyMatch(method -> method.name().equals("crafting.submit")
                        && method.requiredPermission().equals(DeviceCallContext.WRITE)),
                "submit requires write permission");

        DeviceCallContext reader = new DeviceCallContext(UUID.randomUUID(), "reader",
                Set.of(DeviceCallContext.READ));
        UUID operationId = UUID.randomUUID();
        DeviceResult denied = registry.call(reader, endpointId, "crafting.submit",
                submitArgs(operationId, "minecraft:diamond", 4));
        require(error(denied) == DeviceErrorCode.PERMISSION_DENIED, "read-only submit denied");
        require(service.submitCalls == 0, "permission denial occurs before service invocation");

        DeviceCallContext alice = new DeviceCallContext(UUID.randomUUID(), "alice",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        DeviceResult accepted = registry.call(alice, endpointId, "crafting.submit",
                submitArgs(operationId, "minecraft:diamond", 4));
        DeviceValue.MapValue acceptedValue = map(accepted);
        UUID jobId = UUID.fromString(string(acceptedValue, "job_id"));
        require("accepted".equals(string(acceptedValue, "disposition")), "first submit accepted");
        require("queued".equals(string(acceptedValue, "state")), "new job queued");
        require("4".equals(string(acceptedValue, "amount")), "amount serialized losslessly");

        DeviceResult replayed = registry.call(alice, endpointId, "crafting.submit",
                submitArgs(operationId, "minecraft:diamond", 4));
        require("replayed".equals(string(map(replayed), "disposition")), "same operation replays");
        require(jobId.toString().equals(string(map(replayed), "job_id")), "replay retains job ID");
        require(service.nativeSubmissions == 1, "replay cannot duplicate native submission");

        DeviceResult conflict = registry.call(alice, endpointId, "crafting.submit",
                submitArgs(operationId, "minecraft:emerald", 4));
        require(error(conflict) == DeviceErrorCode.BUSY && !conflict.error().orElseThrow().retryable(),
                "operation reuse with different request conflicts without retry");

        service.advance(jobId, 2, 4);
        DeviceValue.MapValue running = map(registry.call(alice, endpointId, "crafting.status",
                List.of(DeviceValue.of(jobId.toString()))));
        require("running".equals(string(running, "state")), "status exposes running state");
        require("2".equals(string(running, "completed_work"))
                && "4".equals(string(running, "total_work")), "status exposes exact progress");
        require(bool(running, "progress_known"), "known progress is explicit");

        DeviceCallContext bob = new DeviceCallContext(UUID.randomUUID(), "bob",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        require(error(registry.call(bob, endpointId, "crafting.status",
                List.of(DeviceValue.of(jobId.toString())))) == DeviceErrorCode.NOT_FOUND,
                "another principal cannot discover job state");
        require(error(registry.call(bob, endpointId, "crafting.cancel",
                List.of(DeviceValue.of(jobId.toString())))) == DeviceErrorCode.PERMISSION_DENIED,
                "another principal cannot cancel job");

        DeviceCallContext sameIdService = DeviceCallContext.service(alice.principalId(),
                "alice-service", Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        require(error(registry.call(sameIdService, endpointId, "crafting.status",
                List.of(DeviceValue.of(jobId.toString())))) == DeviceErrorCode.NOT_FOUND,
                "same UUID with another principal kind cannot discover job state");
        require(error(registry.call(sameIdService, endpointId, "crafting.cancel",
                List.of(DeviceValue.of(jobId.toString())))) == DeviceErrorCode.PERMISSION_DENIED,
                "same UUID with another principal kind cannot cancel job");

        DeviceValue.MapValue cancelled = map(registry.call(alice, endpointId, "crafting.cancel",
                List.of(DeviceValue.of(jobId.toString()))));
        require("cancelled".equals(string(cancelled, "state")) && bool(cancelled, "terminal"),
                "owner cancellation produces terminal state");
        require("cancelled".equals(string(cancelled, "terminal_result")),
                "terminal result is explicit");

        require(error(registry.call(alice, endpointId, "crafting.submit",
                submitArgs(UUID.randomUUID(), "not namespaced", 1))) == DeviceErrorCode.INVALID_ARGUMENT,
                "invalid resource rejected");
        require(error(registry.call(alice, endpointId, "crafting.submit",
                submitArgs(UUID.randomUUID(), "minecraft:stone",
                        GenericCraftingService.MAX_REQUEST_AMOUNT + 1))) == DeviceErrorCode.INVALID_ARGUMENT,
                "oversized amount rejected");
        require(error(registry.call(alice, endpointId, "crafting.status",
                List.of(DeviceValue.of("not-a-uuid")))) == DeviceErrorCode.INVALID_ARGUMENT,
                "malformed job ID rejected");
        require(error(registry.call(alice, endpointId, "crafting.submit", List.of()))
                        == DeviceErrorCode.INVALID_ARGUMENT,
                "missing submit arguments are rejected without invoking the service");
        require(error(registry.call(alice, endpointId, "crafting.status", List.of(
                        DeviceValue.of(jobId.toString()), DeviceValue.of("extra"))))
                        == DeviceErrorCode.INVALID_ARGUMENT,
                "excess status arguments are rejected");
        require(error(registry.call(alice, endpointId, "crafting.cancel", List.of()))
                        == DeviceErrorCode.INVALID_ARGUMENT,
                "missing cancel argument is rejected");
        require(error(registry.call(alice, endpointId, "crafting.metadata", List.of(DeviceValue.of("extra"))))
                        == DeviceErrorCode.INVALID_ARGUMENT,
                "metadata rejects excess arguments");

        reject(() -> new GenericCraftingService.Job(UUID.randomUUID(), UUID.randomUUID(),
                alice.principalId(), "minecraft:stone", 1, GenericCraftingService.State.RUNNING,
                2, 1, "", "", true, 0), "invalid progress");
        reject(() -> new GenericCraftingService.Job(UUID.randomUUID(), UUID.randomUUID(),
                alice.principalId(), "minecraft:stone", 1, GenericCraftingService.State.COMPLETED,
                1, 1, "", "", true, 0), "completed state without result");

        System.out.println("Generic crafting service tests: OK");
    }

    private static List<DeviceValue> submitArgs(UUID operationId, String resource, long amount) {
        return List.of(DeviceValue.of(operationId.toString()), DeviceValue.of(resource), DeviceValue.of(amount));
    }

    private static DeviceValue.MapValue map(DeviceResult result) {
        require(result.isSuccess(), "expected successful result: " + result.error());
        return (DeviceValue.MapValue) result.value().orElseThrow();
    }

    private static String string(DeviceValue.MapValue value, String key) {
        return ((DeviceValue.StringValue) value.values().get(key)).value();
    }

    private static boolean bool(DeviceValue.MapValue value, String key) {
        return ((DeviceValue.BooleanValue) value.values().get(key)).value();
    }

    private static DeviceErrorCode error(DeviceResult result) {
        return result.error().orElseThrow().code();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void reject(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message + ": expected rejection");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static final class FakeService implements GenericCraftingService {
        private final Map<UUID, Job> jobs = new LinkedHashMap<>();
        private final Map<Key, UUID> operations = new LinkedHashMap<>();
        private final Map<Key, Submission> requests = new LinkedHashMap<>();
        private int submitCalls;
        private int nativeSubmissions;
        private long clock = 1;

        @Override public Metadata metadata() { return new Metadata("test", true, 8); }

        @Override
        public Response submit(DeviceCallContext caller, Submission submission) {
            submitCalls++;
            Key key = new Key(caller.principal(), submission.operationId());
            UUID existingId = operations.get(key);
            if (existingId != null) {
                if (!requests.get(key).equals(submission)) {
                    return Response.failure(FailureCode.CONFLICT,
                            "operation ID was already used for a different request", false);
                }
                return Response.success(Disposition.REPLAYED, jobs.get(existingId));
            }
            if (jobs.values().stream().filter(job -> !job.state().terminal()).count()
                    >= metadata().maxActiveJobs()) {
                return Response.failure(FailureCode.CAPACITY_EXCEEDED, "crafting queue is full", true);
            }
            UUID id = UUID.randomUUID();
            Job job = new Job(id, submission.operationId(), caller.principal(),
                    submission.resourceId(), submission.amount(), State.QUEUED,
                    0, 0, "", "", true, clock++);
            jobs.put(id, job);
            operations.put(key, id);
            requests.put(key, submission);
            nativeSubmissions++;
            return Response.success(Disposition.ACCEPTED, job);
        }

        @Override
        public Response status(DeviceCallContext caller, UUID jobId) {
            Job job = jobs.get(jobId);
            if (job == null || !job.ownedBy(caller)) {
                return Response.failure(FailureCode.NOT_FOUND, "crafting job not found", false);
            }
            return Response.success(Disposition.FOUND, job);
        }

        @Override
        public Response cancel(DeviceCallContext caller, UUID jobId) {
            Job job = jobs.get(jobId);
            if (job == null) return Response.failure(FailureCode.NOT_FOUND, "crafting job not found", false);
            if (!job.ownedBy(caller)) {
                return Response.failure(FailureCode.PERMISSION_DENIED,
                        "only the submitting principal may cancel this job", false);
            }
            if (job.state().terminal()) {
                return Response.failure(FailureCode.CONFLICT, "crafting job is already terminal", false);
            }
            Job cancelled = new Job(job.jobId(), job.operationId(), job.principal(),
                    job.resourceId(), job.amount(), State.CANCELLED, job.completedWork(),
                    job.totalWork(), "cancelled", "", job.cancellationSupported(), clock++);
            jobs.put(jobId, cancelled);
            return Response.success(Disposition.CANCELLED, cancelled);
        }

        void advance(UUID jobId, long completed, long total) {
            Job job = jobs.get(jobId);
            jobs.put(jobId, new Job(job.jobId(), job.operationId(), job.principal(),
                    job.resourceId(), job.amount(), State.RUNNING, completed, total,
                    "", "", true, clock++));
        }

        private record Key(PrincipalIdentity principal, UUID operationId) {}
    }
}
