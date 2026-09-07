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
   private BashShell shell = new BashShell();
    private net.minecraft.nbt.Tag invalidShellPayload;
    private final EnumMap<Direction, Integer> redstoneOut = new EnumMap<>(Direction.class);
    private UUID deviceId = DeviceIdentity.create();
    private String label = "turtle";
    private final net.minecraftforge.items.ItemStackHandler inventory = new net.minecraftforge.items.ItemStackHandler(16) {
        @Override protected void onContentsChanged(int slot) { setChanged(); }
        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            return isRemoved() ? stack : super.insertItem(slot, stack, simulate);
        }
        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return isRemoved() ? ItemStack.EMPTY : super.extractItem(slot, amount, simulate);
        }
    };
    private net.minecraftforge.common.util.LazyOptional<net.minecraftforge.items.IItemHandler> inventoryCapability =
            net.minecraftforge.common.util.LazyOptional.of(() -> inventory);
    private int selectedSlot;
    private boolean fuelUnlimited;
    private int fuel;
    private boolean movingInventory;

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
    public int bundledInput(String side, int channel) {
        BundledCableBlockEntity cable = findBundledCable(side);
        return cable == null || channel < 0 || channel >= BundledCableBlockEntity.CHANNELS
                ? -1 : cable.getExternalInput(channel);
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
        if (isRemoved() || !(level instanceof ServerLevel server) || level.isClientSide) {
            return false;
        }
        Direction dir = parseSide(side == null || side.isBlank() ? "front" : side);
        if (dir == null) {
            dir = facing();
        }
        BlockPos target = worldPosition.relative(dir);
        if (!canInteractAt(server, target)) return false;
        BlockState state = level.getBlockState(target);
        if (state.isAir() || state.getDestroySpeed(level, target) < 0) {
            return false;
        }
        // Avoid breaking unbreakable / turtle / terminal machines carelessly
        if (state.is(Blocks.BEDROCK) || state.is(Blocks.BARRIER)) {
            return false;
        }
        if (!fuelUnlimited && fuel < 1) {
            return false;
        }
        // Preserve vanilla/Forge loot spawning. Only collect entities created by this
        // synchronous break near its target; existing loose items are not a vacuum source.
        var dropArea = new net.minecraft.world.phys.AABB(target).inflate(0.5);
        var existingDrops = new java.util.HashSet<>(server.getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class, dropArea));
        if (!destroyTurtleTarget(server, target)) return false;
        for (var drop : server.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, dropArea)) {
            if (existingDrops.contains(drop) || !drop.isAlive()) continue;
            ItemStack remainder = net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(
                    inventory, drop.getItem().copy(), false);
            if (remainder.isEmpty()) drop.discard();
            else drop.setItem(remainder);
        }
        consumeFuel(1);
        return true;
    }

    @Override
    public boolean turtlePlace(String side) {
        if (isRemoved() || !(level instanceof ServerLevel server) || level.isClientSide) {
            return false;
        }
        Direction dir = parseSide(side == null || side.isBlank() ? "front" : side);
        if (dir == null) {
            dir = facing();
        }
        BlockPos target = worldPosition.relative(dir);
        if (!canInteractAt(server, target)) return false;
        ItemStack selectedStack = inventory.getStackInSlot(selectedSlot);
        if (selectedStack.isEmpty()) {
            return false;
        }
        if (!(selectedStack.getItem() instanceof net.minecraft.world.item.BlockItem)) {
            return false;
        }
        if (!fuelUnlimited && fuel < 1) {
            return false;
        }
        ItemStack placementStack = selectedStack.copyWithCount(1);
        Direction placementDirection = dir;
        var context = new net.minecraft.world.item.context.DirectionalPlaceContext(
                server, target, facing(), placementStack, dir.getOpposite()) {
            // The target is explicit. Vanilla's directional implementation delegates this
            // back to canPlace(), which recurses through SlabBlock.canBeReplaced().
            @Override public boolean replacingClickedOnBlock() { return true; }
            @Override public Direction getNearestLookingDirection() { return placementDirection; }
            @Override public Direction getNearestLookingVerticalDirection() {
                return placementDirection == Direction.UP ? Direction.UP : Direction.DOWN;
            }
            @Override public Direction[] getNearestLookingDirections() {
                var directions = new java.util.LinkedHashSet<Direction>();
                directions.add(placementDirection);
                directions.add(Direction.DOWN);
                directions.add(Direction.UP);
                java.util.Collections.addAll(directions, Direction.values());
                return directions.toArray(Direction[]::new);
            }
        };
        if (!placeTurtleTarget(server, target, context)) return false;
        consumeFuel(1);
        if (!fuelUnlimited) {
            inventory.extractItem(selectedSlot, 1, false);
        }
        setChanged();
        return true;
    }

    private static boolean canInteractAt(ServerLevel level, BlockPos pos) {
        return level.isInWorldBounds(pos) && level.getWorldBorder().isWithinBounds(pos)
                && level.hasChunkAt(pos);
    }

    /** World mutation boundaries also allow deterministic rejection tests. */
    protected boolean destroyTurtleTarget(ServerLevel level, BlockPos pos) {
        return level.destroyBlock(pos, true);
    }

    protected boolean placeTurtleTarget(ServerLevel level, BlockPos pos,
                                       net.minecraft.world.item.context.BlockPlaceContext context) {
        return ((net.minecraft.world.item.BlockItem) context.getItemInHand().getItem()).place(context).consumesAction();
    }

    @Override public boolean turtleSelect(int slot) {
        if (isRemoved() || slot < 1 || slot > inventory.getSlots()) return false;
        selectedSlot = slot - 1;
        setChanged();
        return true;
    }

    @Override public String turtleFuel() { return fuelUnlimited ? "unlimited" : Integer.toString(fuel); }
    public int selectedInventorySlot() { return selectedSlot; }
    public int fuelBalance() { return fuel; }
    public boolean hasUnlimitedFuel() { return fuelUnlimited; }

    @Override public List<String> turtleInventory() {
        List<String> lines = new ArrayList<>();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            lines.add((slot == selectedSlot ? "* " : "  ") + (slot + 1) + ": "
                    + (stack.isEmpty() ? "empty" : stack.getCount() + " "
                    + net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem())));
        }
        return lines;
    }

    @Override public boolean turtleRefuel(int count) {
        if (isRemoved() || level == null || level.isClientSide || fuelUnlimited || count < 1 || count > 64) return false;
        ItemStack stack = inventory.getStackInSlot(selectedSlot);
        if (stack.isEmpty()) return false;
        int burnTime = net.minecraftforge.common.ForgeHooks.getBurnTime(stack, net.minecraft.world.item.crafting.RecipeType.SMELTING);
        int perItem = burnTime / 20;
        if (perItem < 1) return false;
        int consumed = Math.min(Math.min(count, stack.getCount()), (Integer.MAX_VALUE - fuel) / perItem);
        if (consumed < 1) return false;
        ItemStack remainder = stack.getCraftingRemainingItem();
        // Container fuels must have somewhere to return their container, without dropping it.
        if (!remainder.isEmpty() && (consumed != 1 || stack.getCount() != 1)) return false;
        inventory.extractItem(selectedSlot, consumed, false);
        if (!remainder.isEmpty()) inventory.setStackInSlot(selectedSlot, remainder);
        fuel += consumed * perItem;
        setChanged();
        return true;
    }

    public void dropInventory() {
        if (movingInventory || level == null || level.isClientSide) return;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.extractItem(slot, 64, false);
            if (!stack.isEmpty()) net.minecraft.world.Containers.dropItemStack(level,
                    worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), stack);
        }
    }

    @Override public <T> net.minecraftforge.common.util.LazyOptional<T> getCapability(
            net.minecraftforge.common.capabilities.Capability<T> cap, @Nullable Direction side) {
        if (cap == net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER && !isRemoved())
            return inventoryCapability.cast();
        return super.getCapability(cap, side);
    }

    @Override public void invalidateCaps() {
        super.invalidateCaps();
        inventoryCapability.invalidate();
    }

    @Override public void reviveCaps() {
        super.reviveCaps();
        inventoryCapability = net.minecraftforge.common.util.LazyOptional.of(() -> inventory);
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
    public String monitorScreensaver(String side, String action) {
        MonitorBlockEntity monitor = findMonitor(side);
        return monitor == null ? "" : MonitorScreensaver.command(monitor, action);
    }

    @Override
    public boolean monitorRegisterService(String service, int port) {
        ModemBlockEntity modem = findModem(null);
        return modem != null && modem.registerMonitorService(service, port);
    }

    @Override
    public boolean monitorUnregisterService(String service) {
        ModemBlockEntity modem = findModem(null);
        return modem != null && modem.unregisterMonitorService(service);
    }

    @Override
    public List<String> monitorServices() {
        ModemBlockEntity modem = findModem(null);
        return modem == null ? List.of() : modem.monitorServices();
    }

    @Override
    public boolean monitorRemote(String service, String encodedRequest) {
        ModemBlockEntity modem = findModem(null);
        return modem != null && modem.transmitMonitorService(service, encodedRequest);
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
    public int modemDefaultChannel() {
        ModemBlockEntity modem = findModem(null);
        return modem == null ? com.malice.terminalcraft.network.RednetAutoConfiguration.DEFAULT_CHANNEL
                : modem.defaultChannel();
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
    public List<String> modemDns(int maximum) {
        ModemBlockEntity modem = findModem(null);
        return modem == null ? List.of() : modem.dnsDiagnostics(maximum);
    }

    @Override
    public String modemResolve(String selector) {
        ModemBlockEntity modem = findModem(null);
        return modem == null ? "" : modem.resolveDiagnostics(selector);
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
    public boolean modemRegisterSensorService(String service, int port) {
        ModemBlockEntity modem = findModem(null);
        return modem != null && modem.registerSensorService(service, port);
    }

    @Override
    public boolean modemUnregisterSensorService(String service) {
        ModemBlockEntity modem = findModem(null);
        return modem != null && modem.unregisterSensorService(service);
    }

    @Override
    public List<String> modemSensorServices() {
        ModemBlockEntity modem = findModem(null);
        return modem == null ? List.of() : modem.sensorServices();
    }

    @Override
    public boolean modemTransmitSensorService(String service, String operation, String channel, int replyPort) {
        ModemBlockEntity modem = findModem(null);
        return modem != null && modem.transmitSensorService(service, operation, channel, replyPort);
    }

    @Override
    public boolean modemRegisterScadaService(String service, int port) {
        ModemBlockEntity modem = findModem(null);
        return modem != null && modem.registerScadaService(service, port);
    }

    @Override
    public boolean modemUnregisterScadaService(String service) {
        ModemBlockEntity modem = findModem(null);
        return modem != null && modem.unregisterScadaService(service);
    }

    @Override
    public List<String> modemScadaServices() {
        ModemBlockEntity modem = findModem(null);
        return modem == null ? List.of() : modem.scadaServices();
    }

    @Override
    public boolean modemTransmitScadaService(String service, String operation, String selector,
                                             int limit, int replyPort) {
        ModemBlockEntity modem = findModem(null);
        return modem != null && modem.transmitScadaService(service, operation, selector, limit, replyPort);
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
    public List<String> modemStatus() {
        ModemBlockEntity modem = findModem(null);
        return modem == null ? List.of() : modem.statusDiagnostics();
    }

    @Override
    public boolean modemAutomaticSetup() {
        ModemBlockEntity modem = findModem(null);
        return modem != null && modem.automaticSetup();
    }

    @Override
    public boolean modemSetAutomaticSetup(boolean enabled) {
        ModemBlockEntity modem = findModem(null);
        return modem != null && modem.setAutomaticSetup(enabled);
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
        if (isRemoved() || !(level instanceof ServerLevel server) || level.isClientSide) {
            return false;
        }
        BlockPos dest = worldPosition.relative(dir);
        if (!level.isInWorldBounds(dest) || !level.getWorldBorder().isWithinBounds(dest)
                || !level.hasChunkAt(dest) || !level.getBlockState(dest).canBeReplaced()) {
            return false;
        }
        if (!fuelUnlimited && fuel < 1) {
            return false;
        }

        BlockState oldState = getBlockState();
        BlockState newState = oldState;
        if (newState.hasProperty(TurtleBlock.FACING) && dir.getAxis().isHorizontal()) {
            // Keep current facing when moving; only turn commands rotate.
            newState = oldState;
        }

        CompoundTag saved = saveWithoutMetadata();
        BlockState previousDestination = level.getBlockState(dest);
        BlockEntity previousEntity = level.getBlockEntity(dest);
        CompoundTag previousData = previousEntity == null ? null : previousEntity.saveWithoutMetadata();
        // Validate the destination before touching the authoritative source or charging fuel.
        if (!placeMovementDestination(server, dest, newState)) return false;
        BlockEntity created = level.getBlockEntity(dest);
        if (!(created instanceof TurtleBlockEntity moved)) {
            restoreMovementDestination(server, dest, previousDestination, previousData);
            return false;
        }
        moved.movingInventory = true; // A rolled-back destination must not drop copied items.
        // Deserialize before removing the source, so a rejected payload cannot destroy it.
        try {
            moved.load(saved);
        } catch (RuntimeException failure) {
            restoreMovementDestination(server, dest, previousDestination, previousData);
            com.mojang.logging.LogUtils.getLogger().error("Turtle move payload could not be restored", failure);
            return false;
        }
        movingInventory = true;
        boolean removedSource;
        try {
            removedSource = level.setBlock(worldPosition, Blocks.AIR.defaultBlockState(), 3);
        } finally {
            movingInventory = false;
        }
        if (!removedSource) {
            restoreMovementDestination(server, dest, previousDestination, previousData);
            return false;
        }
        moved.setLevel(level);
        moved.movingInventory = false;
        for (int slot = 0; slot < 16; slot++) inventory.setStackInSlot(slot, ItemStack.EMPTY);
        // Continue the live command/script, including transient execution state, at its new host.
        // A saved-shell copy would strand the running command chain on the removed source.
        moved.shell = shell;
        moved.shell.setHost(moved);
        moved.consumeFuel(1);
        moved.setChanged();
        server.sendBlockUpdated(dest, newState, newState, 3);
        return true;
    }

    /** Isolated placement boundary permits failure injection without replacing the live world. */
    protected boolean placeMovementDestination(ServerLevel server, BlockPos dest, BlockState state) {
        return server.setBlock(dest, state, 3);
    }

    private static void restoreMovementDestination(ServerLevel server, BlockPos pos,
                                                   BlockState state, CompoundTag data) {
        server.setBlock(pos, state, 3);
        BlockEntity restored = server.getBlockEntity(pos);
        if (data != null && restored != null) {
            restored.load(data);
            restored.setChanged();
        }
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

    @Override public String diskMediaToken() {
        DiskDriveBlockEntity drive = findDiskDrive();
        return drive == null ? "" : drive.mediaToken();
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
        saveComputerState(tag, false);
    }

    private void saveComputerState(CompoundTag tag, boolean clientView) {
        PersistedDataVersions.stampCurrent(tag);
        DeviceIdentity.save(tag, deviceId);
        tag.put("Shell", clientView ? shell.saveClientView()
                : invalidShellPayload != null ? invalidShellPayload.copy() : shell.save());
        tag.putString("Label", label);
        tag.putInt("Fuel", fuel);
        tag.putBoolean("FuelUnlimited", fuelUnlimited);
        tag.put("Inventory", inventory.serializeNBT());
        tag.putInt("SelectedSlot", selectedSlot);
        tag.put("Selected", inventory.getStackInSlot(selectedSlot).save(new CompoundTag()));
        CompoundTag rs = new CompoundTag();
        for (Map.Entry<Direction, Integer> e : redstoneOut.entrySet()) {
            rs.putInt(e.getKey().getName(), e.getValue());
        }
        tag.put("RedstoneOut", rs);
    }

    @Override
    public void load(CompoundTag tag) {
        loadComputerState(tag, false);
    }

    @Override
    public void handleUpdateTag(CompoundTag tag) {
        loadComputerState(tag, true);
    }

    private void loadComputerState(CompoundTag tag, boolean clientView) {
        super.load(tag);
        deviceId = DeviceIdentity.loadOrRetain(tag, deviceId);
        if (tag.contains("Shell", CompoundTag.TAG_COMPOUND)) {
            if (!clientView) invalidShellPayload = null;
            if (clientView) shell.load(tag.getCompound("Shell"));
            else com.malice.terminalcraft.persistence.ComputerShellPersistence.load(shell, tag.getCompound("Shell"));
        } else if (!clientView && tag.contains("Shell")) {
            invalidShellPayload = tag.get("Shell").copy();
            CompoundTag retained = new CompoundTag();
            retained.put("InvalidShellPayload", invalidShellPayload.copy());
            shell.preserveUnreadableSnapshot(retained);
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
        for (int slot = 0; slot < 16; slot++) inventory.setStackInSlot(slot, ItemStack.EMPTY);
        selectedSlot = Math.max(0, Math.min(15, tag.getInt("SelectedSlot")));
        if (tag.contains("Inventory", CompoundTag.TAG_COMPOUND)) {
            // Do not trust serialized Size: the inventory is always bounded to sixteen slots.
            var entries = tag.getCompound("Inventory").getList("Items", CompoundTag.TAG_COMPOUND);
            boolean[] seen = new boolean[16];
            for (int index = 0; index < Math.min(entries.size(), 128); index++) {
                var entry = entries.getCompound(index);
                int slot = entry.getInt("Slot");
                if (slot < 0 || slot >= 16 || seen[slot]) continue;
                seen[slot] = true;
                ItemStack stack = ItemStack.of(entry);
                if (!stack.isEmpty()) stack.setCount(Math.min(stack.getCount(), stack.getMaxStackSize()));
                inventory.setStackInSlot(slot, stack);
            }
        } else if (tag.contains("Selected", CompoundTag.TAG_COMPOUND)) {
            ItemStack legacy = ItemStack.of(tag.getCompound("Selected"));
            if (!legacy.isEmpty()) legacy.setCount(Math.min(legacy.getCount(), legacy.getMaxStackSize()));
            inventory.setStackInSlot(0, legacy);
            selectedSlot = 0;
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
        saveComputerState(tag, true);
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
            handleUpdateTag(tag);
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
