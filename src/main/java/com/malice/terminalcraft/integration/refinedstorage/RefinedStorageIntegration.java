package com.malice.terminalcraft.integration.refinedstorage;

import com.malice.terminalcraft.blockentity.RefinedStorageBridgeBlockEntity;
import com.malice.terminalcraft.device.DeviceValue;
import com.malice.terminalcraft.device.GenericItemStorage;
import com.malice.terminalcraft.integration.OptionalDeviceMetadata;
import com.malice.terminalcraft.integration.OptionalDeviceMetadataRegistry;
import com.malice.terminalcraft.integration.OptionalCraftingServiceRegistry;
import com.malice.terminalcraft.integration.OptionalDeviceMutationPolicyRegistry;
import com.malice.terminalcraft.integration.OptionalIntegration;
import com.malice.terminalcraft.integration.OptionalItemStorageRegistry;
import com.refinedmods.refinedstorage.api.IRSAPI;
import com.refinedmods.refinedstorage.api.RSAPIInject;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.node.INetworkNode;
import com.refinedmods.refinedstorage.api.network.node.INetworkNodeProxy;
import com.refinedmods.refinedstorage.api.storage.IStorage;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDisk;
import com.refinedmods.refinedstorage.api.util.StackListEntry;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Read-only, bounded Refined Storage network view attached through TerminalCraft's bridge block. */
public final class RefinedStorageIntegration implements OptionalIntegration {
    private static final int MAX_FLUID_TELEMETRY = 64;

    @RSAPIInject
    public static IRSAPI API;

    @Override
    public void initialize() {
        OptionalDeviceMetadataRegistry.register(RefinedStorageIntegration::describe);
        OptionalItemStorageRegistry.register(RefinedStorageIntegration::storage);
        OptionalCraftingServiceRegistry.register(RefinedStorageIntegration::crafting);
        OptionalDeviceMutationPolicyRegistry.register(blockEntity ->
                blockEntity instanceof RefinedStorageBridgeBlockEntity
                        ? OptionalDeviceMutationPolicyRegistry.Decision.deny(
                                "Refined Storage bridge mutation is disabled until an authenticated RS principal is available")
                        : OptionalDeviceMutationPolicyRegistry.Decision.allow());
    }

    private static Optional<OptionalDeviceMetadata> describe(BlockEntity blockEntity) {
        if (!(blockEntity instanceof RefinedStorageBridgeBlockEntity bridge)) return Optional.empty();
        Attachment attachment = attachment(bridge);
        Map<String, DeviceValue> properties = new LinkedHashMap<>();
        properties.put("refined_storage_attachment", DeviceValue.of("dedicated_adjacent_bridge"));
        properties.put("refined_storage_mutation", DeviceValue.of("read_only_fail_closed"));
        properties.put("refined_storage_attachment_status", DeviceValue.of(attachment.status));
        properties.put("refined_storage_attached_nodes", DeviceValue.of(attachment.nodeCount));
        if (attachment.network != null) addNetworkProperties(properties, attachment.network);
        return Optional.of(new OptionalDeviceMetadata("terminalcraft:refined_storage_bridge",
                "refined_storage_bridge", Set.of("inventory", "refined_storage_network"), properties));
    }

    private static Optional<GenericItemStorage> storage(BlockEntity blockEntity) {
        if (!(blockEntity instanceof RefinedStorageBridgeBlockEntity bridge)) return Optional.empty();
        return Optional.of(query -> queryItems(bridge, query));
    }

    private static Optional<com.malice.terminalcraft.device.GenericCraftingService> crafting(
            BlockEntity blockEntity) {
        return blockEntity instanceof RefinedStorageBridgeBlockEntity bridge
                ? Optional.of(new RefinedStorageCraftingService(bridge)) : Optional.empty();
    }

    static Optional<INetwork> attachedNetwork(RefinedStorageBridgeBlockEntity bridge) {
        Attachment attachment = attachment(bridge);
        return attachment.network != null && attachment.network.canRun()
                ? Optional.of(attachment.network) : Optional.empty();
    }

    private static GenericItemStorage.ItemPage queryItems(RefinedStorageBridgeBlockEntity bridge,
                                                           GenericItemStorage.ItemQuery query) {
        Attachment attachment = attachment(bridge);
        if (attachment.network == null || !attachment.network.canRun()) {
            return new GenericItemStorage.ItemPage(List.of(), "");
        }
        List<GenericItemStorage.ItemResource> resources = new ArrayList<>();
        for (StackListEntry<ItemStack> entry : attachment.network.getItemStorageCache().getList().getStacks()) {
            ItemStack stack = entry.getStack();
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            Set<String> tags = itemTags(stack.getItem());
            if (query.matches(id, tags)) {
                resources.add(new GenericItemStorage.ItemResource(id, stack.getCount(), tags));
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

    private static void addNetworkProperties(Map<String, DeviceValue> properties, INetwork network) {
        properties.put("refined_storage_online", DeviceValue.of(network.canRun()));
        properties.put("refined_storage_energy_stored_fe",
                DeviceValue.of(network.getEnergyStorage().getEnergyStored()));
        properties.put("refined_storage_energy_capacity_fe",
                DeviceValue.of(network.getEnergyStorage().getMaxEnergyStored()));
        properties.put("refined_storage_energy_usage_fe_per_tick", DeviceValue.of(network.getEnergyUsage()));
        properties.put("refined_storage_item_types",
                DeviceValue.of(network.getItemStorageCache().getList().size()));
        properties.put("refined_storage_fluid_types",
                DeviceValue.of(network.getFluidStorageCache().getList().size()));
        addCapacity(properties, "item", network.getItemStorageCache().getStorages());
        addCapacity(properties, "fluid", network.getFluidStorageCache().getStorages());

        List<DeviceValue> fluids = new ArrayList<>();
        int seen = 0;
        for (StackListEntry<FluidStack> entry : network.getFluidStorageCache().getList().getStacks()) {
            if (seen++ == MAX_FLUID_TELEMETRY) break;
            FluidStack stack = entry.getStack();
            if (stack.isEmpty()) continue;
            fluids.add(DeviceValue.map(Map.of(
                    "resource", DeviceValue.of(BuiltInRegistries.FLUID.getKey(stack.getFluid()).toString()),
                    "amount_mb", DeviceValue.of(Integer.toString(stack.getAmount())))));
        }
        properties.put("refined_storage_fluids", DeviceValue.list(fluids));
        properties.put("refined_storage_fluids_complete", DeviceValue.of(seen <= MAX_FLUID_TELEMETRY));
        properties.put("refined_storage_telemetry_limit", DeviceValue.of(MAX_FLUID_TELEMETRY));
    }

    private static void addCapacity(Map<String, DeviceValue> properties, String kind,
                                    List<? extends IStorage<?>> storages) {
        long stored = 0;
        long capacity = 0;
        boolean complete = true;
        for (IStorage<?> storage : storages) {
            stored = saturatingAdd(stored, Math.max(0, storage.getStored()));
            if (storage instanceof IStorageDisk<?> disk) {
                capacity = saturatingAdd(capacity, Math.max(0, disk.getCapacity()));
            } else {
                complete = false;
            }
        }
        properties.put("refined_storage_" + kind + "_stored", DeviceValue.of(Long.toString(stored)));
        properties.put("refined_storage_" + kind + "_known_capacity", DeviceValue.of(Long.toString(capacity)));
        properties.put("refined_storage_" + kind + "_capacity_complete", DeviceValue.of(complete));
    }

    private static Attachment attachment(RefinedStorageBridgeBlockEntity bridge) {
        if (!(bridge.getLevel() instanceof ServerLevel level) || API == null) {
            return new Attachment("api_unavailable", 0, null);
        }
        Set<INetwork> networks = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        int nodes = 0;
        for (Direction direction : Direction.values()) {
            net.minecraft.core.BlockPos nodePosition = bridge.getBlockPos().relative(direction);
            INetworkNode node = API.getNetworkNodeManager(level).getNode(nodePosition);
            if (node == null) {
                BlockEntity adjacent = level.getBlockEntity(nodePosition);
                if (adjacent instanceof INetworkNodeProxy<?> proxy) node = proxy.getNode();
            }
            if (node == null || node.getNetwork() == null) continue;
            nodes++;
            networks.add(node.getNetwork());
        }
        if (nodes == 0) return new Attachment("detached", 0, null);
        if (networks.size() != 1) return new Attachment("ambiguous_multiple_networks", nodes, null);
        INetwork network = networks.iterator().next();
        return new Attachment(network.canRun() ? "attached_online" : "attached_offline", nodes, network);
    }

    private static long saturatingAdd(long left, long right) {
        return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
    }

    private record Attachment(String status, int nodeCount, INetwork network) {}
}
