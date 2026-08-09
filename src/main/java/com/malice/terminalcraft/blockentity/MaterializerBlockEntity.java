package com.malice.terminalcraft.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Read-only output facade over the adjacent NAS. */
public class MaterializerBlockEntity extends BlockEntity {
    private LazyOptional<IItemHandler> itemOptional = LazyOptional.of(OutputItems::new);
    private LazyOptional<IFluidHandler> fluidOptional = LazyOptional.of(OutputFluids::new);

    public MaterializerBlockEntity(BlockPos pos, BlockState state) { super(com.malice.terminalcraft.registry.ModRegistries.MATERIALIZER_BLOCK_ENTITY.get(), pos, state); }

    @Nullable NetworkAccessStorageBlockEntity nas() {
        if (level == null) return null;
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            BlockEntity entity = level.getBlockEntity(worldPosition.relative(direction));
            if (entity instanceof NetworkAccessStorageBlockEntity storage) return storage;
        }
        return null;
    }

    public ItemStack fillContainer(ItemStack held) {
        NetworkAccessStorageBlockEntity storage = nas();
        if (storage == null || held == null || held.isEmpty()) return ItemStack.EMPTY;
        var result = FluidUtil.tryFillContainer(held, storage.storageFluidHandler(), 1_000_000, null, true);
        return result.isSuccess() ? result.getResult() : ItemStack.EMPTY;
    }
    public ItemStack extractMatchingItem(ItemStack template, int amount) { NetworkAccessStorageBlockEntity storage = nas(); return storage == null ? ItemStack.EMPTY : storage.extractMatchingItem(template, amount); }
    public ItemStack extractFirstItem(int amount) { NetworkAccessStorageBlockEntity storage = nas(); return storage == null ? ItemStack.EMPTY : storage.extractFirstItem(amount); }

    @Override public <T> LazyOptional<T> getCapability(@NotNull Capability<T> capability, @Nullable net.minecraft.core.Direction side) {
        if (capability == ForgeCapabilities.ITEM_HANDLER) return itemOptional.cast();
        if (capability == ForgeCapabilities.FLUID_HANDLER) return fluidOptional.cast();
        return super.getCapability(capability, side);
    }
    @Override public void invalidateCaps() { super.invalidateCaps(); itemOptional.invalidate(); fluidOptional.invalidate(); }
    @Override public void reviveCaps() { super.reviveCaps(); itemOptional = LazyOptional.of(OutputItems::new); fluidOptional = LazyOptional.of(OutputFluids::new); }

    private final class OutputItems implements IItemHandler {
        private IItemHandler delegate() { NetworkAccessStorageBlockEntity storage = nas(); return storage == null ? null : storage.storageItemHandler(); }
        @Override public int getSlots() { IItemHandler d = delegate(); return d == null ? 0 : d.getSlots(); }
        @Override public ItemStack getStackInSlot(int slot) { IItemHandler d = delegate(); return d == null ? ItemStack.EMPTY : d.getStackInSlot(slot); }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack.copy(); }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) { IItemHandler d = delegate(); return d == null ? ItemStack.EMPTY : d.extractItem(slot, amount, simulate); }
        @Override public int getSlotLimit(int slot) { return 64; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return false; }
    }

    private final class OutputFluids implements IFluidHandler {
        private IFluidHandler delegate() { NetworkAccessStorageBlockEntity storage = nas(); return storage == null ? null : storage.storageFluidHandler(); }
        @Override public int getTanks() { IFluidHandler d = delegate(); return d == null ? 0 : d.getTanks(); }
        @Override public FluidStack getFluidInTank(int tank) { IFluidHandler d = delegate(); return d == null ? FluidStack.EMPTY : d.getFluidInTank(tank); }
        @Override public int getTankCapacity(int tank) { IFluidHandler d = delegate(); return d == null ? 0 : d.getTankCapacity(tank); }
        @Override public boolean isFluidValid(int tank, FluidStack stack) { return false; }
        @Override public int fill(FluidStack resource, FluidAction action) { return 0; }
        @Override public FluidStack drain(FluidStack resource, FluidAction action) { IFluidHandler d = delegate(); return d == null ? FluidStack.EMPTY : d.drain(resource, action); }
        @Override public FluidStack drain(int amount, FluidAction action) { IFluidHandler d = delegate(); return d == null ? FluidStack.EMPTY : d.drain(amount, action); }
    }
}
