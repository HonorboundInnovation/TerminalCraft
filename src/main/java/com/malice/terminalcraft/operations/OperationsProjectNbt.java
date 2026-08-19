package com.malice.terminalcraft.operations;

import com.malice.terminalcraft.device.DeviceValue;
import com.malice.terminalcraft.device.DeviceValueType;
import com.malice.terminalcraft.device.PrincipalIdentity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Strict bounded NBT codec kept separate so project files can later be imported by a GUI. */
final class OperationsProjectNbt {
    private OperationsProjectNbt() {}

    static CompoundTag save(OperationsProject project) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", project.projectId());
        tag.putInt("Schema", project.schemaVersion());
        tag.putLong("Revision", project.revision());
        tag.putString("Name", project.name());
        tag.put("Owner", savePrincipal(project.owner()));
        tag.putString("Mode", project.mode().name());
        tag.put("Network", saveNetwork(project.network()));

        ListTag devices = new ListTag();
        project.devices().forEach(binding -> devices.add(saveBinding(binding)));
        tag.put("Devices", devices);

        ListTag steps = new ListTag();
        project.deploymentSteps().forEach(step -> steps.add(saveStep(step)));
        tag.put("Steps", steps);
        tag.put("RequiredModSources", saveStrings(project.requiredModSources()));
        return tag;
    }

    static OperationsProject load(CompoundTag tag) {
        if (!tag.hasUUID("Id") || !tag.contains("Owner", Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("project identity is missing");
        }
        int schema = tag.contains("Schema", Tag.TAG_INT) ? tag.getInt("Schema")
                : OperationsProject.CURRENT_SCHEMA_VERSION;
        if (schema != OperationsProject.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported operations project schema: " + schema);
        }

        List<OperationsProject.DeviceBinding> devices = new ArrayList<>();
        ListTag savedDevices = tag.getList("Devices", Tag.TAG_COMPOUND);
        if (savedDevices.size() > OperationsProject.MAX_DEVICES) {
            throw new IllegalArgumentException("saved project exceeds device limit");
        }
        for (int index = 0; index < savedDevices.size(); index++) {
            devices.add(loadBinding(savedDevices.getCompound(index)));
        }

        List<OperationsProject.DeploymentStep> steps = new ArrayList<>();
        ListTag savedSteps = tag.getList("Steps", Tag.TAG_COMPOUND);
        if (savedSteps.size() > OperationsProject.MAX_DEPLOYMENT_STEPS) {
            throw new IllegalArgumentException("saved project exceeds deployment step limit");
        }
        for (int index = 0; index < savedSteps.size(); index++) {
            steps.add(loadStep(savedSteps.getCompound(index)));
        }

        return new OperationsProject(tag.getUUID("Id"), schema, tag.getLong("Revision"),
                tag.getString("Name"), loadPrincipal(tag.getCompound("Owner")),
                OperationsProject.Mode.valueOf(tag.getString("Mode")),
                loadNetwork(tag.getCompound("Network")), devices, steps,
                loadStrings(tag.getList("RequiredModSources", Tag.TAG_STRING),
                        OperationsProject.MAX_REQUIRED_MOD_SOURCES));
    }

    private static CompoundTag savePrincipal(PrincipalIdentity principal) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Kind", principal.kind().name());
        tag.putUUID("Id", principal.id());
        tag.putString("Name", principal.name());
        return tag;
    }

    private static PrincipalIdentity loadPrincipal(CompoundTag tag) {
        if (!tag.hasUUID("Id")) throw new IllegalArgumentException("project owner is missing");
        return new PrincipalIdentity(PrincipalIdentity.Kind.valueOf(tag.getString("Kind")),
                tag.getUUID("Id"), tag.getString("Name"));
    }

    private static CompoundTag saveNetwork(OperationsProject.NetworkPlan network) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", network.networkName());
        tag.putBoolean("Automatic", network.automaticAddressing());
        tag.putInt("DefaultChannel", network.defaultChannel());
        tag.putInt("ReplyChannel", network.replyChannel());
        return tag;
    }

    private static OperationsProject.NetworkPlan loadNetwork(CompoundTag tag) {
        return new OperationsProject.NetworkPlan(tag.getString("Name"), tag.getBoolean("Automatic"),
                tag.getInt("DefaultChannel"), tag.getInt("ReplyChannel"));
    }

    private static CompoundTag saveBinding(OperationsProject.DeviceBinding binding) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Device", binding.deviceId());
        tag.putString("Alias", binding.alias());
        tag.putBoolean("Required", binding.required());
        tag.putString("ExpectedType", binding.expectedType());
        tag.putString("ExpectedModSource", binding.expectedModSource());
        tag.put("Capabilities", saveStrings(binding.requiredCapabilities()));
        return tag;
    }

    private static OperationsProject.DeviceBinding loadBinding(CompoundTag tag) {
        if (!tag.hasUUID("Device")) throw new IllegalArgumentException("bound device is missing");
        return new OperationsProject.DeviceBinding(tag.getUUID("Device"), tag.getString("Alias"),
                tag.getBoolean("Required"), tag.getString("ExpectedType"),
                tag.getString("ExpectedModSource"),
                loadStrings(tag.getList("Capabilities", Tag.TAG_STRING), 64));
    }

    private static CompoundTag saveStep(OperationsProject.DeploymentStep step) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", step.stepId());
        tag.putString("Label", step.label());
        tag.putUUID("Device", step.deviceId());
        tag.putString("Method", step.method());
        tag.put("Arguments", saveValues(step.arguments()));
        step.compensation().ifPresent(compensation -> tag.put("Compensation", saveCompensation(compensation)));
        return tag;
    }

    private static OperationsProject.DeploymentStep loadStep(CompoundTag tag) {
        if (!tag.hasUUID("Id") || !tag.hasUUID("Device")) {
            throw new IllegalArgumentException("deployment step identity is missing");
        }
        Optional<OperationsProject.Compensation> compensation = tag.contains("Compensation", Tag.TAG_COMPOUND)
                ? Optional.of(loadCompensation(tag.getCompound("Compensation"))) : Optional.empty();
        return new OperationsProject.DeploymentStep(tag.getUUID("Id"), tag.getString("Label"),
                tag.getUUID("Device"), tag.getString("Method"),
                loadValues(tag.getList("Arguments", Tag.TAG_COMPOUND), 32), compensation);
    }

    private static CompoundTag saveCompensation(OperationsProject.Compensation compensation) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Method", compensation.method());
        tag.put("Arguments", saveValues(compensation.arguments()));
        return tag;
    }

    private static OperationsProject.Compensation loadCompensation(CompoundTag tag) {
        return new OperationsProject.Compensation(tag.getString("Method"),
                loadValues(tag.getList("Arguments", Tag.TAG_COMPOUND), 32));
    }

    private static ListTag saveStrings(Iterable<String> values) {
        ListTag result = new ListTag();
        values.forEach(value -> result.add(StringTag.valueOf(value)));
        return result;
    }

    private static Set<String> loadStrings(ListTag values, int maximum) {
        if (values.size() > maximum) throw new IllegalArgumentException("saved string set exceeds limit");
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; index < values.size(); index++) result.add(values.getString(index));
        return Set.copyOf(result);
    }

    private static ListTag saveValues(List<DeviceValue> values) {
        ListTag result = new ListTag();
        values.forEach(value -> result.add(saveValue(value)));
        return result;
    }

    private static List<DeviceValue> loadValues(ListTag values, int maximum) {
        if (values.size() > maximum) throw new IllegalArgumentException("saved value list exceeds limit");
        List<DeviceValue> result = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            result.add(loadValue(values.getCompound(index), 1));
        }
        return List.copyOf(result);
    }

    private static CompoundTag saveValue(DeviceValue value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Type", value.type().name());
        if (value instanceof DeviceValue.StringValue string) {
            tag.putString("Text", string.value());
        } else if (value instanceof DeviceValue.NumberValue number) {
            tag.putDouble("Number", number.value());
        } else if (value instanceof DeviceValue.BooleanValue bool) {
            tag.putBoolean("Boolean", bool.value());
        } else if (value instanceof DeviceValue.ListValue list) {
            tag.put("Values", saveValues(list.values()));
        } else if (value instanceof DeviceValue.MapValue map) {
            ListTag entries = new ListTag();
            map.values().forEach((key, child) -> {
                CompoundTag entry = new CompoundTag();
                entry.putString("Key", key);
                entry.put("Value", saveValue(child));
                entries.add(entry);
            });
            tag.put("Entries", entries);
        }
        return tag;
    }

    private static DeviceValue loadValue(CompoundTag tag, int depth) {
        if (depth > DeviceValue.MAX_NESTING_DEPTH) {
            throw new IllegalArgumentException("saved device value exceeds nesting limit");
        }
        DeviceValueType type = DeviceValueType.valueOf(tag.getString("Type"));
        if (type == DeviceValueType.NULL) return DeviceValue.nullValue();
        if (type == DeviceValueType.STRING) return DeviceValue.of(tag.getString("Text"));
        if (type == DeviceValueType.NUMBER) return DeviceValue.of(tag.getDouble("Number"));
        if (type == DeviceValueType.BOOLEAN) return DeviceValue.of(tag.getBoolean("Boolean"));
        if (type == DeviceValueType.LIST) {
            ListTag values = tag.getList("Values", Tag.TAG_COMPOUND);
            if (values.size() > DeviceValue.MAX_COLLECTION_ENTRIES) {
                throw new IllegalArgumentException("saved device list exceeds limit");
            }
            List<DeviceValue> result = new ArrayList<>();
            for (int index = 0; index < values.size(); index++) {
                result.add(loadValue(values.getCompound(index), depth + 1));
            }
            return DeviceValue.list(result);
        }
        ListTag entries = tag.getList("Entries", Tag.TAG_COMPOUND);
        if (entries.size() > DeviceValue.MAX_COLLECTION_ENTRIES) {
            throw new IllegalArgumentException("saved device map exceeds limit");
        }
        Map<String, DeviceValue> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag entry = entries.getCompound(index);
            if (!entry.contains("Value", Tag.TAG_COMPOUND)) {
                throw new IllegalArgumentException("saved device map value is missing");
            }
            String key = entry.getString("Key");
            if (result.putIfAbsent(key, loadValue(entry.getCompound("Value"), depth + 1)) != null) {
                throw new IllegalArgumentException("duplicate saved device map key");
            }
        }
        return DeviceValue.map(result);
    }
}
