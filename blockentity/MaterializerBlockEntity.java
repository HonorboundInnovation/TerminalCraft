package com.malice.terminalcraft.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Read-only output facade over the adjacent NAS. */
public class MaterializerBlockEntity extends BlockEntity {
    private LazyOptional<IItemHandler> itemOptional = LazyOptional.of(() -> new OutputItemHandler());
    private LazyOptional<IFluidHandler> fluidOptional = LazyOptional.of(() -> new OutputFluidHandler());

    public MaterializerBlockEntity(BlockPos pos, BlockState state) {
        super(com.malice.terminalcraft.registry.ModRegistries.MATERIALIZER_BLOCK_ENTITY.get(), pos, state);
    }

    @Nullable
    public NetworkAccessStorageBlockEntity nas() {
        if (level == null) return null;
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            BlockEntity entity = level.getBlockEntity(worldPosition.relative(direction));
            if (entity instanceof NetworkAccessStorageBlockEntity nas) return nas;
        }
        return null;
    }

    /** Attempts to fill a held fluid container from the NAS. Returns EMPTY on no compatible fluid. */
    public ItemStack fillContainer(ItemStack held) {
        NetworkAccessStorageBlockEntity storage = nas();
        if (storage == null || held == null || held.isEmpty()) return ItemStack.EMPTY;
        var result = FluidUtil.tryFillContainer(held, storage.storageFluidHandler(), true);
        return result.isSuccess() ? result.getResult() : ItemStack.EMPTY;
    }

    public ItemStack extractMatchingItem(ItemStack template, int amount) {
        NetworkAccessStorageBlockEntity storage = nas();
        return storage == null ? ItemStack.EMPTY : storage.extractMatchingItem(template, amount);
    }

    public ItemStack extractFirstItem(int amount) {
        NetworkAccessStorageBlockEntity storage = nas();
        return storage == null ? ItemStack.EMPTY : storage.extractFirstItem(amount);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, MaterializerBlockEntity materializer) {}

    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> capability, @Nullable net.minecraft.core.Direction side) {
        if (capability == ForgeCapabilities.ITEM_HANDLER) return itemOptional.cast();
        if (capability == ForgeCapabilities.FLUID_HANDLER) return fluidOptional.cast();
        return super.getCapability(capability, side);
    }

    @Override public void invalidateCaps() { super.invalidateCaps(); itemOptional.invalidate(); fluidOptional.invalidate(); }
    @Override public void reviveCaps() {
        super.reviveCaps();
        itemOptional = LazyOptional.of(() -> new OutputItemHandler());
        fluidOptional = LazyOptional.of(() -> new OutputFluidHandler());
    }

    private final class OutputItemHandler implements IItemHandler {
        private IItemHandler delegate() {
            NetworkAccessStorageBlockEntity storage = nas();
            return storage == null ? null : storage.storageItemHandler();
        }
        @Override public int getSlots() { IItemHandler handler = delegate(); return handler == null ? 0 : handler.getSlots(); }
        @Override public ItemStack getStackInSlot(int slot) { IItemHandler handler = delegate(); return handler == null ? ItemStack.EMPTY : handler.getStackInSlot(slot); }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return stack.copy(); }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            IItemHandler handler = delegate(); return handler == null ? ItemStack.EMPTY : handler.extractItem(slot, amount, simulate);
        }
        @Override public int getSlotLimit(int slot) { return 64; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return false; }
    }

    private final class OutputFluidHandler implements IFluidHandler {
        private IFluidHandler delegate() {
            NetworkAccessStorageBlockEntity storage = nas();
            return storage == null ? null : storage.storageFluidHandler();
        }
        @Override public int getTanks() { IFluidHandler handler = delegate(); return handler == null ? 0 : handler.getTanks(); }
        @Override public net.minecraftforge.fluids.FluidStack getFluidInTank(int tank) { IFluidHandler handler = delegate(); return handler == null ? net.minecraftforge.fluids.FluidStack.EMPTY : handler.getFluidInTank(tank); }
        @Override public int getTankCapacity(int tank) { IFluidHandler handler = delegate(); return handler == null ? 0 : handler.getTankCapacity(tank); }
        @Override public boolean isFluidValid(int tank, net.minecraftforge.fluids.FluidStack stack) { return false; }
        @Override public int fill(net.minecraftforge.fluids.FluidStack resource, FluidAction action) { return 0; }
        @Override public net.minecraftforge.fluids.FluidStack drain(net.minecraftforge.fluids.FluidStack resource, FluidAction action) { IFluidHandler handler = delegate(); return handler == null ? net.minecraftforge.fluids.FluidStack.EMPTY : handler.drain(resource, action); }
        @Override public net.minecraftforge.fluids.FluidStack drain(int amount, FluidAction action) { IFluidHandler handler = delegate(); return handler == null ? net.minecraftforge.fluids.FluidStack.EMPTY : handler.drain(amount, action); }
    }
}
