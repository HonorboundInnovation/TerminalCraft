package com.malice.terminalcraft.operations;

import com.malice.terminalcraft.device.DeviceValue;
import com.malice.terminalcraft.device.PrincipalIdentity;
import com.malice.terminalcraft.network.RednetAutoConfiguration;
import com.malice.terminalcraft.network.RednetNetworkName;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Versioned, server-authoritative configuration document consumed by setup and management UIs.
 * The project intentionally stores stable device identities and typed calls instead of block
 * positions, allowing devices to move without invalidating a commissioned system.
 */
public record OperationsProject(UUID projectId, int schemaVersion, long revision, String name,
                                PrincipalIdentity owner, Mode mode, NetworkPlan network,
                                List<DeviceBinding> devices, List<DeploymentStep> deploymentSteps,
                                Set<String> requiredModSources) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int MAX_NAME_LENGTH = 96;
    public static final int MAX_DEVICES = 128;
    public static final int MAX_DEPLOYMENT_STEPS = 256;
    public static final int MAX_REQUIRED_MOD_SOURCES = 32;
    public static final int MAX_PROJECT_VALUE_NODES = 8192;
    public static final int MAX_PROJECT_VALUE_TEXT = 65_536;

    public enum Mode { EASY, ADVANCED }

    public OperationsProject {
        projectId = Objects.requireNonNull(projectId, "projectId");
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported operations project schema: " + schemaVersion);
        }
        if (revision < 0) throw new IllegalArgumentException("project revision must not be negative");
        name = boundedText(name, MAX_NAME_LENGTH, "project name");
        owner = Objects.requireNonNull(owner, "owner");
        mode = Objects.requireNonNull(mode, "mode");
        network = Objects.requireNonNull(network, "network");
        devices = List.copyOf(Objects.requireNonNull(devices, "devices"));
        deploymentSteps = List.copyOf(Objects.requireNonNull(deploymentSteps, "deploymentSteps"));
        if (devices.size() > MAX_DEVICES) throw new IllegalArgumentException("too many project devices");
        if (deploymentSteps.size() > MAX_DEPLOYMENT_STEPS) {
            throw new IllegalArgumentException("too many deployment steps");
        }

        TreeSet<String> sources = new TreeSet<>();
        for (String source : Objects.requireNonNull(requiredModSources, "requiredModSources")) {
            sources.add(identifier(source, "required mod source"));
        }
        if (sources.size() > MAX_REQUIRED_MOD_SOURCES) {
            throw new IllegalArgumentException("too many required mod sources");
        }
        requiredModSources = Set.copyOf(sources);

        Set<UUID> deviceIds = new HashSet<>();
        Set<String> aliases = new HashSet<>();
        for (DeviceBinding binding : devices) {
            Objects.requireNonNull(binding, "device binding");
            if (!deviceIds.add(binding.deviceId())) {
                throw new IllegalArgumentException("duplicate project device: " + binding.deviceId());
            }
            if (!aliases.add(binding.alias())) {
                throw new IllegalArgumentException("duplicate device alias: " + binding.alias());
            }
        }

        Set<UUID> stepIds = new HashSet<>();
        List<DeviceValue> values = new ArrayList<>();
        for (DeploymentStep step : deploymentSteps) {
            Objects.requireNonNull(step, "deployment step");
            if (!stepIds.add(step.stepId())) {
                throw new IllegalArgumentException("duplicate deployment step: " + step.stepId());
            }
            if (!deviceIds.contains(step.deviceId())) {
                throw new IllegalArgumentException("deployment target is not bound: " + step.deviceId());
            }
            values.addAll(step.arguments());
            step.compensation().ifPresent(value -> values.addAll(value.arguments()));
        }
        if (!values.isEmpty()) {
            DeviceValue.requireWithinBudget(values, MAX_PROJECT_VALUE_NODES,
                    MAX_PROJECT_VALUE_TEXT, "operations project values");
        }
    }

    public static OperationsProject draft(UUID projectId, String name, PrincipalIdentity owner,
                                          Mode mode, NetworkPlan network,
                                          List<DeviceBinding> devices,
                                          List<DeploymentStep> deploymentSteps,
                                          Set<String> requiredModSources) {
        return new OperationsProject(projectId, CURRENT_SCHEMA_VERSION, 0, name, owner, mode,
                network, devices, deploymentSteps, requiredModSources);
    }

    public OperationsProject withRevision(long nextRevision) {
        return new OperationsProject(projectId, schemaVersion, nextRevision, name, owner, mode,
                network, devices, deploymentSteps, requiredModSources);
    }

    public record NetworkPlan(String networkName, boolean automaticAddressing,
                              int defaultChannel, int replyChannel) {
        public NetworkPlan {
            networkName = RednetNetworkName.normalize(networkName)
                    .orElseThrow(() -> new IllegalArgumentException("invalid RedNet network name"));
            requirePort(defaultChannel, "default channel");
            requirePort(replyChannel, "reply channel");
            if (defaultChannel == replyChannel) {
                throw new IllegalArgumentException("default and reply channels must differ");
            }
        }

        public static NetworkPlan easyDefaults(String networkName) {
            return new NetworkPlan(networkName, true, RednetAutoConfiguration.DEFAULT_CHANNEL,
                    RednetAutoConfiguration.DEFAULT_REPLY_CHANNEL);
        }
    }

    public record DeviceBinding(UUID deviceId, String alias, boolean required,
                                String expectedType, String expectedModSource,
                                Set<String> requiredCapabilities) {
        public DeviceBinding {
            deviceId = Objects.requireNonNull(deviceId, "deviceId");
            alias = canonicalAlias(alias);
            expectedType = optionalIdentifier(expectedType, "expected type");
            expectedModSource = optionalIdentifier(expectedModSource, "expected mod source");
            TreeSet<String> capabilities = new TreeSet<>();
            for (String capability : Objects.requireNonNull(requiredCapabilities, "requiredCapabilities")) {
                capabilities.add(identifier(capability, "required capability"));
            }
            if (capabilities.size() > 64) throw new IllegalArgumentException("too many required capabilities");
            requiredCapabilities = Set.copyOf(capabilities);
        }
    }

    public record Compensation(String method, List<DeviceValue> arguments) {
        public Compensation {
            method = identifier(method, "compensation method");
            arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
            if (arguments.size() > 32) throw new IllegalArgumentException("too many compensation arguments");
            DeviceValue.requireWithinBudget(arguments, DeviceValue.MAX_TOTAL_NODES,
                    DeviceValue.MAX_TOTAL_TEXT_LENGTH, "compensation arguments");
        }
    }

    public record DeploymentStep(UUID stepId, String label, UUID deviceId, String method,
                                 List<DeviceValue> arguments, Optional<Compensation> compensation) {
        public DeploymentStep {
            stepId = Objects.requireNonNull(stepId, "stepId");
            label = boundedText(label, 96, "step label");
            deviceId = Objects.requireNonNull(deviceId, "deviceId");
            method = identifier(method, "deployment method");
            arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
            compensation = Objects.requireNonNull(compensation, "compensation");
            if (arguments.size() > 32) throw new IllegalArgumentException("too many deployment arguments");
            DeviceValue.requireWithinBudget(arguments, DeviceValue.MAX_TOTAL_NODES,
                    DeviceValue.MAX_TOTAL_TEXT_LENGTH, "deployment arguments");
        }

        public DeploymentStep(UUID stepId, String label, UUID deviceId, String method,
                              List<DeviceValue> arguments, Compensation compensation) {
            this(stepId, label, deviceId, method, arguments, Optional.ofNullable(compensation));
        }
    }

    private static void requirePort(int value, String label) {
        if (value < 0 || value > 65_535) throw new IllegalArgumentException(label + " must be 0..65535");
    }

    private static String canonicalAlias(String value) {
        String result = Objects.requireNonNull(value, "alias").trim().toLowerCase(Locale.ROOT);
        if (!result.matches("[a-z][a-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("invalid device alias: " + value);
        }
        return result;
    }

    private static String optionalIdentifier(String value, String label) {
        String result = Objects.requireNonNullElse(value, "").trim().toLowerCase(Locale.ROOT);
        return result.isEmpty() ? "" : identifier(result, label);
    }

    static String identifier(String value, String label) {
        String result = Objects.requireNonNull(value, label).trim().toLowerCase(Locale.ROOT);
        if (!result.matches("[a-z][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("invalid " + label + ": " + value);
        }
        return result;
    }

    static String boundedText(String value, int maximum, String label) {
        String result = Objects.requireNonNull(value, label).trim();
        if (result.isEmpty() || result.length() > maximum || result.indexOf('\n') >= 0
                || result.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(label + " must be non-blank, single-line, and bounded");
        }
        return result;
    }
}
