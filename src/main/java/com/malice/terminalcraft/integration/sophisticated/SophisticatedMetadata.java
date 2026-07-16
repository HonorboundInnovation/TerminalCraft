package com.malice.terminalcraft.integration.sophisticated;

import com.malice.terminalcraft.device.DeviceValue;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler;
import net.p3pp3rf1y.sophisticatedcore.settings.memory.MemorySettingsCategory;
import net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded read-only translation of stable Sophisticated Core wrapper APIs. */
final class SophisticatedMetadata {
    private static final int MAX_UPGRADES = 32;
    private static final int MAX_SLOT_DETAILS = 64;

    private SophisticatedMetadata() {}

    static Map<String, DeviceValue> properties(IStorageWrapper wrapper) {
        InventoryHandler inventory = wrapper.getInventoryHandler();
        Map<String, DeviceValue> properties = new LinkedHashMap<>();
        properties.put("sophisticated_storage_type", DeviceValue.of(wrapper.getStorageType()));
        properties.put("sophisticated_sort_mode",
                DeviceValue.of(wrapper.getSortBy().getSerializedName()));
        properties.put("sophisticated_inventory_slots", DeviceValue.of(inventory.getSlots()));
        properties.put("sophisticated_accessible_slots", DeviceValue.of(accessibleSlots(inventory)));
        properties.put("sophisticated_accessible_slots_complete",
                DeviceValue.of(inventory.getSlots() <= MAX_SLOT_DETAILS));
        properties.put("sophisticated_upgrade_slots",
                DeviceValue.of(wrapper.getUpgradeHandler().getSlots()));
        properties.put("sophisticated_base_slot_limit",
                DeviceValue.of(inventory.getBaseSlotLimit()));
        properties.put("sophisticated_stack_size_multiplier",
                DeviceValue.of(inventory.getStackSizeMultiplier()));
        properties.put("sophisticated_effective_slot_capacity",
                DeviceValue.of(Long.toString(effectiveSlotCapacity(inventory))));
        properties.put("sophisticated_effective_slot_capacity_complete",
                DeviceValue.of(inventory.getSlots() <= MAX_SLOT_DETAILS));
        properties.put("sophisticated_capacity_unit", DeviceValue.of("items"));
        properties.put("sophisticated_slot_limits", slotLimits(inventory));
        properties.put("sophisticated_slot_limits_complete",
                DeviceValue.of(inventory.getSlots() <= MAX_SLOT_DETAILS));
        MemorySettingsCategory memory = wrapper.getSettingsHandler()
                .getTypeCategory(MemorySettingsCategory.class);
        properties.put("sophisticated_filters", filters(inventory, memory));
        properties.put("sophisticated_filters_complete",
                DeviceValue.of(inventory.getSlots() <= MAX_SLOT_DETAILS));
        properties.put("sophisticated_filter_ignores_nbt",
                DeviceValue.of(memory != null && memory.ignoresNbt()));
        properties.put("sophisticated_upgrades", upgrades(wrapper.getUpgradeHandler()));
        properties.put("sophisticated_upgrades_complete",
                DeviceValue.of(wrapper.getUpgradeHandler().getSlots() <= MAX_UPGRADES));
        return properties;
    }

    private static int accessibleSlots(InventoryHandler inventory) {
        int accessible = 0;
        for (int slot = 0; slot < Math.min(inventory.getSlots(), MAX_SLOT_DETAILS); slot++) {
            if (inventory.isSlotAccessible(slot)) accessible++;
        }
        return accessible;
    }

    private static long effectiveSlotCapacity(InventoryHandler inventory) {
        long capacity = 0;
        for (int slot = 0; slot < Math.min(inventory.getSlots(), MAX_SLOT_DETAILS); slot++) {
            if (!inventory.isSlotAccessible(slot)) continue;
            capacity = saturatingAdd(capacity, Math.max(0, inventory.getSlotLimit(slot)));
        }
        return capacity;
    }

    private static DeviceValue slotLimits(InventoryHandler inventory) {
        List<DeviceValue> limits = new ArrayList<>();
        for (int slot = 0; slot < Math.min(inventory.getSlots(), MAX_SLOT_DETAILS); slot++) {
            limits.add(DeviceValue.map(Map.of(
                    "slot", DeviceValue.of(slot),
                    "accessible", DeviceValue.of(inventory.isSlotAccessible(slot)),
                    "limit", DeviceValue.of(inventory.getSlotLimit(slot)))));
        }
        return DeviceValue.list(limits);
    }

    private static DeviceValue filters(InventoryHandler inventory,
                                       MemorySettingsCategory memory) {
        if (memory == null) return DeviceValue.list(List.of());
        List<DeviceValue> filters = new ArrayList<>();
        for (int slot = 0; slot < Math.min(inventory.getSlots(), MAX_SLOT_DETAILS); slot++) {
            ItemStack filter = memory.getSlotFilterStack(slot, true).orElse(ItemStack.EMPTY);
            if (filter.isEmpty()) continue;
            filters.add(DeviceValue.map(Map.of(
                    "slot", DeviceValue.of(slot),
                    "resource", DeviceValue.of(BuiltInRegistries.ITEM.getKey(filter.getItem()).toString()))));
        }
        return DeviceValue.list(filters);
    }

    private static DeviceValue upgrades(UpgradeHandler handler) {
        List<DeviceValue> upgrades = new ArrayList<>();
        for (int slot = 0; slot < Math.min(handler.getSlots(), MAX_UPGRADES); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            upgrades.add(DeviceValue.map(Map.of(
                    "slot", DeviceValue.of(slot),
                    "resource", DeviceValue.of(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()),
                    "count", DeviceValue.of(stack.getCount()))));
        }
        return DeviceValue.list(upgrades);
    }

    private static long saturatingAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }
}
