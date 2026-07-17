package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.persistence.PersistedDataLimits;
import com.malice.terminalcraft.persistence.PersistedDataVersions;
import com.malice.terminalcraft.device.ServerDeviceManager;
import com.malice.terminalcraft.device.AdjacentForgeDeviceAccess;
import com.malice.terminalcraft.device.DeviceAccess;
import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.DeviceIdentity;
import com.malice.terminalcraft.block.TurtleBlock;
import com.malice.terminalcraft.menu.TerminalMenu;
import com.malice.terminalcraft.registry.ModRegistries;
import com.malice.terminalcraft.shell.BashShell;
import com.malice.terminalcraft.shell.ShellComputer;
import com.malice.terminalcraft.shell.TerminalHost;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Mobile bash computer (turtle). Right-click opens shell; turtle/* commands move it.
 */
public class TurtleBlockEntity extends BlockEntity implements MenuProvider, TerminalHost, ShellComputer {
    private final BashShell shell = new BashShell();
    private final EnumMap<Direction, Integer> redstoneOut = new EnumMap<>(Direction.class);
    private UUID deviceId = DeviceIdentity.create();
    private String label = "turtle";
    private ItemStack selectedStack = new ItemStack(Items.COBBLESTONE, 64);
    private boolean fuelUnlimited = true;
    private int fuel = 1000;

    public TurtleBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistries.TURTLE_BLOCK_ENTITY.get(), pos, state);
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
        return Component.translatable("block.terminalcraft.turtle");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        shell.setHost(this);
        return new TerminalMenu(containerId, playerInventory, this);
    }

    // ------------------------------------------------------------------
    // TerminalHost — identity / redstone
    // ------------------------------------------------------------------

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
        this.label = label == null || label.isBlank() ? "turtle" : label.trim();
        setChanged();
        markShellChanged();
    }

    // ------------------------------------------------------------------
    // Turtle motion
    // ------------------------------------------------------------------

    @Override
    public boolean isTurtle() {
        return true;
    }

    @Override
    public boolean turtleForward() {
        return tryMove(facing());
    }

    @Override
    public boolean turtleBack() {
        return tryMove(facing().getOpposite());
    }

    @Override
    public boolean turtleUp() {
        return tryMove(Direction.UP);
    }

    @Override
    public boolean turtleDown() {
        return tryMove(Direction.DOWN);
    }

    @Override
    public boolean turtleTurnLeft() {
        return tryTurn(facing().getCounterClockWise());
    }

    @Override
    public boolean turtleTurnRight() {
        return tryTurn(facing().getClockWise());
    }

    @Override
    public boolean turtleDig(String side) {
        if (!(level instanceof ServerLevel server) || level.isClientSide) {
            return false;
        }
        Direction dir = parseSide(side == null || side.isBlank() ? "front" : side);
        if (dir == null) {
            dir = facing();
        }
        BlockPos target = worldPosition.relative(dir);
        BlockState state = level.getBlockState(target);
        if (state.isAir() || state.getDestroySpeed(level, target) < 0) {
            return false;
        }
        // Avoid breaking unbreakable / turtle / terminal machines carelessly
        if (state.is(Blocks.BEDROCK) || state.is(Blocks.BARRIER)) {
            return false;
        }
        if (!consumeFuel(1)) {
            return false;
        }
        return server.destroyBlock(target, true);
    }

    @Override
    public boolean turtlePlace(String side) {
        if (level == null || level.isClientSide) {
            return false;
        }
        Direction dir = parseSide(side == null || side.isBlank() ? "front" : side);
        if (dir == null) {
            dir = facing();
        }
        BlockPos target = worldPosition.relative(dir);
        if (!level.getBlockState(target).canBeReplaced()) {
            return false;
        }
        if (selectedStack.isEmpty()) {
            return false;
        }
        Block block = Block.byItem(selectedStack.getItem());
        if (block == Blocks.AIR) {
            return false;
        }
        if (!consumeFuel(1)) {
            return false;
        }
        level.setBlock(target, block.defaultBlockState(), 3);
        if (!fuelUnlimited) {
            selectedStack.shrink(1);
        }
        setChanged();
        return true;
    }

    @Override
    public String turtleInspect(String side) {
        if (level == null) {
            return "no level";
        }
        Direction dir = parseSide(side == null || side.isBlank() ? "front" : side);
        if (dir == null) {
            dir = facing();
        }
        BlockPos target = worldPosition.relative(dir);
        BlockState st = level.getBlockState(target);
        if (st.isAir()) {
            return "air";
        }
        BlockEntity be = level.getBlockEntity(target);
        if (be instanceof MonitorBlockEntity) {
            return "monitor";
        }
        if (be instanceof ModemBlockEntity) {
            return "modem";
        }
        if (be instanceof TerminalBlockEntity) {
            return "terminal";
        }
        if (be instanceof TurtleBlockEntity) {
            return "turtle";
        }
        return st.getBlock().getDescriptionId();
    }

    @Override
    public String turtleFacing() {
        return facing().getName();
    }

    // ------------------------------------------------------------------
    // Monitor / modem via adjacent peripherals
    // ------------------------------------------------------------------

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
    // Movement helpers
    // ------------------------------------------------------------------

    private boolean tryMove(Direction dir) {
        if (!(level instanceof ServerLevel server) || level.isClientSide) {
            return false;
        }
        BlockPos dest = worldPosition.relative(dir);
        if (!level.getBlockState(dest).canBeReplaced()) {
            return false;
        }
        if (!consumeFuel(1)) {
            return false;
        }

        BlockState oldState = getBlockState();
        BlockState newState = oldState;
        if (newState.hasProperty(TurtleBlock.FACING) && dir.getAxis().isHorizontal()) {
            // Keep current facing when moving; only turn commands rotate.
            newState = oldState;
        }

        CompoundTag saved = saveWithoutMetadata();
        level.removeBlockEntity(worldPosition);
        level.setBlock(worldPosition, Blocks.AIR.defaultBlockState(), 3);

        level.setBlock(dest, newState, 3);
        BlockEntity created = level.getBlockEntity(dest);
        if (!(created instanceof TurtleBlockEntity moved)) {
            // Rollback best-effort
            level.setBlock(worldPosition, oldState, 3);
            return false;
        }
        moved.load(saved);
        moved.setLevel(level);
        moved.shell.setHost(moved);
        server.sendBlockUpdated(dest, newState, newState, 3);
        return true;
    }

    private boolean tryTurn(Direction newFacing) {
        if (level == null || level.isClientSide) {
            return false;
        }
        BlockState state = getBlockState();
        if (!state.hasProperty(TurtleBlock.FACING)) {
            return false;
        }
        if (!consumeFuel(0)) {
            return false;
        }
        BlockState updated = state.setValue(TurtleBlock.FACING, newFacing);
        level.setBlock(worldPosition, updated, 3);
        setChanged();
        return true;
    }

    private boolean consumeFuel(int amount) {
        if (fuelUnlimited) {
            return true;
        }
        if (fuel < amount) {
            return false;
        }
        fuel -= amount;
        setChanged();
        return true;
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
        if (state.hasProperty(TurtleBlock.FACING)) {
            return state.getValue(TurtleBlock.FACING);
        }
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return state.getValue(BlockStateProperties.HORIZONTAL_FACING);
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
        tag.putInt("Fuel", fuel);
        tag.putBoolean("FuelUnlimited", fuelUnlimited);
        tag.put("Selected", selectedStack.save(new CompoundTag()));
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
                    PersistedDataLimits.MAX_LABEL_CHARS, "turtle");
        }
        if (tag.contains("Fuel")) {
            fuel = Math.max(0, tag.getInt("Fuel"));
        }
        if (tag.contains("FuelUnlimited")) {
            fuelUnlimited = tag.getBoolean("FuelUnlimited");
        }
        if (tag.contains("Selected", CompoundTag.TAG_COMPOUND)) {
            selectedStack = ItemStack.of(tag.getCompound("Selected"));
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

    public static void serverTick(Level level, BlockPos pos, BlockState state, TurtleBlockEntity be) {
        ServerDeviceManager.ensureRegistered(be, be.getDeviceId(), be.getDeviceAddress(), be);
        // Reserved for queued turtle programs / modem polling.
    }
}
