package com.malice.terminalcraft.menu;

import com.malice.terminalcraft.device.DeviceCallContext;

import com.malice.terminalcraft.blockentity.TerminalBlockEntity;
import com.malice.terminalcraft.blockentity.ServerRackBlockEntity;
import com.malice.terminalcraft.blockentity.TurtleBlockEntity;
import com.malice.terminalcraft.registry.ModRegistries;
import com.malice.terminalcraft.shell.BashShell;
import com.malice.terminalcraft.shell.PocketShellComputer;
import com.malice.terminalcraft.shell.ShellComputer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Server/client menu bridge for terminal, turtle, and pocket GUIs.
 * No item slots - pure interaction surface for the shell.
 */
public class TerminalMenu extends AbstractContainerMenu {
    public static final byte TYPE_BLOCK = 0;
    public static final byte TYPE_POCKET = 1;

    private final ShellComputer computer;
    private final ContainerLevelAccess access;
    private final Block validBlock;
    private final boolean pocket;
    private final InteractionHand pocketHand;

    public TerminalMenu(int containerId, Inventory playerInventory, TerminalBlockEntity terminal) {
        this(containerId, playerInventory, terminal, ModRegistries.TERMINAL_BLOCK.get(), false, InteractionHand.MAIN_HAND);
    }

    public TerminalMenu(int containerId, Inventory playerInventory, TurtleBlockEntity turtle) {
        this(containerId, playerInventory, turtle, ModRegistries.TURTLE_BLOCK.get(), false, InteractionHand.MAIN_HAND);
    }

    public TerminalMenu(int containerId, Inventory playerInventory, ServerRackBlockEntity rack) {
        this(containerId, playerInventory, rack, ModRegistries.SERVER_RACK_BLOCK.get(), false, InteractionHand.MAIN_HAND);
    }

    public TerminalMenu(int containerId, Inventory playerInventory, PocketShellComputer pocketComputer) {
        this(containerId, playerInventory, pocketComputer, Blocks.AIR, true, pocketComputer.getHand());
    }

    private TerminalMenu(int containerId, Inventory playerInventory, ShellComputer computer,
                         Block validBlock, boolean pocket, InteractionHand pocketHand) {
        super(ModRegistries.TERMINAL_MENU.get(), containerId);
        this.computer = computer;
        this.validBlock = validBlock;
        this.pocket = pocket;
        this.pocketHand = pocketHand;
        if (pocket) {
            this.access = ContainerLevelAccess.NULL;
        } else {
            this.access = ContainerLevelAccess.create(computer.getLevel(), computer.getBlockPos());
        }
    }

    public static TerminalMenu fromNetwork(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
        byte type = buf.readByte();
        if (type == TYPE_POCKET) {
            InteractionHand hand = buf.readEnum(InteractionHand.class);
            PocketShellComputer pocket = new PocketShellComputer(playerInventory.player, hand);
            return new TerminalMenu(containerId, playerInventory, pocket);
        }

        BlockPos pos = buf.readBlockPos();
        BlockEntity be = playerInventory.player.level().getBlockEntity(pos);
        if (be instanceof TurtleBlockEntity turtle) {
            return new TerminalMenu(containerId, playerInventory, turtle);
        }
        if (be instanceof TerminalBlockEntity terminal) {
            return new TerminalMenu(containerId, playerInventory, terminal);
        }
        if (be instanceof ServerRackBlockEntity rack) {
            return new TerminalMenu(containerId, playerInventory, rack);
        }
        // Fallback so a desync never hard-crashes the client
        TerminalBlockEntity fallback = new TerminalBlockEntity(pos,
                ModRegistries.TERMINAL_BLOCK.get().defaultBlockState());
        return new TerminalMenu(containerId, playerInventory, fallback);
    }

    public ShellComputer getComputer() {
        return computer;
    }

    public boolean isPocket() {
        return pocket;
    }

    /** @deprecated use {@link #getComputer()} */
    @Deprecated
    public TerminalBlockEntity getTerminal() {
        if (computer instanceof TerminalBlockEntity terminal) {
            return terminal;
        }
        return null;
    }

    public BashShell getShell() {
        return computer.getShell();
    }

    /** Execute a command line on the bound shell (server-side authority). */
    public void submitCommand(String line, DeviceCallContext context) {
        if (line == null) {
            return;
        }
        computer.getShell().execute(line, context);
        computer.markShellChanged();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        if (pocket) {
            if (!(computer instanceof PocketShellComputer pocketComputer)) {
                return false;
            }
            return pocketComputer.isStillHolding() && player == pocketComputer.getPlayer();
        }
        return stillValid(access, player, validBlock);
    }
}
