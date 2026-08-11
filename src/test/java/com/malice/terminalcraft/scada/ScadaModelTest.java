package com.malice.terminalcraft.scada;

import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.DeviceValue;
import com.malice.terminalcraft.device.PrincipalIdentity;
import net.minecraft.nbt.CompoundTag;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Acceptance tests for SCADA security, acquisition, history, alarms, dashboards and persistence. */
public final class ScadaModelTest {
    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000701");
    private static final UUID VIEWER_ID = UUID.fromString("00000000-0000-0000-0000-000000000702");
    private static final UUID SENSOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000703");
    private static final UUID MONITOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000704");

    private ScadaModelTest() {}

    public static void main(String[] args) {
        com.malice.terminalcraft.testsupport.HeadlessMinecraftBootstrap.initialize();
        completeProcessLifecyclePersists();
        roleHierarchyFailsClosed();
        boundedHistoryRetainsNewestSamples();
        valuePathsAndDashboardProjectionAreDeterministic();
        commandTagsAreExplicitAndUnambiguous();
        System.out.println("SCADA model tests: OK");
    }

    private static void completeProcessLifecyclePersists() {
        ScadaSavedData data = new ScadaSavedData();
        DeviceCallContext admin = player(ADMIN_ID, "admin");
        require(data.initialize(admin, 1).success(), "first authenticated player initializes SCADA");
        require(!data.initialize(admin, 2).success(), "SCADA cannot be claimed twice");

        ScadaTag tag = temperatureTag();
        require(data.putTag(admin, tag, 3).success(), "engineer creates tag");
        require(data.dueTags(3, 16).equals(List.of(tag)), "new tag is immediately due");
        require(data.recordAcquisition(tag.name(), ScadaScalar.number(90), ScadaQuality.GOOD, "", 4).tagChanged(),
                "first sample changes live state");

        ScadaAlarmRule rule = new ScadaAlarmRule("boiler-high", tag.name(), ScadaAlarmRule.Operator.ABOVE,
                ScadaScalar.number(80), ScadaAlarmRule.Severity.CRITICAL, 2, "Boiler temperature high");
        require(data.putAlarm(admin, rule, 5).success(), "engineer creates alarm");
        ScadaSavedData.Update active = data.recordAcquisition(tag.name(), ScadaScalar.number(90),
                ScadaQuality.GOOD, "", 6);
        require(active.changedAlarms().equals(List.of("boiler-high")), "threshold activates alarm");
        require(data.alarm("boiler-high", 6).orElseThrow().state() == ScadaSavedData.AlarmState.ACTIVE,
                "alarm is active before acknowledgment");
        require(data.acknowledgeAlarm(admin, "boiler-high", 7).success(), "operator acknowledges active alarm");
        require(data.alarm("boiler-high", 7).orElseThrow().state() == ScadaSavedData.AlarmState.ACKNOWLEDGED,
                "acknowledgment has explicit lifecycle state");
        data.recordAcquisition(tag.name(), ScadaScalar.number(77), ScadaQuality.GOOD, "", 8);
        require(data.alarm("boiler-high", 8).orElseThrow().state() == ScadaSavedData.AlarmState.NORMAL,
                "deadband clear returns alarm to normal");

        ScadaAlarmRule quality = new ScadaAlarmRule("boiler-signal-bad", tag.name(),
                ScadaAlarmRule.Operator.BAD_QUALITY, null, ScadaAlarmRule.Severity.HIGH, 0, "Signal unavailable");
        require(data.putAlarm(admin, quality, 9).success(), "quality alarm created");
        require(data.markStale(109).stream().anyMatch(update -> update.tagName().equals(tag.name())),
                "overdue sample materializes stale quality");
        require(data.alarm("boiler-signal-bad", 109).orElseThrow().state() == ScadaSavedData.AlarmState.ACTIVE,
                "stale quality activates quality alarm");
        require(data.shelveAlarm(admin, "boiler-signal-bad", 10, 110).success(), "active alarm shelved");
        require(data.alarm("boiler-signal-bad", 110).orElseThrow().state() == ScadaSavedData.AlarmState.SHELVED,
                "shelved lifecycle visible");
        require(data.expireShelves(120).equals(List.of("boiler-signal-bad")), "shelf expires on logical time");

        require(data.putDashboard(admin, new ScadaDashboard(MONITOR_ID, "factory.boiler", "BOILER HMI", 20), 121).success(),
                "engineer binds monitor dashboard");
        require(data.grantRole(admin, PrincipalIdentity.player(VIEWER_ID, "viewer"), ScadaRole.VIEWER, 122).success(),
                "administrator grants viewer role");

        CompoundTag serialized = data.save(new CompoundTag());
        ScadaSavedData restored = ScadaSavedData.load(serialized.copy());
        require(restored.tag(tag.name()).orElseThrow().equals(tag), "tag definition survives persistence");
        require(restored.snapshot(tag.name(), 109).orElseThrow().value().equivalent(ScadaScalar.number(77)),
                "latest value survives persistence");
        require(restored.history(tag.name(), 16).size() == 4, "historian including quality transition survives persistence");
        require(restored.alarm("boiler-high", 8).orElseThrow().state() == ScadaSavedData.AlarmState.NORMAL,
                "alarm lifecycle survives persistence");
        require(restored.dashboards().size() == 1, "dashboard survives persistence");
        require(restored.role(PrincipalIdentity.player(VIEWER_ID, "renamed")).orElseThrow() == ScadaRole.VIEWER,
                "role authority uses typed UUID, not display name");
        require(!restored.audit(64).isEmpty(), "audit trail survives persistence");
    }

    private static void roleHierarchyFailsClosed() {
        ScadaSavedData data = new ScadaSavedData();
        DeviceCallContext admin = player(ADMIN_ID, "admin");
        DeviceCallContext viewer = player(VIEWER_ID, "viewer");
        require(data.initialize(admin, 1).success(), "admin initialized");
        require(data.grantRole(admin, viewer.principal(), ScadaRole.VIEWER, 2).success(), "viewer granted");
        require(data.authorized(viewer, ScadaAction.VIEW), "viewer can view");
        require(!data.authorized(viewer, ScadaAction.CONTROL), "viewer cannot control");
        require(!data.putTag(viewer, temperatureTag(), 3).success(), "viewer cannot configure");
        require(!data.revokeRole(viewer, admin.principal(), 4).success(), "viewer cannot manage security");
        require(!data.revokeRole(admin, admin.principal(), 5).success(), "final administrator cannot be removed");
        require(!data.grantRole(admin, admin.principal(), ScadaRole.OPERATOR, 6).success(),
                "final administrator cannot be demoted");
    }

    private static void boundedHistoryRetainsNewestSamples() {
        ScadaSavedData data = new ScadaSavedData();
        DeviceCallContext admin = player(ADMIN_ID, "admin");
        data.initialize(admin, 1);
        data.putTag(admin, temperatureTag(), 2);
        int supplied = ScadaSavedData.MAX_HISTORY_PER_TAG + 7;
        for (int index = 0; index < supplied; index++) {
            data.recordAcquisition("factory.boiler.temperature", ScadaScalar.number(index),
                    ScadaQuality.GOOD, "", index + 3L);
        }
        List<ScadaSample> retained = data.history("factory.boiler.temperature", ScadaSavedData.MAX_ENUMERATION);
        require(retained.size() == ScadaSavedData.MAX_ENUMERATION, "public history enumeration remains bounded");
        require(retained.get(retained.size() - 1).value().equivalent(ScadaScalar.number(supplied - 1)),
                "historian retains newest sample");
        CompoundTag saved = data.save(new CompoundTag());
        require(ScadaSavedData.load(saved).history("factory.boiler.temperature", ScadaSavedData.MAX_ENUMERATION)
                .get(ScadaSavedData.MAX_ENUMERATION - 1).value().equivalent(ScadaScalar.number(supplied - 1)),
                "bounded historian reload retains newest sample");
    }

    private static void valuePathsAndDashboardProjectionAreDeterministic() {
        DeviceValue root = DeviceValue.map(java.util.Map.of("nested", DeviceValue.list(List.of(
                DeviceValue.map(java.util.Map.of("value", DeviceValue.of(42)))))));
        require(ScadaRuntime.resolve(root, "nested/0/value").orElseThrow() instanceof DeviceValue.NumberValue number
                        && number.value() == 42,
                "slash paths traverse maps and lists without confusing dotted keys");

        ScadaSavedData data = new ScadaSavedData();
        DeviceCallContext admin = player(ADMIN_ID, "admin");
        data.initialize(admin, 1);
        data.putTag(admin, temperatureTag(), 2);
        data.recordAcquisition("factory.boiler.temperature", ScadaScalar.number(75), ScadaQuality.GOOD, "", 3);
        List<String> lines = ScadaRuntime.dashboardLines(data,
                new ScadaDashboard(MONITOR_ID, "factory.boiler", "BOILER", 20), 3, 8, 40);
        require(lines.stream().anyMatch(line -> line.contains("factory.boiler")),
                "dashboard includes matching process tag");
        require(lines.stream().noneMatch(line -> line.length() > 40), "dashboard respects monitor width");
    }

    private static ScadaTag temperatureTag() {
        return new ScadaTag("factory.boiler.temperature", SENSOR_ID, "sensor.read",
                List.of(DeviceValue.of("temperature")), "value", "C", 20, 100, "sensor.setpoint");
    }

    private static void commandTagsAreExplicitAndUnambiguous() {
        ScadaTag command = new ScadaTag("factory.line1.plc.start", SENSOR_ID, "status",
                List.of(), "state", "", 20, 100, "@control.run");
        require(!command.writeRequiresValue(), "@ write binding is recognized as a no-value command");
        require(command.callableWriteMethod().equals("control.run"), "command marker is not sent to the device");
        require(command.writeMethod().equals("@control.run"), "command intent survives persistent definition");
        require(temperatureTag().writeRequiresValue(), "ordinary write binding still requires a value");
    }

    private static DeviceCallContext player(UUID id, String name) {
        return DeviceCallContext.player(id, name, Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
