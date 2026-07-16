package com.malice.terminalcraft.device;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Reacquires side-aware Forge capabilities for every operation; no LazyOptional is retained. */
final class ForgeCapabilityDevice implements GenericCapabilityDevice {
    private final ServerLevel level;
    private final BlockPos target;
    private final Direction accessSide;

    ForgeCapabilityDevice(ServerLevel level, BlockPos target, Direction accessSide) {
        this.level = level;
        this.target = target.immutable();
        this.accessSide = accessSide;
    }

    @Override public boolean hasInventory() { return itemHandler() != null; }
    @Override public boolean hasFluidStorage() { return fluidHandler() != null; }
    @Override public boolean hasEnergyStorage() { return energyStorage() != null; }

    @Override
    public List<ItemSlot> itemSlots(int limit) {
        IItemHandler handler = itemHandler();
        if (handler == null) return List.of();
        List<ItemSlot> result = new ArrayList<>();
        int slots = Math.min(handler.getSlots(), MAX_INVENTORY_SLOTS);
        for (int slot = 0; slot < slots && result.size() < limit; slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty()) result.add(new ItemSlot(slot,
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(),
                    handler.getSlotLimit(slot)));
        }
        return List.copyOf(result);
    }

    @Override
    public ItemPage queryItems(ItemQuery query) {
        IItemHandler handler = itemHandler();
        if (handler == null) return new ItemPage(List.of(), "");

        Map<String, Long> counts = new LinkedHashMap<>();
        for (int slot = 0; slot < Math.min(handler.getSlots(), MAX_INVENTORY_SLOTS); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            String resourceId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            if (query.matches(resourceId, itemTags(stack.getItem()))) {
                counts.merge(resourceId, (long) stack.getCount(), Math::addExact);
            }
        }

        List<ItemResource> resources = counts.entrySet().stream()
                .map(entry -> new ItemResource(entry.getKey(), entry.getValue(),
                        itemTags(item(entry.getKey()))))
                .sorted(Comparator.comparing(ItemResource::resourceId))
                .toList();
        int offset = Math.min(query.offset(), resources.size());
        int end = Math.min(offset + query.limit(), resources.size());
        String nextCursor = end < resources.size() ? Integer.toString(end) : "";
        return new ItemPage(resources.subList(offset, end), nextCursor);
    }

    @Override
    public long itemCount(String resourceId) {
        IItemHandler handler = itemHandler();
        Item item = item(resourceId);
        if (handler == null || item == null) return 0;
        long count = 0;
        for (int slot = 0; slot < Math.min(handler.getSlots(), MAX_INVENTORY_SLOTS); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    @Override public long simulateItemInsert(String resourceId, int count) {
        IItemHandler handler = itemHandler();
        Item item = item(resourceId);
        return handler == null || item == null ? 0 : insert(handler, item, count, true);
    }

    @Override public long simulateItemExtract(String resourceId, int count) {
        IItemHandler handler = itemHandler();
        Item item = item(resourceId);
        return handler == null || item == null ? 0 : extract(handler, item, count, true);
    }

    @Override
    public TransferOutcome insertItems(String resourceId, int count) {
        IItemHandler handler = itemHandler();
        Item item = item(resourceId);
        if (handler == null || item == null) return TransferOutcome.none(count);
        long simulated = insert(handler, item, count, true);
        long executed = insert(handler, item, (int) simulated, false);
        return new TransferOutcome(count, simulated, executed);
    }

    @Override
    public TransferOutcome extractItems(String resourceId, int count) {
        IItemHandler handler = itemHandler();
        Item item = item(resourceId);
        if (handler == null || item == null) return TransferOutcome.none(count);
        long simulated = extract(handler, item, count, true);
        long executed = extract(handler, item, (int) simulated, false);
        return new TransferOutcome(count, simulated, executed);
    }

    @Override
    public List<FluidTank> fluidTanks(int limit) {
        IFluidHandler handler = fluidHandler();
        if (handler == null) return List.of();
        List<FluidTank> result = new ArrayList<>();
        int tanks = Math.min(handler.getTanks(), MAX_FLUID_TANKS);
        for (int tank = 0; tank < tanks && result.size() < limit; tank++) {
            FluidStack stack = handler.getFluidInTank(tank);
            ResourceLocation key = stack.isEmpty() ? null : ForgeRegistries.FLUIDS.getKey(stack.getFluid());
            String id = key == null ? "minecraft:empty" : key.toString();
            result.add(new FluidTank(tank, id, stack.getAmount(), handler.getTankCapacity(tank)));
        }
        return List.copyOf(result);
    }

    @Override public long simulateFluidFill(String resourceId, int amountMb) {
        IFluidHandler handler = fluidHandler();
        net.minecraft.world.level.material.Fluid fluid = fluid(resourceId);
        return handler == null || fluid == null ? 0
                : handler.fill(new FluidStack(fluid, amountMb), IFluidHandler.FluidAction.SIMULATE);
    }

    @Override public long simulateFluidDrain(String resourceId, int amountMb) {
        IFluidHandler handler = fluidHandler();
        net.minecraft.world.level.material.Fluid fluid = fluid(resourceId);
        return handler == null || fluid == null ? 0
                : handler.drain(new FluidStack(fluid, amountMb), IFluidHandler.FluidAction.SIMULATE).getAmount();
    }

    @Override
    public TransferOutcome fillFluid(String resourceId, int amountMb) {
        IFluidHandler handler = fluidHandler();
        net.minecraft.world.level.material.Fluid fluid = fluid(resourceId);
        if (handler == null || fluid == null) return TransferOutcome.none(amountMb);
        int simulated = handler.fill(new FluidStack(fluid, amountMb), IFluidHandler.FluidAction.SIMULATE);
        int executed = handler.fill(new FluidStack(fluid, simulated), IFluidHandler.FluidAction.EXECUTE);
        return new TransferOutcome(amountMb, simulated, executed);
    }

    @Override
    public TransferOutcome drainFluid(String resourceId, int amountMb) {
        IFluidHandler handler = fluidHandler();
        net.minecraft.world.level.material.Fluid fluid = fluid(resourceId);
        if (handler == null || fluid == null) return TransferOutcome.none(amountMb);
        int simulated = handler.drain(new FluidStack(fluid, amountMb), IFluidHandler.FluidAction.SIMULATE).getAmount();
        FluidStack executedStack = handler.drain(new FluidStack(fluid, simulated), IFluidHandler.FluidAction.EXECUTE);
        int executed = executedStack.isEmpty() || executedStack.getFluid() != fluid ? 0 : executedStack.getAmount();
        return new TransferOutcome(amountMb, simulated, executed);
    }

    @Override public EnergyStatus energyStatus() {
        IEnergyStorage storage = energyStorage();
        return storage == null ? new EnergyStatus(0, 0, false, false)
                : new EnergyStatus(storage.getEnergyStored(), storage.getMaxEnergyStored(),
                storage.canReceive(), storage.canExtract());
    }

    @Override public long simulateEnergyReceive(int amountFe) {
        EnergyEndpoint endpoint = energyEndpoint();
        return endpoint == null ? 0 : checkedEnergyAmount(
                endpoint.storage().receiveEnergy(amountFe, true), amountFe, "receive simulation");
    }

    @Override public long simulateEnergyExtract(int amountFe) {
        EnergyEndpoint endpoint = energyEndpoint();
        return endpoint == null ? 0 : checkedEnergyAmount(
                endpoint.storage().extractEnergy(amountFe, true), amountFe, "extract simulation");
    }

    @Override
    public TransferOutcome receiveEnergy(int amountFe) {
        return mutateEnergy(amountFe, true);
    }

    @Override
    public TransferOutcome extractEnergy(int amountFe) {
        return mutateEnergy(amountFe, false);
    }

    /**
     * FE mutation is a single, non-reversible attempt. Simulation is advisory; the executed
     * amount is authoritative. The block entity and capability identity are revalidated after
     * simulation so a replacement is never mutated through a stale handle.
     */
    private TransferOutcome mutateEnergy(int amountFe, boolean receive) {
        EnergyEndpoint endpoint = energyEndpoint();
        if (endpoint == null) return TransferOutcome.none(amountFe);
        int simulated = checkedEnergyAmount(receive
                ? endpoint.storage().receiveEnergy(amountFe, true)
                : endpoint.storage().extractEnergy(amountFe, true), amountFe,
                receive ? "receive simulation" : "extract simulation");
        if (simulated == 0 || !isCurrent(endpoint)) {
            return new TransferOutcome(amountFe, simulated, 0);
        }

        final int executed;
        try {
            executed = receive ? endpoint.storage().receiveEnergy(simulated, false)
                    : endpoint.storage().extractEnergy(simulated, false);
        } catch (RuntimeException exception) {
            throw new IndeterminateEnergyMutationException(
                    "Forge Energy execution failed; the mutation outcome is indeterminate", exception);
        }
        try {
            return new TransferOutcome(amountFe, simulated,
                    checkedEnergyAmount(executed, simulated,
                            receive ? "receive execution" : "extract execution"));
        } catch (RuntimeException exception) {
            throw new IndeterminateEnergyMutationException(
                    "Forge Energy execution returned an invalid amount; the mutation outcome is indeterminate",
                    exception);
        }
    }

    private boolean isCurrent(EnergyEndpoint expected) {
        BlockEntity current = blockEntity();
        if (current != expected.owner() || current == null || current.isRemoved()) return false;
        IEnergyStorage storage = current.getCapability(ForgeCapabilities.ENERGY, accessSide)
                .resolve().orElse(null);
        return storage == expected.storage();
    }

    private static int checkedEnergyAmount(int amount, int maximum, String operation) {
        if (amount < 0 || amount > maximum) {
            throw new IllegalStateException("energy handler returned invalid " + operation + " amount");
        }
        return amount;
    }

    private static int insert(IItemHandler handler, Item item, int count, boolean simulate) {
        ItemStack remainder = new ItemStack(item, count);
        for (int slot = 0; slot < Math.min(handler.getSlots(), MAX_INVENTORY_SLOTS) && !remainder.isEmpty(); slot++) {
            remainder = handler.insertItem(slot, remainder, simulate);
        }
        return count - remainder.getCount();
    }

    private static int extract(IItemHandler handler, Item item, int count, boolean simulate) {
        int extracted = 0;
        for (int slot = 0; slot < Math.min(handler.getSlots(), MAX_INVENTORY_SLOTS) && extracted < count; slot++) {
            ItemStack visible = handler.getStackInSlot(slot);
            if (!visible.is(item)) continue;
            ItemStack result = handler.extractItem(slot, count - extracted, simulate);
            if (result.is(item)) extracted += Math.min(result.getCount(), count - extracted);
        }
        return extracted;
    }

    private BlockEntity blockEntity() {
        if (!level.hasChunkAt(target)) return null;
        return level.getBlockEntity(target);
    }

    private IItemHandler itemHandler() {
        BlockEntity blockEntity = blockEntity();
        return blockEntity == null ? null : blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, accessSide).resolve().orElse(null);
    }

    private IFluidHandler fluidHandler() {
        BlockEntity blockEntity = blockEntity();
        return blockEntity == null ? null : blockEntity.getCapability(ForgeCapabilities.FLUID_HANDLER, accessSide).resolve().orElse(null);
    }

    private IEnergyStorage energyStorage() {
        EnergyEndpoint endpoint = energyEndpoint();
        return endpoint == null ? null : endpoint.storage();
    }

    private EnergyEndpoint energyEndpoint() {
        BlockEntity blockEntity = blockEntity();
        if (blockEntity == null || blockEntity.isRemoved()) return null;
        IEnergyStorage storage = blockEntity.getCapability(ForgeCapabilities.ENERGY, accessSide)
                .resolve().orElse(null);
        return storage == null ? null : new EnergyEndpoint(blockEntity, storage);
    }

    private record EnergyEndpoint(BlockEntity owner, IEnergyStorage storage) {}

    private static Set<String> itemTags(Item item) {
        if (item == null) return Set.of();
        TreeSet<String> tags = new TreeSet<>();
        item.builtInRegistryHolder().tags().forEach(tag -> tags.add(tag.location().toString()));
        return Set.copyOf(tags);
    }

    private static Item item(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        return key == null || !BuiltInRegistries.ITEM.containsKey(key) ? null : BuiltInRegistries.ITEM.get(key);
    }

    private static net.minecraft.world.level.material.Fluid fluid(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        return key == null || !ForgeRegistries.FLUIDS.containsKey(key) ? null : ForgeRegistries.FLUIDS.getValue(key);
    }
}
