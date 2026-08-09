package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.device.DeviceIdentity;
import com.malice.terminalcraft.device.GenericCapabilityDevice;
import com.malice.terminalcraft.device.GenericCapabilityDeviceEndpoint;
import com.malice.terminalcraft.device.GenericItemStorage;
import com.malice.terminalcraft.device.ServerDeviceManager;
import com.malice.terminalcraft.item.SolidStateDriveItem;
import com.malice.terminalcraft.item.SolidStateDriveTier;
import com.malice.terminalcraft.persistence.PersistedDataVersions;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Containers;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Eight-slot NAS controller. Drive contents live in the inserted SSD ItemStacks, so removing a
 * drive is safe and portable while the NAS remains a bounded aggregate storage endpoint.
 */
public class NetworkAccessStorageBlockEntity extends BlockEntity implements GenericCapabilityDevice {
    public static final int DRIVE_SLOTS = 8;
    private static final int MAX_ENTRIES_PER_DRIVE = 128;
    private static final int MAX_FLUID_ENTRIES_PER_DRIVE = 16;
    private static final String DRIVE_INVENTORY = "Drives";

    private UUID deviceId = DeviceIdentity.create();
    private final ItemStackHandler drives = new ItemStackHandler(DRIVE_SLOTS) {
        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            return SolidStateDriveItem.tier(stack) != null;
        }

        @Override
        public int getSlotLimit(int slot) { return 1; }

        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
            sync();
        }
    };
    private LazyOptional<IItemHandler> itemOptional = LazyOptional.of(() -> new StorageItemHandler());
    private LazyOptional<IFluidHandler> fluidOptional = LazyOptional.of(() -> new StorageFluidHandler());

    public NetworkAccessStorageBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistries.NETWORK_ACCESS_STORAGE_BLOCK_ENTITY.get(), pos, state);
    }

    public UUID getDeviceId() { return deviceId; }

    public String getDeviceAddress() {
        String dimension = level == null ? "unbound" : level.dimension().location().toString();
        return dimension + ":" + worldPosition.getX() + "," + worldPosition.getY() + "," + worldPosition.getZ();
    }

    public int installedDriveCount() {
        int count = 0;
        for (int slot = 0; slot < DRIVE_SLOTS; slot++) if (SolidStateDriveItem.tier(drives.getStackInSlot(slot)) != null) count++;
        return count;
    }

    public int totalItemCapacity() {
        int total = 0;
        for (int slot = 0; slot < DRIVE_SLOTS; slot++) {
            SolidStateDriveTier tier = SolidStateDriveItem.tier(drives.getStackInSlot(slot));
            if (tier != null) total = Math.addExact(total, tier.itemCapacity());
        }
        return total;
    }

    public int totalFluidCapacityMb() {
        int total = 0;
        for (int slot = 0; slot < DRIVE_SLOTS; slot++) {
            SolidStateDriveTier tier = SolidStateDriveItem.tier(drives.getStackInSlot(slot));
            if (tier != null) total = Math.addExact(total, tier.fluidCapacityMb());
        }
        return total;
    }

    public ItemStack drive(int slot) {
        return slot < 0 || slot >= DRIVE_SLOTS ? ItemStack.EMPTY : drives.getStackInSlot(slot);
    }

    public boolean insertDrive(ItemStack stack) {
        if (SolidStateDriveItem.tier(stack) == null) return false;
        for (int slot = 0; slot < DRIVE_SLOTS; slot++) {
            if (!drives.getStackInSlot(slot).isEmpty()) continue;
            ItemStack copy = stack.copy();
            copy.setCount(1);
            drives.setStackInSlot(slot, copy);
            return true;
        }
        return false;
    }

    public ItemStack ejectLastDrive() {
        for (int slot = DRIVE_SLOTS - 1; slot >= 0; slot--) {
            ItemStack current = drives.getStackInSlot(slot);
            if (current.isEmpty()) continue;
            ItemStack result = current.copy();
            drives.setStackInSlot(slot, ItemStack.EMPTY);
            return result;
        }
        return ItemStack.EMPTY;
    }

    public List<String> summary() {
        List<String> lines = new ArrayList<>();
        lines.add("nas drives=" + installedDriveCount() + "/" + DRIVE_SLOTS
                + " item_capacity=" + totalItemCapacity() + " fluid_capacity=" + totalFluidCapacityMb() + "mB");
        for (int slot = 0; slot < DRIVE_SLOTS; slot++) {
            ItemStack drive = drives.getStackInSlot(slot);
            SolidStateDriveTier tier = SolidStateDriveItem.tier(drive);
            if (tier == null) lines.add("slot" + (slot + 1) + " empty");
            else lines.add("slot" + (slot + 1) + " " + tier.id() + " " + SolidStateDriveItem.label(drive)
                    + " items=" + driveItemCount(slot) + "/" + tier.itemCapacity()
                    + " fluid=" + driveFluidAmount(slot) + "/" + tier.fluidCapacityMb() + "mB");
        }
        return List.copyOf(lines);
    }

    @Override public boolean hasInventory() { return installedDriveCount() > 0; }
    @Override public boolean hasFluidStorage() { return installedDriveCount() > 0; }

    @Override
    public List<ItemSlot> itemSlots(int limit) {
        List<ItemStack> entries = itemEntries();
        List<ItemSlot> result = new ArrayList<>();
        for (int slot = 0; slot < entries.size() && result.size() < limit; slot++) {
            ItemStack stack = entries.get(slot);
            result.add(new ItemSlot(slot, itemId(stack), stack.getCount(), Math.max(1, stack.getMaxStackSize())));
        }
        return List.copyOf(result);
    }

    @Override
    public ItemPage queryItems(ItemQuery query) {
        Map<String, Long> counts = new TreeMap<>();
        Map<String, Set<String>> tags = new LinkedHashMap<>();
        for (ItemStack stack : itemEntries()) {
            String id = itemId(stack);
            if (!query.matches(id, itemTags(stack.getItem()))) continue;
            counts.merge(id, (long) stack.getCount(), Math::addExact);
            tags.putIfAbsent(id, itemTags(stack.getItem()));
        }
        List<ItemResource> resources = counts.entrySet().stream()
                .map(entry -> new ItemResource(entry.getKey(), entry.getValue(), tags.getOrDefault(entry.getKey(), Set.of())))
                .toList();
        int offset = Math.min(query.offset(), resources.size());
        int end = Math.min(offset + query.limit(), resources.size());
        return new ItemPage(resources.subList(offset, end), end < resources.size() ? Integer.toString(end) : "");
    }

    @Override
    public long itemCount(String resourceId) {
        Item item = item(resourceId);
        if (item == null) return 0;
        long total = 0;
        for (ItemStack stack : itemEntries()) if (stack.is(item)) total = Math.addExact(total, stack.getCount());
        return total;
    }

    @Override public long simulateItemInsert(String resourceId, int count) {
        Item item = item(resourceId);
        return item == null ? 0 : insertStored(new ItemStack(item, count), true);
    }

    @Override public long simulateItemExtract(String resourceId, int count) {
        Item item = item(resourceId);
        return item == null ? 0 : extractStored(new ItemStack(item, 1), count, true).getCount();
    }

    @Override
    public TransferOutcome insertItems(String resourceId, int count) {
        Item item = item(resourceId);
        if (item == null) return TransferOutcome.none(count);
        int simulated = (int) insertStored(new ItemStack(item, count), true);
        int executed = (int) insertStored(new ItemStack(item, simulated), false);
        return new TransferOutcome(count, simulated, executed);
    }

    @Override
    public TransferOutcome extractItems(String resourceId, int count) {
        Item item = item(resourceId);
        if (item == null) return TransferOutcome.none(count);
        int simulated = extractStored(new ItemStack(item, 1), count, true).getCount();
        int executed = extractStored(new ItemStack(item, 1), simulated, false).getCount();
        return new TransferOutcome(count, simulated, executed);
    }

    @Override
    public List<FluidTank> fluidTanks(int limit) {
        List<FluidStack> entries = fluidEntries();
        List<FluidTank> result = new ArrayList<>();
        int capacity = Math.max(1, totalFluidCapacityMb());
        for (int tank = 0; tank < entries.size() && result.size() < limit; tank++) {
            FluidStack stack = entries.get(tank);
            result.add(new FluidTank(tank, fluidId(stack), stack.getAmount(), capacity));
        }
        return List.copyOf(result);
    }

    @Override public long simulateFluidFill(String resourceId, int amountMb) {
        net.minecraft.world.level.material.Fluid fluid = fluid(resourceId);
        return fluid == null ? 0 : fillStored(new FluidStack(fluid, amountMb), IFluidHandler.FluidAction.SIMULATE);
    }

    @Override public long simulateFluidDrain(String resourceId, int amountMb) {
        net.minecraft.world.level.material.Fluid fluid = fluid(resourceId);
        return fluid == null ? 0 : drainStored(new FluidStack(fluid, amountMb), IFluidHandler.FluidAction.SIMULATE).getAmount();
    }

    @Override
    public TransferOutcome fillFluid(String resourceId, int amountMb) {
        net.minecraft.world.level.material.Fluid fluid = fluid(resourceId);
        if (fluid == null) return TransferOutcome.none(amountMb);
        int simulated = fillStored(new FluidStack(fluid, amountMb), IFluidHandler.FluidAction.SIMULATE);
        int executed = fillStored(new FluidStack(fluid, simulated), IFluidHandler.FluidAction.EXECUTE);
        return new TransferOutcome(amountMb, simulated, executed);
    }

    @Override
    public TransferOutcome drainFluid(String resourceId, int amountMb) {
        net.minecraft.world.level.material.Fluid fluid = fluid(resourceId);
        if (fluid == null) return TransferOutcome.none(amountMb);
        int simulated = drainStored(new FluidStack(fluid, amountMb), IFluidHandler.FluidAction.SIMULATE).getAmount();
        int executed = drainStored(new FluidStack(fluid, simulated), IFluidHandler.FluidAction.EXECUTE).getAmount();
        return new TransferOutcome(amountMb, simulated, executed);
    }

    public ItemStack extractFirstItem(int amount) {
        List<ItemStack> entries = itemEntries();
        return entries.isEmpty() ? ItemStack.EMPTY : extractStored(entries.get(0), Math.max(1, amount), false);
    }

    public ItemStack extractMatchingItem(ItemStack template, int amount) {
        return template == null || template.isEmpty() ? ItemStack.EMPTY
                : extractStored(template, Math.max(1, amount), false);
    }

    public FluidStack drainFirstFluid(int amount) {
        List<FluidStack> entries = fluidEntries();
        return entries.isEmpty() ? FluidStack.EMPTY
                : drainStored(new FluidStack(entries.get(0).getFluid(), Math.max(1, amount)), IFluidHandler.FluidAction.EXECUTE);
    }

    public IItemHandler storageItemHandler() { return itemOptional.orElse(null); }
    public IFluidHandler storageFluidHandler() { return fluidOptional.orElse(null); }

    @Override
    public <T> LazyOptional<T> getCapability(@NotNull Capability<T> capability, @Nullable net.minecraft.core.Direction side) {
        if (capability == ForgeCapabilities.ITEM_HANDLER) return itemOptional.cast();
        if (capability == ForgeCapabilities.FLUID_HANDLER) return fluidOptional.cast();
        return super.getCapability(capability, side);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, NetworkAccessStorageBlockEntity nas) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel)) return;
        ServerDeviceManager.ensureRegistered(nas, nas.deviceId, nas.getDeviceAddress(),
                () -> new GenericCapabilityDeviceEndpoint(nas.deviceId, "terminalcraft:nas", "nas",
                        "Network Access Storage", "terminalcraft", nas.getDeviceAddress(), nas,
                        () -> !nas.isRemoved(), () -> !nas.isRemoved(), Set.of("nas"),
                        Map.of("drive_slots", com.malice.terminalcraft.device.DeviceValue.of(DRIVE_SLOTS),
                                "installed_drives", com.malice.terminalcraft.device.DeviceValue.of(nas.installedDriveCount()),
                                "item_capacity", com.malice.terminalcraft.device.DeviceValue.of(nas.totalItemCapacity()),
                                "fluid_capacity_mb", com.malice.terminalcraft.device.DeviceValue.of(nas.totalFluidCapacityMb()))));
    }

    private int driveItemCount(int driveSlot) {
        int total = 0;
        for (ItemStack stack : readItems(driveSlot)) total = Math.addExact(total, stack.getCount());
        return total;
    }

    private int driveFluidAmount(int driveSlot) {
        int total = 0;
        for (FluidStack stack : readFluids(driveSlot)) total = Math.addExact(total, stack.getAmount());
        return total;
    }

    private List<ItemStack> itemEntries() {
        List<ItemStack> result = new ArrayList<>();
        for (int drive = 0; drive < DRIVE_SLOTS; drive++) result.addAll(readItems(drive));
        result.sort(Comparator.comparing(NetworkAccessStorageBlockEntity::itemId)
                .thenComparing(stack -> stack.getTag() == null ? "" : stack.getTag().toString()));
        return result;
    }

    private List<FluidStack> fluidEntries() {
        List<FluidStack> result = new ArrayList<>();
        for (int drive = 0; drive < DRIVE_SLOTS; drive++) result.addAll(readFluids(drive));
        result.sort(Comparator.comparing(NetworkAccessStorageBlockEntity::fluidId));
        return result;
    }

    private int insertStored(ItemStack requested, boolean simulate) {
        if (requested.isEmpty() || installedDriveCount() == 0) return 0;
        int remaining = requested.getCount();
        int inserted = 0;
        for (int drive = 0; drive < DRIVE_SLOTS && remaining > 0; drive++) {
            SolidStateDriveTier tier = SolidStateDriveItem.tier(drives.getStackInSlot(drive));
            if (tier == null) continue;
            List<ItemStack> entries = readItems(drive);
            int used = entries.stream().mapToInt(ItemStack::getCount).sum();
            for (ItemStack entry : entries) {
                if (!ItemStack.isSameItemSameTags(entry, requested)) continue;
                int accepted = Math.min(remaining, Math.max(0, tier.itemCapacity() - used));
                if (accepted <= 0) break;
                if (!simulate) entry.grow(accepted);
                remaining -= accepted;
                inserted += accepted;
                used += accepted;
                break;
            }
            if (remaining > 0 && entries.stream().noneMatch(entry -> ItemStack.isSameItemSameTags(entry, requested))
                    && entries.size() < Math.min(tier.itemEntries(), MAX_ENTRIES_PER_DRIVE)) {
                int accepted = Math.min(remaining, Math.max(0, tier.itemCapacity() - used));
                if (accepted > 0) {
                    ItemStack added = requested.copy();
                    added.setCount(accepted);
                    entries.add(added);
                    remaining -= accepted;
                    inserted += accepted;
                }
            }
            if (!simulate && inserted > 0) writeItems(drive, entries);
        }
        return inserted;
    }

    private ItemStack extractStored(ItemStack requested, int amount, boolean simulate) {
        if (requested.isEmpty() || amount < 1) return ItemStack.EMPTY;
        int remaining = amount;
        ItemStack result = ItemStack.EMPTY;
        for (int drive = 0; drive < DRIVE_SLOTS && remaining > 0; drive++) {
            if (SolidStateDriveItem.tier(drives.getStackInSlot(drive)) == null) continue;
            List<ItemStack> entries = readItems(drive);
            boolean changed = false;
            for (int index = entries.size() - 1; index >= 0 && remaining > 0; index--) {
                ItemStack entry = entries.get(index);
                if (!ItemStack.isSameItemSameTags(entry, requested)) continue;
                int removed = Math.min(remaining, entry.getCount());
                if (result.isEmpty()) result = entry.copy();
                result.setCount(result.getCount() + removed);
                remaining -= removed;
                changed = true;
                if (!simulate) {
                    entry.shrink(removed);
                    if (entry.isEmpty()) entries.remove(index);
                }
            }
            if (!simulate && changed) writeItems(drive, entries);
        }
        return result;
    }

    private int fillStored(FluidStack requested, IFluidHandler.FluidAction action) {
        if (requested.isEmpty() || installedDriveCount() == 0) return 0;
        int remaining = requested.getAmount();
        int filled = 0;
        for (int drive = 0; drive < DRIVE_SLOTS && remaining > 0; drive++) {
            SolidStateDriveTier tier = SolidStateDriveItem.tier(drives.getStackInSlot(drive));
            if (tier == null) continue;
            List<FluidStack> entries = readFluids(drive);
            int used = entries.stream().mapToInt(FluidStack::getAmount).sum();
            boolean matched = false;
            for (FluidStack entry : entries) {
                if (!entry.isFluidEqual(requested)) continue;
                matched = true;
                int accepted = Math.min(remaining, Math.max(0, tier.fluidCapacityMb() - used));
                if (accepted <= 0) break;
                if (action.execute()) entry.grow(accepted);
                remaining -= accepted;
                filled += accepted;
                used += accepted;
                break;
            }
            if (remaining > 0 && !matched && entries.size() < Math.min(tier.fluidEntries(), MAX_FLUID_ENTRIES_PER_DRIVE)) {
                int accepted = Math.min(remaining, Math.max(0, tier.fluidCapacityMb() - used));
                if (accepted > 0) {
                    FluidStack added = requested.copy();
                    added.setAmount(accepted);
                    entries.add(added);
                    remaining -= accepted;
                    filled += accepted;
                }
            }
            if (action.execute() && filled > 0) writeFluids(drive, entries);
        }
        return filled;
    }

    private FluidStack drainStored(FluidStack requested, IFluidHandler.FluidAction action) {
        if (requested.isEmpty()) return FluidStack.EMPTY;
        int remaining = requested.getAmount();
        FluidStack result = FluidStack.EMPTY;
        for (int drive = 0; drive < DRIVE_SLOTS && remaining > 0; drive++) {
            if (SolidStateDriveItem.tier(drives.getStackInSlot(drive)) == null) continue;
            List<FluidStack> entries = readFluids(drive);
            boolean changed = false;
            for (int index = entries.size() - 1; index >= 0 && remaining > 0; index--) {
                FluidStack entry = entries.get(index);
                if (!entry.isFluidEqual(requested)) continue;
                int removed = Math.min(remaining, entry.getAmount());
                if (result.isEmpty()) result = entry.copy();
                result.setAmount(result.getAmount() + removed);
                remaining -= removed;
                changed = true;
                if (action.execute()) {
                    entry.shrink(removed);
                    if (entry.isEmpty()) entries.remove(index);
                }
            }
            if (action.execute() && changed) writeFluids(drive, entries);
        }
        return result;
    }

    private List<ItemStack> readItems(int driveSlot) {
        ItemStack drive = drives.getStackInSlot(driveSlot);
        if (SolidStateDriveItem.tier(drive) == null || !drive.hasTag()
                || !drive.getTag().contains(SolidStateDriveItem.TAG_ITEMS, Tag.TAG_LIST)) return new ArrayList<>();
        ListTag saved = drive.getTag().getList(SolidStateDriveItem.TAG_ITEMS, Tag.TAG_COMPOUND);
        List<ItemStack> result = new ArrayList<>();
        for (int index = 0; index < Math.min(saved.size(), MAX_ENTRIES_PER_DRIVE); index++) {
            ItemStack stack = ItemStack.of(saved.getCompound(index));
            if (!stack.isEmpty() && stack.getCount() > 0) result.add(stack);
        }
        return result;
    }

    private void writeItems(int driveSlot, List<ItemStack> entries) {
        ItemStack drive = drives.getStackInSlot(driveSlot).copy();
        ListTag saved = new ListTag();
        for (ItemStack stack : entries) if (!stack.isEmpty()) saved.add(stack.save(new CompoundTag()));
        CompoundTag tag = drive.getOrCreateTag();
        if (saved.isEmpty()) tag.remove(SolidStateDriveItem.TAG_ITEMS); else tag.put(SolidStateDriveItem.TAG_ITEMS, saved);
        drives.setStackInSlot(driveSlot, drive);
    }

    private List<FluidStack> readFluids(int driveSlot) {
        ItemStack drive = drives.getStackInSlot(driveSlot);
        if (SolidStateDriveItem.tier(drive) == null || !drive.hasTag()
                || !drive.getTag().contains(SolidStateDriveItem.TAG_FLUIDS, Tag.TAG_LIST)) return new ArrayList<>();
        ListTag saved = drive.getTag().getList(SolidStateDriveItem.TAG_FLUIDS, Tag.TAG_COMPOUND);
        List<FluidStack> result = new ArrayList<>();
        for (int index = 0; index < Math.min(saved.size(), MAX_FLUID_ENTRIES_PER_DRIVE); index++) {
            FluidStack stack = FluidStack.loadFluidStackFromNBT(saved.getCompound(index));
            if (!stack.isEmpty() && stack.getAmount() > 0) result.add(stack);
        }
        return result;
    }

    private void writeFluids(int driveSlot, List<FluidStack> entries) {
        ItemStack drive = drives.getStackInSlot(driveSlot).copy();
        ListTag saved = new ListTag();
        for (FluidStack stack : entries) if (!stack.isEmpty()) saved.add(stack.writeToNBT(new CompoundTag()));
        CompoundTag tag = drive.getOrCreateTag();
        if (saved.isEmpty()) tag.remove(SolidStateDriveItem.TAG_FLUIDS); else tag.put(SolidStateDriveItem.TAG_FLUIDS, saved);
        drives.setStackInSlot(driveSlot, drive);
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static String fluidId(FluidStack stack) {
        ResourceLocation key = ForgeRegistries.FLUIDS.getKey(stack.getFluid());
        return key == null ? "minecraft:empty" : key.toString();
    }

    private static Set<String> itemTags(Item item) {
        java.util.TreeSet<String> tags = new java.util.TreeSet<>();
        if (item != null) item.builtInRegistryHolder().tags().forEach(tag -> tags.add(tag.location().toString()));
        return Set.copyOf(tags);
    }

    private static Item item(String resourceId) {
        ResourceLocation key = ResourceLocation.tryParse(resourceId);
        return key == null || !BuiltInRegistries.ITEM.containsKey(key) ? null : BuiltInRegistries.ITEM.get(key);
    }

    private static net.minecraft.world.level.material.Fluid fluid(String resourceId) {
        ResourceLocation key = ResourceLocation.tryParse(resourceId);
        return key == null || !ForgeRegistries.FLUIDS.containsKey(key) ? null : ForgeRegistries.FLUIDS.getValue(key);
    }

    private void sync() {
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        PersistedDataVersions.stampCurrent(tag);
        DeviceIdentity.save(tag, deviceId);
        tag.put(DRIVE_INVENTORY, drives.serializeNBT());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        deviceId = DeviceIdentity.loadOrRetain(tag, deviceId);
        if (tag.contains(DRIVE_INVENTORY, Tag.TAG_COMPOUND)) drives.deserializeNBT(tag.getCompound(DRIVE_INVENTORY));
    }

    @Override
    public void setRemoved() {
        ServerDeviceManager.invalidate(this);
        super.setRemoved();
    }

    public void dropContents() {
        if (level == null) return;
        for (int slot = 0; slot < DRIVE_SLOTS; slot++) {
            ItemStack drive = drives.getStackInSlot(slot);
            if (!drive.isEmpty()) Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), drive);
        }
        drives.setSize(DRIVE_SLOTS);
        for (int slot = 0; slot < DRIVE_SLOTS; slot++) drives.setStackInSlot(slot, ItemStack.EMPTY);
    }

    @Override public void invalidateCaps() { super.invalidateCaps(); itemOptional.invalidate(); fluidOptional.invalidate(); }
    @Override public void reviveCaps() {
        super.reviveCaps();
        itemOptional = LazyOptional.of(() -> new StorageItemHandler());
        fluidOptional = LazyOptional.of(() -> new StorageFluidHandler());
    }

    @Override
    public CompoundTag getUpdateTag() { CompoundTag tag = super.getUpdateTag(); saveAdditional(tag); return tag; }

    @Nullable @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) { if (packet.getTag() != null) load(packet.getTag()); }

    private final class StorageItemHandler implements IItemHandler {
        @Override public int getSlots() { return Math.min(GenericCapabilityDevice.MAX_INVENTORY_SLOTS, totalItemEntriesCapacity()); }
        @Override public ItemStack getStackInSlot(int slot) {
            List<ItemStack> entries = itemEntries();
            return slot >= 0 && slot < entries.size() ? entries.get(slot).copy() : ItemStack.EMPTY;
        }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) return ItemStack.EMPTY;
            int accepted = (int) insertStored(stack, simulate);
            if (accepted >= stack.getCount()) return ItemStack.EMPTY;
            ItemStack remainder = stack.copy();
            remainder.setCount(stack.getCount() - accepted);
            return remainder;
        }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            ItemStack visible = getStackInSlot(slot);
            return visible.isEmpty() ? ItemStack.EMPTY : extractStored(visible, amount, simulate);
        }
        @Override public int getSlotLimit(int slot) { return 64; }
        @Override public boolean isItemValid(int slot, ItemStack stack) { return !stack.isEmpty(); }
    }

    private final class StorageFluidHandler implements IFluidHandler {
        @Override public int getTanks() { return Math.min(GenericCapabilityDevice.MAX_FLUID_TANKS, totalFluidEntriesCapacity()); }
        @Override public FluidStack getFluidInTank(int tank) {
            List<FluidStack> entries = fluidEntries();
            return tank >= 0 && tank < entries.size() ? entries.get(tank).copy() : FluidStack.EMPTY;
        }
        @Override public int getTankCapacity(int tank) { return Math.max(0, totalFluidCapacityMb()); }
        @Override public boolean isFluidValid(int tank, FluidStack stack) { return !stack.isEmpty(); }
        @Override public int fill(FluidStack resource, FluidAction action) { return fillStored(resource, action); }
        @Override public FluidStack drain(FluidStack resource, FluidAction action) { return drainStored(resource, action); }
        @Override public FluidStack drain(int maxDrain, FluidAction action) {
            List<FluidStack> entries = fluidEntries();
            return entries.isEmpty() ? FluidStack.EMPTY
                    : drainStored(new FluidStack(entries.get(0).getFluid(), maxDrain), action);
        }
    }

    private int totalItemEntriesCapacity() {
        int total = 0;
        for (int slot = 0; slot < DRIVE_SLOTS; slot++) {
            SolidStateDriveTier tier = SolidStateDriveItem.tier(drives.getStackInSlot(slot));
            if (tier != null) total += tier.itemEntries();
        }
        return total;
    }

    private int totalFluidEntriesCapacity() {
        int total = 0;
        for (int slot = 0; slot < DRIVE_SLOTS; slot++) {
            SolidStateDriveTier tier = SolidStateDriveItem.tier(drives.getStackInSlot(slot));
            if (tier != null) total += tier.fluidEntries();
        }
        return total;
    }
}
