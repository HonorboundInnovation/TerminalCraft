package com.malice.terminalcraft.menu;

import com.malice.terminalcraft.blockentity.ProgrammableLogicControllerBlockEntity;
import com.malice.terminalcraft.plc.PlcProgram;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Dedicated server-authoritative PLC programming and commissioning menu. */
public final class PlcProgrammingMenu extends AbstractContainerMenu {
    private final BlockPos targetPosition;
    private final ContainerLevelAccess access;
    private final boolean remote;
    private final String initialSource;

    public PlcProgrammingMenu(int containerId, Inventory inventory,
                              ProgrammableLogicControllerBlockEntity plc) {
        this(containerId, inventory, plc, false);
    }

    /** Server-created remote session opened from an authenticated terminal/device caller. */
    public PlcProgrammingMenu(int containerId, Inventory inventory,
                              ProgrammableLogicControllerBlockEntity plc, boolean remote) {
        super(ModRegistries.PLC_PROGRAMMING_MENU.get(), containerId);
        targetPosition = plc.getBlockPos().immutable();
        access = ContainerLevelAccess.create(plc.getLevel(), targetPosition);
        this.remote = remote;
        initialSource = plc.programSource();
    }

    private PlcProgrammingMenu(int containerId, Inventory inventory, BlockPos position,
                               ProgrammableLogicControllerBlockEntity plc, String initialSource) {
        super(ModRegistries.PLC_PROGRAMMING_MENU.get(), containerId);
        targetPosition = position.immutable();
        access = ContainerLevelAccess.create(plc.getLevel(), targetPosition);
        remote = false;
        this.initialSource = initialSource == null ? "" : initialSource;
    }

    public static PlcProgrammingMenu fromNetwork(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        BlockPos position = buffer.readBlockPos();
        String initialSource = buffer.readUtf(PlcProgram.MAX_SOURCE_CHARS);
        BlockEntity entity = inventory.player.level().getBlockEntity(position);
        if (entity instanceof ProgrammableLogicControllerBlockEntity plc) {
            return new PlcProgrammingMenu(containerId, inventory, position, plc, initialSource);
        }
        ProgrammableLogicControllerBlockEntity fallback = new ProgrammableLogicControllerBlockEntity(
                position, ModRegistries.PROGRAMMABLE_LOGIC_CONTROLLER_BLOCK.get().defaultBlockState());
        return new PlcProgrammingMenu(containerId, inventory, position, fallback, initialSource);
    }

    public BlockPos targetPosition() { return targetPosition; }
    public boolean remote() { return remote; }
    /** Server-authoritative source captured at the instant this menu was opened. */
    public String initialSource() { return initialSource; }

    public ProgrammableLogicControllerBlockEntity plc() {
        if (access == ContainerLevelAccess.NULL) return null;
        return access.evaluate((level, position) -> level.getBlockEntity(position)
                instanceof ProgrammableLogicControllerBlockEntity plc ? plc : null).orElse(null);
    }

    @Override
    public boolean stillValid(Player player) {
        if (remote) {
            return player != null && player.level().getBlockEntity(targetPosition)
                    instanceof ProgrammableLogicControllerBlockEntity plc
                    && plc.canControl(player);
        }
        return stillValid(access, player, ModRegistries.PROGRAMMABLE_LOGIC_CONTROLLER_BLOCK.get());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
