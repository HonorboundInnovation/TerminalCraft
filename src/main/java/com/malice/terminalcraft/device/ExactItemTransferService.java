package com.malice.terminalcraft.device;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Authority-preserving boundary between endpoint UUID resolution and exact-item coordination.
 * Resolution must return only endpoints visible to the current caller/host context.
 */
final class ExactItemTransferService<T> {
    interface Resolver<T> {
        Resolution<T> resolve(UUID endpointId);
    }

    enum ResolutionStatus {
        FOUND, NOT_FOUND, CHUNK_UNLOADED, UNSUPPORTED, PERMISSION_DENIED
    }

    record Resolution<T>(ResolutionStatus status, ResolvedEndpoint<T> endpoint) {
        Resolution {
            Objects.requireNonNull(status, "status");
            if ((status == ResolutionStatus.FOUND) != (endpoint != null)) {
                throw new IllegalArgumentException("only found resolutions may contain an endpoint");
            }
        }

        static <T> Resolution<T> found(ResolvedEndpoint<T> endpoint) {
            return new Resolution<>(ResolutionStatus.FOUND,
                    Objects.requireNonNull(endpoint, "endpoint"));
        }

        static <T> Resolution<T> failure(ResolutionStatus status) {
            if (status == ResolutionStatus.FOUND) {
                throw new IllegalArgumentException("found resolution requires an endpoint");
            }
            return new Resolution<>(status, null);
        }

        Optional<ResolvedEndpoint<T>> endpointOptional() {
            return Optional.ofNullable(endpoint);
        }
    }

    /** backingIdentity is internal and must identify the actual mutable storage, not its address. */
    record ResolvedEndpoint<T>(UUID id, Object backingIdentity,
                               ExactItemTransferCoordinator.Port<T> port) {
        ResolvedEndpoint {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(backingIdentity, "backingIdentity");
            Objects.requireNonNull(port, "port");
        }
    }

    private final ExactItemTransferCoordinator<T> coordinator;
    private final Resolver<T> resolver;

    ExactItemTransferService(ExactItemTransferCoordinator<T> coordinator, Resolver<T> resolver) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    DeviceResult transfer(DeviceCallContext context, UUID operationId, UUID sourceId,
                          UUID destinationId, String resourceId, int count) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(destinationId, "destinationId");

        if (!DeviceAuthorization.allows(context, DeviceAuthorization.Action.MUTATE)) {
            return DeviceResult.failure(DeviceErrorCode.PERMISSION_DENIED,
                    "item transfer requires device.write", false);
        }
        if (sourceId.equals(destinationId)) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT,
                    "source and destination must differ", false);
        }
        if (resourceId == null || !resourceId.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT,
                    "resource must be a namespaced identifier", false);
        }
        if (count < 1 || count > ExactItemTransferCoordinator.MAX_TRANSFER_AMOUNT) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT,
                    "count must be from 1 to " + ExactItemTransferCoordinator.MAX_TRANSFER_AMOUNT, false);
        }

        Optional<ExactItemTransferCoordinator.TransferResult> replay = coordinator.replayResult(
                context, operationId, sourceId, destinationId, resourceId, count);
        if (replay.isPresent()) return map(replay.orElseThrow());

        Resolution<T> sourceResolution = resolver.resolve(sourceId);
        if (sourceResolution.status() != ResolutionStatus.FOUND) {
            return resolutionFailure("source", sourceResolution.status());
        }
        ResolvedEndpoint<T> source = sourceResolution.endpointOptional().orElseThrow();
        if (!source.id().equals(sourceId)) {
            return resolutionFailure("source", ResolutionStatus.NOT_FOUND);
        }

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
                    "source and destination refer to the same backing item storage", false);
        }

        ExactItemTransferCoordinator.TransferResult result = coordinator.transfer(context, operationId,
                sourceId, source.port(), destinationId, destination.port(), resourceId, count);
        return map(result);
    }

    private static DeviceResult resolutionFailure(String role, ResolutionStatus status) {
        return switch (status) {
            case NOT_FOUND -> DeviceResult.failure(DeviceErrorCode.NOT_FOUND,
                    role + " item endpoint is not currently available", true);
            case CHUNK_UNLOADED -> DeviceResult.failure(DeviceErrorCode.CHUNK_UNLOADED,
                    role + " item endpoint chunk is unloaded", true);
            case PERMISSION_DENIED -> DeviceResult.failure(DeviceErrorCode.PERMISSION_DENIED,
                    role + " endpoint mutation is denied by the optional integration policy", false);
            case UNSUPPORTED -> DeviceResult.failure(DeviceErrorCode.UNSUPPORTED,
                    role + " endpoint does not expose an item capability", false);
            case FOUND -> throw new IllegalArgumentException("found resolution is not a failure");
        };
    }

    private static DeviceResult map(ExactItemTransferCoordinator.TransferResult result) {
        return switch (result.status()) {
            case COMPLETE, PARTIAL, ESCROWED -> DeviceResult.success(value(result));
            case PERMISSION_DENIED -> DeviceResult.failure(DeviceErrorCode.PERMISSION_DENIED,
                    "item transfer requires device.write", false);
            case SAME_ENDPOINT -> DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT,
                    "source and destination must differ", false);
            case OPERATION_CONFLICT -> DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT,
                    "operation ID was already used for a different request", false);
            case ESCROW_CAPACITY -> DeviceResult.failure(DeviceErrorCode.CAPACITY_EXCEEDED,
                    "item transfer escrow capacity is exhausted", false);
            case SOURCE_ERROR -> DeviceResult.failure(DeviceErrorCode.ADAPTER_ERROR,
                    "source item endpoint failed", true);
            case DESTINATION_ERROR -> DeviceResult.failure(DeviceErrorCode.ADAPTER_ERROR,
                    "destination item endpoint failed", true);
        };
    }

    private static DeviceValue.MapValue value(ExactItemTransferCoordinator.TransferResult result) {
        Map<String, DeviceValue> values = new LinkedHashMap<>();
        values.put("status", DeviceValue.of(result.status().name().toLowerCase(java.util.Locale.ROOT)));
        values.put("requested", DeviceValue.of(result.requested()));
        values.put("extracted", DeviceValue.of(result.extracted()));
        values.put("inserted", DeviceValue.of(result.inserted()));
        values.put("rolled_back", DeviceValue.of(result.rolledBack()));
        values.put("escrowed", DeviceValue.of(result.escrowed()));
        values.put("complete", DeviceValue.of(result.complete()));
        values.put("replayed", DeviceValue.of(result.replayed()));
        return new DeviceValue.MapValue(values);
    }
}
