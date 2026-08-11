package com.malice.terminalcraft.scada;

import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.DeviceValue;
import net.minecraft.nbt.CompoundTag;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Acceptance tests for persistent multi-page advanced-HMI layouts and full-color rendering. */
public final class ScadaAdvancedHmiTest {
    private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000721");
    private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000722");
    private static final UUID SENSOR = UUID.fromString("00000000-0000-0000-0000-000000000723");
    private static final UUID MONITOR = UUID.fromString("00000000-0000-0000-0000-000000000724");

    private ScadaAdvancedHmiTest() {}

    public static void main(String[] args) {
        com.malice.terminalcraft.testsupport.HeadlessMinecraftBootstrap.initialize();
        layoutsRenderHitTestAuthorizeAndPersist();
        invalidBindingsFailClosed();
        System.out.println("SCADA advanced HMI tests: OK");
    }

    private static void layoutsRenderHitTestAuthorizeAndPersist() {
        ScadaSavedData data = new ScadaSavedData();
        DeviceCallContext admin = player(ADMIN, "admin");
        DeviceCallContext viewer = player(VIEWER, "viewer");
        require(data.initialize(admin, 1).success(), "SCADA initializes");
        require(data.grantRole(admin, viewer.principal(), ScadaRole.VIEWER, 2).success(), "viewer role granted");
        ScadaTag temperature = new ScadaTag("factory.boiler.temperature", SENSOR, "sensor.read",
                List.of(DeviceValue.of("temperature")), "value", "C", 5, 40, "sensor.setpoint");
        require(data.putTag(admin, temperature, 3).success(), "HMI source tag created");
        for (int point = 0; point < 12; point++) {
            data.recordAcquisition(temperature.name(), ScadaScalar.number(68 + point),
                    ScadaQuality.GOOD, "", 4 + point);
        }
        require(data.putAlarm(admin, new ScadaAlarmRule("boiler-hot", temperature.name(),
                ScadaAlarmRule.Operator.ABOVE, ScadaScalar.number(75), ScadaAlarmRule.Severity.CRITICAL,
                1, "Boiler hot"), 16).success(), "HMI alarm created");
        data.recordAcquisition(temperature.name(), ScadaScalar.number(82), ScadaQuality.GOOD, "", 17);

        ScadaHmiDashboard dashboard = new ScadaHmiDashboard("boiler-room", MONITOR, "BOILER ROOM", 5,
                "overview", List.of(new ScadaHmiPage("overview", "Overview", List.of())));
        require(data.putHmiDashboard(admin, dashboard, 18).success(), "advanced HMI created");
        require(data.putHmiPage(admin, dashboard.name(),
                new ScadaHmiPage("controls", "Controls", List.of()), 19).success(), "second page created");
        add(data, admin, new ScadaHmiWidget("heading", ScadaHmiWidget.Type.TEXT,
                0, 0, 12, 1, "", "BOILER PROCESS", 0, 100, null), 20);
        add(data, admin, new ScadaHmiWidget("temperature", ScadaHmiWidget.Type.VALUE,
                0, 1, 4, 3, temperature.name(), "Temperature", 0, 100, null), 21);
        add(data, admin, new ScadaHmiWidget("temperature_gauge", ScadaHmiWidget.Type.GAUGE,
                4, 1, 8, 3, temperature.name(), "Temperature", 0, 100, null), 22);
        add(data, admin, new ScadaHmiWidget("temperature_trend", ScadaHmiWidget.Type.TREND,
                0, 4, 8, 4, temperature.name(), "Trend", 0, 100, null), 23);
        add(data, admin, new ScadaHmiWidget("alarm_panel", ScadaHmiWidget.Type.ALARMS,
                8, 4, 4, 6, "factory.boiler", "Alarms", 0, 100, null), 24);
        add(data, admin, new ScadaHmiWidget("setpoint", ScadaHmiWidget.Type.BUTTON,
                0, 8, 4, 3, temperature.name(), "Set 70 C", 0, 100, ScadaScalar.number(70)), 25);
        add(data, admin, new ScadaHmiWidget("controls", ScadaHmiWidget.Type.PAGE_LINK,
                4, 8, 4, 3, "controls", "Controls", 0, 100, null), 26);

        ScadaHmiDashboard configured = data.hmiDashboard("boiler-room").orElseThrow();
        ScadaHmiFrame frame = ScadaHmiRenderer.render(data, configured, 27, 80, 24, "setpoint");
        require(frame.width() == 80 && frame.height() == 24, "renderer preserves monitor geometry");
        require(frame.palette().size() == 16, "renderer supplies full color palette");
        require(frame.lines().stream().allMatch(line -> line.length() == 80), "text rows are full width");
        require(frame.foreground().stream().allMatch(line -> line.matches("[0-9a-f]{80}")),
                "foreground rows contain one color digit per cell");
        require(frame.background().stream().allMatch(line -> line.matches("[0-9a-f]{80}")),
                "background rows contain one color digit per cell");
        String rendered = String.join("\n", frame.lines());
        require(rendered.contains("82 C"), "value widget renders sampled value and unit");
        require(rendered.contains("#"), "gauge widget renders a proportional bar");
        require(rendered.contains("boiler-hot"), "alarm panel renders active alarms");

        ScadaHmiWidget button = configured.selectedPage().widget("setpoint");
        ScadaHmiRenderer.Bounds bounds = ScadaHmiRenderer.bounds(button, 80, 24);
        require(ScadaHmiRenderer.widgetAt(configured, 80, 24, bounds.left() + 1, bounds.top() + 1, true)
                .id().equals("setpoint"), "touch hit-testing resolves the top interactive widget");
        require(data.selectHmiPage(viewer, configured.name(), "controls", 28).success(),
                "viewers may navigate HMI pages");
        require(!data.putHmiPage(viewer, configured.name(),
                new ScadaHmiPage("forbidden", "Forbidden", List.of()), 29).success(),
                "viewers cannot edit HMI layouts");

        ScadaSavedData restored = ScadaSavedData.load(data.save(new CompoundTag()).copy());
        ScadaHmiDashboard saved = restored.hmiDashboard("boiler-room").orElseThrow();
        require(saved.pages().size() == 2, "HMI pages survive world persistence");
        require(saved.page("overview").widgets().size() == 7, "HMI widget order and layout survive persistence");
        require(saved.activePage().equals("controls"), "selected page survives persistence");
        require(restored.audit(64).stream().anyMatch(entry -> entry.action().equals("hmi.widget.create")),
                "HMI design changes are audited");
    }

    private static void invalidBindingsFailClosed() {
        ScadaSavedData data = new ScadaSavedData();
        DeviceCallContext admin = player(ADMIN, "admin");
        data.initialize(admin, 1);
        data.putHmiDashboard(admin, new ScadaHmiDashboard("invalid-check", MONITOR, "CHECK", 10,
                "overview", List.of()), 2);
        ScadaHmiWidget missingTag = new ScadaHmiWidget("missing", ScadaHmiWidget.Type.VALUE,
                0, 0, 4, 3, "factory.missing", "Missing", 0, 100, null);
        require(!data.putHmiWidget(admin, "invalid-check", "overview", missingTag, 3).success(),
                "tag-bound widgets reject missing sources");
        ScadaHmiWidget missingPage = new ScadaHmiWidget("page", ScadaHmiWidget.Type.PAGE_LINK,
                0, 0, 4, 3, "nowhere", "Nowhere", 0, 100, null);
        require(!data.putHmiWidget(admin, "invalid-check", "overview", missingPage, 4).success(),
                "page links reject missing targets");
    }

    private static void add(ScadaSavedData data, DeviceCallContext admin, ScadaHmiWidget widget, long time) {
        require(data.putHmiWidget(admin, "boiler-room", "overview", widget, time).success(),
                "widget added: " + widget.id());
    }

    private static DeviceCallContext player(UUID id, String name) {
        return DeviceCallContext.player(id, name, Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
