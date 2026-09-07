package com.malice.terminalcraft.persistence;

import com.malice.terminalcraft.item.PocketTerminalItem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Migrate direct saved inventory pockets before login synchronization constructs item packets. */
@Mod.EventBusSubscriber(modid = "terminalcraft", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PocketInventoryMigration {
    private PocketInventoryMigration() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void beforeVanillaContainer(net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        var player = event.getEntity();
        if (event.isCanceled() || event.getUseBlock() == net.minecraftforge.eventbus.api.Event.Result.DENY
                || player.isSpectator() || !(player.level() instanceof ServerLevel level)
                || !level.getServer().isSameThread() || !level.hasChunkAt(event.getPos())) return;
        if (player.isSecondaryUseActive() && (!player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty())
                && event.getUseBlock() != net.minecraftforge.eventbus.api.Event.Result.ALLOW) return;
        var state = level.getBlockState(event.getPos());
        if (!"minecraft".equals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace())) return;
        Container inventory;
        if (state.getBlock() instanceof net.minecraft.world.level.block.EnderChestBlock) {
            inventory = player.getEnderChestInventory();
        } else {
            if (!(level.getBlockEntity(event.getPos()) instanceof net.minecraft.world.level.block.entity.BaseContainerBlockEntity base)
                    || hasPendingLoot(base) || !base.canOpen(player)) return;
            inventory = base;
            if (state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock chest) {
                if (state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)
                        != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
                    var partner = event.getPos().relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(state));
                    if (!level.hasChunkAt(partner)
                            || !(level.getBlockEntity(partner) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity other)
                            || hasPendingLoot(other) || !other.canOpen(player)) return;
                }
                inventory = net.minecraft.world.level.block.ChestBlock.getContainer(chest, state, level, event.getPos(), false);
                if (inventory == null) return;
            }
        }
        if (migrate(level, inventory) > 0) {
            // Do not send the container's first slot packet with an unverified legacy payload.
            event.setCanceled(true);
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "Pocket files could not be migrated; container kept closed. Ask an operator to recover the original data."), false);
        }
    }

    private static boolean hasPendingLoot(net.minecraft.world.level.block.entity.BaseContainerBlockEntity container) {
        // getItem would unpack a loot table with a null player, changing vanilla loot/luck semantics.
        return container instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity
                && container.saveWithoutMetadata().contains("LootTable");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void loaded(PlayerEvent.LoadFromFile event) {
        migratePlayer(event.getEntity());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void cloned(PlayerEvent.Clone event) {
        // restoreFrom has copied retained inventories, but respawn synchronization has not run yet.
        migratePlayer(event.getEntity());
    }

    private static void migratePlayer(Player player) {
        if (!(player.level() instanceof ServerLevel level)) return;
        if (!level.getServer().isSameThread()) {
            com.mojang.logging.LogUtils.getLogger().warn("Skipped off-thread pocket inventory migration for {}", player.getUUID());
            return;
        }
        int failures = migrate(level, player.getInventory()) + migrate(level, player.getEnderChestInventory());
        if (failures > 0) {
            com.mojang.logging.LogUtils.getLogger().warn(
                    "{} legacy pocket(s) for {} could not be migrated; original items retained. Restore/recover their data before oversized inventory synchronization.",
                    failures, player.getUUID());
        }
    }

    private static int migrate(ServerLevel level, Container inventory) {
        int failures = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            // Blank unopened pockets must remain eligible for archive import.
            if (!PocketItemMigration.supports(stack) || stack.getTag() == null) continue;
            try {
                if (PocketItemMigration.migrate(level, stack)) inventory.setChanged();
            } catch (java.io.IOException failure) {
                failures++;
                com.mojang.logging.LogUtils.getLogger().warn("Pocket migration refused inventory slot {}: {}", slot, failure.getMessage());
            }
        }
        return failures;
    }
}
