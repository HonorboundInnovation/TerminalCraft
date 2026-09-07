package com.malice.terminalcraft.persistence;

import com.malice.terminalcraft.item.PocketTerminalItem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Resolve direct legacy drops before entity tracking; never discard a failed original. */
@Mod.EventBusSubscriber(modid = "terminalcraft", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PocketItemEntityMigration {
    private PocketItemEntityMigration() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void joining(EntityJoinLevelEvent event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof ItemEntity item)) return;
        var original = item.getItem();
        if (!PocketItemMigration.supports(original) || original.getTag() == null) return;
        if (!level.getServer().isSameThread()) {
            com.mojang.logging.LogUtils.getLogger().warn("Skipped off-thread legacy pocket entity migration for {}", item.getUUID());
            return;
        }
        // Entity join can precede chunk promotion. Only access saved data/files, never chunks or blocks.
        var migrated = original.copy();
        try {
            if (PocketItemMigration.migrate(level, migrated)) item.setItem(migrated);
        } catch (java.io.IOException failure) {
            // Cancelling the spawn here would lose a stack already removed from its source inventory.
            com.mojang.logging.LogUtils.getLogger().warn(
                    "Pocket item entity {} retained its original after migration failure: {}", item.getUUID(), failure.getMessage());
        }
    }
}
