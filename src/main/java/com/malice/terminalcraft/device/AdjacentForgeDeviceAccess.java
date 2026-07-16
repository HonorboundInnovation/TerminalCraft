package com.malice.terminalcraft.device;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Caller-local adjacency overlay for generic Forge capabilities. */
public final class AdjacentForgeDeviceAccess implements DeviceAccess, ExactItemTransferAccess, ExactItemEscrowAccess,
        ExactFluidTransferAccess, ExactFluidEscrowAccess {
    private final DeviceAccess base;
    private final ServerLevel level;
    private final BlockPos hostPosition;

    public AdjacentForgeDeviceAccess(DeviceAccess base, ServerLevel level, BlockPos hostPosition) {
        this.base = Objects.requireNonNull(base, "base");
        this.level = Objects.requireNonNull(level, "level");
        this.hostPosition = Objects.requireNonNull(hostPosition, "hostPosition").immutable();
    }

    @Override public DeviceCallContext context() { return base.context(); }

    @Override
    public List<DeviceDescriptor> descriptors(int limit) {
        requireServerThread();
        if (!DeviceAuthorization.allows(context(), DeviceAuthorization.Action.DISCOVER)) {
            return List.of();
        }
        int bounded = Math.max(0, Math.min(limit, DeviceRegistry.MAX_ENUMERATION_RESULTS));
        Map<UUID, DeviceDescriptor> merged = new LinkedHashMap<>();
        for (DeviceDescriptor descriptor : base.descriptors(bounded)) merged.put(descriptor.deviceId(), descriptor);
        for (DeviceEndpoint endpoint : adjacent().values()) {
            DeviceDescriptor descriptor = endpoint.descriptor();
            merged.putIfAbsent(descriptor.deviceId(), descriptor);
        }
        List<DeviceDescriptor> result = new ArrayList<>(merged.values());
        result.sort(Comparator.comparing(descriptor -> descriptor.deviceId().toString()));
        return List.copyOf(result.subList(0, Math.min(bounded, result.size())));
    }

    @Override
    public Optional<DeviceDescriptor> descriptor(UUID deviceId) {
        requireServerThread();
        if (!DeviceAuthorization.allows(context(), DeviceAuthorization.Action.DISCOVER)) {
            return Optional.empty();
        }
        Optional<DeviceDescriptor> registered = base.descriptor(deviceId);
        if (registered.isPresent()) return registered;
        DeviceEndpoint endpoint = adjacent().get(deviceId);
        return endpoint == null ? Optional.empty() : Optional.of(endpoint.descriptor());
    }

    @Override
    public DeviceResult call(UUID deviceId, String method, List<DeviceValue> arguments) {
        requireServerThread();
        DeviceResult admission = ServerDeviceManager.admitDeviceCall(
                level, hostPosition, context(), callWorkUnits(method));
        if (!admission.isSuccess()) return admission;
        if (base.descriptor(deviceId).isPresent()) return base.call(deviceId, method, arguments);
        Optional<AdjacentForgeEndpointResolver.Candidate> requested =
                AdjacentForgeEndpointResolver.candidate(level.dimension().location().toString(),
                        hostPosition, deviceId);
        if (requested.isEmpty()) {
            return DeviceResult.failure(DeviceErrorCode.NOT_FOUND,
                    "device is outside this host's adjacent endpoint authority", false);
        }
        AdjacentForgeEndpointResolver.Candidate requestedCandidate = requested.orElseThrow();
        if (baseOwnsAddress(requestedCandidate.positionAddress())) {
            return DeviceResult.failure(DeviceErrorCode.NOT_FOUND,
                    "adjacent endpoint is owned by a registered device adapter", false);
        }
        if (!level.hasChunkAt(requestedCandidate.target())) {
            return DeviceResult.failure(DeviceErrorCode.CHUNK_UNLOADED,
                    "device chunk is unloaded", true);
        }
        BlockEntity currentTarget = level.getBlockEntity(requestedCandidate.target());
        if (currentTarget == null || currentTarget.isRemoved()) {
            return DeviceResult.failure(DeviceErrorCode.REMOVED,
                    "adjacent device was removed", true);
        }
        DeviceEndpoint endpoint = adjacent().get(deviceId);
        if (endpoint == null) {
            return DeviceResult.failure(DeviceErrorCode.UNSUPPORTED,
                    "adjacent block does not expose a supported capability on the host-facing side", false);
        }
        boolean mutation = endpoint.descriptor().methods().stream()
                .anyMatch(candidate -> candidate.name().equals(method)
                        && DeviceCallContext.WRITE.equals(candidate.requiredPermission()));
        if (mutation && (method == null || !method.startsWith("crafting."))) {
            Optional<AdjacentForgeEndpointResolver.Candidate> candidate =
                    AdjacentForgeEndpointResolver.candidate(level.dimension().location().toString(), hostPosition, deviceId);
            BlockEntity target = candidate.map(value -> level.getBlockEntity(value.target())).orElse(null);
            if (target != null) {
                com.malice.terminalcraft.integration.OptionalDeviceMutationPolicyRegistry.Decision decision =
                        com.malice.terminalcraft.integration.OptionalDeviceMutationPolicyRegistry.evaluate(target);
                DeviceAuthorization.Decision authorization = DeviceAuthorization.decide(
                        context(), DeviceAuthorization.Action.MUTATE,
                        decision.allowed(), decision.reason());
                if (!authorization.allowed()) {
                    return DeviceResult.failure(DeviceErrorCode.PERMISSION_DENIED,
                            authorization.reason(), false);
                }
            }
        }
        DeviceRegistry local = new DeviceRegistry();
        local.register(endpoint);
        return local.call(context(), deviceId, method, arguments);
    }

    @Override public DeviceEventBatch pollEvents(int limit) { return base.pollEvents(limit); }

    /** Executes a caller-scoped exact transfer without accepting positions or capability sides. */
    @Override
    public DeviceResult transferExactItems(UUID operationId, UUID sourceId, UUID destinationId,
                                    String resourceId, int count) {
        requireServerThread();
        DeviceResult admission = ServerDeviceManager.admitDeviceCall(
                level, hostPosition, context(), 256);
        if (!admission.isSuccess()) return admission;
        ResourceLocation resource = ResourceLocation.tryParse(resourceId);
        if (resource == null || !BuiltInRegistries.ITEM.containsKey(resource)) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT,
                    "unknown item resource", false);
        }
        ExactItemTransferService<ItemStack> service = new ExactItemTransferService<>(
                ServerDeviceManager.itemTransfers(level.getServer()),
                id -> toTransferResolution(resolveItemEndpoint(id)));
        return service.transfer(context(), operationId, sourceId, destinationId, resourceId, count);
    }

    /** Executes a caller-scoped exact fluid transfer with durable replay and escrow custody. */
    @Override
    public DeviceResult transferExactFluid(UUID operationId, UUID sourceId, UUID destinationId,
                                           String resourceId, int amountMb) {
        requireServerThread();
        DeviceResult admission = ServerDeviceManager.admitDeviceCall(
                level, hostPosition, context(), 256);
        if (!admission.isSuccess()) return admission;
        ResourceLocation resource = ResourceLocation.tryParse(resourceId);
        if (resource == null || !BuiltInRegistries.FLUID.containsKey(resource)
                || BuiltInRegistries.FLUID.get(resource) == net.minecraft.world.level.material.Fluids.EMPTY) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT,
                    "unknown or empty fluid resource", false);
        }
        ExactFluidTransferService<FluidStack> service = new ExactFluidTransferService<>(
                ServerDeviceManager.fluidTransfers(level.getServer()),
                id -> toFluidTransferResolution(resolveFluidEndpoint(id)));
        return service.transfer(context(), operationId, sourceId, destinationId, resourceId, amountMb);
    }

    @Override
    public DeviceResult listFluidEscrow(int limit) {
        requireServerThread();
        int bounded = Math.max(0, Math.min(limit, ExactFluidTransferCoordinator.MAX_ESCROW_ENTRIES));
        DeviceResult admission = ServerDeviceManager.admitDeviceCall(
                level, hostPosition, context(), Math.max(1, bounded));
        if (!admission.isSuccess()) return admission;
        if (!DeviceAuthorization.allows(context(), DeviceAuthorization.Action.ESCROW_ADMIN)) {
            return DeviceResult.failure(DeviceErrorCode.PERMISSION_DENIED,
                    "fluid escrow requires device.escrow.admin", false);
        }
        List<ExactFluidTransferCoordinator.EscrowEntry<FluidStack>> escrow = new ArrayList<>(
                ServerDeviceManager.fluidTransfers(level.getServer()).escrowEntries().values());
        escrow.sort(Comparator.comparing(entry -> entry.escrowId().toString()));
        List<DeviceValue> entries = new ArrayList<>();
        for (ExactFluidTransferCoordinator.EscrowEntry<FluidStack> entry : escrow) {
            if (entries.size() == bounded) break;
            entries.add(fluidEscrowValue(entry));
        }
        return DeviceResult.success(DeviceValue.list(entries));
    }

    @Override
    public DeviceResult recoverFluidEscrow(UUID escrowId, UUID destinationId) {
        requireServerThread();
        DeviceResult admission = ServerDeviceManager.admitDeviceCall(
                level, hostPosition, context(), GenericCapabilityDevice.MAX_FLUID_TANKS);
        if (!admission.isSuccess()) return admission;
        if (!DeviceAuthorization.allows(context(), DeviceAuthorization.Action.ESCROW_ADMIN)) {
            return DeviceResult.failure(DeviceErrorCode.PERMISSION_DENIED,
                    "fluid escrow requires device.escrow.admin", false);
        }
        AdjacentForgeEndpointResolver.FluidResolution destination = resolveFluidEndpoint(destinationId);
        if (destination.status() != AdjacentForgeEndpointResolver.ItemResolutionStatus.FOUND) {
            return fluidResolutionFailure("destination", destination.status());
        }
        ExactFluidTransferCoordinator.EscrowRecoveryResult result =
                ServerDeviceManager.fluidTransfers(level.getServer()).recoverEscrow(
                        context(), escrowId, destination.endpointOptional().orElseThrow().port());
        return mapFluidEscrowRecovery(result);
    }

    @Override
    public DeviceResult listItemEscrow(int limit) {
        requireServerThread();
        int bounded = Math.max(0, Math.min(limit, ExactItemTransferCoordinator.MAX_ESCROW_PARTS));
        DeviceResult admission = ServerDeviceManager.admitDeviceCall(
                level, hostPosition, context(), Math.max(1, bounded));
        if (!admission.isSuccess()) return admission;
        if (!DeviceAuthorization.allows(context(), DeviceAuthorization.Action.ESCROW_ADMIN)) {
            return DeviceResult.failure(DeviceErrorCode.PERMISSION_DENIED,
                    "item escrow requires device.escrow.admin", false);
        }
        List<DeviceValue> entries = new ArrayList<>();
        for (ExactItemTransferCoordinator.EscrowEntry<ItemStack> entry
                : ServerDeviceManager.itemTransfers(level.getServer()).escrowEntries()) {
            if (entries.size() == bounded) break;
            Map<String, DeviceValue> value = new LinkedHashMap<>();
            value.put("escrow_id", DeviceValue.of(entry.escrowId().toString()));
            value.put("operation_id", DeviceValue.of(entry.operationId().toString()));
            value.put("source_id", DeviceValue.of(entry.sourceId().toString()));
            value.put("destination_id", DeviceValue.of(entry.destinationId().toString()));
            value.put("resource", DeviceValue.of(BuiltInRegistries.ITEM.getKey(entry.payload().getItem()).toString()));
            value.put("count", DeviceValue.of(entry.payload().getCount()));
            entries.add(DeviceValue.map(value));
        }
        return DeviceResult.success(DeviceValue.list(entries));
    }

    @Override
    public DeviceResult recoverItemEscrow(UUID escrowId, UUID destinationId) {
        requireServerThread();
        DeviceResult admission = ServerDeviceManager.admitDeviceCall(
                level, hostPosition, context(), GenericCapabilityDevice.MAX_INVENTORY_SLOTS);
        if (!admission.isSuccess()) return admission;
        if (!DeviceAuthorization.allows(context(), DeviceAuthorization.Action.ESCROW_ADMIN)) {
            return DeviceResult.failure(DeviceErrorCode.PERMISSION_DENIED,
                    "item escrow requires device.escrow.admin", false);
        }
        AdjacentForgeEndpointResolver.ItemResolution destination =
                resolveItemEndpoint(destinationId);
        if (destination.status() != AdjacentForgeEndpointResolver.ItemResolutionStatus.FOUND) {
            return itemResolutionFailure("destination", destination.status());
        }
        ExactItemTransferCoordinator.EscrowRecoveryResult result =
                ServerDeviceManager.itemTransfers(level.getServer()).recoverEscrow(
                        context(), escrowId, destination.endpointOptional().orElseThrow().port());
        return switch (result.status()) {
            case COMPLETE, PARTIAL, NO_CAPACITY -> DeviceResult.success(DeviceValue.map(Map.of(
                    "status", DeviceValue.of(result.status().name().toLowerCase(java.util.Locale.ROOT)),
                    "inserted", DeviceValue.of(result.inserted()),
                    "remaining", DeviceValue.of(result.remaining()))));
            case PERMISSION_DENIED -> DeviceResult.failure(DeviceErrorCode.PERMISSION_DENIED,
                    "item escrow requires device.escrow.admin", false);
            case NOT_FOUND -> DeviceResult.failure(DeviceErrorCode.NOT_FOUND,
                    "escrow entry not found", false);
            case DESTINATION_ERROR -> DeviceResult.failure(DeviceErrorCode.ADAPTER_ERROR,
                    "destination item endpoint failed", true);
        };
    }

    /** Resolves an item port only when the UUID still names a visible adjacent Forge endpoint. */
    AdjacentForgeEndpointResolver.ItemResolution resolveItemEndpoint(UUID deviceId) {
        AdjacentForgeEndpointResolver resolver = new AdjacentForgeEndpointResolver(level, hostPosition);
        Optional<AdjacentForgeEndpointResolver.Candidate> candidate =
                AdjacentForgeEndpointResolver.candidate(level.dimension().location().toString(),
                        hostPosition, deviceId);
        if (candidate.isEmpty()) {
            return AdjacentForgeEndpointResolver.ItemResolution.failure(
                    AdjacentForgeEndpointResolver.ItemResolutionStatus.NOT_FOUND);
        }
        if (baseOwnsAddress(candidate.orElseThrow().positionAddress())) {
            return AdjacentForgeEndpointResolver.ItemResolution.failure(
                    AdjacentForgeEndpointResolver.ItemResolutionStatus.NOT_FOUND);
        }
        BlockEntity target = level.getBlockEntity(candidate.orElseThrow().target());
        if (target != null && !mutationAllowed(target)) {
            return AdjacentForgeEndpointResolver.ItemResolution.failure(
                    AdjacentForgeEndpointResolver.ItemResolutionStatus.PERMISSION_DENIED);
        }
        return resolver.resolveItem(deviceId);
    }

    /** Resolves a fluid port only when the UUID still names a visible adjacent Forge endpoint. */
    AdjacentForgeEndpointResolver.FluidResolution resolveFluidEndpoint(UUID deviceId) {
        AdjacentForgeEndpointResolver resolver = new AdjacentForgeEndpointResolver(level, hostPosition);
        Optional<AdjacentForgeEndpointResolver.Candidate> candidate =
                AdjacentForgeEndpointResolver.candidate(level.dimension().location().toString(),
                        hostPosition, deviceId);
        if (candidate.isEmpty()) {
            return AdjacentForgeEndpointResolver.FluidResolution.failure(
                    AdjacentForgeEndpointResolver.ItemResolutionStatus.NOT_FOUND);
        }
        if (baseOwnsAddress(candidate.orElseThrow().positionAddress())) {
            return AdjacentForgeEndpointResolver.FluidResolution.failure(
                    AdjacentForgeEndpointResolver.ItemResolutionStatus.NOT_FOUND);
        }
        BlockEntity target = level.getBlockEntity(candidate.orElseThrow().target());
        if (target != null && !mutationAllowed(target)) {
            return AdjacentForgeEndpointResolver.FluidResolution.failure(
                    AdjacentForgeEndpointResolver.ItemResolutionStatus.PERMISSION_DENIED);
        }
        return resolver.resolveFluid(deviceId);
    }

    private boolean mutationAllowed(BlockEntity target) {
        com.malice.terminalcraft.integration.OptionalDeviceMutationPolicyRegistry.Decision policy =
                com.malice.terminalcraft.integration.OptionalDeviceMutationPolicyRegistry.evaluate(target);
        return DeviceAuthorization.decide(context(), DeviceAuthorization.Action.MUTATE,
                policy.allowed(), policy.reason()).allowed();
    }

    static DeviceValue.MapValue fluidEscrowValue(
            ExactFluidTransferCoordinator.EscrowEntry<FluidStack> entry) {
        Map<String, DeviceValue> value = new LinkedHashMap<>();
        value.put("escrow_id", DeviceValue.of(entry.escrowId().toString()));
        value.put("operation_id", DeviceValue.of(entry.operationId().toString()));
        value.put("source_id", DeviceValue.of(entry.sourceId().toString()));
        value.put("destination_id", DeviceValue.of(entry.destinationId().toString()));
        value.put("resource", DeviceValue.of(BuiltInRegistries.FLUID.getKey(entry.payload().getFluid()).toString()));
        value.put("amount", DeviceValue.of(entry.payload().getAmount()));
        value.put("unit", DeviceValue.of("mB"));
        return new DeviceValue.MapValue(value);
    }

    static DeviceResult mapFluidEscrowRecovery(
            ExactFluidTransferCoordinator.EscrowRecoveryResult result) {
        return switch (result.status()) {
            case COMPLETE, PARTIAL, NO_CAPACITY -> DeviceResult.success(DeviceValue.map(Map.of(
                    "status", DeviceValue.of(result.status().name().toLowerCase(java.util.Locale.ROOT)),
                    "inserted", DeviceValue.of(result.insertedMb()),
                    "remaining", DeviceValue.of(result.remainingMb()),
                    "unit", DeviceValue.of("mB"))));
            case PERMISSION_DENIED -> DeviceResult.failure(DeviceErrorCode.PERMISSION_DENIED,
                    "fluid escrow requires device.escrow.admin", false);
            case NOT_FOUND -> DeviceResult.failure(DeviceErrorCode.NOT_FOUND,
                    "escrow entry not found", false);
            case DESTINATION_ERROR -> DeviceResult.failure(DeviceErrorCode.ADAPTER_ERROR,
                    "destination fluid endpoint failed", true);
        };
    }

    private static ExactFluidTransferService.Resolution<FluidStack> toFluidTransferResolution(
            AdjacentForgeEndpointResolver.FluidResolution resolution) {
        return switch (resolution.status()) {
            case FOUND -> {
                AdjacentForgeEndpointResolver.ResolvedFluidEndpoint endpoint =
                        resolution.endpointOptional().orElseThrow();
                yield ExactFluidTransferService.Resolution.found(
                        new ExactFluidTransferService.ResolvedEndpoint<>(endpoint.id(),
                                endpoint.backingIdentity(), endpoint.port()));
            }
            case NOT_FOUND -> ExactFluidTransferService.Resolution.failure(
                    ExactFluidTransferService.ResolutionStatus.NOT_FOUND);
            case CHUNK_UNLOADED -> ExactFluidTransferService.Resolution.failure(
                    ExactFluidTransferService.ResolutionStatus.CHUNK_UNLOADED);
            case PERMISSION_DENIED -> ExactFluidTransferService.Resolution.failure(
                    ExactFluidTransferService.ResolutionStatus.PERMISSION_DENIED);
            case REMOVED, REPLACED -> ExactFluidTransferService.Resolution.failure(
                    ExactFluidTransferService.ResolutionStatus.NOT_FOUND);
            case UNSUPPORTED -> ExactFluidTransferService.Resolution.failure(
                    ExactFluidTransferService.ResolutionStatus.UNSUPPORTED);
        };
    }

    private static ExactItemTransferService.Resolution<ItemStack> toTransferResolution(
            AdjacentForgeEndpointResolver.ItemResolution resolution) {
        return switch (resolution.status()) {
            case FOUND -> {
                AdjacentForgeEndpointResolver.ResolvedItemEndpoint endpoint =
                        resolution.endpointOptional().orElseThrow();
                yield ExactItemTransferService.Resolution.found(
                        new ExactItemTransferService.ResolvedEndpoint<>(endpoint.id(),
                                endpoint.backingIdentity(), endpoint.port()));
            }
            case NOT_FOUND -> ExactItemTransferService.Resolution.failure(
                    ExactItemTransferService.ResolutionStatus.NOT_FOUND);
            case CHUNK_UNLOADED -> ExactItemTransferService.Resolution.failure(
                    ExactItemTransferService.ResolutionStatus.CHUNK_UNLOADED);
            case PERMISSION_DENIED -> ExactItemTransferService.Resolution.failure(
                    ExactItemTransferService.ResolutionStatus.PERMISSION_DENIED);
            case REMOVED, REPLACED -> ExactItemTransferService.Resolution.failure(
                    ExactItemTransferService.ResolutionStatus.NOT_FOUND);
            case UNSUPPORTED -> ExactItemTransferService.Resolution.failure(
                    ExactItemTransferService.ResolutionStatus.UNSUPPORTED);
        };
    }

    private static DeviceResult itemResolutionFailure(String role,
            AdjacentForgeEndpointResolver.ItemResolutionStatus status) {
        return switch (status) {
            case NOT_FOUND -> DeviceResult.failure(DeviceErrorCode.NOT_FOUND,
                    role + " item endpoint is not currently available", true);
            case CHUNK_UNLOADED -> DeviceResult.failure(DeviceErrorCode.CHUNK_UNLOADED,
                    role + " item endpoint chunk is unloaded", true);
            case REMOVED -> DeviceResult.failure(DeviceErrorCode.REMOVED,
                    role + " item endpoint was removed", true);
            case REPLACED -> DeviceResult.failure(DeviceErrorCode.REPLACED,
                    role + " item endpoint changed during the operation", true);
            case PERMISSION_DENIED -> DeviceResult.failure(DeviceErrorCode.PERMISSION_DENIED,
                    role + " item endpoint mutation is denied by the optional integration policy", false);
            case UNSUPPORTED -> DeviceResult.failure(DeviceErrorCode.UNSUPPORTED,
                    role + " endpoint does not expose an item capability", false);
            case FOUND -> throw new IllegalArgumentException("found resolution is not a failure");
        };
    }

    private static DeviceResult fluidResolutionFailure(String role,
            AdjacentForgeEndpointResolver.ItemResolutionStatus status) {
        return switch (status) {
            case NOT_FOUND -> DeviceResult.failure(DeviceErrorCode.NOT_FOUND,
                    role + " fluid endpoint is not currently available", true);
            case CHUNK_UNLOADED -> DeviceResult.failure(DeviceErrorCode.CHUNK_UNLOADED,
                    role + " fluid endpoint chunk is unloaded", true);
            case REMOVED -> DeviceResult.failure(DeviceErrorCode.REMOVED,
                    role + " fluid endpoint was removed", true);
            case REPLACED -> DeviceResult.failure(DeviceErrorCode.REPLACED,
                    role + " fluid endpoint changed during the operation", true);
            case PERMISSION_DENIED -> DeviceResult.failure(DeviceErrorCode.PERMISSION_DENIED,
                    role + " fluid endpoint mutation is denied by the optional integration policy", false);
            case UNSUPPORTED -> DeviceResult.failure(DeviceErrorCode.UNSUPPORTED,
                    role + " endpoint does not expose a fluid capability", false);
            case FOUND -> throw new IllegalArgumentException("found resolution is not a failure");
        };
    }

    private boolean baseOwnsAddress(String address) {
        if (base instanceof AddressAwareDeviceAccess aware) return aware.ownsAddress(address);
        for (DeviceDescriptor descriptor : base.descriptors(DeviceRegistry.MAX_ENUMERATION_RESULTS)) {
            if (address.equals(descriptor.address())) return true;
        }
        return false;
    }

    /** Conservative bounded work estimate charged before endpoint lookup or adapter invocation. */
    static int callWorkUnits(String method) {
        if (method == null) return 1;
        if (method.startsWith("inventory.") || method.startsWith("storage.")) {
            return GenericCapabilityDevice.MAX_INVENTORY_SLOTS;
        }
        if (method.startsWith("fluid.")) return GenericCapabilityDevice.MAX_FLUID_TANKS;
        return 1;
    }

    private void requireServerThread() {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("adjacent device access must run on the logical server thread");
        }
    }

    private Map<UUID, DeviceEndpoint> adjacent() {
        Map<UUID, DeviceEndpoint> result = new LinkedHashMap<>();

        for (Direction direction : Direction.values()) {
            BlockPos target = hostPosition.relative(direction);
            if (!level.hasChunkAt(target)) continue;
            BlockEntity blockEntity = level.getBlockEntity(target);
            if (blockEntity == null) continue;
            Direction accessSide = direction.getOpposite();
            ForgeCapabilityDevice device = new ForgeCapabilityDevice(level, target, accessSide);
            GenericItemStorage logicalStorage = com.malice.terminalcraft.integration.OptionalItemStorageRegistry
                    .resolve(blockEntity).orElse(null);
            if (!device.hasInventory() && !device.hasFluidStorage() && !device.hasEnergyStorage()
                    && logicalStorage == null) continue;

            String dimension = level.dimension().location().toString();
            String positionAddress = AdjacentForgeEndpointResolver.positionAddress(dimension, target);
            if (baseOwnsAddress(positionAddress)) continue;
            AdjacentForgeEndpointResolver.Candidate candidate =
                    AdjacentForgeEndpointResolver.adjacent(dimension, hostPosition, direction);
            String address = candidate.address();
            UUID id = candidate.id();
            String blockId = BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock()).toString();
            String source = BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock()).getNamespace();
            com.malice.terminalcraft.integration.OptionalDeviceMetadata metadata =
                    com.malice.terminalcraft.integration.OptionalDeviceMetadataRegistry.describe(blockEntity)
                            .orElse(null);
            String adapterId = metadata == null ? "terminalcraft:forge_capability" : metadata.adapterId();
            String typeName = metadata == null ? "forge_capability" : metadata.typeName();
            java.util.Set<String> extraCapabilities = metadata == null
                    ? java.util.Set.of() : metadata.capabilities();
            java.util.Map<String, DeviceValue> extraProperties = metadata == null
                    ? java.util.Map.of() : metadata.properties();
            DeviceEndpoint capabilityEndpoint = new GenericCapabilityDeviceEndpoint(id, adapterId, typeName,
                    blockId, source, address, device,
                    () -> isCurrent(target, blockEntity), () -> level.hasChunkAt(target),
                    extraCapabilities, extraProperties, logicalStorage);
            com.malice.terminalcraft.device.GenericCraftingService craftingService =
                    com.malice.terminalcraft.integration.OptionalCraftingServiceRegistry
                            .resolve(blockEntity).orElse(null);
            if (craftingService == null) {
                result.put(id, capabilityEndpoint);
            } else {
                DeviceEndpoint craftingEndpoint = new GenericCraftingServiceEndpoint(id, adapterId,
                        blockId, source, address, craftingService,
                        () -> isCurrent(target, blockEntity), () -> level.hasChunkAt(target));
                result.put(id, new CompositeDeviceEndpoint(capabilityEndpoint, craftingEndpoint));
            }
        }
        return result;
    }

    private boolean isCurrent(BlockPos position, BlockEntity expected) {
        return level.hasChunkAt(position) && !expected.isRemoved() && level.getBlockEntity(position) == expected;
    }
}
