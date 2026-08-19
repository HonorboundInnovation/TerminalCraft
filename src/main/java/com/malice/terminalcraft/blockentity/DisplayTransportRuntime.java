package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.block.VideoCableBlock;
import com.malice.terminalcraft.device.TerminalBuffer;
import com.malice.terminalcraft.shell.ShellComputer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Bounded server-side transport for wireless display links and routed video-cable components.
 * Display payloads never execute code: they are copied as character cells and palette indexes.
 */
public final class DisplayTransportRuntime {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_VIDEO_COMPONENT_NODES = 2048;
    private static final long UPDATE_INTERVAL_TICKS = 4;
    private static final Map<MinecraftServer, Scope> SCOPES = new WeakHashMap<>();

    private DisplayTransportRuntime() {}

    public static void register(WirelessDisplayLinkBlockEntity link) {
        if (link.getLevel() instanceof ServerLevel level) scope(level).links.add(link);
    }

    public static void register(VideoCableBlockEntity cable) {
        if (cable.getLevel() instanceof ServerLevel level) scope(level).cables.add(cable);
    }

    public static void unregister(WirelessDisplayLinkBlockEntity link) {
        Scope scope = scopeIfPresent(link.getLevel());
        if (scope != null) scope.links.remove(link);
    }

    public static void unregister(VideoCableBlockEntity cable) {
        Scope scope = scopeIfPresent(cable.getLevel());
        if (scope != null) scope.cables.remove(cable);
    }

    public static void tick(MinecraftServer server) {
        Scope scope = SCOPES.get(server);
        if (scope == null || ++scope.tick < UPDATE_INTERVAL_TICKS) return;
        scope.tick = 0;
        scope.links.removeIf(link -> !(link.getLevel() instanceof ServerLevel) || link.isRemoved());
        scope.cables.removeIf(cable -> !(cable.getLevel() instanceof ServerLevel) || cable.isRemoved());
        scope.displaySources.entrySet().removeIf(entry -> entry.getKey().isRemoved()
                || entry.getKey().getLevel() == null
                || entry.getValue() instanceof BlockEntity entity && entity.isRemoved());

        refreshWireless(scope);
        refreshVideo(scope);
        scope.refreshes++;
    }

    /** Direct PLC-to-monitor attachment has no transport block and is refreshed here. */
    public static void refreshDirect(ProgrammableLogicControllerBlockEntity plc) {
        if (!(plc.getLevel() instanceof ServerLevel level)) return;
        for (Direction direction : Direction.values()) {
            if (level.getBlockEntity(plc.getBlockPos().relative(direction)) instanceof MonitorBlockEntity monitor) {
                render(plc, monitor);
            }
        }
    }

    /** Routes a monitor touch event back to a PLC dashboard when a display path owns the wall. */
    public static boolean handleTouch(MonitorBlockEntity touched, int x, int y, Player player) {
        if (!(touched.getLevel() instanceof ServerLevel level)) return false;
        MonitorGroupDevice.Group group = MonitorGroupDevice.discover(touched);
        Scope scope = scope(level);
        ShellComputer renderedSource = scope.displaySources.get(group.anchor());
        if (renderedSource instanceof ProgrammableLogicControllerBlockEntity plc
                && PlcDashboard.handleTouch(plc, x, y, player)) return true;
        for (MonitorBlockEntity tile : group.tiles()) {
            for (Direction direction : Direction.values()) {
                BlockPos adjacent = tile.getBlockPos().relative(direction);
                BlockEntity entity = level.getBlockEntity(adjacent);
                if (entity instanceof ProgrammableLogicControllerBlockEntity plc
                        && PlcDashboard.handleTouch(plc, x, y, player)) return true;
                if (entity instanceof WirelessDisplayLinkBlockEntity link && !link.isSource()) {
                    ShellComputer source = sourceForLink(level.getServer(), link);
                    if (source instanceof ProgrammableLogicControllerBlockEntity plc
                            && PlcDashboard.handleTouch(plc, x, y, player)) return true;
                }
                if (entity instanceof VideoCableBlockEntity cable) {
                    ShellComputer source = sourceForCable(level, cable.getBlockPos());
                    if (source instanceof ProgrammableLogicControllerBlockEntity plc
                            && PlcDashboard.handleTouch(plc, x, y, player)) return true;
                }
            }
        }
        return false;
    }

    private static void refreshWireless(Scope scope) {
        Map<String, List<WirelessDisplayLinkBlockEntity>> byChannel = new LinkedHashMap<>();
        for (WirelessDisplayLinkBlockEntity link : scope.links) {
            if (!link.channel().isEmpty()) byChannel.computeIfAbsent(link.channel(), ignored -> new ArrayList<>()).add(link);
        }
        for (List<WirelessDisplayLinkBlockEntity> links : byChannel.values()) {
            links.sort(Comparator.comparing(link -> link.getBlockPos().asLong()));
            for (WirelessDisplayLinkBlockEntity sink : links) {
                if (sink.isSource() || sink.attachedMonitor() == null) continue;
                ShellComputer source = null;
                for (WirelessDisplayLinkBlockEntity candidate : links) {
                    if (!candidate.isSource() || candidate.getLevel() != sink.getLevel()) continue;
                    source = candidate.attachedHost();
                    if (source != null) break;
                }
                if (source != null) render(source, sink.attachedMonitor());
            }
        }
    }

    private static void refreshVideo(Scope scope) {
        Set<BlockPos> processed = new java.util.HashSet<>();
        for (VideoCableBlockEntity cable : List.copyOf(scope.cables)) {
            if (!(cable.getLevel() instanceof ServerLevel level) || !processed.add(cable.getBlockPos())) continue;
            VideoComponent component = videoComponent(level, cable.getBlockPos());
            processed.addAll(component.cables());
            if (component.source() == null) continue;
            for (MonitorBlockEntity monitor : component.monitors()) render(component.source(), monitor);
        }
    }

    private static VideoComponent videoComponent(ServerLevel level, BlockPos start) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> visited = new java.util.HashSet<>();
        List<MonitorBlockEntity> monitors = new ArrayList<>();
        List<ShellComputer> sources = new ArrayList<>();
        queue.add(start.immutable());
        while (!queue.isEmpty() && visited.size() < MAX_VIDEO_COMPONENT_NODES) {
            BlockPos current = queue.removeFirst();
            if (!visited.add(current)) continue;
            if (!(level.getBlockState(current).getBlock() instanceof VideoCableBlock)) continue;
            for (Direction direction : Direction.values()) {
                BlockPos adjacent = current.relative(direction);
                BlockEntity entity = level.getBlockEntity(adjacent);
                if (entity instanceof VideoCableBlockEntity) queue.addLast(adjacent.immutable());
                else if (entity instanceof MonitorBlockEntity monitor) monitors.add(monitor);
                else if (entity instanceof ShellComputer source) sources.add(source);
            }
        }
        sources.sort(Comparator.comparing(source -> source.getBlockPos().asLong()));
        return new VideoComponent(Set.copyOf(visited), List.copyOf(monitors), sources.isEmpty() ? null : sources.get(0));
    }

    private static ShellComputer sourceForCable(ServerLevel level, BlockPos start) {
        return videoComponent(level, start).source();
    }

    private static ShellComputer sourceForLink(MinecraftServer server, WirelessDisplayLinkBlockEntity sink) {
        Scope scope = SCOPES.get(server);
        if (scope == null) return null;
        List<WirelessDisplayLinkBlockEntity> candidates = new ArrayList<>();
        for (WirelessDisplayLinkBlockEntity link : scope.links) {
            if (link.isSource() && link.channel().equals(sink.channel()) && link.getLevel() == sink.getLevel()) candidates.add(link);
        }
        candidates.sort(Comparator.comparing(link -> link.getBlockPos().asLong()));
        return candidates.isEmpty() ? null : candidates.get(0).attachedHost();
    }

    private static void render(ShellComputer source, MonitorBlockEntity monitor) {
        try {
            if (monitor.getLevel() instanceof ServerLevel level) {
                scope(level).displaySources.put(MonitorGroupDevice.discover(monitor).anchor(), source);
            }
            if (source instanceof ProgrammableLogicControllerBlockEntity plc) PlcDashboard.render(plc, monitor);
            else renderSurface(source.terminalSurface(), monitor);
            if (monitor.getLevel() instanceof ServerLevel level) scope(level).frames++;
        } catch (RuntimeException failure) {
            if (monitor.getLevel() instanceof ServerLevel level) scope(level).renderErrors++;
            LOGGER.debug("Display transport frame rejected for {} -> {}", source.getBlockPos(), monitor.getBlockPos(), failure);
        }
    }

    private static void renderSurface(TerminalBuffer source, MonitorBlockEntity target) {
        if (source == null) return;
        MonitorGroupDevice.Group group = MonitorGroupDevice.discover(target);
        int width = group.width() * MonitorBlockEntity.MAX_LINE_LEN;
        int height = group.height() * MonitorBlockEntity.MAX_LINES;
        char[][] text = new char[height][width];
        char[][] foreground = new char[height][width];
        char[][] background = new char[height][width];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            text[y][x] = ' ';
            foreground[y][x] = digit(source.textColor());
            background[y][x] = digit(source.backgroundColor());
            if (x < source.width() && y < source.height()) {
                text[y][x] = source.characterAt(x, y);
                foreground[y][x] = digit(source.foregroundAt(x, y));
                background[y][x] = digit(source.backgroundAt(x, y));
            }
        }
        List<String> lines = new ArrayList<>(height);
        List<String> fg = new ArrayList<>(height);
        List<String> bg = new ArrayList<>(height);
        for (int y = 0; y < height; y++) {
            lines.add(new String(text[y]));
            fg.add(new String(foreground[y]));
            bg.add(new String(background[y]));
        }
        int[] palette = source.palette().stream().mapToInt(Integer::intValue).toArray();
        new MonitorGroupDevice(target).renderColorFrame(new MonitorScreensaver.ColorFrame(lines, fg, bg), palette);
    }

    private static char digit(int value) { return Character.forDigit(Math.max(0, Math.min(15, value)), 16); }

    private static Scope scope(ServerLevel level) {
        if (level == null || level.getServer() == null) return new Scope();
        return SCOPES.computeIfAbsent(level.getServer(), ignored -> new Scope());
    }

    private static Scope scopeIfPresent(net.minecraft.world.level.Level level) {
        return level instanceof ServerLevel serverLevel && serverLevel.getServer() != null
                ? SCOPES.get(serverLevel.getServer()) : null;
    }

    public static void clear(MinecraftServer server) { SCOPES.remove(server); }

    /** Bounded operator snapshot for future admin screens and logs. */
    public static Snapshot snapshot(MinecraftServer server) {
        Scope scope = SCOPES.get(server);
        return scope == null ? new Snapshot(0, 0, 0, 0, 0)
                : new Snapshot(scope.links.size(), scope.cables.size(), scope.refreshes, scope.frames, scope.renderErrors);
    }

    private static final class Scope {
        private final Set<WirelessDisplayLinkBlockEntity> links = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<VideoCableBlockEntity> cables = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<MonitorBlockEntity, ShellComputer> displaySources =
                Collections.synchronizedMap(new IdentityHashMap<>());
        private long tick;
        private long refreshes;
        private long frames;
        private long renderErrors;
    }

    public record Snapshot(int links, int cables, long refreshes, long frames, long renderErrors) {}

    private record VideoComponent(Set<BlockPos> cables, List<MonitorBlockEntity> monitors, ShellComputer source) {}
}
