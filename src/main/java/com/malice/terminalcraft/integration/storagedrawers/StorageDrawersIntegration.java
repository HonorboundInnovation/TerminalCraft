package com.malice.terminalcraft.integration.storagedrawers;

import com.jaquadro.minecraft.storagedrawers.api.storage.IControlGroup;
import com.jaquadro.minecraft.storagedrawers.api.storage.IDrawer;
import com.jaquadro.minecraft.storagedrawers.api.storage.IDrawerAttributes;
import com.jaquadro.minecraft.storagedrawers.api.storage.IDrawerGroup;
import com.jaquadro.minecraft.storagedrawers.api.storage.IFractionalDrawer;
import com.jaquadro.minecraft.storagedrawers.api.storage.attribute.IProtectable;
import com.jaquadro.minecraft.storagedrawers.api.storage.attribute.LockAttribute;
import com.malice.terminalcraft.device.DeviceValue;
import com.malice.terminalcraft.device.GenericItemStorage;
import com.malice.terminalcraft.integration.OptionalDeviceMetadata;
import com.malice.terminalcraft.integration.OptionalDeviceMetadataRegistry;
import com.malice.terminalcraft.integration.OptionalDeviceMutationPolicyRegistry;
import com.malice.terminalcraft.integration.OptionalIntegration;
import com.malice.terminalcraft.integration.OptionalItemStorageRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Read-only logical drawer and controller metadata through Storage Drawers' public storage API. */
public final class StorageDrawersIntegration implements OptionalIntegration {
    private static final int MAX_DRAWERS = 48;

    @Override
    public void initialize() {
        OptionalDeviceMetadataRegistry.register(StorageDrawersIntegration::describe);
        OptionalDeviceMutationPolicyRegistry.register(StorageDrawersIntegration::mutationPolicy);
        OptionalItemStorageRegistry.register(StorageDrawersIntegration::logicalStorage);
    }

    private static Optional<GenericItemStorage> logicalStorage(BlockEntity blockEntity) {
        if (!(blockEntity instanceof IDrawerGroup group) || !group.isGroupValid()) return Optional.empty();
        return Optional.of(query -> {
            List<GenericItemStorage.ItemResource> matching = aggregateCounts(group, group.getDrawerCount())
                    .entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .filter(entry -> query.matches(entry.getKey()))
                    .map(entry -> new GenericItemStorage.ItemResource(entry.getKey(), entry.getValue()))
                    .toList();
            int start = Math.min(query.offset(), matching.size());
            int end = Math.min(start + query.limit(), matching.size());
            String nextCursor = end < matching.size() ? Integer.toString(end) : "";
            return new GenericItemStorage.ItemPage(matching.subList(start, end), nextCursor);
        });
    }

    private static OptionalDeviceMutationPolicyRegistry.Decision mutationPolicy(BlockEntity blockEntity) {
        if (blockEntity instanceof IControlGroup) {
            return OptionalDeviceMutationPolicyRegistry.Decision.deny(
                    "Storage Drawers controller mutation is disabled until caller-aware network protection is available");
        }
        if (blockEntity instanceof IProtectable protectable && protectable.getOwner() != null) {
            return OptionalDeviceMutationPolicyRegistry.Decision.deny(
                    "Storage Drawers endpoint is protected");
        }
        return OptionalDeviceMutationPolicyRegistry.Decision.allow();
    }

    private static Optional<OptionalDeviceMetadata> describe(BlockEntity blockEntity) {
        if (!(blockEntity instanceof IDrawerGroup group) || !group.isGroupValid()) {
            return Optional.empty();
        }

        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock());
        boolean controller = blockEntity instanceof IControlGroup;
        int drawerCount = Math.max(0, group.getDrawerCount());
        FractionalSummary fractional = fractionalSummary(group, drawerCount);
        boolean truncated = drawerCount > MAX_DRAWERS;

        Map<String, DeviceValue> properties = new LinkedHashMap<>();
        properties.put("storage_drawers_variant", DeviceValue.of(blockId.toString()));
        properties.put("storage_drawers_endpoint_kind", DeviceValue.of(controller ? "controller" : "drawer_group"));
        properties.put("storage_drawers_drawer_count", DeviceValue.of(drawerCount));
        properties.put("storage_drawers_has_missing_drawers", DeviceValue.of(group.hasMissingDrawers()));
        properties.put("storage_drawers_metadata_limit", DeviceValue.of(MAX_DRAWERS));
        properties.put("storage_drawers_metadata_truncated", DeviceValue.of(truncated));
        properties.put("storage_drawers_aggregate_complete", DeviceValue.of(!truncated && !group.hasMissingDrawers()));
        properties.put("storage_drawers_compacting", DeviceValue.of(fractional.compacting()));
        properties.put("storage_drawers_count_semantics", DeviceValue.of(
                fractional.compacting() ? "shared_pool_views" : "independent_drawers"));
        properties.put("storage_drawers_canonical_resource", DeviceValue.of(fractional.canonicalResource()));
        properties.put("storage_drawers_canonical_count", DeviceValue.of(fractional.canonicalCount()));
        properties.put("storage_drawers_aggregate_entries", aggregateValues(group, drawerCount));
        properties.put("storage_drawers_drawers", drawerValues(group, drawerCount, fractional.maxConversionRate()));
        properties.put("storage_drawers_mutation_supported", DeviceValue.of(!controller
                && (!(blockEntity instanceof IProtectable protectable) || protectable.getOwner() == null)));
        if (blockEntity instanceof IProtectable protectable) {
            boolean protectedEndpoint = protectable.getOwner() != null;
            properties.put("storage_drawers_protected", DeviceValue.of(protectedEndpoint));
            properties.put("storage_drawers_security_provider", DeviceValue.of(
                    protectable.getSecurityProvider() == null ? "" : protectable.getSecurityProvider().getProviderID()));
        }
        if (controller) {
            List<?> remoteNodes = ((IControlGroup) blockEntity).getBoundRemoteNodes();
            properties.put("storage_drawers_remote_node_count", DeviceValue.of(remoteNodes == null ? 0 : remoteNodes.size()));
        }

        Set<String> capabilities = controller
                ? Set.of("storage_drawers", "storage_drawers_controller")
                : Set.of("storage_drawers");
        return Optional.of(new OptionalDeviceMetadata(
                controller ? "storagedrawers:controller" : "storagedrawers:drawer_group",
                controller ? "storage_drawer_controller" : "storage_drawer",
                capabilities, properties));
    }

    /**
     * Finds the one non-duplicating count for a compacting drawer: its smallest-unit view.
     * Other fractional slots are convertible views over the same pool and must not be summed.
     */
    private static FractionalSummary fractionalSummary(IDrawerGroup group, int drawerCount) {
        boolean compacting = false;
        int maxConversionRate = 1;
        String canonicalResource = "minecraft:air";
        String canonicalCount = "0";
        for (int slot = 0; slot < Math.min(drawerCount, MAX_DRAWERS); slot++) {
            IDrawer drawer = group.getDrawer(slot);
            if (!(drawer instanceof IFractionalDrawer fractional)) continue;
            compacting = true;
            maxConversionRate = Math.max(maxConversionRate, Math.max(1, fractional.getConversionRate()));
            if (fractional.isSmallestUnit()) {
                ItemStack prototype = fractional.getStoredItemPrototype();
                canonicalResource = resource(prototype);
                canonicalCount = decimal(fractional.getStoredItemCount());
            }
        }
        return new FractionalSummary(compacting, maxConversionRate, canonicalResource, canonicalCount);
    }

    /** Builds a bounded, sorted logical snapshot while counting each compacting pool only once. */
    private static DeviceValue aggregateValues(IDrawerGroup group, int drawerCount) {
        Map<String, Long> counts = aggregateCounts(group, drawerCount);
        List<DeviceValue> values = new ArrayList<>(counts.size());
        counts.entrySet().stream().sorted(Comparator.comparing(Map.Entry::getKey)).forEach(entry -> {
            Map<String, DeviceValue> value = new LinkedHashMap<>();
            value.put("resource", DeviceValue.of(entry.getKey()));
            value.put("count", DeviceValue.of(Long.toString(entry.getValue())));
            values.add(DeviceValue.map(value));
        });
        return DeviceValue.list(values);
    }

    private static Map<String, Long> aggregateCounts(IDrawerGroup group, int drawerCount) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (int slot = 0; slot < Math.min(drawerCount, MAX_DRAWERS); slot++) {
            IDrawer drawer = group.getDrawer(slot);
            if (drawer == null || drawer.isMissing() || !drawer.isEnabled()) continue;
            if (drawer instanceof IFractionalDrawer fractional && !fractional.isSmallestUnit()) continue;
            String resource = resource(drawer.getStoredItemPrototype());
            if ("minecraft:air".equals(resource)) continue;
            counts.merge(resource, (long) Math.max(0, drawer.getStoredItemCount()),
                    StorageDrawersIntegration::saturatingAdd);
        }
        return counts;
    }

    private static long saturatingAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static DeviceValue drawerValues(IDrawerGroup group, int drawerCount, int maxConversionRate) {
        List<DeviceValue> values = new ArrayList<>();
        for (int slot = 0; slot < Math.min(drawerCount, MAX_DRAWERS); slot++) {
            IDrawer drawer = group.getDrawer(slot);
            if (drawer == null) continue;

            ItemStack prototype = drawer.getStoredItemPrototype();
            IDrawerAttributes attributes = drawer.getAttributes();
            Map<String, DeviceValue> value = new LinkedHashMap<>();
            value.put("slot", DeviceValue.of(slot));
            value.put("resource", DeviceValue.of(resource(prototype)));
            value.put("count", DeviceValue.of(decimal(drawer.getStoredItemCount())));
            value.put("capacity", DeviceValue.of(decimal(drawer.getMaxCapacity())));
            value.put("remaining_capacity", DeviceValue.of(decimal(drawer.getRemainingCapacity())));
            value.put("enabled", DeviceValue.of(drawer.isEnabled()));
            value.put("missing", DeviceValue.of(drawer.isMissing()));
            value.put("lock_populated", DeviceValue.of(
                    attributes.isItemLocked(LockAttribute.LOCK_POPULATED)));
            value.put("lock_empty", DeviceValue.of(attributes.isItemLocked(LockAttribute.LOCK_EMPTY)));
            value.put("void", DeviceValue.of(attributes.isVoid()));
            value.put("unlimited_storage", DeviceValue.of(attributes.isUnlimitedStorage()));
            value.put("unlimited_vending", DeviceValue.of(attributes.isUnlimitedVending()));
            value.put("priority", DeviceValue.of(attributes.getPriority()));
            value.put("concealed", DeviceValue.of(attributes.isConcealed()));
            value.put("sealed", DeviceValue.of(attributes.isSealed()));
            value.put("showing_quantity", DeviceValue.of(attributes.isShowingQuantity()));
            value.put("fill_level", DeviceValue.of(attributes.hasFillLevel()));
            value.put("dictionary_conversion", DeviceValue.of(attributes.isDictConvertible()));
            value.put("balanced_fill", DeviceValue.of(attributes.isBalancedFill()));
            value.put("hopper", DeviceValue.of(attributes.isHopper()));
            value.put("magnet", DeviceValue.of(attributes.isMagnet()));
            value.put("suspended", DeviceValue.of(attributes.isSuspended()));
            if (drawer instanceof IFractionalDrawer fractional) {
                int conversionRate = Math.max(1, fractional.getConversionRate());
                value.put("fractional", DeviceValue.of(true));
                value.put("count_independent", DeviceValue.of(false));
                value.put("conversion_rate", DeviceValue.of(conversionRate));
                value.put("smallest_units_per_item", DeviceValue.of(
                        Math.max(1, maxConversionRate / conversionRate)));
                value.put("stored_remainder", DeviceValue.of(fractional.getStoredItemRemainder()));
                value.put("smallest_unit", DeviceValue.of(fractional.isSmallestUnit()));
            } else {
                value.put("fractional", DeviceValue.of(false));
                value.put("count_independent", DeviceValue.of(true));
            }
            values.add(DeviceValue.map(value));
        }
        return DeviceValue.list(values);
    }

    private static String resource(ItemStack prototype) {
        return prototype.isEmpty() ? "minecraft:air"
                : BuiltInRegistries.ITEM.getKey(prototype.getItem()).toString();
    }

    private static String decimal(int value) {
        return Integer.toString(Math.max(0, value));
    }

    private record FractionalSummary(boolean compacting, int maxConversionRate,
                                     String canonicalResource, String canonicalCount) {}
}
