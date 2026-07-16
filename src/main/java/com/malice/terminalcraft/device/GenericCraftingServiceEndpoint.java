package com.malice.terminalcraft.device;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Device API projection of the implementation-neutral crafting-service/job contract. */
public final class GenericCraftingServiceEndpoint implements ContextualDeviceEndpoint {
    private static final DeviceParameterDescriptor OPERATION_ID = parameter(
            "operation_id", DeviceValueType.STRING, "Client-generated UUID idempotency key");
    private static final DeviceParameterDescriptor JOB_ID = parameter(
            "job_id", DeviceValueType.STRING, "Stable TerminalCraft crafting job UUID");
    private static final DeviceParameterDescriptor RESOURCE = parameter(
            "resource", DeviceValueType.STRING, "Namespaced output resource identifier");
    private static final DeviceParameterDescriptor AMOUNT = parameter(
            "amount", DeviceValueType.NUMBER, "Positive output amount");

    private static final DeviceMethodDescriptor METADATA = method("crafting.metadata",
            "Returns the generic crafting-service contract", List.of(), DeviceValueType.MAP,
            DeviceCallContext.READ);
    private static final DeviceMethodDescriptor SUBMIT = method("crafting.submit",
            "Submits or replays one bounded crafting operation",
            List.of(OPERATION_ID, RESOURCE, AMOUNT), DeviceValueType.MAP, DeviceCallContext.WRITE);
    private static final DeviceMethodDescriptor STATUS = method("crafting.status",
            "Returns one caller-authorized crafting job snapshot",
            List.of(JOB_ID), DeviceValueType.MAP, DeviceCallContext.READ);
    private static final DeviceMethodDescriptor CANCEL = method("crafting.cancel",
            "Requests cancellation through the authoritative native service",
            List.of(JOB_ID), DeviceValueType.MAP, DeviceCallContext.WRITE);

    private final UUID deviceId;
    private final String adapterId;
    private final String displayName;
    private final String modSource;
    private final String address;
    private final GenericCraftingService service;
    private final BooleanSupplier online;
    private final BooleanSupplier loaded;

    public GenericCraftingServiceEndpoint(UUID deviceId, String adapterId, String displayName,
                                           String modSource, String address,
                                           GenericCraftingService service,
                                           BooleanSupplier online, BooleanSupplier loaded) {
        this.deviceId = Objects.requireNonNull(deviceId, "deviceId");
        this.adapterId = Objects.requireNonNull(adapterId, "adapterId");
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.modSource = Objects.requireNonNull(modSource, "modSource");
        this.address = Objects.requireNonNull(address, "address");
        this.service = Objects.requireNonNull(service, "service");
        this.online = Objects.requireNonNull(online, "online");
        this.loaded = Objects.requireNonNull(loaded, "loaded");
    }

    @Override
    public DeviceDescriptor descriptor() {
        GenericCraftingService.Metadata metadata = service.metadata();
        Map<String, DeviceValue> properties = new LinkedHashMap<>();
        properties.put("crafting_contract", DeviceValue.of("terminalcraft:crafting_service"));
        properties.put("crafting_contract_version", DeviceValue.of(GenericCraftingService.CONTRACT_VERSION));
        properties.put("crafting_service_kind", DeviceValue.of(metadata.serviceKind()));
        properties.put("crafting_max_request_amount", DeviceValue.of(
                Long.toString(GenericCraftingService.MAX_REQUEST_AMOUNT)));
        properties.put("crafting_max_active_jobs", DeviceValue.of(metadata.maxActiveJobs()));
        properties.put("crafting_cancellation_supported", DeviceValue.of(metadata.cancellationSupported()));
        properties.put("crafting_amount_encoding", DeviceValue.of("decimal_string_in_results"));
        properties.put("crafting_submission_semantics", DeviceValue.of("operation_id_idempotent"));
        properties.put("crafting_restart_state", DeviceValue.of("adapter_reconciled"));
        return new DeviceDescriptor(deviceId, adapterId, "crafting_service", displayName,
                modSource, address, Set.of("crafting_service"), properties,
                List.of(METADATA, SUBMIT, STATUS, CANCEL), Set.of(),
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE),
                online.getAsBoolean(), loaded.getAsBoolean());
    }

    @Override
    public DeviceResult call(DeviceCallContext context, String method, List<DeviceValue> arguments) {
        Objects.requireNonNull(context, "context");
        List<DeviceValue> args = arguments == null ? List.of() : arguments;
        try {
            return switch (method == null ? "" : method) {
                case "crafting.metadata" -> {
                    requireArgumentCount(args, 0, "crafting.metadata");
                    yield DeviceResult.success(metadataValue(service.metadata()));
                }
                case "crafting.submit" -> {
                    requireArgumentCount(args, 3, "crafting.submit");
                    yield map(service.submit(context, new GenericCraftingService.Submission(
                            uuid(args.get(0), "operation_id"), string(args.get(1), "resource"),
                            amount(args.get(2)))));
                }
                case "crafting.status" -> {
                    requireArgumentCount(args, 1, "crafting.status");
                    yield map(service.status(context, uuid(args.get(0), "job_id")));
                }
                case "crafting.cancel" -> {
                    requireArgumentCount(args, 1, "crafting.cancel");
                    yield map(service.cancel(context, uuid(args.get(0), "job_id")));
                }
                default -> DeviceResult.failure(DeviceErrorCode.UNSUPPORTED,
                        "method is unsupported", false);
            };
        } catch (IllegalArgumentException exception) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT,
                    exception.getMessage() == null ? "invalid crafting argument" : exception.getMessage(), false);
        }
    }

    private static DeviceValue metadataValue(GenericCraftingService.Metadata metadata) {
        return DeviceValue.map(Map.of(
                "contract", DeviceValue.of("terminalcraft:crafting_service"),
                "version", DeviceValue.of(GenericCraftingService.CONTRACT_VERSION),
                "service_kind", DeviceValue.of(metadata.serviceKind()),
                "operation_id_required", DeviceValue.of(true),
                "cancellation_supported", DeviceValue.of(metadata.cancellationSupported()),
                "max_active_jobs", DeviceValue.of(metadata.maxActiveJobs()),
                "max_request_amount", DeviceValue.of(Long.toString(GenericCraftingService.MAX_REQUEST_AMOUNT))));
    }

    private static DeviceResult map(GenericCraftingService.Response response) {
        Objects.requireNonNull(response, "crafting service response");
        if (response.failure() != null) {
            GenericCraftingService.Failure failure = response.failure();
            return DeviceResult.failure(mapCode(failure.code()), failure.message(), failure.retryable());
        }
        Map<String, DeviceValue> result = new LinkedHashMap<>(jobValue(response.job()).values());
        result.put("disposition", DeviceValue.of(response.disposition().name().toLowerCase(java.util.Locale.ROOT)));
        return DeviceResult.success(DeviceValue.map(result));
    }

    private static DeviceValue.MapValue jobValue(GenericCraftingService.Job job) {
        Map<String, DeviceValue> value = new LinkedHashMap<>();
        value.put("job_id", DeviceValue.of(job.jobId().toString()));
        value.put("operation_id", DeviceValue.of(job.operationId().toString()));
        value.put("resource", DeviceValue.of(job.resourceId()));
        value.put("amount", DeviceValue.of(Long.toString(job.amount())));
        value.put("state", DeviceValue.of(job.state().name().toLowerCase(java.util.Locale.ROOT)));
        value.put("terminal", DeviceValue.of(job.state().terminal()));
        value.put("completed_work", DeviceValue.of(Long.toString(job.completedWork())));
        value.put("total_work", DeviceValue.of(Long.toString(job.totalWork())));
        value.put("progress_known", DeviceValue.of(job.totalWork() > 0));
        value.put("terminal_result", DeviceValue.of(job.terminalResult()));
        value.put("last_error", DeviceValue.of(job.lastError()));
        value.put("cancellation_supported", DeviceValue.of(job.cancellationSupported()));
        value.put("updated_at", DeviceValue.of(Long.toString(job.updatedAt())));
        return new DeviceValue.MapValue(value);
    }

    private static DeviceErrorCode mapCode(GenericCraftingService.FailureCode code) {
        return switch (code) {
            case INVALID_ARGUMENT -> DeviceErrorCode.INVALID_ARGUMENT;
            case NOT_FOUND -> DeviceErrorCode.NOT_FOUND;
            case PERMISSION_DENIED -> DeviceErrorCode.PERMISSION_DENIED;
            case UNSUPPORTED -> DeviceErrorCode.UNSUPPORTED;
            case OFFLINE -> DeviceErrorCode.OFFLINE;
            case UNAVAILABLE -> DeviceErrorCode.CHUNK_UNLOADED;
            case CONFLICT -> DeviceErrorCode.BUSY;
            case CAPACITY_EXCEEDED -> DeviceErrorCode.CAPACITY_EXCEEDED;
            case ADAPTER_ERROR -> DeviceErrorCode.ADAPTER_ERROR;
        };
    }

    private static void requireArgumentCount(List<DeviceValue> arguments, int expected, String method) {
        if (arguments.size() != expected) {
            throw new IllegalArgumentException(method + " requires exactly " + expected + " arguments");
        }
    }

    private static UUID uuid(DeviceValue value, String name) {
        String text = string(value, name);
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(name + " must be a UUID");
        }
    }

    private static String string(DeviceValue value, String name) {
        if (!(value instanceof DeviceValue.StringValue string)) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return string.value();
    }

    private static long amount(DeviceValue value) {
        if (!(value instanceof DeviceValue.NumberValue number)) {
            throw new IllegalArgumentException("amount must be a number");
        }
        double raw = number.value();
        if (raw != Math.rint(raw) || raw < 1 || raw > GenericCraftingService.MAX_REQUEST_AMOUNT) {
            throw new IllegalArgumentException("amount must be a positive bounded integer");
        }
        return (long) raw;
    }

    private static DeviceParameterDescriptor parameter(String name, DeviceValueType type, String description) {
        return new DeviceParameterDescriptor(name, type, true, description);
    }

    private static DeviceMethodDescriptor method(String name, String description,
                                                  List<DeviceParameterDescriptor> parameters,
                                                  DeviceValueType result, String permission) {
        return new DeviceMethodDescriptor(name, description, parameters, result, permission);
    }
}
