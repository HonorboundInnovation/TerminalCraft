package com.malice.terminalcraft.operations;

import com.malice.terminalcraft.device.DeviceAuthorization;
import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.DeviceDescriptor;
import com.malice.terminalcraft.device.DeviceMethodDescriptor;
import com.malice.terminalcraft.device.DeviceParameterDescriptor;
import com.malice.terminalcraft.device.DeviceValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Pure validation pass shared by the future wizard, advanced GUI, CLI, and deployment service. */
public final class OperationsProjectValidator {
    private OperationsProjectValidator() {}

    public enum Severity { ERROR, WARNING, INFO }

    public record Issue(Severity severity, String code, String message,
                        Optional<UUID> deviceId, Optional<UUID> stepId) {
        public Issue {
            severity = Objects.requireNonNull(severity, "severity");
            code = OperationsProject.identifier(code, "validation code");
            message = OperationsProject.boundedText(message, 256, "validation message");
            deviceId = Objects.requireNonNull(deviceId, "deviceId");
            stepId = Objects.requireNonNull(stepId, "stepId");
        }
    }

    public record Report(List<Issue> issues) {
        public Report { issues = List.copyOf(Objects.requireNonNull(issues, "issues")); }
        public boolean ready() { return issues.stream().noneMatch(issue -> issue.severity() == Severity.ERROR); }
        public long errors() { return issues.stream().filter(issue -> issue.severity() == Severity.ERROR).count(); }
        public long warnings() { return issues.stream().filter(issue -> issue.severity() == Severity.WARNING).count(); }
    }

    public static Report validate(OperationsProject project, OperationsDiscoverySnapshot snapshot) {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(snapshot, "snapshot");
        List<Issue> issues = new ArrayList<>();
        if (!DeviceAuthorization.owns(snapshot.context(), project.owner())
                && !DeviceAuthorization.allows(snapshot.context(), OperationsProjectSavedData.ADMIN_PERMISSION)) {
            issues.add(issue(Severity.ERROR, "project.owner_mismatch",
                    "The authenticated principal does not own this project", null, null));
        }
        if (snapshot.truncated()) {
            issues.add(issue(Severity.WARNING, "discovery.truncated",
                    "Device discovery reached its limit; unlisted optional devices may still exist", null, null));
        }
        if (project.mode() == OperationsProject.Mode.EASY && !project.network().automaticAddressing()) {
            issues.add(issue(Severity.ERROR, "network.easy_requires_auto",
                    "Easy mode requires automatic RedNet addressing", null, null));
        }
        if (project.devices().isEmpty()) {
            issues.add(issue(Severity.WARNING, "project.no_devices",
                    "The project has no bound devices", null, null));
        }
        if (project.deploymentSteps().isEmpty()) {
            issues.add(issue(Severity.INFO, "project.no_steps",
                    "The project has no deployment changes", null, null));
        }
        for (String source : project.requiredModSources()) {
            if (!snapshot.installedModSources().contains(source)) {
                issues.add(issue(Severity.ERROR, "integration.missing",
                        "Required integration is not loaded: " + source, null, null));
            }
        }

        for (OperationsProject.DeviceBinding binding : project.devices()) {
            Optional<DeviceDescriptor> found = snapshot.device(binding.deviceId());
            if (found.isEmpty()) {
                issues.add(issue(binding.required() ? Severity.ERROR : Severity.WARNING,
                        "device.missing", "Device is not currently discoverable: " + binding.alias(),
                        binding.deviceId(), null));
                continue;
            }
            DeviceDescriptor descriptor = found.get();
            if (!descriptor.loaded()) {
                issues.add(issue(binding.required() ? Severity.ERROR : Severity.WARNING,
                        "device.unloaded", "Device chunk is unloaded: " + binding.alias(),
                        binding.deviceId(), null));
            } else if (!descriptor.online()) {
                issues.add(issue(binding.required() ? Severity.ERROR : Severity.WARNING,
                        "device.offline", "Device is offline: " + binding.alias(), binding.deviceId(), null));
            }
            if (!binding.expectedType().isEmpty() && !binding.expectedType().equals(descriptor.typeName())) {
                issues.add(issue(Severity.ERROR, "device.type_mismatch",
                        "Device " + binding.alias() + " is " + descriptor.typeName()
                                + ", expected " + binding.expectedType(), binding.deviceId(), null));
            }
            if (!binding.expectedModSource().isEmpty()
                    && !binding.expectedModSource().equals(descriptor.modSource())) {
                issues.add(issue(Severity.ERROR, "device.source_mismatch",
                        "Device " + binding.alias() + " comes from " + descriptor.modSource()
                                + ", expected " + binding.expectedModSource(), binding.deviceId(), null));
            }
            for (String capability : binding.requiredCapabilities()) {
                if (!descriptor.capabilities().contains(capability)) {
                    issues.add(issue(Severity.ERROR, "device.capability_missing",
                            "Device " + binding.alias() + " lacks capability " + capability,
                            binding.deviceId(), null));
                }
            }
        }

        for (OperationsProject.DeploymentStep step : project.deploymentSteps()) {
            DeviceDescriptor descriptor = snapshot.device(step.deviceId()).orElse(null);
            if (descriptor == null) {
                issues.add(issue(Severity.ERROR, "step.device_missing",
                        "Deployment target is not discoverable: " + step.label(),
                        step.deviceId(), step.stepId()));
                continue;
            }
            validateCall(issues, snapshot.context(), descriptor, step.method(), step.arguments(),
                    false, step.deviceId(), step.stepId());
            DeviceMethodDescriptor method = method(descriptor, step.method());
            boolean mutating = method != null && !DeviceCallContext.READ.equals(method.requiredPermission());
            if (mutating && step.compensation().isEmpty()) {
                issues.add(issue(Severity.ERROR, "step.compensation_missing",
                        "Mutating step has no rollback action: " + step.label(),
                        step.deviceId(), step.stepId()));
            }
            step.compensation().ifPresent(compensation -> validateCall(issues, snapshot.context(), descriptor,
                    compensation.method(), compensation.arguments(), true, step.deviceId(), step.stepId()));
        }
        return new Report(issues);
    }

    private static void validateCall(List<Issue> issues, DeviceCallContext context,
                                     DeviceDescriptor descriptor, String methodName,
                                     List<DeviceValue> arguments, boolean compensation,
                                     UUID deviceId, UUID stepId) {
        String prefix = compensation ? "Rollback" : "Deployment";
        String codePrefix = compensation ? "compensation" : "step";
        DeviceMethodDescriptor method = method(descriptor, methodName);
        if (method == null) {
            issues.add(issue(Severity.ERROR, codePrefix + ".method_missing",
                    prefix + " method is unavailable: " + methodName, deviceId, stepId));
            return;
        }
        if (!DeviceAuthorization.allows(context, method.requiredPermission())) {
            issues.add(issue(Severity.ERROR, codePrefix + ".permission_denied",
                    prefix + " requires permission " + method.requiredPermission(), deviceId, stepId));
        }
        int required = (int) method.parameters().stream().filter(DeviceParameterDescriptor::required).count();
        if (arguments.size() < required || arguments.size() > method.parameters().size()) {
            issues.add(issue(Severity.ERROR, codePrefix + ".argument_count",
                    prefix + " method " + methodName + " expects " + required + ".."
                            + method.parameters().size() + " arguments, got " + arguments.size(),
                    deviceId, stepId));
            return;
        }
        for (int index = 0; index < arguments.size(); index++) {
            DeviceParameterDescriptor parameter = method.parameters().get(index);
            if (arguments.get(index).type() != parameter.type()) {
                issues.add(issue(Severity.ERROR, codePrefix + ".argument_type",
                        prefix + " argument " + parameter.name() + " expects "
                                + parameter.type().name().toLowerCase(Locale.ROOT), deviceId, stepId));
            }
        }
    }

    private static DeviceMethodDescriptor method(DeviceDescriptor descriptor, String name) {
        return descriptor.methods().stream().filter(candidate -> candidate.name().equals(name))
                .findFirst().orElse(null);
    }

    private static Issue issue(Severity severity, String code, String message,
                               UUID deviceId, UUID stepId) {
        return new Issue(severity, code, message, Optional.ofNullable(deviceId), Optional.ofNullable(stepId));
    }
}
