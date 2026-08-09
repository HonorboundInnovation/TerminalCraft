package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.device.DeviceIdentity;
import com.malice.terminalcraft.device.DeviceValue;
import com.malice.terminalcraft.device.GenericCapabilityDevice;
import com.malice.terminalcraft.device.GenericCapabilityDeviceEndpoint;
import com.malice.terminalcraft.device.ServerDeviceManager;
import com.malice.terminalcraft.item.SolidStateDriveItem;
import com.malice.terminalcraft.persistence.PersistedDataVersions;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Eight-drive, server-authoritative TerminalCraft NAS controller. */
public class NetworkAccessStorageBlockEntity extends BlockEntity implements GenericCapabilityDevice {
    public static final int DRIVE_SLOTS = NasStorage.DRIVE_SLOTS;
    private UUID deviceId = DeviceIdentity.create();
    private final NasStorage storage = new NasStorage(this::storageChanged);
    private LazyOptional<IItemHandler> itemOptional = LazyOptional.of(storage::itemHandler);
    private LazyOptional<IFluidHandler> fluidOptional = LazyOptional.of(storage::fluidHandler);

    public NetworkAccessStorageBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistries.NETWORK_ACCESS_STORAGE_BLOCK_ENTITY.get(), pos, state);
    }

    public UUID getDeviceId() { return deviceId; }
    public String getDeviceAddress() {
        String dimension = level == null ? "unbound" : level.dimension().location().toString();
        return dimension + ":" + worldPosition.getX() + "," + worldPosition.getY() + "," + worldPosition.getZ();
    }
    public int installedDriveCount() { return storage.installedDrives(); }
    public int totalItemCapacity() { return storage.itemCapacity(); }
    public int totalFluidCapacityMb() { return storage.fluidCapacity(); }
    public ItemStack drive(int slot) { return storage.drive(slot); }
    public boolean insertDrive(ItemStack stack) { return storage.insertDrive(stack); }
    public ItemStack ejectLastDrive() { return storage.ejectLastDrive(); }
    public ItemStack extractFirstItem(int amount) { return storage.extractFirst(amount); }
    public ItemStack extractMatchingItem(ItemStack stack, int amount) { return storage.extractMatching(stack, amount); }
    public int insertItemStack(ItemStack stack) { return storage.insertStack(stack); }
    public IItemHandler storageItemHandler() { return itemOptional.orElse(null); }
    public IFluidHandler storageFluidHandler() { return fluidOptional.orElse(null); }

    public List<String> summary() {
        List<String> lines = new ArrayList<>();
        lines.add("nas drives=" + installedDriveCount() + "/" + DRIVE_SLOTS
                + " item_capacity=" + totalItemCapacity() + " fluid_capacity=" + totalFluidCapacityMb() + "mB");
        for (int slot = 0; slot < DRIVE_SLOTS; slot++) {
            ItemStack drive = storage.drive(slot);
            if (drive.isEmpty()) lines.add("slot" + (slot + 1) + " empty");
            else lines.add("slot" + (slot + 1) + " " + SolidStateDriveItem.label(drive));
        }
        return List.copyOf(lines);
    }

    @Override public boolean hasInventory() { return storage.hasInventory(); }
    @Override public boolean hasFluidStorage() { return storage.hasFluidStorage(); }
    @Override public List<ItemSlot> itemSlots(int limit) { return storage.itemSlots(limit); }
    @Override public ItemPage queryItems(ItemQuery query) { return storage.queryItems(query); }
    @Override public long itemCount(String resourceId) { return storage.itemCount(resourceId); }
    @Override public long simulateItemInsert(String resourceId, int count) { return storage.simulateItemInsert(resourceId, count); }
    @Override public long simulateItemExtract(String resourceId, int count) { return storage.simulateItemExtract(resourceId, count); }
    @Override public TransferOutcome insertItems(String resourceId, int count) { return storage.insertItems(resourceId, count); }
    @Override public TransferOutcome extractItems(String resourceId, int count) { return storage.extractItems(resourceId, count); }
    @Override public List<FluidTank> fluidTanks(int limit) { return storage.fluidTanks(limit); }
    @Override public long simulateFluidFill(String resourceId, int amount) { return storage.simulateFluidFill(resourceId, amount); }
    @Override public long simulateFluidDrain(String resourceId, int amount) { return storage.simulateFluidDrain(resourceId, amount); }
    @Override public TransferOutcome fillFluid(String resourceId, int amount) { return storage.fillFluid(resourceId, amount); }
    @Override public TransferOutcome drainFluid(String resourceId, int amount) { return storage.drainFluid(resourceId, amount); }

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
                        Map.of("drive_slots", DeviceValue.of(DRIVE_SLOTS),
                                "installed_drives", DeviceValue.of(nas.installedDriveCount()),
                                "item_capacity", DeviceValue.of(nas.totalItemCapacity()),
                                "fluid_capacity_mb", DeviceValue.of(nas.totalFluidCapacityMb()))));
    }

    private void storageChanged() {
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag); PersistedDataVersions.stampCurrent(tag); DeviceIdentity.save(tag, deviceId); storage.save(tag);
    }
    @Override public void load(CompoundTag tag) {
        super.load(tag); deviceId = DeviceIdentity.loadOrRetain(tag, deviceId); storage.load(tag);
    }
    @Override public void setRemoved() { ServerDeviceManager.invalidate(this); super.setRemoved(); }
    public void dropContents() { if (level != null) storage.drop(level, worldPosition); }
    @Override public void invalidateCaps() { super.invalidateCaps(); itemOptional.invalidate(); fluidOptional.invalidate(); }
    @Override public void reviveCaps() { super.reviveCaps(); itemOptional = LazyOptional.of(storage::itemHandler); fluidOptional = LazyOptional.of(storage::fluidHandler); }
    @Override public CompoundTag getUpdateTag() { CompoundTag tag = super.getUpdateTag(); saveAdditional(tag); return tag; }
    @Nullable @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) { if (packet.getTag() != null) load(packet.getTag()); }
}
