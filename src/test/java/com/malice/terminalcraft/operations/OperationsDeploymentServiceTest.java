package com.malice.terminalcraft.operations;

import com.malice.terminalcraft.device.DeviceAccess;
import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.DeviceDescriptor;
import com.malice.terminalcraft.device.DeviceErrorCode;
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

/** Headless two-phase deployment, stale-plan, replay, and reverse rollback coverage. */
public final class OperationsDeploymentServiceTest {
    private static final PrincipalIdentity OWNER = PrincipalIdentity.player(
            UUID.fromString("00000000-0000-0000-0000-000000000601"), "Commissioner");
    private static final UUID DEVICE = UUID.fromString("00000000-0000-0000-0000-000000000602");

    private OperationsDeploymentServiceTest() {}

    public static void main(String[] args) {
        DeviceCallContext context = new DeviceCallContext(OWNER,
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        FakeAccess access = new FakeAccess(context);
        OperationsProject project = project(List.of(
                step("Set value", "value.set", List.of(DeviceValue.of("new")),
                        new OperationsProject.Compensation("value.set", List.of(DeviceValue.of("old")))),
                step("Fail safely", "always.fail", List.of(),
                        new OperationsProject.Compensation("restore.noop", List.of()))));
        OperationsDiscoverySnapshot snapshot = OperationsDiscoverySnapshot.capture(access, 200, 16,
                Set.of("terminalcraft"));
        OperationsDeploymentService service = new OperationsDeploymentService();
        OperationsDeploymentService.Plan plan = service.preview(project, snapshot);
        require(plan.ready(), "reversible deployment preview is ready");

        OperationsDeploymentService.Result rolledBack = service.apply(plan, project, access, 201);
        require(rolledBack.status() == OperationsDeploymentService.Status.ROLLED_BACK
                        && access.value.equals("old"),
                "failed deployment restores the original value");
        require(access.calls.equals(List.of("value.set:new", "always.fail", "restore.noop", "value.set:old")),
                "rollback compensates the failed and completed steps in reverse order: " + access.calls);
        require(rolledBack.outcomes().stream().filter(outcome -> outcome.phase()
                        == OperationsDeploymentService.Phase.ROLLBACK).count() == 2,
                "rollback results are explicit for GUI progress display");
        require(service.apply(plan, project, access, 202).status() == OperationsDeploymentService.Status.REJECTED,
                "consumed plans cannot be applied twice");

        FakeAccess successAccess = new FakeAccess(context);
        OperationsProject successProject = project(List.of(step("Set value", "value.set",
                List.of(DeviceValue.of("new")),
                new OperationsProject.Compensation("value.set", List.of(DeviceValue.of("old"))))));
        OperationsDeploymentService.Plan successPlan = service.preview(successProject,
                OperationsDiscoverySnapshot.capture(successAccess, 210, 16, Set.of("terminalcraft")));
        require(service.apply(successPlan, successProject, successAccess, 211).status()
                        == OperationsDeploymentService.Status.APPLIED && successAccess.value.equals("new"),
                "validated deployment applies all steps");

        OperationsProject stale = successProject.withRevision(successProject.revision() + 1);
        OperationsDeploymentService.Plan stalePlan = service.preview(successProject,
                OperationsDiscoverySnapshot.capture(successAccess, 212, 16, Set.of("terminalcraft")));
        require(service.apply(stalePlan, stale, successAccess, 213).status()
                        == OperationsDeploymentService.Status.REJECTED,
                "project revision changes invalidate previews");
        System.out.println("Operations deployment service tests: OK");
    }

    private static OperationsProject project(List<OperationsProject.DeploymentStep> steps) {
        return new OperationsProject(UUID.randomUUID(), OperationsProject.CURRENT_SCHEMA_VERSION, 3,
                "Deployment Test", OWNER, OperationsProject.Mode.ADVANCED,
                OperationsProject.NetworkPlan.easyDefaults("deployment-test"),
                List.of(new OperationsProject.DeviceBinding(DEVICE, "controller", true,
                        "test_controller", "terminalcraft", Set.of("configuration"))),
                steps, Set.of("terminalcraft"));
    }

    private static OperationsProject.DeploymentStep step(String label, String method,
                                                         List<DeviceValue> arguments,
                                                         OperationsProject.Compensation compensation) {
        return new OperationsProject.DeploymentStep(UUID.randomUUID(), label, DEVICE, method,
                arguments, Optional.of(compensation));
    }

    private static final class FakeAccess implements DeviceAccess {
        private final DeviceCallContext context;
        private final DeviceDescriptor descriptor;
        private final List<String> calls = new ArrayList<>();
        private String value = "old";

        private FakeAccess(DeviceCallContext context) {
            this.context = context;
            DeviceParameterDescriptor text = new DeviceParameterDescriptor("value",
                    DeviceValueType.STRING, true, "Value");
            this.descriptor = new DeviceDescriptor(DEVICE, "terminalcraft:test", "test_controller",
                    "Test Controller", "terminalcraft", "terminalcraft:test-controller",
                    Set.of("configuration"), Map.of(), List.of(
                    method("value.set", List.of(text)), method("always.fail", List.of()),
                    method("restore.noop", List.of())), Set.of(),
                    Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE), true, true);
        }

        @Override public DeviceCallContext context() { return context; }
        @Override public List<DeviceDescriptor> descriptors(int limit) {
            return limit <= 0 ? List.of() : List.of(descriptor);
        }
        @Override public Optional<DeviceDescriptor> descriptor(UUID deviceId) {
            return DEVICE.equals(deviceId) ? Optional.of(descriptor) : Optional.empty();
        }
        @Override public DeviceResult call(UUID deviceId, String method, List<DeviceValue> arguments) {
            if (!DEVICE.equals(deviceId)) {
                return DeviceResult.failure(DeviceErrorCode.NOT_FOUND, "missing", false);
            }
            if (method.equals("value.set")) {
                String next = ((DeviceValue.StringValue) arguments.get(0)).value();
                calls.add(method + ":" + next);
                value = next;
                return DeviceResult.success();
            }
            calls.add(method);
            return method.equals("always.fail")
                    ? DeviceResult.failure(DeviceErrorCode.ADAPTER_ERROR, "injected failure", false)
                    : DeviceResult.success();
        }
        @Override public DeviceEventBatch pollEvents(int limit) { return new DeviceEventBatch(List.of(), 0); }

        private static DeviceMethodDescriptor method(String name,
                                                     List<DeviceParameterDescriptor> parameters) {
            return new DeviceMethodDescriptor(name, "Test method", parameters,
                    DeviceValueType.NULL, DeviceCallContext.WRITE);
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
