package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.block.ProgrammableLogicControllerBlock;
import com.malice.terminalcraft.device.AdjacentForgeDeviceAccess;
import com.malice.terminalcraft.device.DeviceAccess;
import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.DeviceIdentity;
import com.malice.terminalcraft.menu.PlcProgrammingMenu;
import com.malice.terminalcraft.persistence.PersistedDataLimits;
import com.malice.terminalcraft.persistence.PersistedDataVersions;
import com.malice.terminalcraft.plc.PlcProgram;
import com.malice.terminalcraft.registry.ModRegistries;
import com.malice.terminalcraft.shell.BashShell;
import com.malice.terminalcraft.shell.ShellComputer;
import com.malice.terminalcraft.shell.TerminalHost;
import com.malice.terminalcraft.world.TerminalChunkLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
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

/** Server-authoritative programmable logic controller with bounded scan-cycle execution. */
public class ProgrammableLogicControllerBlockEntity extends BlockEntity
        implements MenuProvider, TerminalHost, ShellComputer {
    private static final int MAX_PROGRAM_STATUS_CHARS = 256;

    private final BashShell shell = new BashShell();
    private final PlcProgram.Controller controller = new PlcProgram.Controller();
    private final EnumMap<Direction, Integer> automaticRedstone = new EnumMap<>(Direction.class);
    private final EnumMap<Direction, Integer> manualRedstone = new EnumMap<>(Direction.class);
    private UUID deviceId = DeviceIdentity.create();
    private String label = "plc";
    private String programSource = "";
    private String compileError = "";
    private int scanCountdown;
    private int displayCountdown;
    private int telemetryCountdown;
    private final Map<String, Boolean> forcedInputs = new java.util.LinkedHashMap<>();
    private final Map<String, Integer> forcedAnalogInputs = new java.util.LinkedHashMap<>();
    private final Map<String, Integer> forcedOutputs = new java.util.LinkedHashMap<>();
    private final Map<String, List<Integer>> trendHistory = new java.util.LinkedHashMap<>();
    private final Map<Integer, String> programSlots = new java.util.LinkedHashMap<>();
    private final List<String> faultHistory = new ArrayList<>();
    private boolean alarmLatched;
    private int dashboardPage;
    private UUID ownerId;

    public ProgrammableLogicControllerBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistries.PROGRAMMABLE_LOGIC_CONTROLLER_BLOCK_ENTITY.get(), pos, state);
        for (Direction direction : Direction.values()) {
            automaticRedstone.put(direction, 0);
            manualRedstone.put(direction, 0);
        }
        shell.setHost(this);
    }

    @Override public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            TerminalChunkLoader.ensureLoaded(serverLevel, worldPosition, deviceId);
        }
    }

    @Override public BashShell getShell() { return shell; }

    @Override
    public DeviceAccess deviceAccess(DeviceCallContext context) {
        if (!(level instanceof ServerLevel serverLevel)) return null;
        return new AdjacentForgeDeviceAccess(
                com.malice.terminalcraft.device.ServerDeviceManager.access(serverLevel.getServer(), context),
                serverLevel, worldPosition);
    }

    @Override public Component getDisplayName() {
        return Component.translatable("block.terminalcraft.programmable_logic_controller");
    }

    @Nullable
    @Override public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        shell.setHost(this);
        return new PlcProgrammingMenu(containerId, inventory, this);
    }

    public String statusLine() {
        String state = !compileError.isEmpty() ? "FAULT" : controller.running() ? "RUN" : "STOP";
        String detail = !compileError.isEmpty() ? compileError : controller.fault();
        if (detail.length() > MAX_PROGRAM_STATUS_CHARS) detail = detail.substring(0, MAX_PROGRAM_STATUS_CHARS);
        return "state=" + state + " scan=" + controller.scanCount()
                + " interval=" + controller.program().scanIntervalTicks()
                + (detail.isEmpty() ? "" : " fault=" + detail);
    }

    public String programSource() { return programSource; }
    public boolean isRunning() { return controller.running(); }
    public String compileError() { return compileError; }
    public PlcProgram.Compiled dashboardProgram() { return controller.program(); }
    public Map<String, Boolean> dashboardSignals() { return controller.signals(); }
    public long dashboardScanCount() { return controller.scanCount(); }
    public String controllerFault() { return controller.fault(); }
    public int dashboardPage() { return dashboardPage; }
    public Map<String, Boolean> forcedInputs() { return Map.copyOf(forcedInputs); }
    public Map<String, Integer> forcedAnalogInputs() { return Map.copyOf(forcedAnalogInputs); }
    public Map<String, Integer> forcedOutputs() { return Map.copyOf(forcedOutputs); }
    public Map<String, List<Integer>> dashboardTrend() {
        Map<String, List<Integer>> copy = new java.util.LinkedHashMap<>();
        trendHistory.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        return Map.copyOf(copy);
    }
    public Map<String, Integer> dashboardAnalogValues() { return controller.analogValues(); }
    public Map<String, Integer> dashboardPidOutputs() { return controller.pidOutputs(); }
    public List<String> faultHistory() { return List.copyOf(faultHistory); }
    public boolean alarmLatched() { return alarmLatched; }
    public UUID ownerId() { return ownerId; }
    public UUID getDeviceId() { return deviceId; }
    public String getDeviceAddress() {
        String dimension = level == null ? "unbound" : level.dimension().location().toString();
        return dimension + ":" + worldPosition.getX() + "," + worldPosition.getY() + "," + worldPosition.getZ();
    }
    public void setOwner(UUID owner) { ownerId = owner; setChanged(); }
    public boolean canControl(Player player) {
        return player != null && (player.hasPermissions(2) || canControl(player.getUUID()));
    }
    public boolean canControl(UUID player) { return ownerId == null || ownerId.equals(player); }
    public Map<Integer, String> programSlots() { return Map.copyOf(programSlots); }

    public boolean saveProgramSlot(int slot) {
        if (slot < 0 || slot > 3) return false;
        programSlots.put(slot, PersistedDataLimits.truncate(programSource, PlcProgram.MAX_SOURCE_CHARS));
        markShellChanged();
        return true;
    }

    public boolean loadProgramSlot(int slot) {
        if (slot < 0 || slot > 3 || !programSlots.containsKey(slot)) return false;
        return loadProgram(programSlots.get(slot));
    }

    public boolean clearProgramSlot(int slot) {
        if (slot < 0 || slot > 3) return false;
        programSlots.remove(slot);
        markShellChanged();
        return true;
    }
    public Map<String, Integer> dashboardTimerElapsed() { return controller.timerElapsed(); }
    public Map<String, Integer> dashboardCounterValues() { return controller.counterValues(); }
    public Map<String, Boolean> dashboardLatchValues() { return controller.latchValues(); }

    public void setDashboardPage(int page) { dashboardPage = Math.floorMod(page, 4); markShellChanged(); }
    public void nextDashboardPage() { setDashboardPage(dashboardPage + 1); }
    public void previousDashboardPage() { setDashboardPage(dashboardPage - 1); }

    public boolean forceInput(String name, Boolean value) {
        String key = normalizeSignal(name);
        if (key == null || controller.program().inputs().stream().noneMatch(binding -> binding.name().equals(key))) return false;
        if (value == null) forcedInputs.remove(key); else forcedInputs.put(key, value);
        markShellChanged();
        return true;
    }

    public boolean forceAnalogInput(String name, Integer value) {
        String key = normalizeSignal(name);
        if (key == null || !controller.program().analogInputs().contains(key)) return false;
        if (value == null) forcedAnalogInputs.remove(key);
        else forcedAnalogInputs.put(key, Math.max(0, Math.min(15, value)));
        markShellChanged();
        return true;
    }

    public boolean forceOutput(String name, Integer value) {
        String key = normalizeSignal(name);
        if (key == null || controller.program().outputs().stream().noneMatch(binding -> binding.name().equals(key))) return false;
        if (value == null) forcedOutputs.remove(key); else forcedOutputs.put(key, Math.max(0, Math.min(15, value)));
        markShellChanged();
        return true;
    }

    public void clearForces() {
        forcedInputs.clear();
        forcedAnalogInputs.clear();
        forcedOutputs.clear();
        clearAutomaticOutputs();
        markShellChanged();
    }

    public void acknowledgeAlarm() {
        if (compileError.isEmpty() && controller.fault().isEmpty()) alarmLatched = false;
        markShellChanged();
    }

    public boolean loadProgram(String source) {
        PlcProgram.CompileResult result = PlcProgram.compile(source);
        stop();
        if (!result.successful()) {
            compileError = bound(result.error());
            recordFault(compileError);
            programSource = source == null ? "" : PersistedDataLimits.truncate(source, PlcProgram.MAX_SOURCE_CHARS);
            controller.load(null);
            clearAutomaticOutputs();
            markShellChanged();
            return false;
        }
        programSource = result.program().source();
        compileError = "";
        controller.load(result.program());
        forcedInputs.keySet().removeIf(key -> result.program().inputs().stream().noneMatch(binding -> binding.name().equals(key)));
        forcedAnalogInputs.keySet().removeIf(key -> !result.program().analogInputs().contains(key));
        forcedOutputs.keySet().removeIf(key -> result.program().outputs().stream().noneMatch(binding -> binding.name().equals(key)));
        trendHistory.clear();
        scanCountdown = 0;
        clearAutomaticOutputs();
        markShellChanged();
        return true;
    }

    public void start() {
        if (compileError.isEmpty()) {
            controller.start();
            scanCountdown = 0;
            markShellChanged();
        }
    }

    public void stop() {
        controller.stop();
        scanCountdown = 0;
        clearAutomaticOutputs();
        markShellChanged();
    }

    public void resetController() {
        controller.resetState();
        scanCountdown = 0;
        alarmLatched = false;
        clearAutomaticOutputs();
        markShellChanged();
    }

    public void clearProgram() { loadProgram(""); }

    @Override public int getRedstoneInput(String side) {
        if (level == null) return -1;
        Direction direction = parseSide(side);
        if (direction == null) {
            if (!"all".equalsIgnoreCase(side)) return -1;
            int maximum = 0;
            for (Direction candidate : Direction.values()) {
                maximum = Math.max(maximum, level.getSignal(worldPosition.relative(candidate), candidate.getOpposite()));
            }
            return maximum;
        }
        return level.getSignal(worldPosition.relative(direction), direction.getOpposite());
    }

    @Override public int getRedstoneOutput(String side) {
        Direction direction = parseSide(side);
        if (direction == null) {
            return "all".equalsIgnoreCase(side) ? redstoneValues().stream().mapToInt(Integer::intValue).max().orElse(0) : -1;
        }
        return Math.max(automaticRedstone.getOrDefault(direction, 0), manualRedstone.getOrDefault(direction, 0));
    }

    @Override public boolean setRedstoneOutput(String side, int power) {
        int bounded = Math.max(0, Math.min(15, power));
        if ("all".equalsIgnoreCase(side)) {
            for (Direction direction : Direction.values()) manualRedstone.put(direction, bounded);
            notifyRedstoneChanged();
            return true;
        }
        Direction direction = parseSide(side);
        if (direction == null) return false;
        manualRedstone.put(direction, bounded);
        notifyRedstoneChanged();
        return true;
    }

    @Override public List<String> redstoneSides() {
        return List.of("front", "back", "left", "right", "top", "bottom",
                "north", "south", "east", "west", "up", "down", "all");
    }

    @Override public List<String> listPeripherals() {
        List<String> found = new ArrayList<>();
        if (level == null) return found;
        for (Direction direction : Direction.values()) {
            BlockPos adjacent = worldPosition.relative(direction);
            BlockEntity entity = level.getBlockEntity(adjacent);
            if (entity instanceof BundledCableBlockEntity) found.add(sideName(direction) + ":bundled_cable");
            else if (entity instanceof MonitorBlockEntity) found.add(sideName(direction) + ":monitor");
            else if (entity instanceof ModemBlockEntity) found.add(sideName(direction) + ":modem");
            else if (entity != null) found.add(sideName(direction) + ":" + entity.getType());
        }
        return found;
    }

    @Override public String getLabel() { return label; }
    @Override public void setLabel(String value) {
        label = PersistedDataLimits.truncate(value == null ? "" : value.trim(), PersistedDataLimits.MAX_LABEL_CHARS);
        markShellChanged();
    }

    @Override public boolean hasBundledCable(String side) { return findBundledCable(side) != null; }

    @Override public int bundledSignal(String side, int channel) {
        BundledCableBlockEntity cable = findBundledCable(side);
        return cable == null || channel < 0 || channel >= BundledCableBlockEntity.CHANNELS ? -1 : cable.getSignal(channel);
    }

    @Override public int bundledOutput(String side, int channel) {
        BundledCableBlockEntity cable = findBundledCable(side);
        return cable == null || channel < 0 || channel >= BundledCableBlockEntity.CHANNELS ? -1 : cable.getLocalOutput(channel);
    }

    @Override public boolean setBundledOutput(String side, int channel, int strength) {
        BundledCableBlockEntity cable = findBundledCable(side);
        if (cable == null || channel < 0 || channel >= BundledCableBlockEntity.CHANNELS
                || strength < 0 || strength > 15) return false;
        cable.setLocalOutput(channel, strength);
        return true;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  ProgrammableLogicControllerBlockEntity plc) {
        if (!(level instanceof ServerLevel)) return;
        com.malice.terminalcraft.device.ServerDeviceManager.ensureRegistered(plc, plc.deviceId,
                plc.getDeviceAddress(), () -> new com.malice.terminalcraft.device.PlcDeviceEndpoint(
                        plc.deviceId, plc.getDeviceAddress(), plc, () -> !plc.isRemoved(), () -> !plc.isRemoved()));
        if (++plc.displayCountdown >= 4) {
            plc.displayCountdown = 0;
            com.malice.terminalcraft.blockentity.DisplayTransportRuntime.refreshDirect(plc);
        }
        if (!plc.controller.running()) return;
        int interval = plc.controller.program().scanIntervalTicks();
        if (++plc.scanCountdown < interval) return;
        plc.scanCountdown = 0;
        plc.clearAutomaticOutputs();
        PlcProgram.ScanResult result = plc.controller.scan(plc.io());
        if (!result.success()) {
            plc.clearAutomaticOutputs();
            plc.recordFault(result.fault());
        } else {
            plc.recordTrend();
        }
        plc.setChanged();
        if (!result.success()) plc.markShellChanged();
        if (++plc.telemetryCountdown >= 4) {
            plc.telemetryCountdown = 0;
            level.sendBlockUpdated(pos, state, state, 3);
        }
    }

    private PlcProgram.Io io() {
        return new PlcProgram.Io() {
            @Override public int read(PlcProgram.Binding binding) {
                Integer analogForced = forcedAnalogInputs.get(binding.name());
                if (analogForced != null) return analogForced;
                Boolean forced = forcedInputs.get(binding.name());
                if (forced != null) return forced ? 15 : 0;
                if (binding.kind() == PlcProgram.BindingKind.REDSTONE) return getRedstoneInput(binding.side());
                if (binding.kind() == PlcProgram.BindingKind.SENSOR) {
                    BlockEntity sensor = findSensor();
                    if (sensor instanceof SensorArrayBlockEntity array) return array.signal(binding.side());
                    if (sensor instanceof StandaloneSensorBlockEntity standalone) {
                        return "value".equalsIgnoreCase(binding.side()) ? standalone.signal() : -1;
                    }
                    return -1;
                }
                return bundledSignal(binding.side(), binding.channel());
            }
            @Override public boolean write(PlcProgram.Binding binding, int strength) {
                strength = forcedOutputs.getOrDefault(binding.name(), strength);
                if (binding.kind() == PlcProgram.BindingKind.REDSTONE) {
                    Direction direction = parseSide(binding.side());
                    if (direction == null) return false;
                    automaticRedstone.put(direction,
                            Math.max(automaticRedstone.getOrDefault(direction, 0), Math.max(0, Math.min(15, strength))));
                    notifyRedstoneChanged();
                    return true;
                }
                return setBundledOutput(binding.side(), binding.channel(), strength);
            }
        };
    }

    @Nullable private BlockEntity findSensor() {
        if (level == null) return null;
        for (Direction direction : Direction.values()) {
            BlockEntity entity = level.getBlockEntity(worldPosition.relative(direction));
            if (entity instanceof SensorArrayBlockEntity || entity instanceof StandaloneSensorBlockEntity) return entity;
        }
        return null;
    }

    private void recordTrend() {
        Map<String, Integer> analog = controller.analogValues();
        for (PlcProgram.Binding input : controller.program().inputs()) {
            int value = analog.getOrDefault(input.name(), dashboardSignals().getOrDefault(input.name(), false) ? 15 : 0);
            appendTrend(input.name(), value);
        }
        for (PlcProgram.Binding output : controller.program().outputs()) {
            int value = controller.program().analogOutputs().contains(output.name())
                    ? analog.getOrDefault(output.name(), 0)
                    : dashboardSignals().getOrDefault(output.name(), false) ? 15 : 0;
            appendTrend(output.name(), value);
        }
        for (Map.Entry<String, Integer> entry : controller.pidOutputs().entrySet()) {
            appendTrend("PID." + entry.getKey(), entry.getValue());
        }
    }

    private void appendTrend(String name, int value) {
        List<Integer> values = trendHistory.computeIfAbsent(name, ignored -> new ArrayList<>());
        values.add(Math.max(0, Math.min(15, value)));
        while (values.size() > 64) values.remove(0);
    }

    private void clearAutomaticOutputs() {
        boolean changed = automaticRedstone.values().stream().anyMatch(value -> value != 0);
        for (Direction direction : Direction.values()) automaticRedstone.put(direction, 0);
        for (PlcProgram.Binding binding : controller.program().outputs()) {
            if (binding.kind() == PlcProgram.BindingKind.BUNDLED) {
                BundledCableBlockEntity cable = findBundledCable(binding.side());
                if (cable != null && cable.getLocalOutput(binding.channel()) != 0) {
                    cable.setLocalOutput(binding.channel(), 0);
                    changed = true;
                }
            }
        }
        if (changed) notifyRedstoneChanged();
    }

    private static String normalizeSignal(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z_][A-Z0-9_.]*") ? normalized : null;
    }

    private void recordFault(String fault) {
        String safe = bound(fault);
        if (safe.isBlank()) return;
        alarmLatched = true;
        if (faultHistory.isEmpty() || !faultHistory.get(faultHistory.size() - 1).equals(safe)) {
            faultHistory.add(safe);
            while (faultHistory.size() > 8) faultHistory.remove(0);
        }
    }

    private List<Integer> redstoneValues() {
        List<Integer> values = new ArrayList<>();
        for (Direction direction : Direction.values()) values.add(getRedstoneOutput(direction.getName()));
        return values;
    }

    @Nullable private BundledCableBlockEntity findBundledCable(String side) {
        if (level == null) return null;
        if ("all".equalsIgnoreCase(side)) {
            for (Direction direction : Direction.values()) {
                BlockEntity entity = level.getBlockEntity(worldPosition.relative(direction));
                if (entity instanceof BundledCableBlockEntity cable) return cable;
            }
            return null;
        }
        Direction direction = parseSide(side);
        if (direction == null) return null;
        BlockEntity entity = level.getBlockEntity(worldPosition.relative(direction));
        return entity instanceof BundledCableBlockEntity cable ? cable : null;
    }

    @Nullable private Direction parseSide(String side) {
        if (side == null || side.isBlank()) return null;
        return switch (side.toLowerCase(Locale.ROOT)) {
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
        return getBlockState().hasProperty(ProgrammableLogicControllerBlock.FACING)
                ? getBlockState().getValue(ProgrammableLogicControllerBlock.FACING) : Direction.NORTH;
    }

    private static String sideName(Direction direction) {
        return switch (direction) { case UP -> "top"; case DOWN -> "bottom"; default -> direction.getName(); };
    }

    private void notifyRedstoneChanged() {
        setChanged();
        if (level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.updateNeighborsAt(worldPosition, state.getBlock());
            for (Direction direction : Direction.values()) level.updateNeighborsAt(worldPosition.relative(direction), state.getBlock());
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    @Override public void markShellChanged() {
        setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    private static String bound(String value) {
        return PersistedDataLimits.truncate(value == null ? "PLC fault" : value, MAX_PROGRAM_STATUS_CHARS);
    }

    @Override protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        PersistedDataVersions.stampCurrent(tag);
        DeviceIdentity.save(tag, deviceId);
        tag.putString("Label", label);
        tag.putString("Program", PersistedDataLimits.truncate(programSource, PlcProgram.MAX_SOURCE_CHARS));
        tag.put("Shell", shell.save());
        tag.putBoolean("Running", controller.running());
        tag.putBoolean("AlarmLatched", alarmLatched);
        tag.putInt("DashboardPage", dashboardPage);
        if (ownerId != null) tag.putUUID("Owner", ownerId);
        CompoundTag manual = new CompoundTag();
        for (Map.Entry<Direction, Integer> entry : manualRedstone.entrySet()) manual.putInt(entry.getKey().getName(), entry.getValue());
        tag.put("ManualRedstone", manual);
        CompoundTag forced = new CompoundTag();
        forcedInputs.forEach(forced::putBoolean);
        tag.put("ForcedInputs", forced);
        CompoundTag analogForced = new CompoundTag();
        forcedAnalogInputs.forEach(analogForced::putInt);
        tag.put("ForcedAnalogInputs", analogForced);
        CompoundTag outputs = new CompoundTag();
        forcedOutputs.forEach(outputs::putInt);
        tag.put("ForcedOutputs", outputs);
        CompoundTag trend = new CompoundTag();
        trendHistory.forEach((name, values) -> {
            net.minecraft.nbt.IntArrayTag series = new net.minecraft.nbt.IntArrayTag(
                    values.stream().mapToInt(Integer::intValue).toArray());
            trend.put(name, series);
        });
        tag.put("Trend", trend);
        CompoundTag slots = new CompoundTag();
        programSlots.forEach((slot, source) -> slots.putString(Integer.toString(slot),
                PersistedDataLimits.truncate(source, PlcProgram.MAX_SOURCE_CHARS)));
        tag.put("ProgramSlots", slots);
        net.minecraft.nbt.ListTag faults = new net.minecraft.nbt.ListTag();
        faultHistory.stream().map(net.minecraft.nbt.StringTag::valueOf).forEach(faults::add);
        tag.put("FaultHistory", faults);
    }

    @Override public void load(CompoundTag tag) {
        super.load(tag);
        deviceId = DeviceIdentity.loadOrRetain(tag, deviceId);
        label = PersistedDataLimits.readString(tag, "Label", PersistedDataLimits.MAX_LABEL_CHARS, "plc");
        ownerId = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        String source = PersistedDataLimits.readString(tag, "Program", PlcProgram.MAX_SOURCE_CHARS, "");
        if (tag.contains("Shell", Tag.TAG_COMPOUND)) shell.load(tag.getCompound("Shell"));
        PlcProgram.CompileResult result = PlcProgram.compile(source);
        if (result.successful()) {
            programSource = result.program().source();
            compileError = "";
            controller.load(result.program());
            if (tag.getBoolean("Running")) controller.start();
        } else {
            programSource = source;
            compileError = bound(result.error());
            controller.load(null);
        }
        if (tag.contains("ManualRedstone", Tag.TAG_COMPOUND)) {
            CompoundTag manual = tag.getCompound("ManualRedstone");
            for (Direction direction : Direction.values()) {
                if (manual.contains(direction.getName(), Tag.TAG_INT)) manualRedstone.put(direction, Math.max(0, Math.min(15, manual.getInt(direction.getName()))));
            }
        }
        forcedInputs.clear();
        if (tag.contains("ForcedInputs", Tag.TAG_COMPOUND)) {
            CompoundTag forced = tag.getCompound("ForcedInputs");
            for (String key : forced.getAllKeys()) forceInput(key, forced.getBoolean(key));
        }
        forcedAnalogInputs.clear();
        if (tag.contains("ForcedAnalogInputs", Tag.TAG_COMPOUND)) {
            CompoundTag forced = tag.getCompound("ForcedAnalogInputs");
            for (String key : forced.getAllKeys()) if (forced.contains(key, Tag.TAG_INT)) forceAnalogInput(key, forced.getInt(key));
        }
        forcedOutputs.clear();
        if (tag.contains("ForcedOutputs", Tag.TAG_COMPOUND)) {
            CompoundTag outputs = tag.getCompound("ForcedOutputs");
            for (String key : outputs.getAllKeys()) if (outputs.contains(key, Tag.TAG_INT)) forceOutput(key, outputs.getInt(key));
        }
        alarmLatched = tag.getBoolean("AlarmLatched");
        dashboardPage = Math.floorMod(tag.getInt("DashboardPage"), 4);
        trendHistory.clear();
        if (tag.contains("Trend", Tag.TAG_COMPOUND)) {
            CompoundTag trend = tag.getCompound("Trend");
            for (String key : trend.getAllKeys()) {
                if (!trend.contains(key, Tag.TAG_INT_ARRAY)) continue;
                int[] values = trend.getIntArray(key);
                List<Integer> bounded = new ArrayList<>();
                for (int index = Math.max(0, values.length - 64); index < values.length; index++) {
                    bounded.add(Math.max(0, Math.min(15, values[index])));
                }
                trendHistory.put(key, bounded);
            }
        }
        faultHistory.clear();
        if (tag.contains("FaultHistory", Tag.TAG_LIST)) {
            net.minecraft.nbt.ListTag faults = tag.getList("FaultHistory", Tag.TAG_STRING);
            for (int index = Math.max(0, faults.size() - 8); index < faults.size(); index++) faultHistory.add(faults.getString(index));
        }
        if (!compileError.isEmpty()) recordFault(compileError);
        programSlots.clear();
        if (tag.contains("ProgramSlots", Tag.TAG_COMPOUND)) {
            CompoundTag slots = tag.getCompound("ProgramSlots");
            for (String key : slots.getAllKeys()) {
                try {
                    int slot = Integer.parseInt(key);
                    if (slot >= 0 && slot <= 3) programSlots.put(slot,
                            PersistedDataLimits.readString(slots, key, PlcProgram.MAX_SOURCE_CHARS, ""));
                } catch (NumberFormatException ignored) { }
            }
        }
        clearAutomaticOutputs();
        shell.setHost(this);
    }

    @Override public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        saveAdditional(tag);
        if (tag.contains("Shell", CompoundTag.TAG_COMPOUND)
                && !com.malice.terminalcraft.network.ShellSyncPolicy.isAdmissible(tag.getCompound("Shell"))) tag.remove("Shell");
        return tag;
    }

    @Nullable @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) {
        CompoundTag tag = packet.getTag();
        if (tag != null) load(tag);
    }

    @Override public void setRemoved() {
        clearAutomaticOutputs();
        com.malice.terminalcraft.device.ServerDeviceManager.invalidate(this);
        if (level instanceof ServerLevel serverLevel) TerminalChunkLoader.terminalRemoved(serverLevel, deviceId);
        super.setRemoved();
    }
}
