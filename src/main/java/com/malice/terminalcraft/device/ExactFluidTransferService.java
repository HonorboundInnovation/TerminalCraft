package com.malice.terminalcraft.device;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Authority-preserving boundary between endpoint UUID resolution and exact-fluid coordination. */
final class ExactFluidTransferService<T> {
    interface Resolver<T> { Resolution<T> resolve(UUID endpointId); }

    enum ResolutionStatus { FOUND, NOT_FOUND, CHUNK_UNLOADED, UNSUPPORTED, PERMISSION_DENIED }

    record Resolution<T>(ResolutionStatus status, ResolvedEndpoint<T> endpoint) {
        Resolution {
            Objects.requireNonNull(status, "status");
            if ((status == ResolutionStatus.FOUND) != (endpoint != null)) {
                throw new IllegalArgumentException("only found resolutions may contain an endpoint");
            }
        }
        static <T> Resolution<T> found(ResolvedEndpoint<T> endpoint) {
            return new Resolution<>(ResolutionStatus.FOUND, Objects.requireNonNull(endpoint, "endpoint"));
        }
        static <T> Resolution<T> failure(ResolutionStatus status) {
            if (status == ResolutionStatus.FOUND) throw new IllegalArgumentException("found requires endpoint");
            return new Resolution<>(status, null);
        }
        Optional<ResolvedEndpoint<T>> endpointOptional() { return Optional.ofNullable(endpoint); }
    }

    record ResolvedEndpoint<T>(UUID id, Object backingIdentity,
                               ExactFluidTransferCoordinator.Port<T> port) {
        ResolvedEndpoint {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(backingIdentity, "backingIdentity");
            Objects.requireNonNull(port, "port");
        }
    }

    private final ExactFluidTransferCoordinator<T> coordinator;
    private final Resolver<T> resolver;

    ExactFluidTransferService(ExactFluidTransferCoordinator<T> coordinator, Resolver<T> resolver) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    DeviceResult transfer(DeviceCallContext context, UUID operationId, UUID sourceId,
                          UUID destinationId, String resourceId, int amountMb) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(destinationId, "destinationId");

        if (!DeviceAuthorization.allows(context, DeviceAuthorization.Action.MUTATE)) {
            return DeviceResult.failure(DeviceErrorCode.PERMISSION_DENIED,
                    "fluid transfer requires device.write", false);
        }
        if (sourceId.equals(destinationId)) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT,
                    "source and destination must differ", false);
        }
        if (resourceId == null || !resourceId.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT,
                    "resource must be a namespaced identifier", false);
        }
        if (amountMb < 1 || amountMb > ExactFluidTransferCoordinator.MAX_TRANSFER_AMOUNT) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT,
                    "amount must be from 1 to " + ExactFluidTransferCoordinator.MAX_TRANSFER_AMOUNT + " mB", false);
        }

        Optional<ExactFluidTransferCoordinator.TransferResult> replay = coordinator.replayResult(
                context, operationId, sourceId, destinationId, resourceId, amountMb);
        if (replay.isPresent()) return map(replay.orElseThrow());

        Resolution<T> sourceResolution = resolver.resolve(sourceId);
        if (sourceResolution.status() != ResolutionStatus.FOUND) {
            return resolutionFailure("source", sourceResolution.status());
        }
        ResolvedEndpoint<T> source = sourceResolution.endpointOptional().orElseThrow();
        if (!source.id().equals(sourceId)) return resolutionFailure("source", ResolutionStatus.NOT_FOUND);

        Resolution<T> destinationResolution = resolver.resolve(destinationId);
        if (destinationResolution.status() != ResolutionStatus.FOUND) {
            return resolutionFailure("destination", destinationResolution.status());
        }
        ResolvedEndpoint<T> destination = destinationResolution.endpointOptional().orElseThrow();
        if (!destination.id().equals(destinationId)) {
            return resolutionFailure("destination", ResolutionStatus.NOT_FOUND);
        }
        if (source.backingIdentity() == destination.backingIdentity()) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT,
                    "source and destination refer to the same backing fluid storage", false);
        }

        return map(coordinator.transfer(context, operationId, sourceId, source.port(),
                destinationId, destination.port(), resourceId, amountMb));
    }

    private static DeviceResult resolutionFailure(String role, ResolutionStatus status) {
        return switch (status) {
            case NOT_FOUND -> DeviceResult.failure(DeviceErrorCode.NOT_FOUND,
                    role + " fluid endpoint is not currently available", true);
            case CHUNK_UNLOADED -> DeviceResult.failure(DeviceErrorCode.CHUNK_UNLOADED,
                    role + " fluid endpoint chunk is unloaded", true);
            case PERMISSION_DENIED -> DeviceResult.failure(DeviceErrorCode.PERMISSION_DENIED,
                    role + " endpoint mutation is denied by the optional integration policy", false);
            case UNSUPPORTED -> DeviceResult.failure(DeviceErrorCode.UNSUPPORTED,
                    role + " endpoint does not expose a fluid capability", false);
            case FOUND -> throw new IllegalArgumentException("found resolution is not a failure");
        };
    }

    private static DeviceResult map(ExactFluidTransferCoordinator.TransferResult result) {
        return switch (result.status()) {
            case COMPLETE, PARTIAL, ESCROWED -> DeviceResult.success(value(result));
            case PERMISSION_DENIED -> DeviceResult.failure(DeviceErrorCode.PERMISSION_DENIED,
                    "fluid transfer requires device.write", false);
            case SAME_ENDPOINT, OPERATION_CONFLICT -> DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT,
                    result.status() == ExactFluidTransferCoordinator.Status.SAME_ENDPOINT
                            ? "source and destination must differ"
                            : "operation ID was already used for a different request", false);
            case ESCROW_CAPACITY -> DeviceResult.failure(DeviceErrorCode.CAPACITY_EXCEEDED,
                    "fluid transfer escrow capacity is exhausted", false);
            case SOURCE_ERROR -> DeviceResult.failure(DeviceErrorCode.ADAPTER_ERROR,
                    "source fluid endpoint failed", true);
            case DESTINATION_ERROR -> DeviceResult.failure(DeviceErrorCode.ADAPTER_ERROR,
                    "destination fluid endpoint failed", true);
        };
    }

    private static DeviceValue.MapValue value(ExactFluidTransferCoordinator.TransferResult result) {
        Map<String, DeviceValue> values = new LinkedHashMap<>();
        values.put("status", DeviceValue.of(result.status().name().toLowerCase(java.util.Locale.ROOT)));
        values.put("unit", DeviceValue.of("mB"));
        values.put("requested", DeviceValue.of(result.requestedMb()));
        values.put("extracted", DeviceValue.of(result.extractedMb()));
        values.put("inserted", DeviceValue.of(result.insertedMb()));
        values.put("rolled_back", DeviceValue.of(result.rolledBackMb()));
        values.put("escrowed", DeviceValue.of(result.escrowedMb()));
        values.put("complete", DeviceValue.of(result.complete()));
        values.put("replayed", DeviceValue.of(result.replayed()));
        return new DeviceValue.MapValue(values);
    }
}
