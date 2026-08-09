package com.malice.terminalcraft.integration.appliedenergistics;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import com.malice.terminalcraft.blockentity.AppliedEnergisticsBridgeBlockEntity;
import com.malice.terminalcraft.device.DeviceValue;
import com.malice.terminalcraft.device.GenericItemStorage;
import com.malice.terminalcraft.integration.OptionalDeviceMetadata;
import com.malice.terminalcraft.integration.OptionalDeviceMetadataRegistry;
import com.malice.terminalcraft.integration.OptionalDeviceMutationPolicyRegistry;
import com.malice.terminalcraft.integration.OptionalIntegration;
import com.malice.terminalcraft.integration.OptionalItemStorageRegistry;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Read-only, bounded Applied Energistics 2 grid view attached through TerminalCraft's bridge block.
 *
 * <p>The adapter intentionally consumes only AE2's public grid, storage, energy, and node APIs. It
 * does not insert, extract, craft, cancel, or impersonate an AE2 security principal.</p>
 */
public final class AppliedEnergisticsIntegration implements OptionalIntegration {
    private static final int MAX_ITEM_KEY_SCAN = 4096;
    private static final int MAX_CRAFTING_CPU_SCAN = 64;

    @Override
    public void initialize() {
        OptionalDeviceMetadataRegistry.register(AppliedEnergisticsIntegration::describe);
        OptionalItemStorageRegistry.register(AppliedEnergisticsIntegration::storage);
        OptionalDeviceMutationPolicyRegistry.registerContextual((blockEntity, caller) ->
                blockEntity instanceof AppliedEnergisticsBridgeBlockEntity
                        ? OptionalDeviceMutationPolicyRegistry.Decision.deny(
                                "Applied Energistics bridge mutation is disabled until an authenticated AE2 principal is available")
                        : OptionalDeviceMutationPolicyRegistry.Decision.allow());
    }

    private static Optional<OptionalDeviceMetadata> describe(BlockEntity blockEntity) {
        if (!(blockEntity instanceof AppliedEnergisticsBridgeBlockEntity bridge)) return Optional.empty();
        Attachment attachment = attachment(bridge);
        Map<String, DeviceValue> properties = new LinkedHashMap<>();
        properties.put("applied_energistics_attachment", DeviceValue.of("dedicated_adjacent_bridge"));
        properties.put("applied_energistics_mutation", DeviceValue.of("read_only_fail_closed"));
        properties.put("applied_energistics_security", DeviceValue.of("no_principal_impersonation"));
        properties.put("applied_energistics_attachment_status", DeviceValue.of(attachment.status));
        properties.put("applied_energistics_attached_nodes", DeviceValue.of(attachment.nodeCount));
        properties.put("applied_energistics_cached_inventory", DeviceValue.of(true));
        properties.put("applied_energistics_item_scan_limit", DeviceValue.of(MAX_ITEM_KEY_SCAN));
        properties.put("applied_energistics_crafting_cpu_scan_limit", DeviceValue.of(MAX_CRAFTING_CPU_SCAN));
        if (attachment.grid != null) addGridProperties(properties, attachment);
        return Optional.of(new OptionalDeviceMetadata("terminalcraft:applied_energistics_bridge",
                "applied_energistics_bridge", Set.of("inventory", "applied_energistics_network"), properties));
    }

    private static Optional<GenericItemStorage> storage(BlockEntity blockEntity) {
        if (!(blockEntity instanceof AppliedEnergisticsBridgeBlockEntity bridge)) return Optional.empty();
        return Optional.of(query -> queryItems(bridge, query));
    }

    private static GenericItemStorage.ItemPage queryItems(AppliedEnergisticsBridgeBlockEntity bridge,
                                                            GenericItemStorage.ItemQuery query) {
        Attachment attachment = attachment(bridge);
        if (attachment.grid == null || attachment.node == null || !attachment.node.isActive()) {
            return new GenericItemStorage.ItemPage(List.of(), "");
        }
        IStorageService storage = attachment.grid.getStorageService();
        if (storage == null) return new GenericItemStorage.ItemPage(List.of(), "");

        Map<String, Aggregate> aggregates = new TreeMap<>();
        int scanned = 0;
        boolean complete = true;
        for (it.unimi.dsi.fastutil.objects.Object2LongMap.Entry<AEKey> entry
                : storage.getCachedInventory()) {
            if (scanned++ >= MAX_ITEM_KEY_SCAN) {
                complete = false;
                break;
            }
            if (!(entry.getKey() instanceof AEItemKey itemKey)) continue;
            ItemStack stack = itemKey.getReadOnlyStack();
            if (stack.isEmpty()) continue;
            String resourceId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            Set<String> tags = itemTags(stack.getItem());
            Aggregate aggregate = aggregates.computeIfAbsent(resourceId, ignored -> new Aggregate());
            aggregate.count = saturatingAdd(aggregate.count, Math.max(0, entry.getLongValue()));
            aggregate.tags.addAll(tags);
        }

        // The generic page contract has no partial-source marker. Never return a plausible page
        // that silently omits keys beyond the scan budget.
        if (!complete) return new GenericItemStorage.ItemPage(List.of(), "");

        List<GenericItemStorage.ItemResource> resources = new ArrayList<>();
        for (Map.Entry<String, Aggregate> entry : aggregates.entrySet()) {
            if (query.matches(entry.getKey(), entry.getValue().tags)) {
                resources.add(new GenericItemStorage.ItemResource(entry.getKey(), entry.getValue().count,
                        entry.getValue().tags));
            }
        }
        resources.sort(Comparator.comparing(GenericItemStorage.ItemResource::resourceId));
        int start = Math.min(query.offset(), resources.size());
        int end = Math.min(resources.size(), start + query.limit());
        String next = end < resources.size() ? Integer.toString(end) : "";
        return new GenericItemStorage.ItemPage(resources.subList(start, end), next);
    }

    private static Set<String> itemTags(Item item) {
        TreeSet<String> tags = new TreeSet<>();
        BuiltInRegistries.ITEM.wrapAsHolder(item).tags()
                .map(TagKey::location).map(Object::toString).forEach(tags::add);
        return Set.copyOf(tags);
    }

    private static void addGridProperties(Map<String, DeviceValue> properties, Attachment attachment) {
        IGridNode node = attachment.node;
        properties.put("applied_energistics_online", DeviceValue.of(node != null && node.isOnline()));
        properties.put("applied_energistics_active", DeviceValue.of(node != null && node.isActive()));
        properties.put("applied_energistics_powered", DeviceValue.of(node != null && node.isPowered()));
        properties.put("applied_energistics_grid_booted", DeviceValue.of(node != null && node.hasGridBooted()));
        properties.put("applied_energistics_channels_met",
                DeviceValue.of(node != null && node.meetsChannelRequirements()));
        properties.put("applied_energistics_channels_used", DeviceValue.of(node == null ? 0 : node.getUsedChannels()));
        properties.put("applied_energistics_channels_max", DeviceValue.of(node == null ? 0 : node.getMaxChannels()));
        properties.put("applied_energistics_grid_nodes", DeviceValue.of(attachment.grid.size()));

        IEnergyService energy = attachment.grid.getEnergyService();
        if (energy != null) {
            properties.put("applied_energistics_energy_stored_ae", DeviceValue.of(energy.getStoredPower()));
            properties.put("applied_energistics_energy_capacity_ae", DeviceValue.of(energy.getMaxStoredPower()));
            properties.put("applied_energistics_energy_idle_ae_per_tick", DeviceValue.of(energy.getIdlePowerUsage()));
            properties.put("applied_energistics_energy_avg_usage_ae_per_tick", DeviceValue.of(energy.getAvgPowerUsage()));
        }

        IStorageService storage = attachment.grid.getStorageService();
        if (storage != null) {
            KeyCounter cached = storage.getCachedInventory();
            int cachedKeys = cached.size();
            properties.put("applied_energistics_cached_key_count",
                    DeviceValue.of(Math.min(cachedKeys, MAX_ITEM_KEY_SCAN)));
            properties.put("applied_energistics_cached_key_count_complete",
                    DeviceValue.of(cachedKeys <= MAX_ITEM_KEY_SCAN));
        }

        ICraftingService crafting = attachment.grid.getCraftingService();
        if (crafting != null) {
            int cpuCount = 0;
            int busyCount = 0;
            long availableStorage = 0;
            boolean complete = true;
            for (ICraftingCPU cpu : crafting.getCpus()) {
                if (cpuCount >= MAX_CRAFTING_CPU_SCAN) {
                    complete = false;
                    break;
                }
                cpuCount++;
                if (cpu.isBusy()) busyCount++;
                availableStorage = saturatingAdd(availableStorage,
                        Math.max(0, cpu.getAvailableStorage()));
            }
            properties.put("applied_energistics_crafting_cpu_count", DeviceValue.of(cpuCount));
            properties.put("applied_energistics_crafting_busy_cpu_count", DeviceValue.of(busyCount));
            properties.put("applied_energistics_crafting_available_storage",
                    DeviceValue.of(availableStorage));
            properties.put("applied_energistics_crafting_cpu_count_complete", DeviceValue.of(complete));
        }
    }

    private static Attachment attachment(AppliedEnergisticsBridgeBlockEntity bridge) {
        if (!(bridge.getLevel() instanceof ServerLevel level)) {
            return new Attachment("api_unavailable", 0, null, null);
        }
        Set<IGrid> grids = Collections.newSetFromMap(new IdentityHashMap<>());
        IGridNode representative = null;
        int nodes = 0;
        for (Direction direction : Direction.values()) {
            try {
                IGridNode node = GridHelper.getExposedNode(level,
                        bridge.getBlockPos().relative(direction), direction.getOpposite());
                if (node == null || node.getGrid() == null) continue;
                nodes++;
                grids.add(node.getGrid());
                if (representative == null) representative = node;
            } catch (RuntimeException exception) {
                return new Attachment("api_error", nodes, null, null);
            }
        }
        if (nodes == 0) return new Attachment("detached", 0, null, null);
        if (grids.size() != 1) return new Attachment("ambiguous_multiple_networks", nodes, null, null);
        IGrid grid = grids.iterator().next();
        String status = representative != null && representative.isActive()
                ? "attached_active" : "attached_offline";
        return new Attachment(status, nodes, grid, representative);
    }

    private static long saturatingAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private static final class Aggregate {
        private long count;
        private final Set<String> tags = new TreeSet<>();
    }

    private record Attachment(String status, int nodeCount, IGrid grid, IGridNode node) {}
}
