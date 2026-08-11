package com.malice.terminalcraft.scada;

import com.malice.terminalcraft.device.DeviceAccess;
import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.DeviceDescriptor;
import com.malice.terminalcraft.device.DeviceError;
import com.malice.terminalcraft.device.DeviceErrorCode;
import com.malice.terminalcraft.device.DeviceResult;
import com.malice.terminalcraft.device.DeviceValue;
import com.malice.terminalcraft.device.ServerDeviceManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Bounded server-tick acquisition, alarm event and HMI refresh runtime. */
public final class ScadaRuntime {
    public static final UUID DEVICE_ID = UUID.nameUUIDFromBytes(
            "terminalcraft:scada-service".getBytes(StandardCharsets.UTF_8));
    public static final int MAX_TAG_SCANS_PER_TICK = 16;
    public static final int MAX_DASHBOARDS_PER_TICK = 4;

    private static final DeviceCallContext RUNTIME_CONTEXT = DeviceCallContext.service(
            DEVICE_ID, "terminalcraft-scada", Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
    private static final Map<MinecraftServer, RuntimeState> STATES = new WeakHashMap<>();

    private ScadaRuntime() {}

    public static void tick(MinecraftServer server) {
        if (server == null) return;
        ScadaSavedData data = ScadaSavedData.get(server);
        ServerDeviceManager.ensureServiceRegistered(server, DEVICE_ID, () -> new ScadaDeviceEndpoint(server));
        long gameTime = server.overworld().getGameTime();
        DeviceAccess devices = ServerDeviceManager.access(server, RUNTIME_CONTEXT);
        for (String alarm : data.expireShelves(gameTime)) publishAlarmEvent(server, data, alarm, gameTime);
        for (ScadaTag tag : data.dueTags(gameTime, MAX_TAG_SCANS_PER_TICK)) {
            Acquisition acquisition = acquire(devices, tag);
            ScadaSavedData.Update update = data.recordAcquisition(tag.name(), acquisition.value,
                    acquisition.quality, acquisition.detail, gameTime);
            if (update.tagChanged()) publishTagEvent(server, data, tag, gameTime);
            for (String alarm : update.changedAlarms()) publishAlarmEvent(server, data, alarm, gameTime);
        }
        // Mark only tags that remained overdue after this tick's bounded acquisition budget. This
        // avoids a stale/good flicker for a due tag that was successfully refreshed in the same tick.
        for (ScadaSavedData.StaleUpdate stale : data.markStale(gameTime)) {
            ScadaTag tag = data.tag(stale.tagName()).orElse(null);
            if (tag != null) publishTagEvent(server, data, tag, gameTime);
            for (String alarm : stale.changedAlarms()) publishAlarmEvent(server, data, alarm, gameTime);
        }
        refreshDashboards(server, data, devices, gameTime);
    }

    public static void clear(MinecraftServer server) {
        synchronized (STATES) { STATES.remove(server); }
    }

    public static ScadaSavedData.Operation writeTag(MinecraftServer server, DeviceCallContext context,
                                                    String requestedName, ScadaScalar value, long gameTime) {
        ScadaSavedData data = ScadaSavedData.get(server);
        if (!data.authorized(context, ScadaAction.CONTROL)) {
            String message = "SCADA control requires operator role or higher";
            data.recordControl(context, requestedName, value, false, message, gameTime);
            return ScadaSavedData.Operation.fail(message);
        }
        ScadaTag tag = data.tag(requestedName).orElse(null);
        if (tag == null) {
            String message = "tag not found: " + requestedName;
            data.recordControl(context, requestedName, value, false, message, gameTime);
            return ScadaSavedData.Operation.fail(message);
        }
        if (tag.writeMethod().isEmpty()) {
            String message = "tag is read-only: " + tag.name();
            data.recordControl(context, tag.name(), value, false, message, gameTime);
            return ScadaSavedData.Operation.fail(message);
        }
        if (tag.writeRequiresValue() && value == null) {
            String message = "tag requires a typed value: " + tag.name();
            data.recordControl(context, tag.name(), null, false, message, gameTime);
            return ScadaSavedData.Operation.fail(message);
        }
        if (!tag.writeRequiresValue() && value != null) {
            String message = "command tag does not accept a value: " + tag.name();
            data.recordControl(context, tag.name(), value, false, message, gameTime);
            return ScadaSavedData.Operation.fail(message);
        }
        List<DeviceValue> arguments = new ArrayList<>(tag.arguments());
        if (value != null) arguments.add(value.toDeviceValue());
        DeviceResult result = ServerDeviceManager.access(server, context)
                .call(tag.deviceId(), tag.callableWriteMethod(), arguments);
        if (!result.isSuccess()) {
            String message = result.error().map(DeviceError::message).orElse("device rejected control");
            data.recordControl(context, tag.name(), value, false, message, gameTime);
            return ScadaSavedData.Operation.fail(message);
        }
        data.recordControl(context, tag.name(), value, true, "accepted by " + tag.deviceId(), gameTime);
        return ScadaSavedData.Operation.ok(value == null
                ? "command accepted: " + tag.name()
                : "wrote " + tag.name() + "=" + value.display());
    }

    private static Acquisition acquire(DeviceAccess devices, ScadaTag tag) {
        Optional<DeviceDescriptor> descriptor = devices.descriptor(tag.deviceId());
        if (descriptor.isEmpty()) return Acquisition.failure(ScadaQuality.OFFLINE, "source device is not loaded");
        if (!descriptor.get().loaded() || !descriptor.get().online()) {
            return Acquisition.failure(ScadaQuality.OFFLINE, "source device is offline");
        }
        DeviceResult result = devices.call(tag.deviceId(), tag.readMethod(), tag.arguments());
        if (!result.isSuccess()) {
            DeviceError error = result.error().orElse(null);
            ScadaQuality quality = error == null ? ScadaQuality.BAD_RESPONSE : switch (error.code()) {
                case OFFLINE, CHUNK_UNLOADED, NOT_FOUND, REMOVED, REPLACED -> ScadaQuality.OFFLINE;
                case PERMISSION_DENIED -> ScadaQuality.ACCESS_DENIED;
                case INVALID_ARGUMENT, UNSUPPORTED -> ScadaQuality.CONFIG_ERROR;
                default -> ScadaQuality.BAD_RESPONSE;
            };
            return Acquisition.failure(quality, error == null ? "device call failed" : error.message());
        }
        DeviceValue root = result.value().orElse(DeviceValue.nullValue());
        DeviceValue selected = resolve(root, tag.valuePath()).orElse(null);
        ScadaScalar scalar = selected == null ? null : ScadaScalar.from(selected).orElse(null);
        if (scalar == null) return Acquisition.failure(ScadaQuality.BAD_RESPONSE,
                "value path did not resolve to a scalar: " + (tag.valuePath().isEmpty() ? "(root)" : tag.valuePath()));
        ScadaQuality sourceQuality = sourceQuality(root);
        String detail = sourceDetail(root);
        return new Acquisition(scalar, sourceQuality, detail);
    }

    static Optional<DeviceValue> resolve(DeviceValue root, String valuePath) {
        DeviceValue current = root;
        if (valuePath == null || valuePath.isEmpty() || "-".equals(valuePath)) return Optional.of(current);
        for (String component : valuePath.split("/")) {
            if (current instanceof DeviceValue.MapValue map) {
                current = map.values().get(component);
            } else if (current instanceof DeviceValue.ListValue list) {
                try {
                    int index = Integer.parseInt(component);
                    current = index < 0 || index >= list.values().size() ? null : list.values().get(index);
                } catch (NumberFormatException invalid) {
                    current = null;
                }
            } else {
                current = null;
            }
            if (current == null) return Optional.empty();
        }
        return Optional.of(current);
    }

    private static ScadaQuality sourceQuality(DeviceValue root) {
        if (!(root instanceof DeviceValue.MapValue map)) return ScadaQuality.GOOD;
        DeviceValue raw = map.values().get("quality");
        if (!(raw instanceof DeviceValue.StringValue string)) return ScadaQuality.GOOD;
        return switch (string.value().toLowerCase(java.util.Locale.ROOT)) {
            case "ok", "good" -> ScadaQuality.GOOD;
            case "stale" -> ScadaQuality.STALE;
            case "chunk_unloaded", "unavailable" -> ScadaQuality.OFFLINE;
            default -> ScadaQuality.BAD_RESPONSE;
        };
    }

    private static String sourceDetail(DeviceValue root) {
        if (!(root instanceof DeviceValue.MapValue map)) return "";
        DeviceValue raw = map.values().get("detail");
        return raw instanceof DeviceValue.StringValue string ? string.value() : "";
    }

    private static void publishTagEvent(MinecraftServer server, ScadaSavedData data, ScadaTag tag, long gameTime) {
        ScadaSnapshot snapshot = data.snapshot(tag.name(), gameTime).orElse(null);
        if (snapshot == null) return;
        Map<String, DeviceValue> payload = new LinkedHashMap<>();
        payload.put("tag", DeviceValue.of(tag.name()));
        payload.put("quality", DeviceValue.of(snapshot.quality().id()));
        payload.put("category", DeviceValue.of(snapshot.quality().category()));
        payload.put("unit", DeviceValue.of(tag.unit()));
        payload.put("sampled_at", DeviceValue.of(snapshot.sampledAt()));
        payload.put("value", snapshot.value() == null ? DeviceValue.nullValue() : snapshot.value().toDeviceValue());
        ServerDeviceManager.publishEvent(server, DEVICE_ID, "tag_changed", gameTime, new DeviceValue.MapValue(payload));
    }

    private static void publishAlarmEvent(MinecraftServer server, ScadaSavedData data, String alarmName, long gameTime) {
        ScadaSavedData.AlarmView alarm = data.alarm(alarmName, gameTime).orElse(null);
        if (alarm == null) return;
        ServerDeviceManager.publishEvent(server, DEVICE_ID, "alarm_changed", gameTime, new DeviceValue.MapValue(Map.of(
                "alarm", DeviceValue.of(alarm.rule().name()),
                "tag", DeviceValue.of(alarm.rule().tagName()),
                "state", DeviceValue.of(alarm.state().name().toLowerCase(java.util.Locale.ROOT)),
                "severity", DeviceValue.of(alarm.rule().severity().name().toLowerCase(java.util.Locale.ROOT)))));
    }

    private static void refreshDashboards(MinecraftServer server, ScadaSavedData data,
                                          DeviceAccess devices, long gameTime) {
        List<ScadaHmiDashboard> advanced = data.hmiDashboards();
        List<ScadaDashboard> dashboards = data.dashboards();
        if (dashboards.isEmpty() && advanced.isEmpty()) return;
        RuntimeState runtime;
        synchronized (STATES) { runtime = STATES.computeIfAbsent(server, ignored -> new RuntimeState()); }
        int advancedRendered = refreshAdvancedDashboards(data, devices, advanced, gameTime, runtime);
        int remaining = MAX_DASHBOARDS_PER_TICK - advancedRendered;
        if (dashboards.isEmpty() || remaining <= 0) return;
        int start = Math.floorMod(runtime.dashboardCursor, dashboards.size());
        int visited = 0;
        int rendered = 0;
        while (visited < dashboards.size() && rendered < remaining) {
            ScadaDashboard dashboard = dashboards.get((start + visited) % dashboards.size());
            visited++;
            if (data.hmiDashboard(dashboard.monitorId()).isPresent()) continue;
            if (gameTime % dashboard.refreshTicks() != 0) continue;
            renderDashboard(data, devices, dashboard, gameTime);
            rendered++;
        }
        runtime.dashboardCursor = (start + Math.max(1, visited)) % dashboards.size();
    }

    private static int refreshAdvancedDashboards(ScadaSavedData data, DeviceAccess devices,
                                                  List<ScadaHmiDashboard> dashboards, long gameTime,
                                                  RuntimeState runtime) {
        if (dashboards.isEmpty()) return 0;
        int start = Math.floorMod(runtime.hmiDashboardCursor, dashboards.size());
        int visited = 0;
        int rendered = 0;
        while (visited < dashboards.size() && rendered < MAX_DASHBOARDS_PER_TICK) {
            ScadaHmiDashboard dashboard = dashboards.get((start + visited) % dashboards.size());
            visited++;
            if (gameTime % dashboard.refreshTicks() != 0) continue;
            renderHmiDashboard(data, devices, dashboard, gameTime);
            rendered++;
        }
        runtime.hmiDashboardCursor = (start + Math.max(1, visited)) % dashboards.size();
        return rendered;
    }

    private static void renderHmiDashboard(ScadaSavedData data, DeviceAccess devices,
                                           ScadaHmiDashboard dashboard, long gameTime) {
        DeviceDescriptor monitor = devices.descriptor(dashboard.monitorId()).orElse(null);
        if (monitor == null || !monitor.capabilities().contains("monitor_output")
                || !monitor.online() || !monitor.loaded()) return;
        int rows = numberProperty(monitor, "rows", 16, 6, ScadaHmiFrame.MAX_HEIGHT);
        int columns = numberProperty(monitor, "columns", 40, 20, ScadaHmiFrame.MAX_WIDTH);
        ScadaHmiFrame frame = ScadaHmiRenderer.render(data, dashboard, gameTime, columns, rows);
        devices.call(dashboard.monitorId(), "title.set", List.of(DeviceValue.of(dashboard.title())));
        DeviceResult result = devices.call(dashboard.monitorId(), "term.frame", List.of(
                strings(frame.lines()), strings(frame.foreground()), strings(frame.background()),
                DeviceValue.list(frame.palette().stream().map(DeviceValue::of).toList())));
        if (!result.isSuccess()) {
            // Compatibility fallback for a monitor-like endpoint that has not implemented the
            // atomic color-frame extension. It remains a useful plain-text HMI.
            devices.call(dashboard.monitorId(), "clear", List.of());
            for (int row = 0; row < frame.lines().size(); row++) {
                devices.call(dashboard.monitorId(), "line.set", List.of(DeviceValue.of(row), DeviceValue.of(frame.lines().get(row))));
            }
        }
    }

    /** Handles a physical monitor-wall touch using the touching player's SCADA role and device identity. */
    public static ScadaSavedData.Operation handleMonitorTouch(MinecraftServer server, UUID monitorId,
                                                              int column, int row, ServerPlayer player) {
        if (server == null || monitorId == null || player == null) return ScadaSavedData.Operation.fail("invalid HMI touch");
        ScadaSavedData data = ScadaSavedData.get(server);
        ScadaHmiDashboard dashboard = data.hmiDashboard(monitorId).orElse(null);
        if (dashboard == null) return ScadaSavedData.Operation.fail("monitor has no advanced HMI");
        DeviceAccess devices = ServerDeviceManager.access(server, RUNTIME_CONTEXT);
        DeviceDescriptor monitor = devices.descriptor(monitorId).orElse(null);
        if (monitor == null) return ScadaSavedData.Operation.fail("HMI monitor is offline");
        int width = numberProperty(monitor, "columns", 40, 20, ScadaHmiFrame.MAX_WIDTH);
        int height = numberProperty(monitor, "rows", 16, 6, ScadaHmiFrame.MAX_HEIGHT);
        ScadaHmiWidget widget = ScadaHmiRenderer.widgetAt(dashboard, width, height, column, row, true);
        if (widget == null) return ScadaSavedData.Operation.fail("no interactive HMI widget at touch point");
        DeviceCallContext context = DeviceCallContext.player(player.getUUID(), player.getGameProfile().getName(),
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        ScadaSavedData.Operation operation = activateHmiWidget(server, context, dashboard.name(), widget.id(),
                server.overworld().getGameTime());
        if (operation.success()) {
            ScadaHmiDashboard updated = data.hmiDashboard(dashboard.name()).orElse(dashboard);
            renderHmiDashboard(data, devices, updated, server.overworld().getGameTime());
        }
        return operation;
    }

    /** Activates one page-link or control widget from an authenticated terminal or world touch. */
    public static ScadaSavedData.Operation activateHmiWidget(MinecraftServer server, DeviceCallContext context,
                                                             String dashboardName, String widgetId, long gameTime) {
        ScadaSavedData data = ScadaSavedData.get(server);
        ScadaHmiDashboard dashboard = data.hmiDashboard(dashboardName).orElse(null);
        if (dashboard == null) return ScadaSavedData.Operation.fail("advanced HMI not found: " + dashboardName);
        ScadaHmiWidget widget;
        try { widget = dashboard.selectedPage().widget(widgetId); }
        catch (IllegalArgumentException invalid) { return ScadaSavedData.Operation.fail(invalid.getMessage()); }
        if (widget == null || !widget.type().interactive()) return ScadaSavedData.Operation.fail("interactive HMI widget not found");
        if (widget.type() == ScadaHmiWidget.Type.PAGE_LINK) {
            return data.selectHmiPage(context, dashboard.name(), widget.source(), gameTime);
        }
        return writeTag(server, context, widget.source(), widget.actionValue(), gameTime);
    }

    private static DeviceValue strings(List<String> values) {
        return DeviceValue.list(values.stream().map(DeviceValue::of).toList());
    }

    private static void renderDashboard(ScadaSavedData data, DeviceAccess devices,
                                        ScadaDashboard dashboard, long gameTime) {
        DeviceDescriptor monitor = devices.descriptor(dashboard.monitorId()).orElse(null);
        if (monitor == null || !monitor.capabilities().contains("monitor_output") || !monitor.online() || !monitor.loaded()) return;
        int rows = numberProperty(monitor, "rows", 16, 1, 32);
        int columns = numberProperty(monitor, "columns", 40, 8, 320);
        List<String> lines = dashboardLines(data, dashboard, gameTime, rows, columns);
        devices.call(dashboard.monitorId(), "title.set", List.of(DeviceValue.of(dashboard.title())));
        devices.call(dashboard.monitorId(), "palette.set", List.of(DeviceValue.of(0x67E8A1), DeviceValue.of(0x07140E)));
        devices.call(dashboard.monitorId(), "clear", List.of());
        for (int row = 0; row < lines.size() && row < rows; row++) {
            devices.call(dashboard.monitorId(), "line.set", List.of(DeviceValue.of(row), DeviceValue.of(lines.get(row))));
        }
    }

    static List<String> dashboardLines(ScadaSavedData data, ScadaDashboard dashboard,
                                       long gameTime, int rows, int columns) {
        List<String> lines = new ArrayList<>();
        List<ScadaSavedData.AlarmView> active = data.alarms(ScadaSavedData.MAX_ENUMERATION, gameTime).stream()
                .filter(alarm -> alarm.state() != ScadaSavedData.AlarmState.NORMAL).toList();
        lines.add(fit("SCADA // " + (data.initialized() ? "ONLINE" : "UNINITIALIZED")
                + "  ALARMS " + active.size(), columns));
        lines.add(fit("TAG                          VALUE       QUALITY", columns));
        int capacity = Math.max(0, rows - 3);
        for (ScadaTag tag : data.tags(dashboard.tagPrefix(), capacity)) {
            ScadaSnapshot snapshot = data.snapshot(tag.name(), gameTime).orElse(null);
            String value = snapshot == null ? "(pending)" : snapshot.value() == null ? "(none)" : snapshot.value().display();
            String quality = snapshot == null ? "pending" : snapshot.quality().id();
            String marker = active.stream().anyMatch(alarm -> alarm.rule().tagName().equals(tag.name())) ? "!" : " ";
            lines.add(fit(marker + " " + fit(tag.name(), 25) + " " + fit(value + (tag.unit().isBlank() ? "" : " " + tag.unit()), 11)
                    + " " + quality, columns));
        }
        if (lines.size() == 2) lines.add(fit("(no matching tags)", columns));
        if (lines.size() < rows) lines.add(fit("Updated tick " + gameTime + "  ! = alarm", columns));
        return List.copyOf(lines.subList(0, Math.min(rows, lines.size())));
    }

    private static int numberProperty(DeviceDescriptor descriptor, String name, int fallback, int minimum, int maximum) {
        DeviceValue value = descriptor.properties().get(name);
        if (!(value instanceof DeviceValue.NumberValue number)) return fallback;
        return Math.max(minimum, Math.min(maximum, (int) number.value()));
    }

    private static String fit(String value, int maximum) {
        String safe = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
        return safe.length() <= maximum ? safe : safe.substring(0, maximum);
    }

    private record Acquisition(ScadaScalar value, ScadaQuality quality, String detail) {
        private static Acquisition failure(ScadaQuality quality, String detail) {
            return new Acquisition(null, quality, detail);
        }
    }

    private static final class RuntimeState {
        private int dashboardCursor;
        private int hmiDashboardCursor;
    }
}
