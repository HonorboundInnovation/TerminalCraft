package com.malice.terminalcraft.scada;

import com.malice.terminalcraft.device.ContextualDeviceEndpoint;
import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.DeviceDescriptor;
import com.malice.terminalcraft.device.DeviceErrorCode;
import com.malice.terminalcraft.device.DeviceMethodDescriptor;
import com.malice.terminalcraft.device.DeviceParameterDescriptor;
import com.malice.terminalcraft.device.DeviceResult;
import com.malice.terminalcraft.device.DeviceValue;
import com.malice.terminalcraft.device.DeviceValueType;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Discoverable virtual device representing the server-global SCADA service. */
final class ScadaDeviceEndpoint implements ContextualDeviceEndpoint {
    private static final DeviceParameterDescriptor TAG = string("tag", "Canonical process tag name", true);
    private static final DeviceParameterDescriptor LIMIT = number("limit", "Maximum returned records", false);
    private static final DeviceParameterDescriptor PREFIX = string("prefix", "Optional tag prefix", false);
    private static final DeviceParameterDescriptor VALUE = string("value", "Typed s:, n:, or b: value", true);
    private static final DeviceParameterDescriptor DASHBOARD = string("dashboard", "Advanced HMI dashboard name", true);
    private static final DeviceParameterDescriptor PAGE = string("page", "Advanced HMI page name", true);
    private static final DeviceParameterDescriptor WIDGET = string("widget", "Interactive HMI widget id", true);
    private static final List<DeviceMethodDescriptor> METHODS = List.of(
            read("status", "Returns SCADA service health and capacity", List.of(), DeviceValueType.MAP),
            read("tags.list", "Lists configured process tags", List.of(PREFIX), DeviceValueType.LIST),
            read("tag.read", "Reads one live tag value and quality", List.of(TAG), DeviceValueType.MAP),
            read("history.get", "Returns bounded historian points", List.of(TAG, LIMIT), DeviceValueType.LIST),
            read("alarms.list", "Lists alarm lifecycle state", List.of(LIMIT), DeviceValueType.LIST),
            read("hmi.list", "Lists advanced HMI dashboards and selected pages", List.of(), DeviceValueType.LIST),
            write("alarm.ack", "Acknowledges an active alarm", List.of(string("alarm", "Alarm name", true))),
            write("tag.write", "Writes an operator value to a writable tag", List.of(TAG, VALUE)),
            write("tag.command", "Invokes a no-value operator command tag", List.of(TAG)),
            write("hmi.page.select", "Selects a page on an advanced HMI", List.of(DASHBOARD, PAGE)),
            write("hmi.widget.activate", "Activates an HMI page link or authorized control", List.of(DASHBOARD, WIDGET)));

    private final MinecraftServer server;

    ScadaDeviceEndpoint(MinecraftServer server) { this.server = Objects.requireNonNull(server, "server"); }

    @Override
    public DeviceDescriptor descriptor() {
        ScadaSavedData data = ScadaSavedData.get(server);
        long now = server.overworld().getGameTime();
        long active = data.alarms(ScadaSavedData.MAX_ENUMERATION, now).stream()
                .filter(alarm -> alarm.state() != ScadaSavedData.AlarmState.NORMAL).count();
        return new DeviceDescriptor(ScadaRuntime.DEVICE_ID, "terminalcraft:scada", "scada_service",
                "TerminalCraft SCADA", "terminalcraft", "terminalcraft:scada",
                Set.of("scada", "telemetry", "historian", "alarm_management", "hmi", "supervisory_control"),
                Map.of("initialized", DeviceValue.of(data.initialized()),
                        "tags", DeviceValue.of(data.tags("", ScadaSavedData.MAX_TAGS).size()),
                        "active_alarms", DeviceValue.of(active),
                        "dashboards", DeviceValue.of(data.dashboards().size()),
                        "advanced_hmi", DeviceValue.of(data.hmiDashboards().size())),
                METHODS, Set.of("tag_changed", "alarm_changed"),
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE), true, true);
    }

    @Override
    public DeviceResult call(DeviceCallContext context, String method, List<DeviceValue> arguments) {
        ScadaSavedData data = ScadaSavedData.get(server);
        List<DeviceValue> args = arguments == null ? List.of() : arguments;
        ScadaAction action = "alarm.ack".equals(method) ? ScadaAction.ACKNOWLEDGE
                : ("tag.write".equals(method) || "tag.command".equals(method))
                ? ScadaAction.CONTROL : ScadaAction.VIEW;
        // Valid control calls flow through ScadaRuntime so denied attempts are included in the
        // bounded audit trail. Other operations can fail immediately.
        if (action != ScadaAction.CONTROL && !data.authorized(context, action)) {
            return DeviceResult.failure(DeviceErrorCode.PERMISSION_DENIED,
                    "SCADA role " + action.minimumRole().name().toLowerCase(java.util.Locale.ROOT) + " required", false);
        }
        try {
            return switch (method == null ? "" : method) {
                case "status" -> args.isEmpty() ? DeviceResult.success(status(data)) : invalid("status takes no arguments");
                case "tags.list" -> args.size() <= 1 ? DeviceResult.success(tags(data, args)) : invalid("tags.list accepts [prefix]");
                case "tag.read" -> args.size() == 1 ? readTag(data, string(args.get(0))) : invalid("tag.read requires tag");
                case "history.get" -> args.size() >= 1 && args.size() <= 2 ? history(data, args) : invalid("history.get requires tag, [limit]");
                case "alarms.list" -> args.size() <= 1 ? alarms(data, args) : invalid("alarms.list accepts [limit]");
                case "hmi.list" -> args.isEmpty() ? DeviceResult.success(hmiList(data)) : invalid("hmi.list takes no arguments");
                case "alarm.ack" -> acknowledge(data, context, args);
                case "tag.write" -> writeTag(context, args);
                case "tag.command" -> commandTag(context, args);
                case "hmi.page.select" -> selectHmiPage(data, context, args);
                case "hmi.widget.activate" -> activateHmiWidget(context, args);
                default -> DeviceResult.failure(DeviceErrorCode.UNSUPPORTED, "method is unsupported", false);
            };
        } catch (IllegalArgumentException invalid) {
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT, invalid.getMessage(), false);
        }
    }

    private DeviceValue status(ScadaSavedData data) {
        long now = server.overworld().getGameTime();
        long active = data.alarms(ScadaSavedData.MAX_ENUMERATION, now).stream()
                .filter(alarm -> alarm.state() != ScadaSavedData.AlarmState.NORMAL).count();
        return DeviceValue.map(Map.of(
                "initialized", DeviceValue.of(data.initialized()),
                "tags", DeviceValue.of(data.tags("", ScadaSavedData.MAX_TAGS).size()),
                "alarms", DeviceValue.of(data.alarms(ScadaSavedData.MAX_ALARMS, now).size()),
                "active_alarms", DeviceValue.of(active),
                "dashboards", DeviceValue.of(data.dashboards().size()),
                "advanced_hmi", DeviceValue.of(data.hmiDashboards().size())));
    }

    private DeviceValue tags(ScadaSavedData data, List<DeviceValue> args) {
        String prefix = args.isEmpty() ? "" : string(args.get(0));
        return DeviceValue.list(data.tags(prefix, ScadaSavedData.MAX_ENUMERATION).stream().map(tag -> DeviceValue.map(Map.of(
                "name", DeviceValue.of(tag.name()), "device", DeviceValue.of(tag.deviceId().toString()),
                "method", DeviceValue.of(tag.readMethod()), "path", DeviceValue.of(tag.valuePath()),
                "unit", DeviceValue.of(tag.unit()), "writable", DeviceValue.of(!tag.writeMethod().isEmpty()),
                "command", DeviceValue.of(!tag.writeMethod().isEmpty() && !tag.writeRequiresValue())))).toList());
    }

    private DeviceResult readTag(ScadaSavedData data, String name) {
        ScadaTag tag = data.tag(name).orElse(null);
        if (tag == null) return DeviceResult.failure(DeviceErrorCode.NOT_FOUND, "tag not found", false);
        ScadaSnapshot snapshot = data.snapshot(name, server.overworld().getGameTime()).orElse(null);
        if (snapshot == null) return DeviceResult.failure(DeviceErrorCode.NOT_FOUND, "tag has no sample yet", true);
        Map<String, DeviceValue> value = new LinkedHashMap<>();
        value.put("tag", DeviceValue.of(tag.name()));
        value.put("value", snapshot.value() == null ? DeviceValue.nullValue() : snapshot.value().toDeviceValue());
        value.put("unit", DeviceValue.of(tag.unit()));
        value.put("quality", DeviceValue.of(snapshot.quality().id()));
        value.put("category", DeviceValue.of(snapshot.quality().category()));
        value.put("sampled_at", DeviceValue.of(snapshot.sampledAt()));
        value.put("last_good_at", DeviceValue.of(snapshot.lastGoodAt()));
        value.put("detail", DeviceValue.of(snapshot.detail()));
        return DeviceResult.success(DeviceValue.map(value));
    }

    private DeviceResult history(ScadaSavedData data, List<DeviceValue> args) {
        String name = string(args.get(0));
        if (data.tag(name).isEmpty()) return DeviceResult.failure(DeviceErrorCode.NOT_FOUND, "tag not found", false);
        int limit = args.size() == 2 ? integer(args.get(1), 1, 256) : 64;
        List<DeviceValue> values = new ArrayList<>();
        for (ScadaSample sample : data.history(name, limit)) {
            Map<String, DeviceValue> point = new LinkedHashMap<>();
            point.put("time", DeviceValue.of(sample.gameTime()));
            point.put("quality", DeviceValue.of(sample.quality().id()));
            point.put("value", sample.value() == null ? DeviceValue.nullValue() : sample.value().toDeviceValue());
            values.add(DeviceValue.map(point));
        }
        return DeviceResult.success(DeviceValue.list(values));
    }

    private DeviceResult alarms(ScadaSavedData data, List<DeviceValue> args) {
        int limit = args.isEmpty() ? 64 : integer(args.get(0), 1, 256);
        long now = server.overworld().getGameTime();
        return DeviceResult.success(DeviceValue.list(data.alarms(limit, now).stream().map(alarm -> DeviceValue.map(Map.of(
                "name", DeviceValue.of(alarm.rule().name()), "tag", DeviceValue.of(alarm.rule().tagName()),
                "state", DeviceValue.of(alarm.state().name().toLowerCase(java.util.Locale.ROOT)),
                "severity", DeviceValue.of(alarm.rule().severity().name().toLowerCase(java.util.Locale.ROOT)),
                "message", DeviceValue.of(alarm.rule().message())))).toList()));
    }

    private DeviceResult acknowledge(ScadaSavedData data, DeviceCallContext context, List<DeviceValue> args) {
        if (args.size() != 1) return invalid("alarm.ack requires alarm");
        ScadaSavedData.Operation result = data.acknowledgeAlarm(context, string(args.get(0)), server.overworld().getGameTime());
        return result.success() ? DeviceResult.success() : invalid(result.message());
    }

    private DeviceValue hmiList(ScadaSavedData data) {
        return DeviceValue.list(data.hmiDashboards().stream().map(dashboard -> DeviceValue.map(Map.of(
                "name", DeviceValue.of(dashboard.name()),
                "monitor", DeviceValue.of(dashboard.monitorId().toString()),
                "title", DeviceValue.of(dashboard.title()),
                "active_page", DeviceValue.of(dashboard.activePage()),
                "pages", DeviceValue.of(dashboard.pages().size()),
                "refresh_ticks", DeviceValue.of(dashboard.refreshTicks())))).toList());
    }

    private DeviceResult selectHmiPage(ScadaSavedData data, DeviceCallContext context, List<DeviceValue> args) {
        if (args.size() != 2) return invalid("hmi.page.select requires dashboard and page");
        ScadaSavedData.Operation result = data.selectHmiPage(context, string(args.get(0)),
                string(args.get(1)), server.overworld().getGameTime());
        return result.success() ? DeviceResult.success() : invalid(result.message());
    }

    private DeviceResult activateHmiWidget(DeviceCallContext context, List<DeviceValue> args) {
        if (args.size() != 2) return invalid("hmi.widget.activate requires dashboard and widget");
        ScadaSavedData.Operation result = ScadaRuntime.activateHmiWidget(server, context,
                string(args.get(0)), string(args.get(1)), server.overworld().getGameTime());
        return result.success() ? DeviceResult.success() : invalid(result.message());
    }

    private DeviceResult writeTag(DeviceCallContext context, List<DeviceValue> args) {
        if (args.size() != 2) return invalid("tag.write requires tag and typed value");
        ScadaSavedData.Operation result = ScadaRuntime.writeTag(server, context, string(args.get(0)),
                ScadaScalar.parseToken(string(args.get(1))), server.overworld().getGameTime());
        return result.success() ? DeviceResult.success() : invalid(result.message());
    }

    private DeviceResult commandTag(DeviceCallContext context, List<DeviceValue> args) {
        if (args.size() != 1) return invalid("tag.command requires tag");
        ScadaSavedData.Operation result = ScadaRuntime.writeTag(server, context, string(args.get(0)),
                null, server.overworld().getGameTime());
        return result.success() ? DeviceResult.success() : invalid(result.message());
    }

    private static DeviceResult invalid(String message) {
        return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT, message, false);
    }

    private static String string(DeviceValue value) {
        if (!(value instanceof DeviceValue.StringValue text)) throw new IllegalArgumentException("expected string");
        return text.value();
    }

    private static int integer(DeviceValue value, int minimum, int maximum) {
        if (!(value instanceof DeviceValue.NumberValue number) || number.value() != Math.rint(number.value())
                || number.value() < minimum || number.value() > maximum) throw new IllegalArgumentException("integer outside bounds");
        return (int) number.value();
    }

    private static DeviceParameterDescriptor string(String name, String description, boolean required) {
        return new DeviceParameterDescriptor(name, DeviceValueType.STRING, required, description);
    }

    private static DeviceParameterDescriptor number(String name, String description, boolean required) {
        return new DeviceParameterDescriptor(name, DeviceValueType.NUMBER, required, description);
    }

    private static DeviceMethodDescriptor read(String name, String description,
                                               List<DeviceParameterDescriptor> parameters, DeviceValueType result) {
        return new DeviceMethodDescriptor(name, description, parameters, result, DeviceCallContext.READ);
    }

    private static DeviceMethodDescriptor write(String name, String description,
                                                List<DeviceParameterDescriptor> parameters) {
        return new DeviceMethodDescriptor(name, description, parameters, DeviceValueType.NULL, DeviceCallContext.WRITE);
    }
}
