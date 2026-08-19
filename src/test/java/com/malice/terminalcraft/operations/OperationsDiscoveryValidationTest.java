package com.malice.terminalcraft.operations;

import com.malice.terminalcraft.device.DeviceAccess;
import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.DeviceDescriptor;
import com.malice.terminalcraft.device.DeviceEventBatch;
import com.malice.terminalcraft.device.DeviceMethodDescriptor;
import com.malice.terminalcraft.device.DeviceParameterDescriptor;
import com.malice.terminalcraft.device.DeviceResult;
import com.malice.terminalcraft.device.DeviceValue;
import com.malice.terminalcraft.device.DeviceValueType;
import com.malice.terminalcraft.device.PrincipalIdentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Headless proof that discovery and preview validation remain bounded and actionable. */
public final class OperationsDiscoveryValidationTest {
    private static final PrincipalIdentity OWNER = PrincipalIdentity.player(
            UUID.fromString("00000000-0000-0000-0000-000000000501"), "Operator");
    private static final UUID PLC = UUID.fromString("00000000-0000-0000-0000-000000000502");

    private OperationsDiscoveryValidationTest() {}

    public static void main(String[] args) {
        DeviceCallContext context = new DeviceCallContext(OWNER,
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        List<DeviceDescriptor> descriptors = List.of(plc(PLC, true, true),
                plc(UUID.fromString("00000000-0000-0000-0000-000000000503"), true, true),
                plc(UUID.fromString("00000000-0000-0000-0000-000000000504"), true, true));
        OperationsDiscoverySnapshot snapshot = OperationsDiscoverySnapshot.capture(
                new FakeAccess(context, descriptors), 120, 2, Set.of("terminalcraft", "securitycraft"));
        require(snapshot.devices().size() == 2 && snapshot.truncated(),
                "discovery enforces the requested bound and reports truncation");

        OperationsProject valid = validProject();
        OperationsProjectValidator.Report report = OperationsProjectValidator.validate(valid, snapshot);
        require(report.ready() && report.warnings() == 1,
                "a valid reversible project remains deployable with a truncation warning: " + report.issues());

        OperationsProject unsafe = new OperationsProject(valid.projectId(), valid.schemaVersion(),
                valid.revision(), valid.name(), valid.owner(), OperationsProject.Mode.ADVANCED,
                valid.network(), List.of(new OperationsProject.DeviceBinding(PLC, "main-plc", true,
                "programmable_logic_controller", "terminalcraft", Set.of("missing_capability"))),
                List.of(new OperationsProject.DeploymentStep(UUID.randomUUID(), "Unsafe write", PLC,
                        "program.set", List.of(DeviceValue.of("END\n")), Optional.empty())),
                Set.of("terminalcraft", "missingmod"));
        OperationsProjectValidator.Report rejected = OperationsProjectValidator.validate(unsafe, snapshot);
        require(!rejected.ready()
                        && rejected.issues().stream().anyMatch(issue -> issue.code().equals("integration.missing"))
                        && rejected.issues().stream().anyMatch(issue -> issue.code().equals("device.capability_missing"))
                        && rejected.issues().stream().anyMatch(issue -> issue.code().equals("step.compensation_missing")),
                "validation exposes missing integrations, capabilities, and rollback actions");

        DeviceCallContext other = DeviceCallContext.player(UUID.randomUUID(), "Other",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        OperationsDiscoverySnapshot foreign = OperationsDiscoverySnapshot.capture(
                new FakeAccess(other, descriptors), 121, 2, Set.of("terminalcraft", "securitycraft"));
        require(OperationsProjectValidator.validate(valid, foreign).issues().stream()
                        .anyMatch(issue -> issue.code().equals("project.owner_mismatch")),
                "preview is bound to the authenticated project owner");
        System.out.println("Operations discovery and validation tests: OK");
    }

    private static OperationsProject validProject() {
        OperationsProject.DeviceBinding binding = new OperationsProject.DeviceBinding(PLC, "main-plc",
                true, "programmable_logic_controller", "terminalcraft", Set.of("plc"));
        OperationsProject.DeploymentStep step = new OperationsProject.DeploymentStep(UUID.randomUUID(),
                "Load controller", PLC, "program.set", List.of(DeviceValue.of("END\n")),
                Optional.of(new OperationsProject.Compensation("program.set",
                        List.of(DeviceValue.of("OLD\n")))));
        return new OperationsProject(UUID.randomUUID(), OperationsProject.CURRENT_SCHEMA_VERSION, 4,
                "Validated Plant", OWNER, OperationsProject.Mode.EASY,
                OperationsProject.NetworkPlan.easyDefaults("plant"), List.of(binding), List.of(step),
                Set.of("terminalcraft", "securitycraft"));
    }

    static DeviceDescriptor plc(UUID id, boolean online, boolean loaded) {
        DeviceMethodDescriptor set = new DeviceMethodDescriptor("program.set", "Set source",
                List.of(new DeviceParameterDescriptor("source", DeviceValueType.STRING, true, "Source")),
                DeviceValueType.NULL, DeviceCallContext.WRITE);
        return new DeviceDescriptor(id, "terminalcraft:plc", "programmable_logic_controller",
                "PLC", "terminalcraft", "terminalcraft:plc-" + id.toString().substring(0, 8),
                Set.of("plc", "remote_programming"), Map.of(), List.of(set), Set.of(),
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE), online, loaded);
    }

    private record FakeAccess(DeviceCallContext context, List<DeviceDescriptor> all) implements DeviceAccess {
        private FakeAccess {
            all = List.copyOf(all);
        }

        @Override public List<DeviceDescriptor> descriptors(int limit) {
            return new ArrayList<>(all.subList(0, Math.min(Math.max(0, limit), all.size())));
        }
        @Override public Optional<DeviceDescriptor> descriptor(UUID deviceId) {
            return all.stream().filter(value -> value.deviceId().equals(deviceId)).findFirst();
        }
        @Override public DeviceResult call(UUID deviceId, String method, List<DeviceValue> arguments) {
            return DeviceResult.success();
        }
        @Override public DeviceEventBatch pollEvents(int limit) { return new DeviceEventBatch(List.of(), 0); }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
