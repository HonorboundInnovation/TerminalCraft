package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.persistence.PersistedDataLimits;
import com.malice.terminalcraft.persistence.PersistedDataVersions;
import com.malice.terminalcraft.network.RednetNetwork;
import com.malice.terminalcraft.network.RednetDeliveryRuntime;
import com.malice.terminalcraft.network.RednetNetworkName;
import com.malice.terminalcraft.network.MonitorRemoteRequest;
import com.malice.terminalcraft.network.SensorRemoteRequest;
import com.malice.terminalcraft.network.ScadaRemoteRequest;
import com.malice.terminalcraft.network.RednetAutoConfiguration;
import com.malice.terminalcraft.network.RednetLease;
import com.malice.terminalcraft.registry.ModRegistries;
import com.malice.terminalcraft.device.ModemDevice;
import com.malice.terminalcraft.device.ServerDeviceManager;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Wireless/wired modem peripheral. Participates in {@link RednetNetwork}.
 */
public class ModemBlockEntity extends BlockEntity implements ModemDevice {
    public static final int DEFAULT_RANGE = 64;
    public static final int MAX_OPEN_CHANNELS = 128;
    public static final int MAX_RECEIVE_BATCH = 32;

    private UUID modemId = UUID.randomUUID();
    private final Set<Integer> openChannels = new HashSet<>();
    private final Set<Integer> cableDefaultChannels = new HashSet<>();
    private boolean wireless = true;
    private int range = DEFAULT_RANGE;
    private String label = "modem";
    private String hostname = "";
    private String networkName = "";
    private boolean automaticSetup = true;
    private final Map<String, Integer> services = new TreeMap<>();
    private final Map<String, Integer> monitorServices = new TreeMap<>();
    private final Map<String, Integer> sensorServices = new TreeMap<>();
    private final Map<String, Integer> scadaServices = new TreeMap<>();

    public ModemBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistries.MODEM_BLOCK_ENTITY.get(), pos, state);
    }

    public UUID getModemId() { return modemId; }
    public String getDeviceAddress() {
        String dimension = level == null ? "unbound" : level.dimension().location().toString();
        return dimension + ":" + worldPosition.getX() + "," + worldPosition.getY() + "," + worldPosition.getZ();
    }
    @Override public int maxOpenChannels() { return MAX_OPEN_CHANNELS; }
    @Override public int maxReceiveBatch() { return MAX_RECEIVE_BATCH; }
    @Override public String label() { return getLabel(); }
    @Override public boolean wireless() { return isWireless(); }
    @Override public int range() { return getRange(); }
    @Override public List<Integer> openChannels() { return getOpenChannels(); }
    @Override public boolean open(int channel) { return openChannel(channel); }
    @Override public boolean close(int channel) { return closeChannel(channel); }
    @Override public List<String> receive(int limit) { return receiveMessages(limit); }

    public boolean isWireless() {
        return wireless;
    }

    public void setWireless(boolean wireless) {
        if (this.wireless == wireless) {
            return;
        }
        this.wireless = wireless;
        if (level != null && !level.isClientSide) {
            RednetNetwork.rebind(level, modemId, worldPosition, getOpenChannels(), wireless, range);
            if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                com.malice.terminalcraft.network.WiredNetworkTopology.invalidate(serverLevel, worldPosition);
            }
        }
        setChanged();
    }

    public int getRange() {
        return range;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label == null || label.isBlank() ? "modem" : label.trim();
        setChanged();
    }

    public String getHostname() {
        if (!hostname.isBlank()) return hostname;
        String registered = level == null ? "" : RednetNetwork.hostname(level, modemId);
        return registered.isBlank() && automaticSetup
                ? RednetNetwork.automaticHostname(modemId) : registered;
    }

    public boolean automaticSetup() {
        return automaticSetup;
    }

    /** Enables or disables beginner auto-provisioning while preserving explicit advanced settings. */
    public boolean setAutomaticSetup(boolean enabled) {
        if (automaticSetup == enabled) {
            if (enabled) ensureAutomaticSetup();
            return true;
        }
        automaticSetup = enabled;
        if (!enabled) {
            cableDefaultChannels.clear();
            if (level != null && !level.isClientSide) {
                if (hostname.isBlank()) RednetNetwork.unregisterHost(level, modemId);
                RednetNetwork.releaseLease(level, modemId);
            }
            if (openChannels.contains(RednetAutoConfiguration.DEFAULT_CHANNEL)) {
                closeChannel(RednetAutoConfiguration.DEFAULT_CHANNEL);
            }
        } else {
            ensureAutomaticSetup();
        }
        setChanged();
        return true;
    }

    /** Returns the configured logical wired network, or an empty string for legacy automatic mode. */
    public String getNetworkName() {
        return networkName;
    }

    /** Sets a persistent logical wired network name; blank input restores legacy automatic mode. */
    public boolean setNetworkName(String requested) {
        String canonical = requested == null || requested.isBlank()
                ? "" : RednetNetworkName.normalize(requested).orElse(null);
        if (canonical == null) return false;
        if (!canonical.equals(networkName)) {
            networkName = canonical;
            setChanged();
            invalidateWiredTopology();
            if (level != null && !level.isClientSide) {
                RednetNetwork.ensureLease(level, modemId, worldPosition, wireless, networkName);
            }
        }
        return true;
    }

    private void invalidateWiredTopology() {
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            com.malice.terminalcraft.network.WiredNetworkTopology.invalidate(serverLevel, worldPosition);
        }
    }

    /** Registers a unique, dimension-local RedNet host name. */
    public boolean setHostname(String requested) {
        if (level == null || level.isClientSide) return false;
        if (requested == null || requested.isBlank()) {
            RednetNetwork.unregisterHost(level, modemId);
            hostname = "";
            if (automaticSetup) RednetNetwork.ensureAutomaticHost(level, modemId);
            setChanged();
            return true;
        }
        if (!RednetNetwork.registerHost(level, modemId, requested)) return false;
        hostname = RednetNetwork.hostname(level, modemId);
        setChanged();
        return true;
    }

    /** Returns bounded, deterministic snapshots of this modem's live RedNet interfaces. */
    public List<String> interfaceDiagnostics() {
        if (level == null || level.isClientSide) return List.of();
        return RednetNetwork.interfaces(level, modemId).stream().map(value -> {
            String ports = value.openPorts().isEmpty() ? "-" : value.openPorts().stream()
                    .map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
            String rangeValue = value.transport() == com.malice.terminalcraft.network.RednetInterface.Transport.WIRELESS
                    ? " range=" + value.range() : "";
            return "address=" + value.address().encoded()
                    + " transport=" + value.transport().name().toLowerCase(java.util.Locale.ROOT)
                    + " dimension=" + value.dimension()
                    + " position=" + value.position().getX() + "," + value.position().getY() + "," + value.position().getZ()
                    + rangeValue + " ports=" + ports;
        }).toList();
    }

    public List<String> visibleHosts(int maximum) {
        return level == null ? List.of() : RednetNetwork.reachableHosts(
                level, modemId, worldPosition, wireless, range, maximum);
    }

    /** Lists the bounded DNS-style hostname records currently registered in this dimension. */
    public List<String> dnsDiagnostics(int maximum) {
        if (level == null || level.isClientSide) return List.of();
        return RednetNetwork.addresses(level, maximum).stream()
                .map(address -> address.hostname() + " -> " + address.encoded())
                .toList();
    }

    /** Resolves a hostname, raw UUID, or identity-preserving rednet address for shell diagnostics. */
    public String resolveDiagnostics(String selector) {
        if (level == null || level.isClientSide) return "";
        return RednetNetwork.resolveAddress(level, selector)
                .map(address -> "name=" + address.displayName()
                        + " id=" + address.deviceId()
                        + " address=" + address.encoded())
                .orElse("");
    }

    /** Lists bounded, deterministic diagnostics for every reachable named neighbor. */
    public List<String> neighborDiagnostics(int maximum) {
        if (level == null || level.isClientSide) return List.of();
        return RednetNetwork.neighbors(level, modemId, maximum).stream().map(route -> {
            com.malice.terminalcraft.network.RednetInterface destination = route.destination();
            String ports = destination.openPorts().isEmpty() ? "-" : destination.openPorts().stream()
                    .map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
            return "address=" + destination.address().encoded()
                    + " transport=" + destination.transport().name().toLowerCase(java.util.Locale.ROOT)
                    + " position=" + position(destination.position())
                    + " router_hops=" + route.routerHops() + " ports=" + ports;
        }).toList();
    }

    /** Returns a bounded, deterministic route description for one named live destination. */
    /** Performs a bounded, side-effect-free reachability probe to a named live modem. */
    public List<String> pingDiagnostics(String destination) {
        if (level == null || level.isClientSide) return List.of();
        return RednetNetwork.route(level, modemId, destination)
                .map(route -> List.of("reachable target=" + route.destination().address().encoded()
                        + " transport=" + route.source().transport().name().toLowerCase(java.util.Locale.ROOT)
                        + " router_hops=" + route.routerHops()))
                .orElseGet(List::of);
    }

    public List<String> routeDiagnostics(String destination) {
        if (level == null || level.isClientSide) return List.of();
        return RednetNetwork.route(level, modemId, destination).map(route -> {
            List<String> lines = new ArrayList<>();
            lines.add("destination=" + route.destination().address().encoded()
                    + " transport=" + route.source().transport().name().toLowerCase(java.util.Locale.ROOT)
                    + " router_hops=" + route.routerHops());
            for (int i = 0; i < route.routerPasses().size(); i++) {
                com.malice.terminalcraft.network.WiredNetworkTopology.RouterPass pass = route.routerPasses().get(i);
                lines.add("pass=" + (i + 1)
                        + " ingress=" + position(pass.ingressRouter()) + ":" + pass.ingressFace().getName()
                        + " egress=" + position(pass.egressRouter()) + ":" + pass.egressFace().getName()
                        + " routers=" + pass.traversedRouters().size());
            }
            return List.copyOf(lines);
        }).orElseGet(List::of);
    }

    /** Aggregate packet-runtime diagnostics that never expose payloads or endpoint identities. */
    public List<String> packetDiagnostics() {
        if (level == null || level.isClientSide) return List.of();
        RednetNetwork.RuntimeDiagnostics runtime = RednetNetwork.runtimeDiagnostics(level);
        RednetDeliveryRuntime.Diagnostics deliveries = runtime.deliveries();
        RednetNetwork.RejectionDiagnostics rejections = runtime.rejections();
        return List.of(
                "runtime subscriptions=" + runtime.subscriptions()
                        + " hosts=" + runtime.registeredHosts()
                        + " services=" + runtime.registeredServices()
                        + " local_pending=" + pendingCount(),
                "queues application=" + runtime.applicationQueues() + "/" + runtime.applicationMessages()
                        + " control=" + runtime.controlQueues() + "/" + runtime.controlMessages()
                        + " aggregate=" + runtime.queuedEntries() + "/" + runtime.queuedBytes()
                        + " tracked=" + runtime.trackedQueues(),
                "traffic tick=" + runtime.quotaGameTime()
                        + " messages=" + runtime.submittedMessages()
                        + " bytes=" + runtime.submittedBytes()
                        + " senders=" + runtime.trackedSenders(),
                "deliveries retained=" + deliveries.retained()
                        + " pending=" + deliveries.pending()
                        + " attempting=" + deliveries.attempting()
                        + " accepted=" + deliveries.accepted()
                        + " acknowledged=" + deliveries.acknowledged()
                        + " rejected=" + deliveries.rejected()
                        + " timed_out=" + deliveries.timedOut(),
                "rejections malformed=" + rejections.malformed()
                        + " rate_limited=" + rejections.rateLimited()
                        + " application_full=" + rejections.applicationQueueFull()
                        + " control_full=" + rejections.controlQueueFull());
    }

    /** Loaded physical-topology, route-cache, and index diagnostics for this modem's dimension. */
    public List<String> topologyDiagnostics() {
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) return List.of();
        com.malice.terminalcraft.network.WiredNetworkTopology.CacheDiagnostics cache =
                com.malice.terminalcraft.network.WiredNetworkTopology.cacheDiagnostics(serverLevel);
        com.malice.terminalcraft.network.WiredNetworkTopology.IndexDiagnostics index =
                com.malice.terminalcraft.network.WiredNetworkTopology.indexDiagnostics(serverLevel);
        List<String> lines = new ArrayList<>();
        if (wireless) {
            lines.add("topology transport=wireless physical=not_applicable");
        } else {
            List<com.malice.terminalcraft.network.WiredNetworkTopology.Subnet> subnets =
                    com.malice.terminalcraft.network.WiredNetworkTopology.modemSubnets(serverLevel, worldPosition);
            lines.add("topology transport=wired attachments=" + subnets.size());
            for (int i = 0; i < subnets.size(); i++) {
                com.malice.terminalcraft.network.WiredNetworkTopology.Subnet subnet = subnets.get(i);
                lines.add("subnet=" + (i + 1)
                        + " id=" + subnet.id().displayName()
                        + " nodes=" + subnet.nodeCount()
                        + " modems=" + subnet.modemCount()
                        + " truncated=" + subnet.truncated());
            }
        }
        lines.add("cache revision=" + cache.revision()
                + " entries=" + cache.entries()
                + " computations=" + cache.computations()
                + " hits=" + cache.hits());
        lines.add("index revision=" + index.revisions()
                + " nodes=" + index.nodes()
                + " edges=" + index.directedEdges()
                + " refreshed=" + index.refreshedPositions()
                + " truncated=" + index.truncated());
        return List.copyOf(lines);
    }

    private static String position(BlockPos position) {
        return position.getX() + "," + position.getY() + "," + position.getZ();
    }

    public boolean registerService(String requestedName, int port) {
        int boundedPort = clamp(port);
        String canonical = com.malice.terminalcraft.network.RednetHostName.normalize(requestedName).orElse("");
        if (level == null || level.isClientSide || !openChannels.contains(boundedPort)
                || canonical.isEmpty() || (!services.containsKey(canonical) && !monitorServices.containsKey(canonical)
                && !sensorServices.containsKey(canonical) && !scadaServices.containsKey(canonical)
                && serviceCount() >= 32)
                || !RednetNetwork.registerService(level, modemId, canonical, boundedPort)) return false;
        monitorServices.remove(canonical);
        sensorServices.remove(canonical);
        scadaServices.remove(canonical);
        services.put(canonical, boundedPort);
        setChanged();
        return true;
    }

    public boolean unregisterService(String requestedName) {
        if (level == null || level.isClientSide) return false;
        String canonical = com.malice.terminalcraft.network.RednetHostName.normalize(requestedName).orElse("");
        if (canonical.isEmpty() || !RednetNetwork.unregisterService(level, modemId, canonical)) return false;
        services.remove(canonical);
        monitorServices.remove(canonical);
        sensorServices.remove(canonical);
        scadaServices.remove(canonical);
        setChanged();
        return true;
    }

    public List<String> localServices() {
        return services.entrySet().stream().map(entry -> entry.getKey() + " " + entry.getValue()).toList();
    }

    public boolean registerMonitorService(String requestedName, int port) {
        int checkedPort = port;
        if (level == null || level.isClientSide || checkedPort < 0 || checkedPort > 65535
                || !openChannels.contains(checkedPort) || NetworkMonitorService.resolveTarget(this) == null) return false;
        String canonical = com.malice.terminalcraft.network.RednetHostName.normalize(requestedName).orElse("");
        if (canonical.isEmpty() || (!services.containsKey(canonical) && !monitorServices.containsKey(canonical)
                && !sensorServices.containsKey(canonical) && !scadaServices.containsKey(canonical)
                && serviceCount() >= 32) || !RednetNetwork.registerService(
                level, modemId, canonical, checkedPort, MonitorRemoteRequest.PROTOCOL)) return false;
        services.remove(canonical);
        sensorServices.remove(canonical);
        scadaServices.remove(canonical);
        monitorServices.put(canonical, checkedPort);
        setChanged();
        return true;
    }

    public boolean unregisterMonitorService(String requestedName) {
        if (level == null || level.isClientSide) return false;
        String canonical = com.malice.terminalcraft.network.RednetHostName.normalize(requestedName).orElse("");
        if (canonical.isEmpty() || !monitorServices.containsKey(canonical)
                || !RednetNetwork.unregisterService(level, modemId, canonical)) return false;
        monitorServices.remove(canonical);
        setChanged();
        return true;
    }

    public List<String> monitorServices() {
        return monitorServices.entrySet().stream()
                .map(entry -> entry.getKey() + " " + entry.getValue()).toList();
    }

    boolean hasMonitorServiceOnPort(int port) { return monitorServices.containsValue(port); }

    public boolean transmitMonitorService(String serviceName, String payload) {
        if (level == null || level.isClientSide || openChannels.isEmpty()) return false;
        return RednetNetwork.transmitService(level, modemId, worldPosition, serviceName, 0, payload,
                wireless, range, MonitorRemoteRequest.PROTOCOL);
    }

    /** Publishes one adjacent Sensor Array as a typed RedNet telemetry service. */
    public boolean registerSensorService(String requestedName, int port) {
        int checkedPort = port;
        if (level == null || level.isClientSide || checkedPort < 0 || checkedPort > 65535
                || !openChannels.contains(checkedPort) || NetworkSensorService.resolveTarget(this) == null) return false;
        String canonical = com.malice.terminalcraft.network.RednetHostName.normalize(requestedName).orElse("");
        if (canonical.isEmpty() || (!services.containsKey(canonical) && !monitorServices.containsKey(canonical)
                && !sensorServices.containsKey(canonical) && !scadaServices.containsKey(canonical)
                && serviceCount() >= 32) || !RednetNetwork.registerService(
                level, modemId, canonical, checkedPort, SensorRemoteRequest.PROTOCOL)) return false;
        services.remove(canonical);
        monitorServices.remove(canonical);
        scadaServices.remove(canonical);
        sensorServices.put(canonical, checkedPort);
        setChanged();
        return true;
    }

    public boolean unregisterSensorService(String requestedName) {
        if (level == null || level.isClientSide) return false;
        String canonical = com.malice.terminalcraft.network.RednetHostName.normalize(requestedName).orElse("");
        if (canonical.isEmpty() || !sensorServices.containsKey(canonical)
                || !RednetNetwork.unregisterService(level, modemId, canonical)) return false;
        sensorServices.remove(canonical);
        setChanged();
        return true;
    }

    public List<String> sensorServices() {
        return sensorServices.entrySet().stream()
                .map(entry -> entry.getKey() + " " + entry.getValue()).toList();
    }

    boolean hasSensorServiceOnPort(int port) { return sensorServices.containsValue(port); }

    public boolean transmitSensorService(String serviceName, String operation, String channel, int replyPort) {
        if (level == null || level.isClientSide || openChannels.isEmpty()) return false;
        SensorRemoteRequest request;
        try {
            request = switch (operation == null ? "read" : operation.toLowerCase(java.util.Locale.ROOT)) {
                case "list" -> SensorRemoteRequest.list();
                case "snapshot" -> SensorRemoteRequest.snapshot();
                default -> SensorRemoteRequest.read(channel);
            };
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        return RednetNetwork.transmitService(level, modemId, worldPosition, serviceName,
                clamp(replyPort), request.encode(), wireless, range, SensorRemoteRequest.PROTOCOL);
    }

    /** Exposes the global process database through one unambiguous adjacent server rack. */
    public boolean registerScadaService(String requestedName, int port) {
        if (level == null || level.isClientSide || port < 0 || port > 65535
                || !openChannels.contains(port) || NetworkScadaService.resolveTarget(this) == null) return false;
        String canonical = com.malice.terminalcraft.network.RednetHostName.normalize(requestedName).orElse("");
        if (canonical.isEmpty() || (!services.containsKey(canonical) && !monitorServices.containsKey(canonical)
                && !sensorServices.containsKey(canonical) && !scadaServices.containsKey(canonical)
                && serviceCount() >= 32) || !RednetNetwork.registerService(
                level, modemId, canonical, port, ScadaRemoteRequest.PROTOCOL)) return false;
        services.remove(canonical);
        monitorServices.remove(canonical);
        sensorServices.remove(canonical);
        scadaServices.put(canonical, port);
        setChanged();
        return true;
    }

    public boolean unregisterScadaService(String requestedName) {
        if (level == null || level.isClientSide) return false;
        String canonical = com.malice.terminalcraft.network.RednetHostName.normalize(requestedName).orElse("");
        if (canonical.isEmpty() || !scadaServices.containsKey(canonical)
                || !RednetNetwork.unregisterService(level, modemId, canonical)) return false;
        scadaServices.remove(canonical);
        setChanged();
        return true;
    }

    public List<String> scadaServices() {
        return scadaServices.entrySet().stream().map(entry -> entry.getKey() + " " + entry.getValue()).toList();
    }

    boolean hasScadaServiceOnPort(int port) { return scadaServices.containsValue(port); }

    public boolean transmitScadaService(String serviceName, String operation, String selector,
                                        int limit, int replyPort) {
        if (level == null || level.isClientSide || openChannels.isEmpty()) return false;
        ScadaRemoteRequest request;
        try {
            request = new ScadaRemoteRequest(ScadaRemoteRequest.Operation.valueOf(
                    operation.trim().toUpperCase(java.util.Locale.ROOT)), selector, limit);
        } catch (RuntimeException invalid) {
            return false;
        }
        return RednetNetwork.transmitService(level, modemId, worldPosition, serviceName, clamp(replyPort),
                request.encode(), wireless, range, ScadaRemoteRequest.PROTOCOL);
    }

    public List<String> visibleServices(int maximum) {
        if (level == null || level.isClientSide) return List.of();
        return RednetNetwork.reachableServiceEndpoints(
                level, modemId, worldPosition, wireless, range, maximum).stream()
                .map(service -> "name=" + service.name()
                        + " address=" + service.address().encoded()
                        + " port=" + service.port()
                        + " protocol=" + service.protocol().id()
                        + " version=" + service.protocol().version()
                        + " payload=" + service.protocol().payloadType())
                .toList();
    }

    public boolean transmitService(String serviceName, int replyPort, String message) {
        if (level == null || level.isClientSide || openChannels.isEmpty()) return false;
        return RednetNetwork.transmitService(level, modemId, worldPosition, serviceName,
                clamp(replyPort), message, wireless, range);
    }

    public List<Integer> getOpenChannels() {
        List<Integer> list = new ArrayList<>(openChannels);
        list.sort(Integer::compareTo);
        return list;
    }

    public boolean isOpen(int channel) {
        return openChannels.contains(clamp(channel));
    }

    public boolean openChannel(int channel) {
        channel = clamp(channel);
        if (openChannels.size() >= MAX_OPEN_CHANNELS && !openChannels.contains(channel)) {
            return false;
        }
        openChannels.add(channel);
        if (RednetAutoConfiguration.isDefaultChannel(channel)) automaticSetup = true;
        if (level != null && !level.isClientSide) {
            RednetNetwork.open(level, modemId, channel, worldPosition, wireless, range);
            // closeAll removes runtime aliases but intentionally retains the configured hostname.
            // Reopening a channel must therefore republish that identity before directed traffic
            // can reach this modem again.
            if (!hostname.isBlank()) RednetNetwork.registerHost(level, modemId, hostname);
            else if (automaticSetup) RednetNetwork.ensureAutomaticHost(level, modemId);
        }
        setChanged();
        return true;
    }

    public boolean closeChannel(int channel) {
        channel = clamp(channel);
        final int closedChannel = channel;
        if (RednetAutoConfiguration.isDefaultChannel(channel) && automaticSetup) {
            automaticSetup = false;
            if (level != null && !level.isClientSide && hostname.isBlank()) {
                RednetNetwork.unregisterHost(level, modemId);
            }
        }
        boolean removed = openChannels.remove(channel);
        if (removed && level != null && !level.isClientSide) {
            RednetNetwork.close(level, modemId, channel);
            List<String> closedServices = services.entrySet().stream()
                    .filter(entry -> entry.getValue() == closedChannel).map(Map.Entry::getKey).toList();
            for (String service : closedServices) {
                RednetNetwork.unregisterService(level, modemId, service);
                services.remove(service);
            }
            List<String> closedMonitorServices = monitorServices.entrySet().stream()
                    .filter(entry -> entry.getValue() == closedChannel).map(Map.Entry::getKey).toList();
            for (String service : closedMonitorServices) {
                RednetNetwork.unregisterService(level, modemId, service);
                monitorServices.remove(service);
            }
            List<String> closedSensorServices = sensorServices.entrySet().stream()
                    .filter(entry -> entry.getValue() == closedChannel).map(Map.Entry::getKey).toList();
            for (String service : closedSensorServices) {
                RednetNetwork.unregisterService(level, modemId, service);
                sensorServices.remove(service);
            }
            List<String> closedScadaServices = scadaServices.entrySet().stream()
                    .filter(entry -> entry.getValue() == closedChannel).map(Map.Entry::getKey).toList();
            for (String service : closedScadaServices) {
                RednetNetwork.unregisterService(level, modemId, service);
                scadaServices.remove(service);
            }
        }
        if (removed) setChanged();
        return removed;
    }

    public void closeAll() {
        automaticSetup = false;
        if (level != null && !level.isClientSide) {
            RednetNetwork.closeAll(level, modemId);
            RednetNetwork.unregisterHost(level, modemId);
            RednetNetwork.releaseLease(level, modemId);
            RednetNetwork.unregisterServices(level, modemId);
        }
        openChannels.clear();
        cableDefaultChannels.clear();
        services.clear();
        monitorServices.clear();
        sensorServices.clear();
        scadaServices.clear();
        setChanged();
    }

    /** Applies the small automatic profile used by newly placed modems and legacy empty modems. */
    private void ensureAutomaticSetup() {
        if (!automaticSetup || level == null || level.isClientSide) return;
        Set<Integer> desired = wireless ? Set.of(RednetAutoConfiguration.DEFAULT_CHANNEL)
                : com.malice.terminalcraft.block.NetworkCableBlock.attachedChannels(level, worldPosition);
        if (desired.isEmpty()) desired = Set.of(RednetAutoConfiguration.DEFAULT_CHANNEL);
        cableDefaultChannels.clear();
        cableDefaultChannels.addAll(desired);
        for (int channel : desired) if (!openChannels.contains(channel)) openChannel(channel);
        if (hostname.isBlank()) RednetNetwork.ensureAutomaticHost(level, modemId);
        RednetNetwork.ensureLease(level, modemId, worldPosition, wireless, networkName);
    }

    /** Channel selected when a shell command omits one; colored cable IDs map directly to 0..15. */
    public int defaultChannel() {
        return cableDefaultChannels.stream().min(Integer::compareTo)
                .orElse(RednetAutoConfiguration.DEFAULT_CHANNEL);
    }

    /** Bounded player-facing provisioning and protocol status. */
    public List<String> statusDiagnostics() {
        if (level == null || level.isClientSide) return List.of("state=offline");
        RednetLease lease = RednetNetwork.lease(level, modemId).orElse(null);
        String channels = getOpenChannels().isEmpty() ? "-" : getOpenChannels().toString();
        List<String> result = new ArrayList<>();
        result.add("state=" + (openChannels.isEmpty() ? "offline" : "ready")
                + " identity=" + new com.malice.terminalcraft.network.RednetAddress(modemId, getHostname()).encoded());
        result.add("transport=" + (wireless ? "wireless" : "wired")
                + (wireless ? " range=" + range : "")
                + " channels=" + channels
                + " automatic=" + automaticSetup);
        result.add(lease == null
                ? "lease=unassigned"
                : "lease=" + lease.address() + " network=" + lease.networkId()
                        + " source=" + lease.source() + " expires=" + lease.expiresAt());
        result.add("routers=" + RednetNetwork.routerCount(level)
                + " visible_hosts=" + visibleHosts(128).size()
                + " pending=" + pendingCount());
        result.addAll(RednetNetwork.protocolDiagnostics());
        return List.copyOf(result);
    }

    public boolean transmit(int channel, int replyChannel, String message) {
        if (level == null || level.isClientSide) {
            return false;
        }
        if (openChannels.isEmpty()) {
            // Require at least one open channel to be "online"
            return false;
        }
        RednetNetwork.transmit(level, modemId, worldPosition, clamp(channel), clamp(replyChannel),
                message, wireless, range);
        return true;
    }

    public RednetDeliveryRuntime.Delivery transmitReliableTo(String destination, int port, int replyPort,
                                                               String message, long timeoutTicks,
                                                               int maxRetries) {
        if (level == null || level.isClientSide || openChannels.isEmpty()) return null;
        return RednetNetwork.transmitReliableTo(level, modemId, worldPosition, destination,
                clamp(port), clamp(replyPort), message, wireless, range, timeoutTicks, maxRetries);
    }

    /** Submits one bounded acknowledged delivery and returns its initial diagnostic record. */
    public String probe(String destination, int port, int replyPort, String message) {
        RednetDeliveryRuntime.Delivery delivery = transmitReliableTo(destination, port, replyPort,
                message, RednetNetwork.DEFAULT_ACK_TIMEOUT_TICKS, RednetNetwork.DEFAULT_MAX_RETRIES);
        return delivery == null ? "" : formatDelivery(delivery);
    }

    /** Returns sender-authorized state for one retained reliable delivery. */
    public String deliveryDiagnostics(String messageId) {
        if (level == null || level.isClientSide) return "";
        UUID id;
        try {
            id = UUID.fromString(messageId);
        } catch (IllegalArgumentException invalid) {
            return "";
        }
        return RednetNetwork.delivery(level, modemId, id).map(ModemBlockEntity::formatDelivery).orElse("");
    }

    private static String formatDelivery(RednetDeliveryRuntime.Delivery delivery) {
        return "id=" + delivery.messageId()
                + " state=" + delivery.state().name().toLowerCase(java.util.Locale.ROOT)
                + " attempts=" + delivery.attempts()
                + " retries=" + delivery.maxRetries()
                + " timeout=" + delivery.timeoutTicks()
                + (delivery.lastError().isEmpty() ? "" : " error=" + delivery.lastError().replace(' ', '_'));
    }

    public boolean acknowledge(RednetNetwork.PendingMessage message) {
        return RednetNetwork.acknowledge(level, modemId, message);
    }

    public boolean transmitTo(String destination, int port, int replyPort, String message) {
        if (level == null || level.isClientSide || openChannels.isEmpty()) return false;
        return RednetNetwork.transmitTo(level, modemId, worldPosition, destination,
                clamp(port), clamp(replyPort), message, wireless, range);
    }

    public List<String> receiveMessages(int max) {
        List<RednetNetwork.PendingMessage> msgs = RednetNetwork.receive(level, modemId, max);
        List<String> lines = new ArrayList<>();
        for (RednetNetwork.PendingMessage m : msgs) {
            lines.add(m.format());
        }
        return lines;
    }

    public int pendingCount() {
        return RednetNetwork.pendingCount(level, modemId);
    }

    public void reregister() {
        if (level == null || level.isClientSide) {
            return;
        }
        ensureAutomaticSetup();
        for (int ch : openChannels) {
            RednetNetwork.open(level, modemId, ch, worldPosition, wireless, range);
        }
        if (!hostname.isBlank()) RednetNetwork.registerHost(level, modemId, hostname);
        else if (automaticSetup) RednetNetwork.ensureAutomaticHost(level, modemId);
        for (Map.Entry<String, Integer> service : services.entrySet()) {
            if (openChannels.contains(service.getValue())) {
                RednetNetwork.registerService(level, modemId, service.getKey(), service.getValue());
            }
        }
        for (Map.Entry<String, Integer> service : monitorServices.entrySet()) {
            if (openChannels.contains(service.getValue())) {
                RednetNetwork.registerService(level, modemId, service.getKey(), service.getValue(),
                        MonitorRemoteRequest.PROTOCOL);
            }
        }
        for (Map.Entry<String, Integer> service : sensorServices.entrySet()) {
            if (openChannels.contains(service.getValue())) {
                RednetNetwork.registerService(level, modemId, service.getKey(), service.getValue(),
                        SensorRemoteRequest.PROTOCOL);
            }
        }
        for (Map.Entry<String, Integer> service : scadaServices.entrySet()) {
            if (openChannels.contains(service.getValue())) {
                RednetNetwork.registerService(level, modemId, service.getKey(), service.getValue(),
                        ScadaRemoteRequest.PROTOCOL);
            }
        }
        RednetNetwork.updatePosition(level, modemId, worldPosition);
        RednetNetwork.ensureLease(level, modemId, worldPosition, wireless, networkName);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ModemBlockEntity be) {
        ServerDeviceManager.ensureModemRegistered(be, be.modemId, be.getDeviceAddress(), be);
        be.ensureAutomaticSetup();
        RednetNetwork.tickDeliveries(level);
        NetworkMonitorService.tick(be);
        NetworkSensorService.tick(be);
        NetworkScadaService.tick(be);
        if (level.getGameTime() % 40 == 0) {
            RednetNetwork.updatePosition(level, be.modemId, pos);
            RednetNetwork.ensureLease(level, be.modemId, pos, be.wireless, be.networkName);
        }
    }

    private static int clamp(int channel) {
        if (channel < 0) {
            return 0;
        }
        if (channel > 65535) {
            return 65535;
        }
        return channel;
    }

    private int serviceCount() {
        return services.size() + monitorServices.size() + sensorServices.size() + scadaServices.size();
    }

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) {
            RednetNetwork.closeAll(level, modemId);
            RednetNetwork.unregisterHost(level, modemId);
            RednetNetwork.unregisterServices(level, modemId);
            RednetNetwork.releaseLease(level, modemId);
        }
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            com.malice.terminalcraft.network.WiredNetworkTopology.remove(serverLevel, worldPosition);
        }
        ServerDeviceManager.invalidate(this);
        super.setRemoved();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            reregister();
            invalidateWiredTopology();
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        PersistedDataVersions.stampCurrent(tag);
        tag.putUUID("ModemId", modemId);
        tag.putBoolean("Wireless", wireless);
        tag.putInt("Range", range);
        tag.putString("Label", label);
        if (!hostname.isBlank()) tag.putString("Hostname", hostname);
        if (!networkName.isBlank()) tag.putString("NetworkName", networkName);
        tag.putBoolean("AutomaticSetup", automaticSetup);
        ListTag savedServices = new ListTag();
        for (Map.Entry<String, Integer> service : services.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Name", service.getKey());
            entry.putInt("Port", service.getValue());
            savedServices.add(entry);
        }
        tag.put("Services", savedServices);
        ListTag savedMonitorServices = new ListTag();
        for (Map.Entry<String, Integer> service : monitorServices.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Name", service.getKey());
            entry.putInt("Port", service.getValue());
            savedMonitorServices.add(entry);
        }
        tag.put("MonitorServices", savedMonitorServices);
        ListTag savedSensorServices = new ListTag();
        for (Map.Entry<String, Integer> service : sensorServices.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Name", service.getKey());
            entry.putInt("Port", service.getValue());
            savedSensorServices.add(entry);
        }
        tag.put("SensorServices", savedSensorServices);
        ListTag savedScadaServices = new ListTag();
        for (Map.Entry<String, Integer> service : scadaServices.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Name", service.getKey());
            entry.putInt("Port", service.getValue());
            savedScadaServices.add(entry);
        }
        tag.put("ScadaServices", savedScadaServices);
        int[] arr = openChannels.stream().mapToInt(Integer::intValue).toArray();
        tag.put("Channels", new IntArrayTag(arr));
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.hasUUID("ModemId")) {
            modemId = tag.getUUID("ModemId");
        }
        if (tag.contains("Wireless")) {
            wireless = tag.getBoolean("Wireless");
        }
        if (tag.contains("Range")) {
            range = Math.max(1, Math.min(256, tag.getInt("Range")));
        }
        if (tag.contains("Label", Tag.TAG_STRING)) {
            label = PersistedDataLimits.readString(tag, "Label",
                    PersistedDataLimits.MAX_LABEL_CHARS, "modem");
        }
        hostname = tag.contains("Hostname", Tag.TAG_STRING) ? tag.getString("Hostname") : "";
        networkName = tag.contains("NetworkName", Tag.TAG_STRING)
                ? RednetNetworkName.normalize(tag.getString("NetworkName")).orElse("") : "";
        automaticSetup = !tag.contains("AutomaticSetup") || tag.getBoolean("AutomaticSetup");
        services.clear();
        if (tag.contains("Services", Tag.TAG_LIST)) {
            ListTag savedServices = tag.getList("Services", Tag.TAG_COMPOUND);
            for (int i = 0; i < savedServices.size() && services.size() < 32; i++) {
                CompoundTag entry = savedServices.getCompound(i);
                String rawName = PersistedDataLimits.readString(entry, "Name", 128, "");
                String name = com.malice.terminalcraft.network.RednetHostName.normalize(rawName).orElse("");
                if (!name.isEmpty() && entry.contains("Port", Tag.TAG_INT)) {
                    services.put(name, clamp(entry.getInt("Port")));
                }
            }
        }
        monitorServices.clear();
        if (tag.contains("MonitorServices", Tag.TAG_LIST)) {
            ListTag savedMonitorServices = tag.getList("MonitorServices", Tag.TAG_COMPOUND);
            for (int i = 0; i < savedMonitorServices.size()
                    && services.size() + monitorServices.size() < 32; i++) {
                CompoundTag entry = savedMonitorServices.getCompound(i);
                String rawName = PersistedDataLimits.readString(entry, "Name", 128, "");
                String name = com.malice.terminalcraft.network.RednetHostName.normalize(rawName).orElse("");
                if (!name.isEmpty() && entry.contains("Port", Tag.TAG_INT)) {
                    monitorServices.put(name, clamp(entry.getInt("Port")));
                }
            }
        }
        sensorServices.clear();
        if (tag.contains("SensorServices", Tag.TAG_LIST)) {
            ListTag savedSensorServices = tag.getList("SensorServices", Tag.TAG_COMPOUND);
            for (int i = 0; i < savedSensorServices.size()
                    && services.size() + monitorServices.size() + sensorServices.size() < 32; i++) {
                CompoundTag entry = savedSensorServices.getCompound(i);
                String rawName = PersistedDataLimits.readString(entry, "Name", 128, "");
                String name = com.malice.terminalcraft.network.RednetHostName.normalize(rawName).orElse("");
                if (!name.isEmpty() && entry.contains("Port", Tag.TAG_INT)) {
                    sensorServices.put(name, clamp(entry.getInt("Port")));
                }
            }
        }
        scadaServices.clear();
        if (tag.contains("ScadaServices", Tag.TAG_LIST)) {
            ListTag savedScadaServices = tag.getList("ScadaServices", Tag.TAG_COMPOUND);
            for (int i = 0; i < savedScadaServices.size() && serviceCount() < 32; i++) {
                CompoundTag entry = savedScadaServices.getCompound(i);
                String rawName = PersistedDataLimits.readString(entry, "Name", 128, "");
                String name = com.malice.terminalcraft.network.RednetHostName.normalize(rawName).orElse("");
                if (!name.isEmpty() && !services.containsKey(name) && !monitorServices.containsKey(name)
                        && !sensorServices.containsKey(name) && entry.contains("Port", Tag.TAG_INT)) {
                    scadaServices.put(name, clamp(entry.getInt("Port")));
                }
            }
        }
        openChannels.clear();
        openChannels.addAll(PersistedDataLimits.readBoundedIntArray(tag, "Channels",
                0, 65535, MAX_OPEN_CHANNELS));
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        saveAdditional(tag);
        return tag;
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt) {
        CompoundTag tag = pkt.getTag();
        if (tag != null) {
            load(tag);
        }
    }
}
