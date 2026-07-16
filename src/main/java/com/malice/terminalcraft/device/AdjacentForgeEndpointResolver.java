package com.malice.terminalcraft.device;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Shared identity and authority boundary for caller-adjacent Forge capability endpoints. */
final class AdjacentForgeEndpointResolver {
    private static final String ID_PREFIX = "terminalcraft:forge:";

    private final ServerLevel level;
    private final BlockPos hostPosition;

    AdjacentForgeEndpointResolver(ServerLevel level, BlockPos hostPosition) {
        this.level = Objects.requireNonNull(level, "level");
        this.hostPosition = Objects.requireNonNull(hostPosition, "hostPosition").immutable();
    }

    /** Resolves only a directly adjacent, side-aware item endpoint with an explicit lifecycle result. */
    ItemResolution resolveItem(UUID endpointId) {
        Optional<Candidate> candidate = authorizedCandidate(endpointId);
        if (candidate.isEmpty()) return ItemResolution.failure(ItemResolutionStatus.NOT_FOUND);
        Candidate value = candidate.orElseThrow();
        if (!level.hasChunkAt(value.target())) {
            return ItemResolution.failure(ItemResolutionStatus.CHUNK_UNLOADED);
        }
        BlockEntity blockEntity = level.getBlockEntity(value.target());
        if (blockEntity == null || blockEntity.isRemoved()) {
            return ItemResolution.failure(ItemResolutionStatus.REMOVED);
        }
        if (!blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER,
                value.accessSide()).isPresent()) {
            return ItemResolution.failure(ItemResolutionStatus.UNSUPPORTED);
        }
        return ItemResolution.found(new ResolvedItemEndpoint(value.id(), value.address(), blockEntity,
                new ForgeItemStackTransferPort(level, value.target(), value.accessSide(), blockEntity)));
    }

    /** Resolves only a directly adjacent, side-aware fluid endpoint with an explicit lifecycle result. */
    FluidResolution resolveFluid(UUID endpointId) {
        Optional<Candidate> candidate = authorizedCandidate(endpointId);
        if (candidate.isEmpty()) return FluidResolution.failure(ItemResolutionStatus.NOT_FOUND);
        Candidate value = candidate.orElseThrow();
        if (!level.hasChunkAt(value.target())) {
            return FluidResolution.failure(ItemResolutionStatus.CHUNK_UNLOADED);
        }
        BlockEntity blockEntity = level.getBlockEntity(value.target());
        if (blockEntity == null || blockEntity.isRemoved()) {
            return FluidResolution.failure(ItemResolutionStatus.REMOVED);
        }
        if (!blockEntity.getCapability(ForgeCapabilities.FLUID_HANDLER,
                value.accessSide()).isPresent()) {
            return FluidResolution.failure(ItemResolutionStatus.UNSUPPORTED);
        }
        return FluidResolution.found(new ResolvedFluidEndpoint(value.id(), value.address(), blockEntity,
                new ForgeFluidStackTransferPort(level, value.target(), value.accessSide(), blockEntity)));
    }

    private Optional<Candidate> authorizedCandidate(UUID endpointId) {
        Objects.requireNonNull(endpointId, "endpointId");
        return candidate(level.dimension().location().toString(), hostPosition, endpointId);
    }

    static Candidate adjacent(String dimension, BlockPos hostPosition, Direction direction) {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(hostPosition, "hostPosition");
        Objects.requireNonNull(direction, "direction");
        BlockPos target = hostPosition.relative(direction).immutable();
        Direction accessSide = direction.getOpposite();
        String positionAddress = positionAddress(dimension, target);
        String address = positionAddress + "@" + accessSide.getName();
        UUID id = UUID.nameUUIDFromBytes((ID_PREFIX + address).getBytes(StandardCharsets.UTF_8));
        return new Candidate(id, positionAddress, address, target, accessSide);
    }

    static Optional<Candidate> candidate(String dimension, BlockPos hostPosition, UUID endpointId) {
        Objects.requireNonNull(endpointId, "endpointId");
        for (Direction direction : Direction.values()) {
            Candidate candidate = adjacent(dimension, hostPosition, direction);
            if (candidate.id().equals(endpointId)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    static String positionAddress(String dimension, BlockPos position) {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(position, "position");
        return dimension + ":" + position.getX() + "," + position.getY() + "," + position.getZ();
    }

    enum ItemResolutionStatus {
        FOUND, NOT_FOUND, CHUNK_UNLOADED, REMOVED, REPLACED, UNSUPPORTED, PERMISSION_DENIED
    }

    record ItemResolution(ItemResolutionStatus status, ResolvedItemEndpoint endpoint) {
        ItemResolution { validateResolution(status, endpoint); }
        static ItemResolution found(ResolvedItemEndpoint endpoint) {
            return new ItemResolution(ItemResolutionStatus.FOUND, Objects.requireNonNull(endpoint, "endpoint"));
        }
        static ItemResolution failure(ItemResolutionStatus status) {
            requireFailure(status);
            return new ItemResolution(status, null);
        }
        Optional<ResolvedItemEndpoint> endpointOptional() { return Optional.ofNullable(endpoint); }
    }

    record FluidResolution(ItemResolutionStatus status, ResolvedFluidEndpoint endpoint) {
        FluidResolution { validateResolution(status, endpoint); }
        static FluidResolution found(ResolvedFluidEndpoint endpoint) {
            return new FluidResolution(ItemResolutionStatus.FOUND, Objects.requireNonNull(endpoint, "endpoint"));
        }
        static FluidResolution failure(ItemResolutionStatus status) {
            requireFailure(status);
            return new FluidResolution(status, null);
        }
        Optional<ResolvedFluidEndpoint> endpointOptional() { return Optional.ofNullable(endpoint); }
    }

    private static void validateResolution(ItemResolutionStatus status, Object endpoint) {
        Objects.requireNonNull(status, "status");
        if ((status == ItemResolutionStatus.FOUND) != (endpoint != null)) {
            throw new IllegalArgumentException("only found resolutions may contain an endpoint");
        }
    }

    private static void requireFailure(ItemResolutionStatus status) {
        Objects.requireNonNull(status, "status");
        if (status == ItemResolutionStatus.FOUND) {
            throw new IllegalArgumentException("found resolution requires an endpoint");
        }
    }

    record Candidate(UUID id, String positionAddress, String address, BlockPos target, Direction accessSide) {
        Candidate {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(positionAddress, "positionAddress");
            Objects.requireNonNull(address, "address");
            target = Objects.requireNonNull(target, "target").immutable();
            Objects.requireNonNull(accessSide, "accessSide");
        }
    }

    record ResolvedItemEndpoint(UUID id, String address, Object backingIdentity,
                                ExactItemTransferCoordinator.Port<ItemStack> port) {
        ResolvedItemEndpoint { validateEndpoint(id, address, backingIdentity, port); }
    }

    record ResolvedFluidEndpoint(UUID id, String address, Object backingIdentity,
                                 ExactFluidTransferCoordinator.Port<FluidStack> port) {
        ResolvedFluidEndpoint { validateEndpoint(id, address, backingIdentity, port); }
    }

    private static void validateEndpoint(UUID id, String address, Object backingIdentity, Object port) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(backingIdentity, "backingIdentity");
        Objects.requireNonNull(port, "port");
    }
}
