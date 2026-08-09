package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.persistence.PersistedDataLimits;
import com.malice.terminalcraft.persistence.PersistedDataVersions;
import com.malice.terminalcraft.registry.ModRegistries;
import com.malice.terminalcraft.shell.ShellComputer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Persistent endpoint for a named wireless display channel. */
public class WirelessDisplayLinkBlockEntity extends BlockEntity implements MenuProvider {
    private static final int MAX_CHANNEL_LENGTH = 48;
    private static final Map<UUID, String> PENDING_PAIRS = new ConcurrentHashMap<>();

    private String channel = "";
    private boolean source;

    public WirelessDisplayLinkBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistries.WIRELESS_DISPLAY_LINK_BLOCK_ENTITY.get(), pos, state);
    }

    public String channel() { return channel; }
    public boolean isSource() { return source; }

    public void configureSource(String value) {
        channel = normalizeChannel(value);
        source = true;
        sync();
    }

    public void configureSink(String value) {
        channel = normalizeChannel(value);
        source = false;
        sync();
    }

    @Nullable public ShellComputer attachedHost() {
        if (level == null) return null;
        for (Direction direction : Direction.values()) {
            if (level.getBlockEntity(worldPosition.relative(direction)) instanceof ShellComputer host) return host;
        }
        return null;
    }

    @Nullable public MonitorBlockEntity attachedMonitor() {
        if (level == null) return null;
        for (Direction direction : Direction.values()) {
            if (level.getBlockEntity(worldPosition.relative(direction)) instanceof MonitorBlockEntity monitor) return monitor;
        }
        return null;
    }

    /** Arms a source channel for the two-click pairing interaction. */
    public void armPair(UUID playerId) {
        armPair(playerId, generatedChannel(playerId));
    }

    public void armPair(UUID playerId, String value) {
        String generated = normalizeChannel(value);
        configureSource(generated);
        PENDING_PAIRS.put(playerId, generated);
    }

    /** Completes the two-click pairing interaction on a receiver link. */
    public boolean acceptPair(UUID playerId) {
        String pending = PENDING_PAIRS.get(playerId);
        if (pending == null || pending.isBlank()) return false;
        configureSink(pending);
        return true;
    }

    public static void clearPending(UUID playerId) { if (playerId != null) PENDING_PAIRS.remove(playerId); }

    public String status() {
        return (source ? "source" : "receiver") + " channel=" + (channel.isEmpty() ? "(unpaired)" : channel)
                + " host=" + (attachedHost() != null) + " monitor=" + (attachedMonitor() != null);
    }

    @Override public Component getDisplayName() {
        return Component.translatable("block.terminalcraft.wireless_display_link");
    }

    @Nullable
    @Override public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new com.malice.terminalcraft.menu.DisplayDiagnosticsMenu(containerId, inventory, this);
    }

    @Override public void onLoad() {
        super.onLoad();
        DisplayTransportRuntime.register(this);
    }

    @Override public void setRemoved() {
        DisplayTransportRuntime.unregister(this);
        super.setRemoved();
    }

    private void sync() {
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public static String generatedChannel(UUID playerId) {
        if (playerId == null) return "player-unknown";
        String compact = playerId.toString().replace("-", "");
        return normalizeChannel("player-" + compact.substring(0, Math.min(12, compact.length())));
    }

    public static String normalizeChannel(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > MAX_CHANNEL_LENGTH) normalized = normalized.substring(0, MAX_CHANNEL_LENGTH);
        return normalized.replaceAll("[^a-z0-9_.:-]", "-");
    }

    @Override protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        PersistedDataVersions.stampCurrent(tag);
        tag.putString("Channel", channel);
        tag.putBoolean("Source", source);
    }

    @Override public void load(CompoundTag tag) {
        super.load(tag);
        channel = PersistedDataLimits.readString(tag, "Channel", MAX_CHANNEL_LENGTH, "");
        source = tag.contains("Source", Tag.TAG_BYTE) && tag.getBoolean("Source");
    }

    @Override public CompoundTag getUpdateTag() { CompoundTag tag = super.getUpdateTag(); saveAdditional(tag); return tag; }

    @Nullable @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) {
        if (packet.getTag() != null) load(packet.getTag());
    }
}
