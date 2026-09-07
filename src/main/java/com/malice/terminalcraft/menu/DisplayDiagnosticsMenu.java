package com.malice.terminalcraft.menu;

import com.malice.terminalcraft.blockentity.VideoCableBlockEntity;
import com.malice.terminalcraft.blockentity.WirelessDisplayLinkBlockEntity;
import com.malice.terminalcraft.blockentity.MonitorBlockEntity;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Server-authoritative diagnostics/configuration menu for display transport endpoints. */
public final class DisplayDiagnosticsMenu extends AbstractContainerMenu {
    public static final byte TYPE_LINK = 0;
    public static final byte TYPE_CABLE = 1;
    public static final byte TYPE_MONITOR = 2;

    private final BlockPos targetPosition;
    private final byte targetType;

    public DisplayDiagnosticsMenu(int containerId, Inventory inventory,
                                  WirelessDisplayLinkBlockEntity link) {
        this(containerId, inventory, link.getBlockPos(), TYPE_LINK);
    }

    public DisplayDiagnosticsMenu(int containerId, Inventory inventory,
                                  VideoCableBlockEntity cable) {
        this(containerId, inventory, cable.getBlockPos(), TYPE_CABLE);
    }

    public DisplayDiagnosticsMenu(int containerId, Inventory inventory,
                                  MonitorBlockEntity monitor) {
        this(containerId, inventory, monitor.getBlockPos(), TYPE_MONITOR);
    }

    private DisplayDiagnosticsMenu(int containerId, Inventory inventory, BlockPos position, byte type) {
        super(ModRegistries.DISPLAY_DIAGNOSTICS_MENU.get(), containerId);
        this.targetPosition = position.immutable();
        this.targetType = type;
    }

    public static DisplayDiagnosticsMenu fromNetwork(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        byte type = buffer.readByte();
        BlockPos position = buffer.readBlockPos();
        return new DisplayDiagnosticsMenu(containerId, inventory, position, type);
    }

    public BlockPos targetPosition() { return targetPosition; }
    public boolean isLink() { return targetType == TYPE_LINK; }
    public boolean isCable() { return targetType == TYPE_CABLE; }
    public boolean isMonitor() { return targetType == TYPE_MONITOR; }

    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        if (!stillValid(player)) return false;
        BlockEntity entity = player.level().getBlockEntity(targetPosition);
        if (entity instanceof WirelessDisplayLinkBlockEntity link && isLink()) {
            switch (buttonId) {
                case 0 -> {
                    if (link.isSource()) link.configureSink(link.channel());
                    else link.configureSource(link.channel());
                    return true;
                }
                case 1 -> {
                    if (link.attachedHost() != null) {
                        link.armPair(player.getUUID());
                        return true;
                    }
                    if (link.attachedMonitor() != null && link.acceptPair(player.getUUID())) {
                        WirelessDisplayLinkBlockEntity.clearPending(player.getUUID());
                        return true;
                    }
                    return false;
                }
                case 2 -> {
                    link.configureSink("");
                    WirelessDisplayLinkBlockEntity.clearPending(player.getUUID());
                    return true;
                }
                default -> { return false; }
            }
        }
        return false;
    }

    /** Applies a bounded channel edit received from the client configuration field. */
    public boolean configureLink(Player player, String channel, boolean source) {
        if (!stillValid(player) || !isLink()) return false;
        if (!(player.level().getBlockEntity(targetPosition) instanceof WirelessDisplayLinkBlockEntity link)) return false;
        if (source) link.configureSource(channel);
        else link.configureSink(channel);
        return true;
    }

    /** Applies one validated, wall-wide monitor appearance edit from the wrench screen. */
    public boolean configureMonitor(Player player, double textScale, int foreground) {
        if (!stillValid(player) || !isMonitor()) return false;
        if (!(player.level().getBlockEntity(targetPosition) instanceof MonitorBlockEntity monitor)) return false;
        try {
            monitor.configureWallAppearance(textScale, foreground);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    @Override public net.minecraft.world.item.ItemStack quickMoveStack(Player player, int index) {
        return net.minecraft.world.item.ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        if (player == null || player.isRemoved() || player.level().isClientSide && player.level().getBlockEntity(targetPosition) == null) {
            return false;
        }
        if (player.distanceToSqr(targetPosition.getX() + 0.5, targetPosition.getY() + 0.5,
                targetPosition.getZ() + 0.5) > 64.0) return false;
        BlockEntity entity = player.level().getBlockEntity(targetPosition);
        if (isLink()) return entity instanceof WirelessDisplayLinkBlockEntity;
        if (isCable()) return entity instanceof VideoCableBlockEntity;
        return isMonitor() && entity instanceof MonitorBlockEntity;
    }
}
