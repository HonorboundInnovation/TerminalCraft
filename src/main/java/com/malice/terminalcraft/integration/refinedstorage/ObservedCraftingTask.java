package com.malice.terminalcraft.integration.refinedstorage;

import com.refinedmods.refinedstorage.api.autocrafting.ICraftingPattern;
import com.refinedmods.refinedstorage.api.autocrafting.craftingmonitor.ICraftingMonitorElement;
import com.refinedmods.refinedstorage.api.autocrafting.task.ICraftingRequestInfo;
import com.refinedmods.refinedstorage.api.autocrafting.task.ICraftingTask;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

/** Delegates native recipe work unchanged and observes its explicit terminal signals. */
final class ObservedCraftingTask implements ICraftingTask {
    private final ICraftingTask task;
    private final BiConsumer<UUID, Boolean> observer;
    private boolean reported;
    ObservedCraftingTask(ICraftingTask task, BiConsumer<UUID, Boolean> observer) {
        this.task = task; this.observer = observer;
    }
    private void report(boolean completed) {
        if (!reported) { observer.accept(task.getId(), completed); reported = true; }
    }
    @Override public boolean update() {
        boolean complete = task.update();
        if (complete) report(true);
        return complete;
    }
    @Override public void onCancelled() { task.onCancelled(); report(false); }
    @Override public int getQuantity() { return task.getQuantity(); }
    @Override public int getCompletionPercentage() { return task.getCompletionPercentage(); }
    @Override public ICraftingRequestInfo getRequested() { return task.getRequested(); }
    @Override public int onTrackedInsert(ItemStack stack, int amount) { return task.onTrackedInsert(stack, amount); }
    @Override public int onTrackedInsert(FluidStack stack, int amount) { return task.onTrackedInsert(stack, amount); }
    @Override public CompoundTag writeToNbt(CompoundTag tag) { return task.writeToNbt(tag); }
    @Override public List<ICraftingMonitorElement> getCraftingMonitorElements() { return task.getCraftingMonitorElements(); }
    @Override public ICraftingPattern getPattern() { return task.getPattern(); }
    @Override public long getStartTime() { return task.getStartTime(); }
    @Override public UUID getId() { return task.getId(); }
    @Override public void start() { task.start(); }
}
