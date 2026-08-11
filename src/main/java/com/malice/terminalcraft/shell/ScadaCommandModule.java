package com.malice.terminalcraft.shell;

import com.malice.terminalcraft.device.DeviceAccess;
import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.DeviceDescriptor;
import com.malice.terminalcraft.device.DeviceValue;
import com.malice.terminalcraft.device.PrincipalIdentity;
import com.malice.terminalcraft.network.RednetAddress;
import com.malice.terminalcraft.network.RednetNetwork;
import com.malice.terminalcraft.scada.ScadaAlarmRule;
import com.malice.terminalcraft.scada.ScadaDashboard;
import com.malice.terminalcraft.scada.ScadaHmiDashboard;
import com.malice.terminalcraft.scada.ScadaHmiPage;
import com.malice.terminalcraft.scada.ScadaHmiWidget;
import com.malice.terminalcraft.scada.ScadaRole;
import com.malice.terminalcraft.scada.ScadaRuntime;
import com.malice.terminalcraft.scada.ScadaSample;
import com.malice.terminalcraft.scada.ScadaSavedData;
import com.malice.terminalcraft.scada.ScadaScalar;
import com.malice.terminalcraft.scada.ScadaSnapshot;
import com.malice.terminalcraft.scada.ScadaTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Comprehensive operator, engineering and administration shell for the SCADA process database. */
final class ScadaCommandModule implements ShellCommandModule {
    @Override
    public void register(Registrar registrar) {
        registrar.register("scada", this::scada);
    }

    private void scada(Context context, List<String> args) {
        if (args.isEmpty() || "help".equalsIgnoreCase(args.get(0))) {
            printHelp(context);
            context.setExitCode(0);
            return;
        }
        ServerLevel level = serverLevel(context);
        if (level == null) { fail(context, "scada: a server-side terminal host is required"); return; }
        ScadaSavedData data = ScadaSavedData.get(level.getServer());
        long now = level.getServer().overworld().getGameTime();
        try {
            switch (args.get(0).toLowerCase(Locale.ROOT)) {
                case "init", "initialize" -> operation(context, data.initialize(context.callerContext(), now));
                case "status" -> status(context, data, now);
                case "tags" -> listTags(context, data, args, now);
                case "tag" -> tag(context, level, data, args, now);
                case "read" -> read(context, data, args, now);
                case "history", "trend" -> history(context, data, args);
                case "write", "control", "command" -> write(context, level.getServer(), args, now);
                case "alarm", "alarms" -> alarm(context, data, args, now);
                case "dashboard" -> dashboard(context, level, data, args, now);
                case "hmi" -> advancedHmi(context, level, data, args, now);
                case "role", "roles" -> role(context, data, args, now);
                case "audit" -> audit(context, data, args);
                default -> fail(context, "scada: unknown operation; use scada help");
            }
        } catch (IllegalArgumentException invalid) {
            fail(context, "scada: " + (invalid.getMessage() == null ? "invalid argument" : invalid.getMessage()));
        }
    }

    static void printHelp(Context context) {
        context.printLine("SCADA supervisory control");
        context.printLine("");
        context.printLine("Setup and live data:");
        context.printLine("  scada init");
        context.printLine("  scada status");
        context.printLine("  scada tags [prefix]");
        context.printLine("  scada tag add <name> <device> <readMethod> <path|-> <unit|-> <interval> <stale> <writeMethod|@commandMethod|-> [typedArgs...]");
        context.printLine("  scada tag remove <name>");
        context.printLine("  scada read <tag>");
        context.printLine("  scada history <tag> [limit]");
        context.printLine("  scada write <tag> <typedValue>");
        context.printLine("  scada command <tag>");
        context.printLine("");
        context.printLine("Alarms and HMI:");
        context.printLine("  scada alarm list");
        context.printLine("  scada alarm add <name> <tag> <above|below|equal|not_equal|bad_quality> <threshold|-> <severity> <deadband> [message...]");
        context.printLine("  scada alarm ack <name>");
        context.printLine("  scada alarm shelve <name> <ticks>   (0 unshelves)");
        context.printLine("  scada alarm remove <name>");
        context.printLine("  scada dashboard list");
        context.printLine("  scada dashboard add <monitor> <prefix|*> <refreshTicks> [title...]");
        context.printLine("  scada dashboard remove <monitor>");
        context.printLine("");
        context.printLine("Advanced HMI:");
        context.printLine("  scada hmi list");
        context.printLine("  scada hmi create <name> <monitor> <refreshTicks> [title...]");
        context.printLine("  scada hmi remove <name>");
        context.printLine("  scada hmi page list <dashboard>");
        context.printLine("  scada hmi page add|select|remove <dashboard> <page> [title...]");
        context.printLine("  scada hmi widget list <dashboard> <page>");
        context.printLine("  scada hmi widget add <dashboard> <page> <id> <type> <x> <y> <w> <h> <source|-> <arg|-> [label...]");
        context.printLine("  scada hmi widget remove <dashboard> <page> <id>");
        context.printLine("  widget types: text value gauge trend alarms button page_link");
        context.printLine("  gauge arg: <min>:<max>; button arg: command or typed value");
        context.printLine("  run top-level 'hmi' to open the graphical viewer/designer");
        context.printLine("");
        context.printLine("Security and values:");
        context.printLine("  scada role list");
        context.printLine("  scada role grant <playerUuid> <viewer|operator|engineer|admin> [name]");
        context.printLine("  scada role revoke <playerUuid> [name]");
        context.printLine("  scada audit [limit]");
        context.printLine("  typed values: n:12.5  b:true  s:text");
        context.printLine("  paths use slash-separated keys, e.g. value or nested/0/value");
        context.printLine("  devices accept UUIDs or RedNet DNS names");
    }

    private static void status(Context context, ScadaSavedData data, long now) {
        long active = data.alarms(ScadaSavedData.MAX_ALARMS, now).stream()
                .filter(alarm -> alarm.state() != ScadaSavedData.AlarmState.NORMAL).count();
        context.printLine("SCADA " + (data.initialized() ? "ONLINE" : "UNINITIALIZED"));
        context.printLine("tags=" + data.tags("", ScadaSavedData.MAX_TAGS).size()
                + "/" + ScadaSavedData.MAX_TAGS + " alarms=" + data.alarms(ScadaSavedData.MAX_ALARMS, now).size()
                + " active=" + active + " dashboards=" + data.dashboards().size()
                + " advanced-hmi=" + data.hmiDashboards().size());
        context.printLine("role=" + data.role(context.callerContext().principal())
                .map(role -> role.name().toLowerCase(Locale.ROOT)).orElse("none"));
        if (!data.initialized()) context.printLine("run 'scada init' to claim the first administrator");
        context.setExitCode(0);
    }

    private static void listTags(Context context, ScadaSavedData data, List<String> args, long now) {
        if (args.size() > 2) { fail(context, "scada: usage: scada tags [prefix]"); return; }
        if (!viewAllowed(context, data)) return;
        List<ScadaTag> tags = data.tags(args.size() == 2 ? args.get(1) : "", ScadaSavedData.MAX_ENUMERATION);
        if (tags.isEmpty()) context.printLine("(no tags)");
        for (ScadaTag tag : tags) {
            ScadaSnapshot snapshot = data.snapshot(tag.name(), now).orElse(null);
            context.printLine(tag.name() + " = " + (snapshot == null ? "(pending)" : snapshot.display(tag.unit()))
                    + " source=" + tag.deviceId() + "/" + tag.readMethod()
                    + (tag.writeMethod().isEmpty() ? " read-only" : " write=" + tag.writeMethod()));
        }
        context.setExitCode(0);
    }

    private static void tag(Context context, ServerLevel level, ScadaSavedData data, List<String> args, long now) {
        if (args.size() < 2) { fail(context, "scada: use scada tag add|remove"); return; }
        String action = args.get(1).toLowerCase(Locale.ROOT);
        if ("remove".equals(action) || "delete".equals(action)) {
            if (args.size() != 3) { fail(context, "scada: usage: scada tag remove <name>"); return; }
            operation(context, data.removeTag(context.callerContext(), args.get(2), now));
            return;
        }
        if (!"add".equals(action) && !"set".equals(action)) {
            fail(context, "scada: use scada tag add|remove"); return;
        }
        if (args.size() < 10) {
            fail(context, "scada: usage: scada tag add <name> <device> <readMethod> <path|-> <unit|-> <interval> <stale> <writeMethod|@commandMethod|-> [typedArgs...]");
            return;
        }
        DeviceAccess access = access(context);
        UUID device = resolveDevice(level, access, args.get(3));
        if (device == null) { fail(context, "scada: source device not found or ambiguous: " + args.get(3)); return; }
        int interval = integer(args.get(7), ScadaTag.MIN_SAMPLE_INTERVAL, ScadaTag.MAX_SAMPLE_INTERVAL, "interval");
        int stale = integer(args.get(8), interval, ScadaTag.MAX_STALE_TICKS, "stale");
        List<DeviceValue> deviceArgs = new ArrayList<>();
        for (int index = 10; index < args.size(); index++) deviceArgs.add(ScadaScalar.parseToken(args.get(index)).toDeviceValue());
        ScadaTag definition = new ScadaTag(args.get(2), device, args.get(4), deviceArgs, args.get(5),
                "-".equals(args.get(6)) ? "" : args.get(6), interval, stale,
                "-".equals(args.get(9)) ? "" : args.get(9));
        operation(context, data.putTag(context.callerContext(), definition, now));
    }

    private static void read(Context context, ScadaSavedData data, List<String> args, long now) {
        if (args.size() != 2) { fail(context, "scada: usage: scada read <tag>"); return; }
        if (!viewAllowed(context, data)) return;
        ScadaTag tag = data.tag(args.get(1)).orElse(null);
        if (tag == null) { fail(context, "scada: tag not found"); return; }
        ScadaSnapshot snapshot = data.snapshot(tag.name(), now).orElse(null);
        if (snapshot == null) { fail(context, "scada: tag has no sample yet"); return; }
        context.printLine(tag.name() + " = " + snapshot.display(tag.unit()));
        context.printLine("sampled=" + snapshot.sampledAt() + " last-good=" + snapshot.lastGoodAt()
                + (snapshot.detail().isBlank() ? "" : " detail=" + snapshot.detail()));
        context.setExitCode(snapshot.quality().usable() ? 0 : 2);
    }

    private static void history(Context context, ScadaSavedData data, List<String> args) {
        if (args.size() < 2 || args.size() > 3) { fail(context, "scada: usage: scada history <tag> [limit]"); return; }
        if (!viewAllowed(context, data)) return;
        if (data.tag(args.get(1)).isEmpty()) { fail(context, "scada: tag not found"); return; }
        int limit = args.size() == 3 ? integer(args.get(2), 1, 256, "limit") : 32;
        List<ScadaSample> samples = data.history(args.get(1), limit);
        if (samples.isEmpty()) context.printLine("(no history)");
        for (ScadaSample sample : samples) context.printLine(sample.gameTime() + " "
                + (sample.value() == null ? "(none)" : sample.value().display()) + " [" + sample.quality().id() + "]");
        context.setExitCode(0);
    }

    private static void write(Context context, MinecraftServer server, List<String> args, long now) {
        boolean command = "command".equalsIgnoreCase(args.get(0));
        if (command && args.size() != 2) { fail(context, "scada: usage: scada command <tag>"); return; }
        if (!command && args.size() != 3) { fail(context, "scada: usage: scada write <tag> <typedValue>"); return; }
        operation(context, ScadaRuntime.writeTag(server, context.callerContext(), args.get(1),
                command ? null : ScadaScalar.parseToken(args.get(2)), now));
    }

    private static void alarm(Context context, ScadaSavedData data, List<String> args, long now) {
        String action = args.size() < 2 ? "list" : args.get(1).toLowerCase(Locale.ROOT);
        if ("list".equals(action)) {
            if (!viewAllowed(context, data)) return;
            List<ScadaSavedData.AlarmView> alarms = data.alarms(ScadaSavedData.MAX_ENUMERATION, now);
            if (alarms.isEmpty()) context.printLine("(no alarms)");
            for (ScadaSavedData.AlarmView alarm : alarms) context.printLine(alarm.rule().name() + " "
                    + alarm.state().name().toLowerCase(Locale.ROOT) + " "
                    + alarm.rule().severity().name().toLowerCase(Locale.ROOT) + " tag=" + alarm.rule().tagName()
                    + (alarm.rule().message().isBlank() ? "" : " " + alarm.rule().message()));
            context.setExitCode(0);
            return;
        }
        if ("add".equals(action) || "set".equals(action)) {
            if (args.size() < 8) {
                fail(context, "scada: usage: scada alarm add <name> <tag> <operator> <threshold|-> <severity> <deadband> [message...]"); return;
            }
            ScadaAlarmRule.Operator operator = ScadaAlarmRule.Operator.parse(args.get(4));
            ScadaScalar threshold = operator == ScadaAlarmRule.Operator.BAD_QUALITY ? null : ScadaScalar.parseToken(args.get(5));
            ScadaAlarmRule rule = new ScadaAlarmRule(args.get(2), args.get(3), operator, threshold,
                    ScadaAlarmRule.Severity.parse(args.get(6)), Double.parseDouble(args.get(7)),
                    args.size() > 8 ? String.join(" ", args.subList(8, args.size())) : "");
            operation(context, data.putAlarm(context.callerContext(), rule, now));
            return;
        }
        if ("ack".equals(action) || "acknowledge".equals(action)) {
            if (args.size() != 3) { fail(context, "scada: usage: scada alarm ack <name>"); return; }
            operation(context, data.acknowledgeAlarm(context.callerContext(), args.get(2), now)); return;
        }
        if ("shelve".equals(action) || "unshelve".equals(action)) {
            if (("shelve".equals(action) && args.size() != 4) || ("unshelve".equals(action) && args.size() != 3)) {
                fail(context, "scada: usage: scada alarm shelve <name> <ticks>"); return;
            }
            long ticks = "unshelve".equals(action) ? 0 : integer(args.get(3), 0, 20 * 60 * 60, "ticks");
            operation(context, data.shelveAlarm(context.callerContext(), args.get(2), ticks, now)); return;
        }
        if ("remove".equals(action) || "delete".equals(action)) {
            if (args.size() != 3) { fail(context, "scada: usage: scada alarm remove <name>"); return; }
            operation(context, data.removeAlarm(context.callerContext(), args.get(2), now)); return;
        }
        fail(context, "scada: use scada alarm list|add|ack|shelve|remove");
    }

    private static void dashboard(Context context, ServerLevel level, ScadaSavedData data, List<String> args, long now) {
        String action = args.size() < 2 ? "list" : args.get(1).toLowerCase(Locale.ROOT);
        if ("list".equals(action)) {
            if (!viewAllowed(context, data)) return;
            if (data.dashboards().isEmpty()) context.printLine("(no dashboards)");
            for (ScadaDashboard dashboard : data.dashboards()) context.printLine(dashboard.monitorId()
                    + " prefix=" + (dashboard.tagPrefix().isBlank() ? "*" : dashboard.tagPrefix())
                    + " refresh=" + dashboard.refreshTicks() + " title=" + dashboard.title());
            context.setExitCode(0); return;
        }
        if ("add".equals(action) || "set".equals(action)) {
            if (args.size() < 5) { fail(context, "scada: usage: scada dashboard add <monitor> <prefix|*> <refreshTicks> [title...]"); return; }
            UUID monitor = resolveDevice(level, access(context), args.get(2));
            if (monitor == null) { fail(context, "scada: monitor device not found"); return; }
            String title = args.size() > 5 ? String.join(" ", args.subList(5, args.size())) : "SCADA OVERVIEW";
            operation(context, data.putDashboard(context.callerContext(), new ScadaDashboard(monitor, args.get(3), title,
                    integer(args.get(4), 10, 20 * 60, "refresh")), now));
            return;
        }
        if ("remove".equals(action) || "delete".equals(action)) {
            if (args.size() != 3) { fail(context, "scada: usage: scada dashboard remove <monitor>"); return; }
            UUID monitor = resolveDevice(level, access(context), args.get(2));
            if (monitor == null) { try { monitor = UUID.fromString(args.get(2)); } catch (IllegalArgumentException ignored) {} }
            operation(context, data.removeDashboard(context.callerContext(), monitor, now)); return;
        }
        fail(context, "scada: use scada dashboard list|add|remove");
    }

    private static void advancedHmi(Context context, ServerLevel level, ScadaSavedData data,
                                    List<String> args, long now) {
        String action = args.size() < 2 ? "list" : args.get(1).toLowerCase(Locale.ROOT);
        if ("list".equals(action)) {
            if (!viewAllowed(context, data)) return;
            if (data.hmiDashboards().isEmpty()) context.printLine("(no advanced HMI dashboards)");
            for (ScadaHmiDashboard dashboard : data.hmiDashboards()) {
                context.printLine(dashboard.name() + " monitor=" + dashboard.monitorId()
                        + " page=" + dashboard.activePage() + " pages=" + dashboard.pages().size()
                        + " refresh=" + dashboard.refreshTicks() + " title=" + dashboard.title());
            }
            context.setExitCode(0);
            return;
        }
        if ("create".equals(action) || "add".equals(action)) {
            if (args.size() < 5) {
                fail(context, "scada: usage: scada hmi create <name> <monitor> <refreshTicks> [title...]"); return;
            }
            UUID monitor = resolveDevice(level, access(context), args.get(3));
            if (monitor == null) { fail(context, "scada: HMI monitor not found or ambiguous"); return; }
            DeviceDescriptor descriptor = access(context) == null ? null : access(context).descriptor(monitor).orElse(null);
            if (descriptor == null || !descriptor.capabilities().contains("monitor_output")) {
                fail(context, "scada: selected device is not a monitor wall"); return;
            }
            String title = args.size() > 5 ? String.join(" ", args.subList(5, args.size())) : args.get(2);
            ScadaHmiDashboard dashboard = new ScadaHmiDashboard(args.get(2), monitor, title,
                    integer(args.get(4), 2, 20 * 60, "refresh"), "overview",
                    List.of(new ScadaHmiPage("overview", "Overview", List.of())));
            operation(context, data.putHmiDashboard(context.callerContext(), dashboard, now));
            return;
        }
        if ("remove".equals(action) || "delete".equals(action)) {
            if (args.size() != 3) { fail(context, "scada: usage: scada hmi remove <name>"); return; }
            operation(context, data.removeHmiDashboard(context.callerContext(), args.get(2), now));
            return;
        }
        if ("page".equals(action)) { hmiPage(context, data, args, now); return; }
        if ("widget".equals(action)) { hmiWidget(context, data, args, now); return; }
        fail(context, "scada: use scada hmi list|create|remove|page|widget");
    }

    private static void hmiPage(Context context, ScadaSavedData data, List<String> args, long now) {
        if (args.size() < 3) { fail(context, "scada: use scada hmi page list|add|select|remove"); return; }
        String action = args.get(2).toLowerCase(Locale.ROOT);
        if ("list".equals(action)) {
            if (args.size() != 4) { fail(context, "scada: usage: scada hmi page list <dashboard>"); return; }
            if (!viewAllowed(context, data)) return;
            ScadaHmiDashboard dashboard = data.hmiDashboard(args.get(3)).orElse(null);
            if (dashboard == null) { fail(context, "scada: advanced HMI not found"); return; }
            for (ScadaHmiPage page : dashboard.pages()) context.printLine(
                    (page.name().equals(dashboard.activePage()) ? "* " : "  ") + page.name()
                            + " widgets=" + page.widgets().size() + " title=" + page.title());
            context.setExitCode(0);
            return;
        }
        if ("add".equals(action) || "set".equals(action)) {
            if (args.size() < 5) { fail(context, "scada: usage: scada hmi page add <dashboard> <page> [title...]"); return; }
            String title = args.size() > 5 ? String.join(" ", args.subList(5, args.size())) : args.get(4);
            operation(context, data.putHmiPage(context.callerContext(), args.get(3),
                    new ScadaHmiPage(args.get(4), title, List.of()), now));
            return;
        }
        if ("select".equals(action) || "show".equals(action)) {
            if (args.size() != 5) { fail(context, "scada: usage: scada hmi page select <dashboard> <page>"); return; }
            operation(context, data.selectHmiPage(context.callerContext(), args.get(3), args.get(4), now));
            return;
        }
        if ("remove".equals(action) || "delete".equals(action)) {
            if (args.size() != 5) { fail(context, "scada: usage: scada hmi page remove <dashboard> <page>"); return; }
            operation(context, data.removeHmiPage(context.callerContext(), args.get(3), args.get(4), now));
            return;
        }
        fail(context, "scada: use scada hmi page list|add|select|remove");
    }

    private static void hmiWidget(Context context, ScadaSavedData data, List<String> args, long now) {
        if (args.size() < 3) { fail(context, "scada: use scada hmi widget list|add|remove"); return; }
        String action = args.get(2).toLowerCase(Locale.ROOT);
        if ("list".equals(action)) {
            if (args.size() != 5) { fail(context, "scada: usage: scada hmi widget list <dashboard> <page>"); return; }
            if (!viewAllowed(context, data)) return;
            ScadaHmiDashboard dashboard = data.hmiDashboard(args.get(3)).orElse(null);
            ScadaHmiPage page = dashboard == null ? null : dashboard.page(args.get(4));
            if (page == null) { fail(context, "scada: HMI dashboard or page not found"); return; }
            if (page.widgets().isEmpty()) context.printLine("(no widgets)");
            for (ScadaHmiWidget widget : page.widgets()) context.printLine(widget.id() + " "
                    + widget.type().name().toLowerCase(Locale.ROOT) + " grid=" + widget.x() + "," + widget.y()
                    + " " + widget.width() + "x" + widget.height() + " source="
                    + (widget.source().isBlank() ? "-" : widget.source()) + " label=" + widget.label());
            context.setExitCode(0);
            return;
        }
        if ("add".equals(action) || "set".equals(action)) {
            if (args.size() < 13) {
                fail(context, "scada: usage: scada hmi widget add <dashboard> <page> <id> <type> <x> <y> <w> <h> <source|-> <arg|-> [label...]"); return;
            }
            ScadaHmiWidget.Type type = ScadaHmiWidget.Type.parse(args.get(6));
            double minimum = 0;
            double maximum = 100;
            ScadaScalar actionValue = null;
            String parameter = args.get(12);
            if (type == ScadaHmiWidget.Type.GAUGE) {
                String[] range = parameter.split(":", -1);
                if (range.length != 2) throw new IllegalArgumentException("gauge arg must be <min>:<max>");
                minimum = Double.parseDouble(range[0]);
                maximum = Double.parseDouble(range[1]);
            } else if (type == ScadaHmiWidget.Type.BUTTON && !"command".equalsIgnoreCase(parameter)) {
                actionValue = ScadaScalar.parseToken(parameter);
            }
            String label = args.size() > 13 ? String.join(" ", args.subList(13, args.size())) : args.get(5);
            ScadaHmiWidget widget = new ScadaHmiWidget(args.get(5), type,
                    integer(args.get(7), 0, 11, "x"), integer(args.get(8), 0, 11, "y"),
                    integer(args.get(9), 1, 12, "width"), integer(args.get(10), 1, 12, "height"),
                    "-".equals(args.get(11)) ? "" : args.get(11), label, minimum, maximum, actionValue);
            operation(context, data.putHmiWidget(context.callerContext(), args.get(3), args.get(4), widget, now));
            return;
        }
        if ("remove".equals(action) || "delete".equals(action)) {
            if (args.size() != 6) { fail(context, "scada: usage: scada hmi widget remove <dashboard> <page> <id>"); return; }
            operation(context, data.removeHmiWidget(context.callerContext(), args.get(3), args.get(4), args.get(5), now));
            return;
        }
        fail(context, "scada: use scada hmi widget list|add|remove");
    }

    private static void role(Context context, ScadaSavedData data, List<String> args, long now) {
        String action = args.size() < 2 ? "list" : args.get(1).toLowerCase(Locale.ROOT);
        if ("list".equals(action)) {
            if (!viewAllowed(context, data)) return;
            for (ScadaSavedData.RoleAssignment assignment : data.roles()) context.printLine(
                    assignment.principal().authorityKey() + " " + assignment.principal().name() + " "
                            + assignment.role().name().toLowerCase(Locale.ROOT));
            context.setExitCode(0); return;
        }
        if ("grant".equals(action)) {
            if (args.size() < 4 || args.size() > 5) { fail(context, "scada: usage: scada role grant <playerUuid> <role> [name]"); return; }
            UUID id = UUID.fromString(args.get(2));
            String name = args.size() == 5 ? args.get(4) : "player-" + id.toString().substring(0, 8);
            operation(context, data.grantRole(context.callerContext(), PrincipalIdentity.player(id, name),
                    ScadaRole.parse(args.get(3)), now)); return;
        }
        if ("revoke".equals(action)) {
            if (args.size() < 3 || args.size() > 4) { fail(context, "scada: usage: scada role revoke <playerUuid> [name]"); return; }
            UUID id = UUID.fromString(args.get(2));
            String name = args.size() == 4 ? args.get(3) : "player-" + id.toString().substring(0, 8);
            operation(context, data.revokeRole(context.callerContext(), PrincipalIdentity.player(id, name), now)); return;
        }
        fail(context, "scada: use scada role list|grant|revoke");
    }

    private static void audit(Context context, ScadaSavedData data, List<String> args) {
        if (args.size() > 2) { fail(context, "scada: usage: scada audit [limit]"); return; }
        if (!viewAllowed(context, data)) return;
        int limit = args.size() == 2 ? integer(args.get(1), 1, 256, "limit") : 32;
        List<ScadaSavedData.AuditEntry> entries = data.audit(limit);
        if (entries.isEmpty()) context.printLine("(no audit records)");
        for (ScadaSavedData.AuditEntry entry : entries) context.printLine(entry.gameTime() + " "
                + entry.principal() + " " + entry.action() + " " + entry.detail());
        context.setExitCode(0);
    }

    private static boolean viewAllowed(Context context, ScadaSavedData data) {
        if (data.authorized(context.callerContext(), com.malice.terminalcraft.scada.ScadaAction.VIEW)) return true;
        fail(context, "scada: viewer role or higher required");
        return false;
    }

    private static DeviceAccess access(Context context) {
        TerminalHostServices services = context.hostServices();
        return services == null ? null : services.devices().access(context.callerContext());
    }

    private static UUID resolveDevice(Level level, DeviceAccess access, String selector) {
        try { return UUID.fromString(selector); }
        catch (IllegalArgumentException ignored) {}
        UUID dns = RednetNetwork.resolveAddress(level, selector).map(RednetAddress::deviceId).orElse(null);
        if (dns != null) return dns;
        if (access == null) return null;
        List<DeviceDescriptor> matches = access.descriptors(256).stream().filter(descriptor ->
                descriptor.displayName().equalsIgnoreCase(selector) || descriptor.address().equalsIgnoreCase(selector)).toList();
        return matches.size() == 1 ? matches.get(0).deviceId() : null;
    }

    private static ServerLevel serverLevel(Context context) {
        TerminalHost host = context.worldHost();
        return host != null && host.getLevel() instanceof ServerLevel level ? level : null;
    }

    private static int integer(String value, int minimum, int maximum, String name) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(name + " must be " + minimum + ".." + maximum);
        }
    }

    private static void operation(Context context, ScadaSavedData.Operation operation) {
        context.printLine((operation.success() ? "scada: " : "scada: error: ") + operation.message());
        context.setExitCode(operation.success() ? 0 : 1);
    }

    private static void fail(Context context, String message) {
        context.printLine(message);
        context.setExitCode(1);
    }
}
