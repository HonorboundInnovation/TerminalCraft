package com.malice.terminalcraft.device;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;

import java.util.Objects;
import java.util.function.Supplier;

/** Exact-fluid transfer port for a side-aware Forge fluid capability. */
final class ForgeFluidStackTransferPort implements ExactFluidTransferCoordinator.Port<FluidStack> {
    private final Supplier<IFluidHandler> handlerSupplier;

    ForgeFluidStackTransferPort(ServerLevel level, BlockPos target, Direction accessSide,
                                BlockEntity expectedBlockEntity) {
        Objects.requireNonNull(level, "level");
        BlockPos immutableTarget = Objects.requireNonNull(target, "target").immutable();
        this.handlerSupplier = () -> {
            if (!level.hasChunkAt(immutableTarget)) return null;
            BlockEntity blockEntity = level.getBlockEntity(immutableTarget);
            IFluidHandler current = blockEntity == null ? null : blockEntity
                    .getCapability(ForgeCapabilities.FLUID_HANDLER, accessSide)
                    .resolve().orElse(null);
            return expectedBlockEntity == null || blockEntity == expectedBlockEntity ? current : null;
        };
    }

    /** Package-private constructor used by focused headless adapter tests. */
    ForgeFluidStackTransferPort(Supplier<IFluidHandler> handlerSupplier) {
        this.handlerSupplier = Objects.requireNonNull(handlerSupplier, "handlerSupplier");
    }

    @Override
    public FluidStack drain(String resourceId, int amountMb) {
        IFluidHandler handler = requireHandler();
        ResourceLocation requested = requireFluid(resourceId);
        int tanks = Math.min(handler.getTanks(), GenericCapabilityDevice.MAX_FLUID_TANKS);

        for (int tank = 0; tank < tanks; tank++) {
            FluidStack visible = Objects.requireNonNull(handler.getFluidInTank(tank),
                    "fluid handler tank content");
            if (visible.isEmpty() || !requested.equals(BuiltInRegistries.FLUID.getKey(visible.getFluid()))) {
                continue;
            }

            FluidStack exactRequest = visible.copy();
            // IFluidHandler's resource-specific drain may aggregate the same exact variant across
            // multiple tanks. Request the caller's full bound instead of limiting the operation to
            // the first visible tank.
            exactRequest.setAmount(amountMb);
            FluidStack drained = Objects.requireNonNull(
                    handler.drain(exactRequest.copy(), IFluidHandler.FluidAction.EXECUTE),
                    "fluid handler drain result");
            if (drained.isEmpty()) continue;
            if (drained.getAmount() < 1 || drained.getAmount() > amountMb
                    || !requested.equals(BuiltInRegistries.FLUID.getKey(drained.getFluid()))) {
                throw new IllegalStateException("fluid handler returned an invalid exact-fluid drain");
            }
            // The exact variant may legitimately change between observation and execution. The
            // executed payload is authoritative and is copied so the coordinator can conserve that
            // actual variant through destination, rollback, or escrow.
            return drained.copy();
        }
        return FluidStack.EMPTY;
    }

    @Override
    public FluidStack fill(FluidStack payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.isEmpty()) return FluidStack.EMPTY;
        IFluidHandler handler = requireHandler();
        FluidStack offered = payload.copy();
        int accepted = handler.fill(offered, IFluidHandler.FluidAction.EXECUTE);
        if (accepted < 0 || accepted > payload.getAmount()) {
            throw new IllegalStateException("fluid handler returned an invalid fill amount");
        }
        int remaining = payload.getAmount() - accepted;
        if (remaining == 0) return FluidStack.EMPTY;
        FluidStack remainder = payload.copy();
        remainder.setAmount(remaining);
        return remainder;
    }

    @Override
    public int amount(FluidStack payload) {
        Objects.requireNonNull(payload, "payload");
        return payload.isEmpty() ? 0 : payload.getAmount();
    }

    @Override
    public boolean sameVariant(FluidStack left, FluidStack right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        return left.isEmpty() ? right.isEmpty()
                : !right.isEmpty() && left.getFluid() == right.getFluid()
                && Objects.equals(left.getTag(), right.getTag());
    }

    private IFluidHandler requireHandler() {
        IFluidHandler handler = handlerSupplier.get();
        if (handler == null) throw new IllegalStateException("fluid capability is unavailable");
        return handler;
    }

    private static ResourceLocation requireFluid(String resourceId) {
        ResourceLocation key = ResourceLocation.tryParse(resourceId);
        if (key == null || !BuiltInRegistries.FLUID.containsKey(key)
                || BuiltInRegistries.FLUID.get(key) == net.minecraft.world.level.material.Fluids.EMPTY) {
            throw new IllegalArgumentException("unknown fluid resource: " + resourceId);
        }
        return key;
    }
}
