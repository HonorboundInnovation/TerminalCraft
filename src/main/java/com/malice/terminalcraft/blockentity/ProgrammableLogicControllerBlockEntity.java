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
import com.malice.terminalcraft.sensor.SensorNetworkResolver;
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
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Server-authoritative programmable logic controller with bounded scan-cycle execution. */
public class ProgrammableLogicControllerBlockEntity extends BlockEntity
        implements MenuProvider, TerminalHost, ShellComputer {
    private static final int MAX_PROGRAM_STATUS_CHARS = 256;
    private static final int TELEMETRY_INTERVAL_TICKS = 20;
    private static final String CLIENT_SYNC_TAG = "TerminalCraftClientSync";

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

    private record BundledTarget(BundledCableBlockEntity cable, int channel) {}
    private record OutputResolution(Map<PlcProgram.Binding, Direction> redstone,
                                    Map<PlcProgram.Binding, BundledTarget> bundled,
                                    String fault) {
        boolean valid() { return fault == null || fault.isEmpty(); }
    }

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
        telemetryCountdown = 0;
        clearAutomaticOutputs();
        markShellChanged();
        return true;
    }

    public void start() {
        if (compileError.isEmpty()) {
            controller.start();
            scanCountdown = 0;
            telemetryCountdown = 0;
            markShellChanged();
        }
    }

    public void stop() {
        controller.stop();
        scanCountdown = 0;
        telemetryCountdown = 0;
        clearAutomaticOutputs();
        markShellChanged();
    }

    public void resetController() {
        controller.resetState();
        scanCountdown = 0;
        telemetryCountdown = 0;
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
            boolean changed = false;
            for (Direction direction : Direction.values()) {
                if (manualRedstone.getOrDefault(direction, 0) != bounded) {
                    manualRedstone.put(direction, bounded);
                    changed = true;
                }
            }
            if (changed) notifyRedstoneChanged();
            return true;
        }
        Direction direction = parseSide(side);
        if (direction == null) return false;
        if (manualRedstone.getOrDefault(direction, 0) == bounded) return true;
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

    @Override public int bundledInput(String side, int channel) {
        BundledCableBlockEntity cable = findBundledCable(side);
        return cable == null || channel < 0 || channel >= BundledCableBlockEntity.CHANNELS
                ? -1 : cable.getExternalInput(channel);
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
        if (!(level instanceof ServerLevel serverLevel)
                || plc.isRemoved()
                || com.malice.terminalcraft.TerminalCraftMod.isStopping(serverLevel.getServer())
                || !serverLevel.getServer().isSameThread()
                || serverLevel.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null
                || serverLevel.getBlockEntity(pos) != plc) return;
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

        // Resolve every world target before advancing timers, counters, or latches. A missing or
        // unloaded target therefore cannot leave a half-applied scan in the Minecraft world.
        OutputResolution targets = plc.resolveOutputTargets();
        if (!targets.valid()) {
            PlcProgram.ScanResult failed = plc.controller.stopWithFault(targets.fault());
            plc.commitOutputs(Map.of(), targets, true);
            plc.recordFault(failed.fault());
            plc.telemetryCountdown = 0;
            plc.markShellChanged();
            return;
        }

        PlcProgram.ScanResult result = plc.controller.evaluate(plc.inputIo());
        if (!result.success()) {
            plc.commitOutputs(Map.of(), targets, true);
            plc.recordFault(result.fault());
        } else if (!plc.commitOutputs(result.desiredOutputs(), targets, false)) {
            PlcProgram.ScanResult failed = plc.controller.stopWithFault("output commit failed");
            plc.commitOutputs(Map.of(), targets, true);
            plc.recordFault(failed.fault());
        }

        if (result.success() && plc.controller.running()) {
            plc.telemetryCountdown += interval;
            if (plc.telemetryCountdown >= TELEMETRY_INTERVAL_TICKS) {
                plc.telemetryCountdown %= TELEMETRY_INTERVAL_TICKS;
                // Trend history is runtime diagnostics, not world-save data. Sampling it does not
                // dirty the chunk or broadcast a block-entity packet.
                plc.recordTrend();
            }
        } else {
            plc.telemetryCountdown = 0;
            plc.markShellChanged();
        }
    }

    private PlcProgram.Io inputIo() {
        return new PlcProgram.Io() {
            @Override public int read(PlcProgram.Binding binding) {
                Integer analogForced = forcedAnalogInputs.get(binding.name());
                if (analogForced != null) return analogForced;
                Boolean forced = forcedInputs.get(binding.name());
                if (forced != null) return forced ? 15 : 0;
                if (binding.kind() == PlcProgram.BindingKind.REDSTONE) return getRedstoneInput(binding.side());
                if (binding.kind() == PlcProgram.BindingKind.SENSOR) {
                    SensorBinding sensorBinding = resolveSensorBinding(binding.side());
                    BlockEntity sensor = sensorBinding.sensor();
                    if (sensor instanceof SensorArrayBlockEntity array) return array.signal(sensorBinding.channel());
                    if (sensor instanceof StandaloneSensorBlockEntity standalone) {
                        return "value".equalsIgnoreCase(sensorBinding.channel()) ? standalone.signal() : -1;
                    }
                    return -1;
                }
                return bundledInput(binding.side(), binding.channel());
            }
            @Override public double readAnalog(PlcProgram.Binding binding) {
                Integer analogForced = forcedAnalogInputs.get(binding.name());
                if (analogForced != null) return analogForced;
                Boolean forced = forcedInputs.get(binding.name());
                if (forced != null) return forced ? 15.0 : 0.0;
                if (binding.kind() != PlcProgram.BindingKind.SENSOR) return read(binding);
                SensorBinding sensorBinding = resolveSensorBinding(binding.side());
                BlockEntity sensor = sensorBinding.sensor();
                if (sensor instanceof SensorArrayBlockEntity array) return array.numericValue(sensorBinding.channel());
                if (sensor instanceof StandaloneSensorBlockEntity standalone
                        && "value".equalsIgnoreCase(sensorBinding.channel())) return standalone.numericValue();
                return -1;
            }
            @Override public boolean write(PlcProgram.Binding binding, int strength) {
                throw new UnsupportedOperationException("PLC evaluation cannot write Minecraft I/O");
            }
        };
    }

    private OutputResolution resolveOutputTargets() {
        Map<PlcProgram.Binding, Direction> redstone = new LinkedHashMap<>();
        Map<PlcProgram.Binding, BundledTarget> bundled = new LinkedHashMap<>();
        for (PlcProgram.Binding binding : controller.program().outputs()) {
            if (binding.kind() == PlcProgram.BindingKind.REDSTONE) {
                Direction direction = parseSide(binding.side());
                if (direction == null) return new OutputResolution(redstone, bundled,
                        "output unavailable: " + binding.name());
                redstone.put(binding, direction);
                continue;
            }
            if (binding.kind() != PlcProgram.BindingKind.BUNDLED
                    || binding.channel() < 0 || binding.channel() >= BundledCableBlockEntity.CHANNELS) {
                return new OutputResolution(redstone, bundled, "output unavailable: " + binding.name());
            }
            BundledCableBlockEntity cable = findBundledCable(binding.side());
            if (cable == null || cable.isRemoved()) return new OutputResolution(redstone, bundled,
                    "output unavailable: " + binding.name());
            bundled.put(binding, new BundledTarget(cable, binding.channel()));
        }
        return new OutputResolution(Map.copyOf(redstone), Map.copyOf(bundled), "");
    }

    /** Commits one complete scan snapshot. No target lookup or program evaluation occurs here. */
    private boolean commitOutputs(Map<PlcProgram.Binding, Integer> desired,
                                  OutputResolution targets, boolean failSafe) {
        if (level == null || level.isClientSide || isRemoved()) return false;
        EnumMap<Direction, Integer> nextRedstone = new EnumMap<>(Direction.class);
        for (Direction direction : Direction.values()) nextRedstone.put(direction, 0);
        Map<BundledCableBlockEntity, Map<Integer, Integer>> cableUpdates = new IdentityHashMap<>();

        for (Map.Entry<PlcProgram.Binding, Direction> entry : targets.redstone().entrySet()) {
            int strength = failSafe ? 0 : forcedOutputs.getOrDefault(entry.getKey().name(),
                    desired.getOrDefault(entry.getKey(), 0));
            nextRedstone.merge(entry.getValue(), Math.max(0, Math.min(15, strength)), Math::max);
        }
        for (Map.Entry<PlcProgram.Binding, BundledTarget> entry : targets.bundled().entrySet()) {
            BundledTarget target = entry.getValue();
            int strength = failSafe ? 0 : forcedOutputs.getOrDefault(entry.getKey().name(),
                    desired.getOrDefault(entry.getKey(), 0));
            cableUpdates.computeIfAbsent(target.cable(), ignored -> new LinkedHashMap<>())
                    .merge(target.channel(), Math.max(0, Math.min(15, strength)), Math::max);
        }

        try {
            for (Map.Entry<BundledCableBlockEntity, Map<Integer, Integer>> update : cableUpdates.entrySet()) {
                if (update.getKey().isRemoved()) return false;
                update.getKey().setLocalOutputs(update.getValue());
            }
            if (!automaticRedstone.equals(nextRedstone)) {
                automaticRedstone.clear();
                automaticRedstone.putAll(nextRedstone);
                notifyRedstoneChanged();
            }
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @Nullable private BlockEntity findSensor() {
        if (level == null) return null;
        for (Direction direction : Direction.values()) {
            BlockEntity entity = loadedBlockEntity(worldPosition.relative(direction));
            if (entity instanceof SensorArrayBlockEntity || entity instanceof StandaloneSensorBlockEntity) return entity;
        }
        return null;
    }

    private record SensorBinding(BlockEntity sensor, String channel) {}

    private SensorBinding resolveSensorBinding(String encoded) {
        int separator = encoded == null ? -1 : encoded.indexOf('/');
        if (separator < 1 || separator == encoded.length() - 1) {
            BlockEntity sensor = findSensor();
            if (sensor == null && level instanceof ServerLevel serverLevel) {
                sensor = SensorNetworkResolver.resolveSingle(serverLevel, worldPosition);
            }
            return new SensorBinding(sensor, encoded == null ? "" : encoded);
        }
        BlockEntity sensor = level instanceof ServerLevel serverLevel
                ? SensorNetworkResolver.resolve(serverLevel, worldPosition, encoded.substring(0, separator)) : null;
        return new SensorBinding(sensor, encoded.substring(separator + 1));
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
        if (level == null || level.isClientSide || isRemoved()
                || !(level instanceof ServerLevel serverLevel)
                || com.malice.terminalcraft.TerminalCraftMod.isStopping(serverLevel.getServer())) {
            for (Direction direction : Direction.values()) automaticRedstone.put(direction, 0);
            return;
        }
        OutputResolution targets = resolveOutputTargets();
        commitOutputs(Map.of(), targets, true);
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
        if (side == null || side.isBlank() || "all".equalsIgnoreCase(side) || "any".equalsIgnoreCase(side)) {
            for (Direction direction : Direction.values()) {
                BlockEntity entity = loadedBlockEntity(worldPosition.relative(direction));
                if (entity instanceof BundledCableBlockEntity cable) return cable;
            }
            return null;
        }
        Direction direction = parseSide(side);
        if (direction == null) return null;
        BlockEntity entity = loadedBlockEntity(worldPosition.relative(direction));
        return entity instanceof BundledCableBlockEntity cable ? cable : null;
    }

    /** Looks up an adjacent target without ever asking Minecraft to load its chunk. */
    @Nullable private BlockEntity loadedBlockEntity(BlockPos position) {
        if (level == null) return null;
        if (level instanceof ServerLevel serverLevel
                && serverLevel.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4) == null) {
            return null;
        }
        return level.getBlockEntity(position);
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
        saveControllerState(tag, true);
    }

    /** Writes a detached NBT snapshot; packet snapshots intentionally omit the complete shell. */
    private void saveControllerState(CompoundTag tag, boolean includeShell) {
        PersistedDataVersions.stampCurrent(tag);
        DeviceIdentity.save(tag, deviceId);
        tag.putString("Label", label);
        tag.putString("Program", PersistedDataLimits.truncate(programSource, PlcProgram.MAX_SOURCE_CHARS));
        if (includeShell) tag.put("Shell", shell.save());
        tag.putBoolean("Running", controller.running());
        tag.putBoolean("AlarmLatched", alarmLatched);
        tag.putInt("DashboardPage", dashboardPage);
        tag.put("Runtime", saveRuntimeState());
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
        // Trend samples are transient diagnostics. Persisting them dirties and enlarges the chunk
        // even though they do not affect PLC control behavior.
        CompoundTag slots = new CompoundTag();
        programSlots.forEach((slot, source) -> slots.putString(Integer.toString(slot),
                PersistedDataLimits.truncate(source, PlcProgram.MAX_SOURCE_CHARS)));
        tag.put("ProgramSlots", slots);
        net.minecraft.nbt.ListTag faults = new net.minecraft.nbt.ListTag();
        faultHistory.stream().map(net.minecraft.nbt.StringTag::valueOf).forEach(faults::add);
        tag.put("FaultHistory", faults);
    }

    private CompoundTag saveRuntimeState() {
        PlcProgram.RuntimeState state = controller.runtimeState();
        CompoundTag runtime = new CompoundTag();
        runtime.putLong("ScanCount", state.scanCount());
        runtime.put("Timers", saveIntMap(state.timerElapsed()));
        runtime.put("Counters", saveIntMap(state.counterValues()));
        runtime.put("CounterPrevious", saveBooleanMap(state.counterPrevious()));
        runtime.put("Latches", saveBooleanMap(state.latchValues()));
        runtime.put("PidIntegral", saveDoubleMap(state.pidIntegral()));
        runtime.put("PidPreviousError", saveDoubleMap(state.pidPreviousError()));
        return runtime;
    }

    private PlcProgram.RuntimeState readRuntimeState(CompoundTag runtime) {
        Map<String, Integer> timers = new LinkedHashMap<>();
        Map<String, Integer> counters = new LinkedHashMap<>();
        Map<String, Boolean> counterPrevious = new LinkedHashMap<>();
        Map<String, Boolean> latches = new LinkedHashMap<>();
        Map<String, Double> pidIntegral = new LinkedHashMap<>();
        Map<String, Double> pidPreviousError = new LinkedHashMap<>();
        CompoundTag timerTag = runtime.getCompound("Timers");
        for (PlcProgram.Timer timer : controller.program().timers()) {
            if (timerTag.contains(timer.name(), Tag.TAG_INT)) timers.put(timer.name(), timerTag.getInt(timer.name()));
        }
        CompoundTag counterTag = runtime.getCompound("Counters");
        CompoundTag previousTag = runtime.getCompound("CounterPrevious");
        for (PlcProgram.Counter counter : controller.program().counters()) {
            if (counterTag.contains(counter.name(), Tag.TAG_INT)) counters.put(counter.name(), counterTag.getInt(counter.name()));
            if (previousTag.contains(counter.name(), Tag.TAG_BYTE)) {
                counterPrevious.put(counter.name(), previousTag.getBoolean(counter.name()));
            }
        }
        CompoundTag latchTag = runtime.getCompound("Latches");
        for (PlcProgram.Latch latch : controller.program().latches()) {
            if (latchTag.contains(latch.name(), Tag.TAG_BYTE)) latches.put(latch.name(), latchTag.getBoolean(latch.name()));
        }
        CompoundTag integralTag = runtime.getCompound("PidIntegral");
        CompoundTag errorTag = runtime.getCompound("PidPreviousError");
        for (PlcProgram.PidLoop pid : controller.program().pidLoops()) {
            if (integralTag.contains(pid.name(), Tag.TAG_DOUBLE)) pidIntegral.put(pid.name(), integralTag.getDouble(pid.name()));
            if (errorTag.contains(pid.name(), Tag.TAG_DOUBLE)) pidPreviousError.put(pid.name(), errorTag.getDouble(pid.name()));
        }
        return new PlcProgram.RuntimeState(timers, counters, counterPrevious, latches,
                pidIntegral, pidPreviousError, Math.max(0, runtime.getLong("ScanCount")));
    }

    private static CompoundTag saveIntMap(Map<String, Integer> values) {
        CompoundTag tag = new CompoundTag();
        values.forEach(tag::putInt);
        return tag;
    }

    private static CompoundTag saveBooleanMap(Map<String, Boolean> values) {
        CompoundTag tag = new CompoundTag();
        values.forEach(tag::putBoolean);
        return tag;
    }

    private static CompoundTag saveDoubleMap(Map<String, Double> values) {
        CompoundTag tag = new CompoundTag();
        values.forEach(tag::putDouble);
        return tag;
    }

    @Override public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.getBoolean(CLIENT_SYNC_TAG)) {
            loadClientState(tag);
            return;
        }
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
            if (tag.contains("Runtime", Tag.TAG_COMPOUND)) {
                controller.restoreRuntimeState(readRuntimeState(tag.getCompound("Runtime")));
            }
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
            for (String key : forced.getAllKeys()) {
                String normalized = normalizeSignal(key);
                if (normalized != null && controller.program().inputs().stream()
                        .anyMatch(binding -> binding.name().equals(normalized))) {
                    forcedInputs.put(normalized, forced.getBoolean(key));
                }
            }
        }
        forcedAnalogInputs.clear();
        if (tag.contains("ForcedAnalogInputs", Tag.TAG_COMPOUND)) {
            CompoundTag forced = tag.getCompound("ForcedAnalogInputs");
            for (String key : forced.getAllKeys()) {
                String normalized = normalizeSignal(key);
                if (normalized != null && forced.contains(key, Tag.TAG_INT)
                        && controller.program().analogInputs().contains(normalized)) {
                    forcedAnalogInputs.put(normalized, Math.max(0, Math.min(15, forced.getInt(key))));
                }
            }
        }
        forcedOutputs.clear();
        if (tag.contains("ForcedOutputs", Tag.TAG_COMPOUND)) {
            CompoundTag outputs = tag.getCompound("ForcedOutputs");
            for (String key : outputs.getAllKeys()) {
                String normalized = normalizeSignal(key);
                if (normalized != null && outputs.contains(key, Tag.TAG_INT)
                        && controller.program().outputs().stream()
                        .anyMatch(binding -> binding.name().equals(normalized))) {
                    forcedOutputs.put(normalized, Math.max(0, Math.min(15, outputs.getInt(key))));
                }
            }
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
        scanCountdown = 0;
        displayCountdown = 0;
        telemetryCountdown = 0;
        for (Direction direction : Direction.values()) automaticRedstone.put(direction, 0);
        shell.setHost(this);
    }

    @Override public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        tag.remove("Shell");
        tag.remove("ForcedInputs");
        tag.remove("ForcedAnalogInputs");
        tag.remove("ForcedOutputs");
        tag.remove("ProgramSlots");
        tag.remove("FaultHistory");
        tag.remove("Trend");
        tag.remove("Runtime");
        tag.putBoolean(CLIENT_SYNC_TAG, true);
        return tag;
    }

    /** Applies the small rendering/status snapshot without invoking disk-load lifecycle work. */
    private void loadClientState(CompoundTag tag) {
        label = PersistedDataLimits.readString(tag, "Label", PersistedDataLimits.MAX_LABEL_CHARS, "plc");
        String source = PersistedDataLimits.readString(tag, "Program", PlcProgram.MAX_SOURCE_CHARS, "");
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
        alarmLatched = tag.getBoolean("AlarmLatched");
        dashboardPage = Math.floorMod(tag.getInt("DashboardPage"), 4);
        shell.setHost(this);
    }

    @Nullable @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) {
        CompoundTag tag = packet.getTag();
        if (tag != null) loadClientState(tag);
    }

    /** Called only for an actual block replacement, never for routine chunk unloading. */
    public void onBlockRemoved() {
        stop();
        if (level instanceof ServerLevel serverLevel
                && !com.malice.terminalcraft.TerminalCraftMod.isStopping(serverLevel.getServer())) {
            TerminalChunkLoader.terminalRemoved(serverLevel, deviceId);
        }
    }

    @Override public void setRemoved() {
        com.malice.terminalcraft.device.ServerDeviceManager.invalidate(this);
        super.setRemoved();
    }
}
