package com.malice.terminalcraft.integration.refinedstorage;

import com.refinedmods.refinedstorage.api.autocrafting.ICraftingManager;
import com.refinedmods.refinedstorage.api.autocrafting.task.ICraftingTask;
import net.minecraft.world.item.ItemStack;
import java.util.UUID;

/** Exact-quantity submission used only after the caller's native authorization succeeds. */
public final class NativeCraftingSubmission {
    private NativeCraftingSubmission() {}

    public static UUID createAndStart(ICraftingManager manager, ItemStack item, int amount) {
        return createAndStart(manager, item, amount, (id, completed) -> {});
    }

    public static UUID createAndStart(ICraftingManager manager, ItemStack item, int amount,
                                     java.util.function.BiConsumer<UUID, Boolean> observer) {
        // request() is a stock-maintenance convenience: it subtracts existing jobs and starts
        // the task itself. An explicit user submission instead needs its own exact calculation.
        var calculation = manager.create(item, amount);
        if (calculation == null || !calculation.isOk()) return null;
        ICraftingTask task = calculation.getTask();
        if (task == null) return null;
        manager.start(new ObservedCraftingTask(task, observer));
        return task.getId();
    }
}
