package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.persistence.PersistedDataVersions;
import com.malice.terminalcraft.item.FloppyDiskItem;
import com.malice.terminalcraft.registry.ModRegistries;
import com.malice.terminalcraft.device.DeviceIdentity;
import com.malice.terminalcraft.device.DiskDriveDevice;
import com.malice.terminalcraft.device.ServerDeviceManager;
import com.malice.terminalcraft.device.DeviceValue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Holds a single floppy disk for mounting by adjacent shell computers.
 */
public class DiskDriveBlockEntity extends BlockEntity implements DiskDriveDevice {
    private UUID deviceId = DeviceIdentity.create();
    private boolean lastMediaPresent;
    private String lastMediaLabel = "";

    private final ItemStackHandler inventory = new ItemStackHandler(1) {
        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            return stack.is(ModRegistries.FLOPPY_DISK.get());
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }

        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
            if (level != null && !level.isClientSide) {
                level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
            }
        }
    };

    private LazyOptional<IItemHandler> invOptional = LazyOptional.of(() -> inventory);

    public DiskDriveBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistries.DISK_DRIVE_BLOCK_ENTITY.get(), pos, state);
    }

    public UUID getDeviceId() { return deviceId; }
    public String getDeviceAddress() {
        String dimension = level == null ? "unbound" : level.dimension().location().toString();
        return dimension + ":" + worldPosition.getX() + "," + worldPosition.getY() + "," + worldPosition.getZ();
    }
    @Override public boolean mediaPresent() { return hasDisk(); }
    @Override public String mediaLabel() { return getDiskLabel(); }
    @Override public void setMediaLabel(String label) { setDiskLabel(label); }

    public boolean hasDisk() {
        return !inventory.getStackInSlot(0).isEmpty();
    }

    public ItemStack getDisk() {
        return inventory.getStackInSlot(0);
    }

    public void setDisk(ItemStack stack) {
        if (stack.isEmpty()) {
            inventory.setStackInSlot(0, ItemStack.EMPTY);
            return;
        }
        ItemStack copy = stack.copy();
        copy.setCount(1);
        FloppyDiskItem.ensureInitialized(copy);
        inventory.setStackInSlot(0, copy);
    }

    public ItemStack ejectDisk() {
        ItemStack stack = inventory.getStackInSlot(0).copy();
        inventory.setStackInSlot(0, ItemStack.EMPTY);
        return stack;
    }

    public String getDiskLabel() {
        if (!hasDisk()) {
            return "";
        }
        return FloppyDiskItem.getDiskLabel(getDisk());
    }

    @Nullable
    public CompoundTag readMedia() {
        if (!hasDisk()) {
            return null;
        }
        return FloppyDiskItem.getVfsTag(getDisk());
    }

    public boolean writeMedia(CompoundTag vfsTag) {
        if (!hasDisk()) {
            return false;
        }
        ItemStack disk = getDisk().copy();
        FloppyDiskItem.setVfsTag(disk, vfsTag);
        inventory.setStackInSlot(0, disk);
        return true;
    }

    public void setDiskLabel(String label) {
        if (!hasDisk()) {
            return;
        }
        ItemStack disk = getDisk().copy();
        FloppyDiskItem.setDiskLabel(disk, label);
        inventory.setStackInSlot(0, disk);
    }

    public void dropContents() {
        if (level == null) {
            return;
        }
        Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(),
                inventory.getStackInSlot(0));
        inventory.setStackInSlot(0, ItemStack.EMPTY);
    }

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            return invOptional.cast();
        }
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        invOptional.invalidate();
    }

    @Override
    public void reviveCaps() {
        super.reviveCaps();
        invOptional = LazyOptional.of(() -> inventory);
    }

    public static void serverTick(net.minecraft.world.level.Level level, BlockPos pos, BlockState state, DiskDriveBlockEntity drive) {
        ServerDeviceManager.ensureDiskDriveRegistered(drive, drive.deviceId, drive.getDeviceAddress(), drive);
        boolean present = drive.hasDisk();
        String label = present ? drive.getDiskLabel() : "";
        if (present != drive.lastMediaPresent || !label.equals(drive.lastMediaLabel)) {
            ServerDeviceManager.publishEvent(drive, "media_changed", level.getGameTime(),
                    (DeviceValue.MapValue) DeviceValue.map(java.util.Map.of(
                            "present", DeviceValue.of(present), "label", DeviceValue.of(label))));
            drive.lastMediaPresent = present;
            drive.lastMediaLabel = label;
        }
    }

    @Override
    public void setRemoved() {
        ServerDeviceManager.invalidate(this);
        super.setRemoved();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        PersistedDataVersions.stampCurrent(tag);
        DeviceIdentity.save(tag, deviceId);
        tag.put("Inventory", inventory.serializeNBT());
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        deviceId = DeviceIdentity.loadOrRetain(tag, deviceId);
        if (tag.contains("Inventory")) {
            inventory.deserializeNBT(tag.getCompound("Inventory"));
        }
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        saveAdditional(tag);
        return tag;
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt) {
        CompoundTag tag = pkt.getTag();
        if (tag != null) {
            load(tag);
        }
    }
}
