package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.persistence.PersistedDataLimits;
import com.malice.terminalcraft.persistence.PersistedDataVersions;
import com.malice.terminalcraft.network.RednetNetwork;
import com.malice.terminalcraft.network.RednetDeliveryRuntime;
import com.malice.terminalcraft.network.RednetNetworkName;
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
    private boolean wireless = true;
    private int range = DEFAULT_RANGE;
    private String label = "modem";
    private String hostname = "";
    private String networkName = "";
    private final Map<String, Integer> services = new TreeMap<>();

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
        return hostname;
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
        if (level == null || level.isClientSide || !openChannels.contains(boundedPort)
                || !RednetNetwork.registerService(level, modemId, requestedName, boundedPort)) return false;
        String canonical = com.malice.terminalcraft.network.RednetHostName.normalize(requestedName).orElse("");
        if (canonical.isEmpty()) return false;
        services.put(canonical, boundedPort);
        setChanged();
        return true;
    }

    public boolean unregisterService(String requestedName) {
        if (level == null || level.isClientSide) return false;
        String canonical = com.malice.terminalcraft.network.RednetHostName.normalize(requestedName).orElse("");
        if (canonical.isEmpty() || !RednetNetwork.unregisterService(level, modemId, canonical)) return false;
        services.remove(canonical);
        setChanged();
        return true;
    }

    public List<String> localServices() {
        return services.entrySet().stream().map(entry -> entry.getKey() + " " + entry.getValue()).toList();
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
        if (level != null && !level.isClientSide) {
            RednetNetwork.open(level, modemId, channel, worldPosition, wireless, range);
        }
        setChanged();
        return true;
    }

    public boolean closeChannel(int channel) {
        channel = clamp(channel);
        final int closedChannel = channel;
        boolean removed = openChannels.remove(channel);
        if (removed && level != null && !level.isClientSide) {
            RednetNetwork.close(level, modemId, channel);
            List<String> closedServices = services.entrySet().stream()
                    .filter(entry -> entry.getValue() == closedChannel).map(Map.Entry::getKey).toList();
            for (String service : closedServices) {
                RednetNetwork.unregisterService(level, modemId, service);
                services.remove(service);
            }
        }
        if (removed) setChanged();
        return removed;
    }

    public void closeAll() {
        if (level != null && !level.isClientSide) {
            RednetNetwork.closeAll(level, modemId);
            RednetNetwork.unregisterServices(level, modemId);
        }
        openChannels.clear();
        services.clear();
        setChanged();
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
        for (int ch : openChannels) {
            RednetNetwork.open(level, modemId, ch, worldPosition, wireless, range);
        }
        if (!hostname.isBlank()) RednetNetwork.registerHost(level, modemId, hostname);
        for (Map.Entry<String, Integer> service : services.entrySet()) {
            if (openChannels.contains(service.getValue())) {
                RednetNetwork.registerService(level, modemId, service.getKey(), service.getValue());
            }
        }
        RednetNetwork.updatePosition(level, modemId, worldPosition);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, ModemBlockEntity be) {
        ServerDeviceManager.ensureModemRegistered(be, be.modemId, be.getDeviceAddress(), be);
        RednetNetwork.tickDeliveries(level);
        if (level.getGameTime() % 40 == 0) RednetNetwork.updatePosition(level, be.modemId, pos);
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

    @Override
    public void setRemoved() {
        if (level != null && !level.isClientSide) {
            RednetNetwork.closeAll(level, modemId);
            RednetNetwork.unregisterHost(level, modemId);
            RednetNetwork.unregisterServices(level, modemId);
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
        ListTag savedServices = new ListTag();
        for (Map.Entry<String, Integer> service : services.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Name", service.getKey());
            entry.putInt("Port", service.getValue());
            savedServices.add(entry);
        }
        tag.put("Services", savedServices);
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
