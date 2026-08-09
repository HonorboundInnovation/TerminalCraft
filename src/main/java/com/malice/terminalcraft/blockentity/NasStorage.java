package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.device.GenericCapabilityDevice;
import com.malice.terminalcraft.device.GenericItemStorage;
import com.malice.terminalcraft.item.SolidStateDriveItem;
import com.malice.terminalcraft.item.SolidStateDriveTier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Portable drive-backed aggregate item/fluid storage used by a NAS block entity. */
final class NasStorage implements GenericCapabilityDevice {
    static final int DRIVE_SLOTS = 8;
    private static final int MAX_ITEM_ENTRIES = 128;
    private static final int MAX_FLUID_ENTRIES = 16;
    private final Runnable changed;
    private final ItemStackHandler drives = new ItemStackHandler(DRIVE_SLOTS) {
        @Override public boolean isItemValid(int slot, @NotNull ItemStack stack) { return SolidStateDriveItem.tier(stack) != null; }
        @Override public int getSlotLimit(int slot) { return 1; }
        @Override protected void onContentsChanged(int slot) { changed.run(); }
    };

    NasStorage(Runnable changed) { this.changed = changed; }

    ItemStack drive(int slot) { return slot < 0 || slot >= DRIVE_SLOTS ? ItemStack.EMPTY : drives.getStackInSlot(slot); }
    int installedDrives() { int n = 0; for (int i = 0; i < DRIVE_SLOTS; i++) if (tier(i) != null) n++; return n; }
    int itemCapacity() { int n = 0; for (int i = 0; i < DRIVE_SLOTS; i++) if (tier(i) != null) n += tier(i).itemCapacity(); return n; }
    int fluidCapacity() { int n = 0; for (int i = 0; i < DRIVE_SLOTS; i++) if (tier(i) != null) n += tier(i).fluidCapacityMb(); return n; }
    private SolidStateDriveTier tier(int slot) { return SolidStateDriveItem.tier(drive(slot)); }

    boolean insertDrive(ItemStack stack) {
        if (SolidStateDriveItem.tier(stack) == null) return false;
        for (int slot = 0; slot < DRIVE_SLOTS; slot++) if (drives.getStackInSlot(slot).isEmpty()) {
            ItemStack copy = stack.copy(); copy.setCount(1); drives.setStackInSlot(slot, copy); return true;
        }
        return false;
    }

    ItemStack ejectLastDrive() {
        for (int slot = DRIVE_SLOTS - 1; slot >= 0; slot--) {
            ItemStack stack = drive(slot);
            if (!stack.isEmpty()) { ItemStack result = stack.copy(); drives.setStackInSlot(slot, ItemStack.EMPTY); return result; }
        }
        return ItemStack.EMPTY;
    }

    List<ItemStack> itemEntries() {
        List<ItemStack> result = new ArrayList<>();
        for (int slot = 0; slot < DRIVE_SLOTS; slot++) result.addAll(readItems(slot));
        result.sort(Comparator.comparing(NasStorage::itemId).thenComparing(s -> s.getTag() == null ? "" : s.getTag().toString()));
        return result;
    }

    List<FluidStack> fluidEntries() {
        List<FluidStack> result = new ArrayList<>();
        for (int slot = 0; slot < DRIVE_SLOTS; slot++) result.addAll(readFluids(slot));
        result.sort(Comparator.comparing(NasStorage::fluidId));
        return result;
    }

    int driveItemCount(int slot) { return readItems(slot).stream().mapToInt(ItemStack::getCount).sum(); }
    int driveFluidAmount(int slot) { return readFluids(slot).stream().mapToInt(FluidStack::getAmount).sum(); }

    ItemStack extractFirst(int amount) {
        List<ItemStack> entries = itemEntries(); return entries.isEmpty() ? ItemStack.EMPTY : extract(entries.get(0), amount, false);
    }
    ItemStack extractMatching(ItemStack stack, int amount) { return stack.isEmpty() ? ItemStack.EMPTY : extract(stack, amount, false); }
    int insertStack(ItemStack stack) { return stack.isEmpty() ? 0 : insert(stack, false); }

    IItemHandler itemHandler() { return new StorageItems(); }
    IFluidHandler fluidHandler() { return new StorageFluids(); }

    void save(CompoundTag tag) { tag.put("Drives", drives.serializeNBT()); }
    void load(CompoundTag tag) { if (tag.contains("Drives", Tag.TAG_COMPOUND)) drives.deserializeNBT(tag.getCompound("Drives")); }
    void drop(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        for (int slot = 0; slot < DRIVE_SLOTS; slot++) if (!drive(slot).isEmpty())
            net.minecraft.world.Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), drive(slot));
        for (int slot = 0; slot < DRIVE_SLOTS; slot++) drives.setStackInSlot(slot, ItemStack.EMPTY);
    }

    @Override public boolean hasInventory() { return installedDrives() > 0; }
    @Override public boolean hasFluidStorage() { return installedDrives() > 0; }

    @Override public List<ItemSlot> itemSlots(int limit) {
        List<ItemSlot> result = new ArrayList<>(); List<ItemStack> entries = itemEntries();
        for (int i = 0; i < entries.size() && result.size() < limit; i++) {
            ItemStack stack = entries.get(i); result.add(new ItemSlot(i, itemId(stack), stack.getCount(), stack.getMaxStackSize()));
        }
        return List.copyOf(result);
    }

    @Override public ItemPage queryItems(ItemQuery query) {
        Map<String, Long> counts = new TreeMap<>(); Map<String, Set<String>> tags = new LinkedHashMap<>();
        for (ItemStack stack : itemEntries()) {
            String id = itemId(stack); if (!query.matches(id, itemTags(stack.getItem()))) continue;
            counts.merge(id, (long) stack.getCount(), Math::addExact); tags.putIfAbsent(id, itemTags(stack.getItem()));
        }
        List<ItemResource> resources = counts.entrySet().stream().map(e -> new ItemResource(e.getKey(), e.getValue(), tags.get(e.getKey()))).toList();
        int from = Math.min(query.offset(), resources.size()), to = Math.min(from + query.limit(), resources.size());
        return new ItemPage(resources.subList(from, to), to < resources.size() ? Integer.toString(to) : "");
    }

    @Override public long itemCount(String resourceId) {
        Item item = item(resourceId); if (item == null) return 0;
        return itemEntries().stream().filter(stack -> stack.is(item)).mapToLong(ItemStack::getCount).sum();
    }

    @Override public long simulateItemInsert(String resourceId, int count) { Item i = item(resourceId); return i == null ? 0 : insert(new ItemStack(i, count), true); }
    @Override public long simulateItemExtract(String resourceId, int count) { Item i = item(resourceId); return i == null ? 0 : extract(new ItemStack(i, 1), count, true).getCount(); }
    @Override public TransferOutcome insertItems(String resourceId, int count) {
        Item i = item(resourceId); if (i == null) return TransferOutcome.none(count);
        int simulated = insert(new ItemStack(i, count), true), executed = insert(new ItemStack(i, simulated), false);
        return new TransferOutcome(count, simulated, executed);
    }
    @Override public TransferOutcome extractItems(String resourceId, int count) {
        Item i = item(resourceId); if (i == null) return TransferOutcome.none(count);
        int simulated = extract(new ItemStack(i, 1), count, true).getCount(), executed = extract(new ItemStack(i, 1), simulated, false).getCount();
        return new TransferOutcome(count, simulated, executed);
    }

    @Override public List<FluidTank> fluidTanks(int limit) {
        List<FluidTank> result = new ArrayList<>(); List<FluidStack> entries = fluidEntries();
        for (int i = 0; i < entries.size() && result.size() < limit; i++) result.add(new FluidTank(i, fluidId(entries.get(i)), entries.get(i).getAmount(), Math.max(1, fluidCapacity())));
        return List.copyOf(result);
    }
    @Override public long simulateFluidFill(String resourceId, int amount) { net.minecraft.world.level.material.Fluid f = fluid(resourceId); return f == null ? 0 : fill(new FluidStack(f, amount), IFluidHandler.FluidAction.SIMULATE); }
    @Override public long simulateFluidDrain(String resourceId, int amount) { net.minecraft.world.level.material.Fluid f = fluid(resourceId); return f == null ? 0 : drain(new FluidStack(f, amount), IFluidHandler.FluidAction.SIMULATE).getAmount(); }
    @Override public TransferOutcome fillFluid(String resourceId, int amount) { net.minecraft.world.level.material.Fluid f = fluid(resourceId); if (f == null) return TransferOutcome.none(amount); int s = fill(new FluidStack(f, amount), IFluidHandler.FluidAction.SIMULATE), e = fill(new FluidStack(f, s), IFluidHandler.FluidAction.EXECUTE); return new TransferOutcome(amount, s, e); }
    @Override public TransferOutcome drainFluid(String resourceId, int amount) { net.minecraft.world.level.material.Fluid f = fluid(resourceId); if (f == null) return TransferOutcome.none(amount); int s = drain(new FluidStack(f, amount), IFluidHandler.FluidAction.SIMULATE).getAmount(), e = drain(new FluidStack(f, s), IFluidHandler.FluidAction.EXECUTE).getAmount(); return new TransferOutcome(amount, s, e); }

    private int insert(ItemStack requested, boolean simulate) {
        if (requested.isEmpty() || installedDrives() == 0) return 0;
        int remaining = requested.getCount(), inserted = 0;
        for (int slot = 0; slot < DRIVE_SLOTS && remaining > 0; slot++) {
            SolidStateDriveTier tier = tier(slot); if (tier == null) continue;
            List<ItemStack> entries = readItems(slot); int used = entries.stream().mapToInt(ItemStack::getCount).sum(); boolean matched = false;
            for (ItemStack entry : entries) if (ItemStack.isSameItemSameTags(entry, requested)) {
                matched = true; int accepted = Math.min(remaining, Math.max(0, tier.itemCapacity() - used));
                if (accepted > 0) { if (!simulate) entry.grow(accepted); remaining -= accepted; inserted += accepted; }
                break;
            }
            if (!matched && remaining > 0 && entries.size() < Math.min(MAX_ITEM_ENTRIES, tier.itemEntries())) {
                int accepted = Math.min(remaining, Math.max(0, tier.itemCapacity() - used));
                if (accepted > 0) { ItemStack added = requested.copy(); added.setCount(accepted); entries.add(added); remaining -= accepted; inserted += accepted; }
            }
            if (!simulate && inserted > 0) writeItems(slot, entries);
        }
        return inserted;
    }

    private ItemStack extract(ItemStack requested, int amount, boolean simulate) {
        if (requested.isEmpty() || amount < 1) return ItemStack.EMPTY;
        int remaining = amount; ItemStack result = ItemStack.EMPTY;
        for (int slot = 0; slot < DRIVE_SLOTS && remaining > 0; slot++) {
            if (tier(slot) == null) continue; List<ItemStack> entries = readItems(slot); boolean changed = false;
            for (int i = entries.size() - 1; i >= 0 && remaining > 0; i--) {
                ItemStack entry = entries.get(i); if (!ItemStack.isSameItemSameTags(entry, requested)) continue;
                int removed = Math.min(remaining, entry.getCount()); if (result.isEmpty()) result = entry.copy(); result.setCount(result.getCount() + removed); remaining -= removed; changed = true;
                if (!simulate) { entry.shrink(removed); if (entry.isEmpty()) entries.remove(i); }
            }
            if (!simulate && changed) writeItems(slot, entries);
        }
        return result;
    }

    private int fill(FluidStack requested, IFluidHandler.FluidAction action) {
        if (requested.isEmpty() || installedDrives() == 0) return 0;
        int remaining = requested.getAmount(), filled = 0;
        for (int slot = 0; slot < DRIVE_SLOTS && remaining > 0; slot++) {
            SolidStateDriveTier tier = tier(slot); if (tier == null) continue;
            List<FluidStack> entries = readFluids(slot); int used = entries.stream().mapToInt(FluidStack::getAmount).sum(); boolean matched = false;
            for (FluidStack entry : entries) if (entry.isFluidEqual(requested)) {
                matched = true; int accepted = Math.min(remaining, Math.max(0, tier.fluidCapacityMb() - used));
                if (accepted > 0) { if (action.execute()) entry.grow(accepted); remaining -= accepted; filled += accepted; }
                break;
            }
            if (!matched && remaining > 0 && entries.size() < Math.min(MAX_FLUID_ENTRIES, tier.fluidEntries())) {
                int accepted = Math.min(remaining, Math.max(0, tier.fluidCapacityMb() - used));
                if (accepted > 0) { FluidStack added = requested.copy(); added.setAmount(accepted); entries.add(added); remaining -= accepted; filled += accepted; }
            }
            if (action.execute() && filled > 0) writeFluids(slot, entries);
        }
        return filled;
    }

    private FluidStack drain(FluidStack requested, IFluidHandler.FluidAction action) {
        if (requested.isEmpty()) return FluidStack.EMPTY;
        int remaining = requested.getAmount(); FluidStack result = FluidStack.EMPTY;
        for (int slot = 0; slot < DRIVE_SLOTS && remaining > 0; slot++) {
            if (tier(slot) == null) continue; List<FluidStack> entries = readFluids(slot); boolean changed = false;
            for (int i = entries.size() - 1; i >= 0 && remaining > 0; i--) {
                FluidStack entry = entries.get(i); if (!entry.isFluidEqual(requested)) continue;
                int removed = Math.min(remaining, entry.getAmount()); if (result.isEmpty()) result = entry.copy(); result.setAmount(result.getAmount() + removed); remaining -= removed; changed = true;
                if (action.execute()) { entry.shrink(removed); if (entry.isEmpty()) entries.remove(i); }
            }
            if (action.execute() && changed) writeFluids(slot, entries);
        }
        return result;
    }

    private List<ItemStack> readItems(int slot) {
        ItemStack drive = drive(slot); if (tier(slot) == null || !drive.hasTag() || !drive.getTag().contains(SolidStateDriveItem.TAG_ITEMS, Tag.TAG_LIST)) return new ArrayList<>();
        ListTag saved = drive.getTag().getList(SolidStateDriveItem.TAG_ITEMS, Tag.TAG_COMPOUND); List<ItemStack> result = new ArrayList<>();
        for (int i = 0; i < Math.min(saved.size(), MAX_ITEM_ENTRIES); i++) { ItemStack stack = ItemStack.of(saved.getCompound(i)); if (!stack.isEmpty() && stack.getCount() > 0) result.add(stack); }
        return result;
    }
    private void writeItems(int slot, List<ItemStack> entries) {
        ItemStack drive = drive(slot).copy(); ListTag saved = new ListTag(); for (ItemStack stack : entries) if (!stack.isEmpty()) saved.add(stack.save(new CompoundTag()));
        CompoundTag tag = drive.getOrCreateTag(); if (saved.isEmpty()) tag.remove(SolidStateDriveItem.TAG_ITEMS); else tag.put(SolidStateDriveItem.TAG_ITEMS, saved); drives.setStackInSlot(slot, drive);
    }
    private List<FluidStack> readFluids(int slot) {
        ItemStack drive = drive(slot); if (tier(slot) == null || !drive.hasTag() || !drive.getTag().contains(SolidStateDriveItem.TAG_FLUIDS, Tag.TAG_LIST)) return new ArrayList<>();
        ListTag saved = drive.getTag().getList(SolidStateDriveItem.TAG_FLUIDS, Tag.TAG_COMPOUND); List<FluidStack> result = new ArrayList<>();
        for (int i = 0; i < Math.min(saved.size(), MAX_FLUID_ENTRIES); i++) { FluidStack stack = FluidStack.loadFluidStackFromNBT(saved.getCompound(i)); if (!stack.isEmpty() && stack.getAmount() > 0) result.add(stack); }
        return result;
    }
    private void writeFluids(int slot, List<FluidStack> entries) {
        ItemStack drive = drive(slot).copy(); ListTag saved = new ListTag(); for (FluidStack stack : entries) if (!stack.isEmpty()) saved.add(stack.writeToNBT(new CompoundTag()));
        CompoundTag tag = drive.getOrCreateTag(); if (saved.isEmpty()) tag.remove(SolidStateDriveItem.TAG_FLUIDS); else tag.put(SolidStateDriveItem.TAG_FLUIDS, saved); drives.setStackInSlot(slot, drive);
    }

    private final class StorageItems implements IItemHandler {
        @Override public int getSlots() { int n = 0; for (int i = 0; i < DRIVE_SLOTS; i++) if (tier(i) != null) n += tier(i).itemEntries(); return Math.min(GenericCapabilityDevice.MAX_INVENTORY_SLOTS, n); }
        @Override public ItemStack getStackInSlot(int slot) { List<ItemStack> entries = itemEntries(); return slot >= 0 && slot < entries.size() ? entries.get(slot).copy() : ItemStack.EMPTY; }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { int accepted = insert(stack, simulate); if (accepted == stack.getCount()) return ItemStack.EMPTY; ItemStack rest = stack.copy(); rest.setCount(stack.getCount() - accepted); return rest; }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) { ItemStack stack = getStackInSlot(slot); return stack.isEmpty() ? ItemStack.EMPTY : extract(stack, amount, simulate); }
        @Override public int getSlotLimit(int slot) { return 64; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return !stack.isEmpty(); }
    }
    private final class StorageFluids implements IFluidHandler {
        @Override public int getTanks() { int n = 0; for (int i = 0; i < DRIVE_SLOTS; i++) if (tier(i) != null) n += tier(i).fluidEntries(); return Math.min(GenericCapabilityDevice.MAX_FLUID_TANKS, n); }
        @Override public FluidStack getFluidInTank(int tank) { List<FluidStack> entries = fluidEntries(); return tank >= 0 && tank < entries.size() ? entries.get(tank).copy() : FluidStack.EMPTY; }
        @Override public int getTankCapacity(int tank) { return Math.max(0, fluidCapacity()); }
        @Override public boolean isFluidValid(int tank, FluidStack stack) { return !stack.isEmpty(); }
        @Override public int fill(FluidStack resource, FluidAction action) { return NasStorage.this.fill(resource, action); }
        @Override public FluidStack drain(FluidStack resource, FluidAction action) { return NasStorage.this.drain(resource, action); }
        @Override public FluidStack drain(int amount, FluidAction action) { List<FluidStack> entries = fluidEntries(); return entries.isEmpty() ? FluidStack.EMPTY : NasStorage.this.drain(new FluidStack(entries.get(0).getFluid(), amount), action); }
    }

    private static String itemId(ItemStack stack) { return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); }
    private static String fluidId(FluidStack stack) { ResourceLocation key = ForgeRegistries.FLUIDS.getKey(stack.getFluid()); return key == null ? "minecraft:empty" : key.toString(); }
    private static Set<String> itemTags(Item item) { java.util.TreeSet<String> tags = new java.util.TreeSet<>(); if (item != null) item.builtInRegistryHolder().tags().forEach(t -> tags.add(t.location().toString())); return Set.copyOf(tags); }
    private static Item item(String id) { ResourceLocation key = ResourceLocation.tryParse(id); return key == null || !BuiltInRegistries.ITEM.containsKey(key) ? null : BuiltInRegistries.ITEM.get(key); }
    private static net.minecraft.world.level.material.Fluid fluid(String id) { ResourceLocation key = ResourceLocation.tryParse(id); return key == null || !ForgeRegistries.FLUIDS.containsKey(key) ? null : ForgeRegistries.FLUIDS.getValue(key); }
}
