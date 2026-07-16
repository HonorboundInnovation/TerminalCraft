package com.malice.terminalcraft.shell;

import com.malice.terminalcraft.device.DeviceAccess;
import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.ServerDeviceManager;
import com.malice.terminalcraft.item.PocketTerminalItem;
import com.malice.terminalcraft.network.RednetNetwork;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Item-backed shell host for a pocket terminal with a built-in wireless modem. */
public class PocketShellComputer implements ShellComputer {
    private final Player player;
    private final InteractionHand hand;
    private final BashShell shell;

    public PocketShellComputer(Player player, InteractionHand hand) {
        this.player = player;
        this.hand = hand;
        this.shell = PocketTerminalItem.loadShell(player.getItemInHand(hand));
        this.shell.setHost(this);
    }

    public InteractionHand getHand() { return hand; }
    public Player getPlayer() { return player; }

    public boolean isStillHolding() {
        return player.getItemInHand(hand).is(ModRegistries.POCKET_TERMINAL.get());
    }

    public ItemStack getStack() { return player.getItemInHand(hand); }

    @Override public BashShell getShell() { return shell; }

    @Override
    public void markShellChanged() {
        ItemStack stack = getStack();
        if (stack.is(ModRegistries.POCKET_TERMINAL.get())) {
            PocketTerminalItem.saveShell(stack, shell);
            PocketTerminalItem.setLabel(stack, getLabel());
        }
    }

    @Override
    public DeviceAccess deviceAccess(DeviceCallContext context) {
        if (!(player.level() instanceof ServerLevel level)) return null;
        return ServerDeviceManager.access(level.getServer(), context);
    }

    @Override public Level getLevel() { return player.level(); }
    @Override public BlockPos getBlockPos() { return player.blockPosition(); }
    @Override public BlockState getBlockState() { return getLevel().getBlockState(getBlockPos()); }
    @Override public int getRedstoneInput(String side) { return -1; }
    @Override public int getRedstoneOutput(String side) { return 0; }
    @Override public boolean setRedstoneOutput(String side, int power) { return false; }
    @Override public List<String> redstoneSides() { return List.of(); }

    @Override
    public List<String> listPeripherals() {
        List<String> found = new ArrayList<>();
        found.add("self:pocket");
        found.add("self:wireless_modem");
        return found;
    }

    @Override public String getLabel() { return PocketTerminalItem.getLabel(getStack()); }

    @Override
    public void setLabel(String label) {
        PocketTerminalItem.setLabel(getStack(), label);
        markShellChanged();
    }

    @Override public boolean hasModem() { return isStillHolding(); }

    @Override
    public boolean modemOpen(int channel) {
        ItemStack stack = getStack();
        if (!stack.is(ModRegistries.POCKET_TERMINAL.get())) return false;
        List<Integer> channels = new ArrayList<>(PocketTerminalItem.getOpenChannels(stack));
        int bounded = clampChannel(channel);
        if (channels.size() >= PocketTerminalItem.MAX_MODEM_CHANNELS && !channels.contains(bounded)) return false;
        if (!channels.contains(bounded)) channels.add(bounded);
        PocketTerminalItem.setOpenChannels(stack, channels);
        RednetNetwork.open(getLevel(), modemId(), bounded, getBlockPos(), true, PocketTerminalItem.MODEM_RANGE);
        return true;
    }

    @Override
    public boolean modemClose(int channel) {
        ItemStack stack = getStack();
        List<Integer> channels = new ArrayList<>(PocketTerminalItem.getOpenChannels(stack));
        boolean removed = channels.remove(Integer.valueOf(clampChannel(channel)));
        if (removed) {
            PocketTerminalItem.setOpenChannels(stack, channels);
            RednetNetwork.close(getLevel(), modemId(), channel);
        }
        return removed;
    }

    @Override public boolean modemIsOpen(int channel) {
        return PocketTerminalItem.getOpenChannels(getStack()).contains(clampChannel(channel));
    }

    @Override public List<Integer> modemOpenChannels() {
        return PocketTerminalItem.getOpenChannels(getStack());
    }

    @Override
    public boolean modemTransmit(int channel, int replyChannel, String message) {
        if (modemOpenChannels().isEmpty()) return false;
        RednetNetwork.transmit(getLevel(), modemId(), getBlockPos(), channel, replyChannel,
                message, true, PocketTerminalItem.MODEM_RANGE);
        return true;
    }

    @Override
    public List<String> modemReceive(int max) {
        return RednetNetwork.receive(getLevel(), modemId(), max).stream().map(RednetNetwork.PendingMessage::format).toList();
    }

    private UUID modemId() { return PocketTerminalItem.getOrCreateModemId(getStack()); }
    private static int clampChannel(int channel) { return Math.max(0, Math.min(65535, channel)); }
}
