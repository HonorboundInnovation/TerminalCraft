package com.malice.terminalcraft.operations;

import com.malice.terminalcraft.device.DeviceAccess;
import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.DeviceDescriptor;
import com.malice.terminalcraft.device.DeviceRegistry;
import com.malice.terminalcraft.device.ServerDeviceManager;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Immutable, bounded view of the world state against which a project is previewed. */
public final class OperationsDiscoverySnapshot {
    private final long capturedAt;
    private final int requestedLimit;
    private final boolean truncated;
    private final DeviceCallContext context;
    private final List<DeviceDescriptor> devices;
    private final Map<UUID, DeviceDescriptor> byId;
    private final Set<String> installedModSources;
    private final Set<String> observedModSources;

    public OperationsDiscoverySnapshot(long capturedAt, int requestedLimit, boolean truncated,
                                       DeviceCallContext context, List<DeviceDescriptor> devices,
                                       Set<String> installedModSources) {
        if (capturedAt < 0) throw new IllegalArgumentException("capture time must not be negative");
        if (requestedLimit < 1 || requestedLimit > DeviceRegistry.MAX_ENUMERATION_RESULTS) {
            throw new IllegalArgumentException("discovery limit is outside bounds");
        }
        this.capturedAt = capturedAt;
        this.requestedLimit = requestedLimit;
        this.truncated = truncated;
        this.context = Objects.requireNonNull(context, "context");
        if (Objects.requireNonNull(devices, "devices").size() > requestedLimit) {
            throw new IllegalArgumentException("snapshot exceeds its requested limit");
        }
        this.devices = devices.stream().map(value -> Objects.requireNonNull(value, "device"))
                .sorted(Comparator.comparing(value -> value.deviceId().toString())).toList();
        Map<UUID, DeviceDescriptor> indexed = new LinkedHashMap<>();
        for (DeviceDescriptor descriptor : this.devices) {
            if (indexed.putIfAbsent(descriptor.deviceId(), descriptor) != null) {
                throw new IllegalArgumentException("duplicate discovered device: " + descriptor.deviceId());
            }
        }
        this.byId = Map.copyOf(indexed);
        TreeSet<String> installed = new TreeSet<>();
        for (String source : Objects.requireNonNull(installedModSources, "installedModSources")) {
            installed.add(OperationsProject.identifier(source, "installed mod source"));
        }
        this.installedModSources = Set.copyOf(installed);
        TreeSet<String> observed = new TreeSet<>();
        this.devices.forEach(device -> observed.add(device.modSource().toLowerCase(Locale.ROOT)));
        this.observedModSources = Set.copyOf(observed);
    }

    /** Captures server-global devices and the actual loaded-mod set on the logical server thread. */
    public static OperationsDiscoverySnapshot capture(MinecraftServer server,
                                                      DeviceCallContext context, int limit) {
        Objects.requireNonNull(server, "server");
        Set<String> mods = new HashSet<>();
        ModList.get().getMods().forEach(mod -> mods.add(mod.getModId()));
        return capture(ServerDeviceManager.access(server, context), server.overworld().getGameTime(),
                limit, mods);
    }

    /** Testable and host-agnostic capture path for adjacent or synthetic DeviceAccess views. */
    public static OperationsDiscoverySnapshot capture(DeviceAccess access, long capturedAt, int limit,
                                                      Set<String> installedModSources) {
        Objects.requireNonNull(access, "access");
        int bounded = Math.max(1, Math.min(limit, DeviceRegistry.MAX_ENUMERATION_RESULTS));
        int probe = Math.min(DeviceRegistry.MAX_ENUMERATION_RESULTS, bounded + 1);
        List<DeviceDescriptor> discovered = access.descriptors(probe);
        boolean truncated = discovered.size() > bounded
                || (bounded == DeviceRegistry.MAX_ENUMERATION_RESULTS && discovered.size() == bounded);
        List<DeviceDescriptor> visible = discovered.subList(0, Math.min(bounded, discovered.size()));
        return new OperationsDiscoverySnapshot(capturedAt, bounded, truncated, access.context(),
                visible, installedModSources);
    }

    public long capturedAt() { return capturedAt; }
    public int requestedLimit() { return requestedLimit; }
    public boolean truncated() { return truncated; }
    public DeviceCallContext context() { return context; }
    public List<DeviceDescriptor> devices() { return devices; }
    public Set<String> installedModSources() { return installedModSources; }
    public Set<String> observedModSources() { return observedModSources; }
    public Optional<DeviceDescriptor> device(UUID id) {
        return Optional.ofNullable(byId.get(Objects.requireNonNull(id, "id")));
    }
}
