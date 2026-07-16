package com.malice.terminalcraft.device;

import com.malice.terminalcraft.shell.TerminalHost;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Logical-server owner for live TerminalCraft device endpoints.
 *
 * <p>Block entities call {@link #ensureRegistered} from their server tick and
 * {@link #invalidate} when removed. Ownership checks prevent a stale block entity from
 * invalidating a newer endpoint with the same persistent UUID.</p>
 */
public final class ServerDeviceManager {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static final Map<MinecraftServer, ServerState> SERVERS = new WeakHashMap<>();

    private ServerDeviceManager() {}

    /** Registers a live block entity once, or refreshes it after its address changes. */
    public static void ensureRegistered(BlockEntity owner, UUID deviceId, String address,
                                        TerminalHost host) {
        Objects.requireNonNull(host, "host");
        ensureRegistered(owner, deviceId, address,
                () -> new TerminalHostDeviceEndpoint(deviceId, address, host,
                        () -> isCurrent(owner), () -> isCurrent(owner)));
    }

    /** Registers a live monitor endpoint once, or refreshes it after its address changes. */
    public static void ensureMonitorRegistered(BlockEntity owner, UUID deviceId, String address,
                                               MonitorDevice monitor) {
        ensureRegistered(owner, deviceId, address,
                () -> new MonitorDeviceEndpoint(deviceId, address, monitor,
                        () -> isCurrent(owner), () -> isCurrent(owner)));
    }

    public static void ensureModemRegistered(BlockEntity owner, UUID deviceId, String address,
                                             ModemDevice modem) {
        ensureRegistered(owner, deviceId, address,
                () -> new ModemDeviceEndpoint(deviceId, address, modem,
                        () -> isCurrent(owner), () -> isCurrent(owner)));
    }

    public static void ensureDiskDriveRegistered(BlockEntity owner, UUID deviceId, String address,
                                                 DiskDriveDevice drive) {
        ensureRegistered(owner, deviceId, address,
                () -> new DiskDriveDeviceEndpoint(deviceId, address, drive,
                        () -> isCurrent(owner), () -> isCurrent(owner)));
    }

    /** Publishes an event to a live device by persistent identity. */
    public static DeviceResult publishEvent(MinecraftServer server, UUID deviceId, String type,
                                            long gameTime, DeviceValue.MapValue payload) {
        Objects.requireNonNull(server, "server");
        requireServerThread(server);
        return state(server).registry.publishEvent(deviceId, type, gameTime, payload);
    }

    /** Publishes a bounded event for a currently registered live block entity. */
    public static DeviceResult publishEvent(BlockEntity owner, String type, long gameTime,
                                            DeviceValue.MapValue payload) {
        if (owner == null || !(owner.getLevel() instanceof ServerLevel level))
            return DeviceResult.failure(DeviceErrorCode.OFFLINE, "device is not server-side", true);
        requireServerThread(level.getServer());
        Binding binding = state(level.getServer()).byOwner.get(owner);
        if (binding == null) return DeviceResult.failure(DeviceErrorCode.NOT_FOUND, "device is not registered", true);
        return state(level.getServer()).registry.publishEvent(binding.deviceId, type, gameTime, payload);
    }

    private static void ensureRegistered(BlockEntity owner, UUID deviceId, String address,
                                         java.util.function.Supplier<DeviceEndpoint> endpointFactory) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(address, "address");
        Objects.requireNonNull(endpointFactory, "endpointFactory");
        if (!(owner.getLevel() instanceof ServerLevel level) || owner.isRemoved()) return;

        MinecraftServer server = level.getServer();
        requireServerThread(server);
        ServerState state = state(server);
        Binding current = state.byOwner.get(owner);
        if (current != null && current.deviceId.equals(deviceId)
                && current.address.equals(address)) return;
        if (current != null) state.remove(owner, current);

        Binding collision = state.byId.get(deviceId);
        if (collision != null && collision.owner != owner) {
            if (!isCurrent(collision.owner)) {
                state.remove(collision.owner, collision);
            } else {
                LOGGER.warn("Duplicate live TerminalCraft device UUID {} at {} and {}; rejecting later endpoint",
                        deviceId, collision.address, address);
                return;
            }
        }

        DeviceEndpoint endpoint = endpointFactory.get();
        state.registry.register(endpoint);
        Binding binding = new Binding(owner, deviceId, address, endpoint);
        state.byOwner.put(owner, binding);
        state.byId.put(deviceId, binding);
    }

    /** Invalidates only the endpoint still owned by this exact block-entity instance. */
    public static void invalidate(BlockEntity owner) {
        if (owner == null || !(owner.getLevel() instanceof ServerLevel level)) return;
        MinecraftServer server = level.getServer();
        requireServerThread(server);
        ServerState state = state(server);
        Binding binding = state.byOwner.get(owner);
        if (binding != null) state.remove(owner, binding);
    }

    /**
     * Charges one caller/host operation against the current logical-tick device budget.
     * Newly created shell access wrappers share this server-owned admission state.
     */
    static DeviceResult admitDeviceCall(ServerLevel level, net.minecraft.core.BlockPos hostPosition,
                                        DeviceCallContext context, int workUnits) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(hostPosition, "hostPosition");
        Objects.requireNonNull(context, "context");
        MinecraftServer server = level.getServer();
        requireServerThread(server);
        String authorityKey = level.dimension().location() + ":" + hostPosition.getX() + ","
                + hostPosition.getY() + "," + hostPosition.getZ() + "#" + context.authorityKey();
        DeviceInvocationBudget.Admission admission = state(server).invocationBudget.admit(
                authorityKey, level.getGameTime(), workUnits);
        if (admission == DeviceInvocationBudget.Admission.ADMITTED) return DeviceResult.success();
        return DeviceResult.failure(DeviceErrorCode.BUSY,
                switch (admission) {
                    case CALL_LIMIT -> "device call rate limit exceeded for this host and principal";
                    case WORK_LIMIT -> "device work budget exceeded for this host and principal";
                    case BUCKET_LIMIT -> "server device caller budget is saturated for this tick";
                    case ADMITTED -> throw new IllegalStateException("admitted call cannot fail");
                }, true);
    }

    /** Caller-bound access is the only public invocation and discovery route. */
    public static DeviceAccess access(MinecraftServer server, DeviceCallContext context) {
        Objects.requireNonNull(server, "server");
        requireServerThread(server);
        return state(server).registry.access(Objects.requireNonNull(context, "context"));
    }

    /** Lifecycle-only raw registry access; never expose this outside the device package. */
    static DeviceRegistry registry(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        requireServerThread(server);
        return state(server).registry;
    }

    /**
     * Returns the logical-server transaction owner for exact ItemStack movement.
     * Package-private until durable replay/escrow storage and endpoint resolution are complete.
     */
    static ExactItemTransferCoordinator<ItemStack> itemTransfers(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        requireServerThread(server);
        return ExactItemTransferSavedData.get(server).coordinator();
    }

    /** Returns the logical-server durable transaction owner for exact FluidStack movement. */
    static ExactFluidTransferCoordinator<FluidStack> fluidTransfers(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        requireServerThread(server);
        return ExactFluidTransferSavedData.get(server).coordinator();
    }

    /** Drops all references when a logical server stops. */
    public static void clear(MinecraftServer server) {
        if (server == null) return;
        requireServerThread(server);
        synchronized (SERVERS) {
            SERVERS.remove(server);
        }
    }

    private static ServerState state(MinecraftServer server) {
        synchronized (SERVERS) {
            return SERVERS.computeIfAbsent(server, ignored -> new ServerState());
        }
    }

    private static boolean isCurrent(BlockEntity owner) {
        if (owner.isRemoved() || !(owner.getLevel() instanceof ServerLevel level)
                || !level.hasChunkAt(owner.getBlockPos())) return false;
        return level.getBlockEntity(owner.getBlockPos()) == owner;
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isSameThread()) {
            throw new IllegalStateException("device registry mutation must run on the logical server thread");
        }
    }

    private static final class ServerState {
        private final DeviceRegistry registry = new DeviceRegistry();
        private final DeviceInvocationBudget invocationBudget = new DeviceInvocationBudget();
        private final Map<BlockEntity, Binding> byOwner = new IdentityHashMap<>();
        private final Map<UUID, Binding> byId = new LinkedHashMap<>();

        private void remove(BlockEntity owner, Binding binding) {
            byOwner.remove(owner);
            if (byId.get(binding.deviceId) == binding) {
                byId.remove(binding.deviceId);
                registry.invalidate(binding.deviceId);
            }
        }
    }

    private record Binding(BlockEntity owner, UUID deviceId, String address,
                           DeviceEndpoint endpoint) {}
}
