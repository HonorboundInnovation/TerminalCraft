package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.persistence.PersistedDataLimits;
import com.malice.terminalcraft.persistence.PersistedDataVersions;
import com.malice.terminalcraft.device.ServerDeviceManager;
import com.malice.terminalcraft.device.AdjacentForgeDeviceAccess;
import com.malice.terminalcraft.device.DeviceAccess;
import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.DeviceIdentity;
import com.malice.terminalcraft.block.TerminalBlock;
import com.malice.terminalcraft.menu.TerminalMenu;
import com.malice.terminalcraft.registry.ModRegistries;
import com.malice.terminalcraft.shell.BashShell;
import com.malice.terminalcraft.shell.ShellComputer;
import com.malice.terminalcraft.shell.TerminalHost;
import com.malice.terminalcraft.world.TerminalChunkLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Stores per-terminal shell state, redstone outputs, and acts as MenuProvider.
 */
public class TerminalBlockEntity extends BlockEntity implements MenuProvider, TerminalHost, ShellComputer {
    private final BashShell shell = new BashShell();
    private final EnumMap<Direction, Integer> redstoneOut = new EnumMap<>(Direction.class);
    private UUID deviceId = DeviceIdentity.create();
    private String label = "terminal";

    public TerminalBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistries.TERMINAL_BLOCK_ENTITY.get(), pos, state);
        for (Direction d : Direction.values()) {
            redstoneOut.put(d, 0);
        }
        shell.setHost(this);
    }

    @Override
    public BashShell getShell() {
        return shell;
    }

    @Override
    public DeviceAccess deviceAccess(DeviceCallContext context) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        return new AdjacentForgeDeviceAccess(
                ServerDeviceManager.access(serverLevel.getServer(), context),
                serverLevel, worldPosition);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.terminalcraft.terminal");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        shell.setHost(this);
        return new TerminalMenu(containerId, playerInventory, this);
    }

    @Override
    public int getRedstoneInput(String side) {
        if (level == null) {
            return -1;
        }
        Direction dir = parseSide(side);
        if (dir == null) {
            if ("all".equalsIgnoreCase(side)) {
                int max = 0;
                for (Direction d : Direction.values()) {
                    max = Math.max(max, level.getSignal(worldPosition.relative(d), d.getOpposite()));
                }
                return max;
            }
            return -1;
        }
        return level.getSignal(worldPosition.relative(dir), dir.getOpposite());
    }

    @Override
    public int getRedstoneOutput(String side) {
        Direction dir = parseSide(side);
        if (dir == null) {
            if ("all".equalsIgnoreCase(side)) {
                return redstoneOut.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            }
            return -1;
        }
        return redstoneOut.getOrDefault(dir, 0);
    }

    @Override
    public boolean setRedstoneOutput(String side, int power) {
        int p = Math.max(0, Math.min(15, power));
        if ("all".equalsIgnoreCase(side)) {
            for (Direction d : Direction.values()) {
                redstoneOut.put(d, p);
            }
            notifyRedstoneChanged();
            return true;
        }
        Direction dir = parseSide(side);
        if (dir == null) {
            return false;
        }
        redstoneOut.put(dir, p);
        notifyRedstoneChanged();
        return true;
    }

    @Override
    public List<String> redstoneSides() {
        return List.of("front", "back", "left", "right", "top", "bottom",
                "north", "south", "east", "west", "up", "down", "all");
    }

    @Override
    public List<String> listPeripherals() {
        List<String> found = new ArrayList<>();
        if (level == null) {
            return found;
        }
        for (Direction d : Direction.values()) {
            BlockPos np = worldPosition.relative(d);
            BlockEntity be = level.getBlockEntity(np);
            if (be instanceof MonitorBlockEntity) {
                found.add(sideName(d) + ":monitor");
            } else if (be instanceof ModemBlockEntity) {
                found.add(sideName(d) + ":modem");
            } else if (be instanceof DiskDriveBlockEntity drive) {
                found.add(sideName(d) + (drive.hasDisk() ? ":disk_drive[loaded]" : ":disk_drive"));
            } else if (be instanceof TurtleBlockEntity) {
                found.add(sideName(d) + ":turtle");
            } else if (be instanceof TerminalBlockEntity) {
                found.add(sideName(d) + ":terminal");
            } else {
                BlockState st = level.getBlockState(np);
                if (!st.isAir()) {
                    found.add(sideName(d) + ":" + st.getBlock().getDescriptionId());
                }
            }
        }
        return found;
    }

    /** Stable identity persisted independently from the current block position. */
    public UUID getDeviceId() {
        return deviceId;
    }

    /** Current dimension-qualified attachment address; identity remains stable when this changes. */
    public String getDeviceAddress() {
        String dimension = level == null ? "unbound" : level.dimension().location().toString();
        return dimension + ":" + worldPosition.getX() + "," + worldPosition.getY() + "," + worldPosition.getZ();
    }

    @Override
    public String getLabel() {
        return label;
    }

    @Override
    public void setLabel(String label) {
        this.label = label == null || label.isBlank() ? "terminal" : label.trim();
        setChanged();
        markShellChanged();
    }

    @Override
    public boolean monitorWrite(String side, String text) {
        MonitorBlockEntity mon = findMonitor(side);
        if (mon == null) {
            return false;
        }
        new MonitorGroupDevice(mon).writeLine(text);
        return true;
    }

    @Override
    public boolean monitorClear(String side) {
        MonitorBlockEntity mon = findMonitor(side);
        if (mon == null) {
            return false;
        }
        new MonitorGroupDevice(mon).clear();
        return true;
    }

    @Override
    public boolean monitorSetLine(String side, int row, String text) {
        MonitorBlockEntity mon = findMonitor(side);
        if (mon == null) return false;
        MonitorGroupDevice group = new MonitorGroupDevice(mon);
        if (row < 0 || row >= group.maxLines() || text.length() > group.maxLineLength()) return false;
        group.setLine(row, text);
        return true;
    }

    @Override
    public boolean monitorSetTitle(String side, String title) {
        MonitorBlockEntity mon = findMonitor(side);
        if (mon == null) return false;
        new MonitorGroupDevice(mon).setTitle(title);
        return true;
    }

    @Override
    public boolean monitorSetPalette(String side, int foreground, int background) {
        MonitorBlockEntity mon = findMonitor(side);
        if (mon == null) return false;
        new MonitorGroupDevice(mon).setPalette(foreground, background);
        return true;
    }

    @Override
    public List<String> monitorLines(String side) {
        MonitorBlockEntity mon = findMonitor(side);
        if (mon == null) {
            return List.of();
        }
        return new MonitorGroupDevice(mon).lines();
    }

    @Override
    public int monitorColumns(String side) {
        MonitorBlockEntity monitor = findMonitor(side);
        return monitor == null ? 0 : new MonitorGroupDevice(monitor).maxLineLength();
    }

    @Override
    public int monitorRows(String side) {
        MonitorBlockEntity monitor = findMonitor(side);
        return monitor == null ? 0 : new MonitorGroupDevice(monitor).maxLines();
    }

    @Override
    public boolean hasModem() {
        return findModem(null) != null;
    }

    @Override
    public boolean modemOpen(int channel) {
        ModemBlockEntity modem = findModem(null);
        return modem != null && modem.openChannel(channel);
    }

    @Override
    public boolean modemClose(int channel) {
        ModemBlockEntity modem = findModem(null);
        return modem != null && modem.closeChannel(channel);
    }

    @Override
    public boolean modemIsOpen(int channel) {
        ModemBlockEntity modem = findModem(null);
        return modem != null && modem.isOpen(channel);
    }

    @Override
    public List<Integer> modemOpenChannels() {
        ModemBlockEntity modem = findModem(null);
        return modem == null ? List.of() : modem.getOpenChannels();
    }

    @Override
    public boolean modemTransmit(int channel, int replyChannel, String message) {
        ModemBlockEntity modem = findModem(null);
        return modem != null && modem.transmit(channel, replyChannel, message);
    }

    @Override
    public String modemHostname() {
        ModemBlockEntity modem = findModem(null);
        return modem == null ? "" : modem.getHostname();
    }

    @Override
    public boolean modemSetHostname(String hostname) {
        ModemBlockEntity modem = findModem(null);
        return modem != null && modem.setHostname(hostname);
    }

    @Override
    public String modemNetworkName() {
        ModemBlockEntity modem = findModem(null);
        return modem == null ? "" : modem.getNetworkName();
    }

    @Override
    public boolean modemSetNetworkName(String networkName) {
        ModemBlockEntity modem = findModem(null);
        return modem != null && modem.setNetworkName(networkName);
    }

    @Override
    public List<String> modemInterfaces() {
        ModemBlockEntity modem = findModem(null);
        return modem == null ? List.of() : modem.interfaceDiagnostics();
    }

    @Override
    public List<String> modemTopologyDiagnostics() {
        ModemBlockEntity modem = findModem(null);
        return modem == null ? List.of() : modem.topologyDiagnostics();
    }

    @Override
    public List<String> modemPacketDiagnostics() {
        ModemBlockEntity modem = findModem(null);
        return modem == null ? List.of() : modem.packetDiagnostics();
    }

    @Override
    public List<String> modemRoute(String destination) {
        ModemBlockEntity modem = findModem(null);
        return modem == null ? List.of() : modem.routeDiagnostics(destination);
    }

    @Override
    public List<String> modemPing(String destination) {
        ModemBlockEntity modem = findModem(null);
        return modem == null ? List.of() : modem.pingDiagnostics(destination);
    }

    @Override
    public List<String> modemNeighbors(int maximum) {
        ModemBlockEntity modem = findModem(null);
        return modem == null ? List.of() : modem.neighborDiagnostics(maximum);
    }

    @Override
    public List<String> modemHosts(int maximum) {
        ModemBlockEntity modem = findModem(null);
        return modem == null ? List.of() : modem.visibleHosts(maximum);
    }

    @Override
    public boolean modemTransmitTo(String hostname, int port, int replyPort, String message) {
        ModemBlockEntity modem = findModem(null);
        return modem != null && modem.transmitTo(hostname, port, replyPort, message);
    }

    @Override
    public String modemProbe(String hostname, int port, int replyPort, String message) {
        ModemBlockEntity modem = findModem(null);
        return modem == null ? "" : modem.probe(hostname, port, replyPort, message);
    }

    @Override
    public String modemDelivery(String messageId) {
        ModemBlockEntity modem = findModem(null);
        return modem == null ? "" : modem.deliveryDiagnostics(messageId);
    }

    @Override
    public boolean modemRegisterService(String service, int port) {
        ModemBlockEntity modem = findModem(null);
        return modem != null && modem.registerService(service, port);
    }

    @Override
    public boolean modemUnregisterService(String service) {
        ModemBlockEntity modem = findModem(null);
        return modem != null && modem.unregisterService(service);
    }

    @Override
    public List<String> modemLocalServices() {
        ModemBlockEntity modem = findModem(null);
        return modem == null ? List.of() : modem.localServices();
    }

    @Override
    public List<String> modemServices(int maximum) {
        ModemBlockEntity modem = findModem(null);
        return modem == null ? List.of() : modem.visibleServices(maximum);
    }

    @Override
    public boolean modemTransmitService(String service, int replyPort, String message) {
        ModemBlockEntity modem = findModem(null);
        return modem != null && modem.transmitService(service, replyPort, message);
    }

    @Override
    public List<String> modemReceive(int max) {
        ModemBlockEntity modem = findModem(null);
        return modem == null ? List.of() : modem.receiveMessages(max);
    }

    @Override
    public String serverSubmit(DeviceCallContext context, String command) {
        ServerRackBlockEntity rack = findServerRack();
        return rack == null ? "" : rack.serverSubmit(context, command);
    }

    @Override
    public List<String> serverJobs() {
        ServerRackBlockEntity rack = findServerRack();
        return rack == null ? List.of() : rack.serverJobs();
    }

    @Override
    public String serverJob(String id) {
        ServerRackBlockEntity rack = findServerRack();
        return rack == null ? "" : rack.serverJob(id);
    }

    @Override
    public List<String> serverJobs(DeviceCallContext context) {
        ServerRackBlockEntity rack = findServerRack();
        return rack == null ? List.of() : rack.serverJobs(context);
    }

    @Override
    public String serverJob(DeviceCallContext context, String id) {
        ServerRackBlockEntity rack = findServerRack();
        return rack == null ? "" : rack.serverJob(context, id);
    }

    @Override
    public boolean serverCancel(DeviceCallContext context, String id) {
        ServerRackBlockEntity rack = findServerRack();
        return rack != null && rack.serverCancel(context, id);
    }

    @Override
    public int serverQueuedJobs() {
        ServerRackBlockEntity rack = findServerRack();
        return rack == null ? -1 : rack.serverQueuedJobs();
    }

    @Override
    public String serverSchedulerDiagnostics() {
        ServerRackBlockEntity rack = findServerRack();
        return rack == null ? "" : rack.serverSchedulerDiagnostics();
    }

    @Nullable
    private ServerRackBlockEntity findServerRack() {
        if (level == null) return null;
        for (Direction direction : Direction.values()) {
            if (level.getBlockEntity(worldPosition.relative(direction)) instanceof ServerRackBlockEntity rack) {
                return rack;
            }
        }
        return null;
    }

    @Override
    public boolean hasBundledCable(String side) {
        return findBundledCable(side) != null;
    }

    @Override
    public int bundledSignal(String side, int channel) {
        BundledCableBlockEntity cable = findBundledCable(side);
        return cable == null || channel < 0 || channel >= BundledCableBlockEntity.CHANNELS
                ? -1 : cable.getSignal(channel);
    }

    @Override
    public int bundledOutput(String side, int channel) {
        BundledCableBlockEntity cable = findBundledCable(side);
        return cable == null || channel < 0 || channel >= BundledCableBlockEntity.CHANNELS
                ? -1 : cable.getLocalOutput(channel);
    }

    @Override
    public boolean setBundledOutput(String side, int channel, int strength) {
        BundledCableBlockEntity cable = findBundledCable(side);
        if (cable == null || channel < 0 || channel >= BundledCableBlockEntity.CHANNELS
                || strength < 0 || strength > 15) return false;
        cable.setLocalOutput(channel, strength);
        return true;
    }

    @Nullable
    private BundledCableBlockEntity findBundledCable(String side) {
        if (level == null) return null;
        if (side == null || side.isBlank() || "any".equalsIgnoreCase(side)) {
            for (Direction direction : Direction.values()) {
                BlockEntity blockEntity = level.getBlockEntity(worldPosition.relative(direction));
                if (blockEntity instanceof BundledCableBlockEntity cable) return cable;
            }
            return null;
        }
        Direction direction = parseSide(side);
        if (direction == null) return null;
        BlockEntity blockEntity = level.getBlockEntity(worldPosition.relative(direction));
        return blockEntity instanceof BundledCableBlockEntity cable ? cable : null;
    }

    @Nullable
    private MonitorBlockEntity findMonitor(String side) {
        if (level == null) {
            return null;
        }
        if (side == null || side.isBlank() || "any".equalsIgnoreCase(side)) {
            for (Direction d : Direction.values()) {
                BlockEntity be = level.getBlockEntity(worldPosition.relative(d));
                if (be instanceof MonitorBlockEntity mon) {
                    return mon;
                }
            }
            return null;
        }
        Direction dir = parseSide(side);
        if (dir == null) {
            return null;
        }
        BlockEntity be = level.getBlockEntity(worldPosition.relative(dir));
        return be instanceof MonitorBlockEntity mon ? mon : null;
    }

    @Nullable
    private ModemBlockEntity findModem(String side) {
        if (level == null) {
            return null;
        }
        if (side == null || side.isBlank() || "any".equalsIgnoreCase(side)) {
            for (Direction d : Direction.values()) {
                BlockEntity be = level.getBlockEntity(worldPosition.relative(d));
                if (be instanceof ModemBlockEntity modem) {
                    return modem;
                }
            }
            return null;
        }
        Direction dir = parseSide(side);
        if (dir == null) {
            return null;
        }
        BlockEntity be = level.getBlockEntity(worldPosition.relative(dir));
        return be instanceof ModemBlockEntity modem ? modem : null;
    }


    // ------------------------------------------------------------------
    // Disk drive media
    // ------------------------------------------------------------------

    @Override
    public boolean hasDiskMedia() {
        DiskDriveBlockEntity drive = findDiskDrive();
        return drive != null && drive.hasDisk();
    }

    @Override
    public String getDiskLabel() {
        DiskDriveBlockEntity drive = findDiskDrive();
        return drive == null ? "" : drive.getDiskLabel();
    }

    @Override
    public boolean setDiskLabel(String label) {
        DiskDriveBlockEntity drive = findDiskDrive();
        if (drive == null || !drive.hasDisk()) {
            return false;
        }
        drive.setDiskLabel(label);
        return true;
    }

    @Override
    public CompoundTag readDiskMedia() {
        DiskDriveBlockEntity drive = findDiskDrive();
        return drive == null ? null : drive.readMedia();
    }

    @Override
    public boolean writeDiskMedia(CompoundTag vfsTag) {
        DiskDriveBlockEntity drive = findDiskDrive();
        return drive != null && drive.writeMedia(vfsTag);
    }

    @Nullable
    private DiskDriveBlockEntity findDiskDrive() {
        if (level == null) {
            return null;
        }
        for (Direction d : Direction.values()) {
            BlockEntity be = level.getBlockEntity(worldPosition.relative(d));
            if (be instanceof DiskDriveBlockEntity drive && drive.hasDisk()) {
                return drive;
            }
        }
        // Prefer any adjacent drive even if empty (for status messages)
        for (Direction d : Direction.values()) {
            BlockEntity be = level.getBlockEntity(worldPosition.relative(d));
            if (be instanceof DiskDriveBlockEntity drive) {
                return drive;
            }
        }
        return null;
    }

    public int getDirectSignal(Direction side) {
        return redstoneOut.getOrDefault(side, 0);
    }

    private void notifyRedstoneChanged() {
        setChanged();
        if (level != null) {
            BlockState state = getBlockState();
            level.updateNeighborsAt(worldPosition, state.getBlock());
            for (Direction d : Direction.values()) {
                level.updateNeighborsAt(worldPosition.relative(d), state.getBlock());
            }
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    @Nullable
    private Direction parseSide(String side) {
        if (side == null || side.isBlank()) {
            return null;
        }
        String s = side.toLowerCase(Locale.ROOT);
        return switch (s) {
            case "up", "top" -> Direction.UP;
            case "down", "bottom" -> Direction.DOWN;
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            case "front", "forward" -> facing();
            case "back", "behind" -> facing().getOpposite();
            case "left" -> facing().getCounterClockWise();
            case "right" -> facing().getClockWise();
            default -> null;
        };
    }

    private Direction facing() {
        BlockState state = getBlockState();
        if (state.hasProperty(TerminalBlock.FACING)) {
            return state.getValue(TerminalBlock.FACING);
        }
        return Direction.NORTH;
    }

    private static String sideName(Direction d) {
        return switch (d) {
            case UP -> "top";
            case DOWN -> "bottom";
            default -> d.getName();
        };
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        PersistedDataVersions.stampCurrent(tag);
        DeviceIdentity.save(tag, deviceId);
        tag.put("Shell", shell.save());
        tag.putString("Label", label);
        CompoundTag rs = new CompoundTag();
        for (Map.Entry<Direction, Integer> e : redstoneOut.entrySet()) {
            rs.putInt(e.getKey().getName(), e.getValue());
        }
        tag.put("RedstoneOut", rs);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        deviceId = DeviceIdentity.loadOrRetain(tag, deviceId);
        if (tag.contains("Shell", CompoundTag.TAG_COMPOUND)) {
            shell.load(tag.getCompound("Shell"));
        }
        shell.setHost(this);
        if (tag.contains("Label", CompoundTag.TAG_STRING)) {
            label = PersistedDataLimits.readString(tag, "Label",
                    PersistedDataLimits.MAX_LABEL_CHARS, "terminal");
        }
        if (tag.contains("RedstoneOut", CompoundTag.TAG_COMPOUND)) {
            CompoundTag rs = tag.getCompound("RedstoneOut");
            for (Direction d : Direction.values()) {
                if (rs.contains(d.getName())) {
                    redstoneOut.put(d, Math.max(0, Math.min(15, rs.getInt(d.getName()))));
                }
            }
        }
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        saveAdditional(tag);
        if (tag.contains("Shell", CompoundTag.TAG_COMPOUND)
                && !com.malice.terminalcraft.network.ShellSyncPolicy.isAdmissible(tag.getCompound("Shell"))) {
            tag.remove("Shell");
        }
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

    @Override
    public void markShellChanged() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public void setRemoved() {
        ServerDeviceManager.invalidate(this);
        super.setRemoved();
    }

    public void onTerminalRemoved() {
        if (level instanceof ServerLevel serverLevel) {
            TerminalChunkLoader.terminalRemoved(serverLevel, deviceId);
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, TerminalBlockEntity be) {
        if (level instanceof ServerLevel serverLevel) {
            TerminalChunkLoader.ensureLoaded(serverLevel, pos, be.getDeviceId());
        }
        ServerDeviceManager.ensureRegistered(be, be.getDeviceId(), be.getDeviceAddress(), be);
        // Reserved for async scripts / modem polling.
    }
}
