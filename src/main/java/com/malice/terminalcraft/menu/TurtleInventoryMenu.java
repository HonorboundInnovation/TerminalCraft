package com.malice.terminalcraft.menu;

import com.malice.terminalcraft.blockentity.TurtleBlockEntity;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.SlotItemHandler;

/** Vanilla slot transactions; a moved/removed turtle closes this inventory session. */
public final class TurtleInventoryMenu extends AbstractContainerMenu {
    private final TurtleBlockEntity turtle;
    private final BlockPos position;
    private final ContainerData data;

    public static TurtleInventoryMenu fromNetwork(int id, Inventory player, FriendlyByteBuf buffer) {
        return new TurtleInventoryMenu(id, player, null, buffer.readBlockPos(), new ItemStackHandler(16), new SimpleContainerData(4));
    }

    public TurtleInventoryMenu(int id, Inventory player, TurtleBlockEntity turtle) {
        this(id, player, turtle, turtle.getBlockPos(),
                turtle.getCapability(ForgeCapabilities.ITEM_HANDLER).orElseThrow(IllegalStateException::new), new ContainerData() {
                    public int get(int index) {
                        return switch (index) {
                            case 0 -> turtle.selectedInventorySlot();
                            case 1 -> turtle.fuelBalance() & 0xffff;
                            case 2 -> turtle.fuelBalance() >>> 16;
                            case 3 -> turtle.hasUnlimitedFuel() ? 1 : 0;
                            default -> 0;
                        };
                    }
                    public void set(int index, int value) { }
                    public int getCount() { return 4; }
                });
    }

    private TurtleInventoryMenu(int id, Inventory player, TurtleBlockEntity turtle, BlockPos position,
                                IItemHandler items, ContainerData data) {
        super(ModRegistries.TURTLE_INVENTORY_MENU.get(), id);
        this.turtle = turtle;
        this.position = position.immutable();
        this.data = data;
        for (int row = 0; row < 4; row++) for (int col = 0; col < 4; col++)
            addSlot(new SlotItemHandler(items, row * 4 + col, 70 + col * 18, 26 + row * 18));
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            addSlot(new Slot(player, col + row * 9 + 9, 26 + col * 18, 153 + row * 18));
        for (int col = 0; col < 9; col++) addSlot(new Slot(player, col, 26 + col * 18, 211));
        addDataSlots(data);
    }

    public int selectedSlot() { return data.get(0); }
    public int fuel() { return (data.get(1) & 0xffff) | ((data.get(2) & 0xffff) << 16); }
    public boolean unlimited() { return data.get(3) != 0; }

    @Override public boolean stillValid(Player player) {
        if (player.level().isClientSide) return true; // Server closes invalid sessions authoritatively.
        return turtle != null && !turtle.isRemoved() && turtle.getLevel() == player.level()
                && player.level().hasChunkAt(position) && player.level().getBlockEntity(position) == turtle
                && player.distanceToSqr(position.getX() + .5, position.getY() + .5, position.getZ() + .5) <= 64;
    }

    @Override public boolean clickMenuButton(Player player, int button) {
        if (!stillValid(player) || turtle == null) return false;
        boolean changed = switch (button) {
            case 0 -> turtle.turtleSelect((selectedSlot() + 15) % 16 + 1);
            case 1 -> turtle.turtleSelect((selectedSlot() + 1) % 16 + 1);
            case 2 -> turtle.turtleRefuel(1);
            default -> false;
        };
        if (changed) broadcastChanges();
        return changed;
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (!stillValid(player) || index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < 16 ? !moveItemStackTo(stack, 16, slots.size(), true) : !moveItemStackTo(stack, 0, 16, false))
            return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged();
        slot.onTake(player, stack);
        if (turtle != null) turtle.setChanged();
        return original;
    }
}
