package com.malice.terminalcraft.world;

import com.malice.terminalcraft.Config;
import com.malice.terminalcraft.TerminalCraftMod;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ForcedChunksSavedData;
import net.minecraftforge.common.world.ForgeChunkManager;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Owns at most one ticking Forge chunk ticket per dimension/chunk containing terminals.
 * Multiple terminals in the same chunk share that ticket. Ticket creation is disabled by
 * default and bounded by the common-config per-dimension and per-server quotas.
 */
public final class TerminalChunkLoader {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long OVERRIDE_CHECK_TICKS = 200L;
    private static final long RESTORE_GRACE_TICKS = 200L;
    private static final Map<MinecraftServer, ServerState> SERVERS = new WeakHashMap<>();
    private static final Comparator<ChunkKey> KEY_ORDER = Comparator
            .comparing(ChunkKey::dimension)
            .thenComparingInt(ChunkKey::chunkX)
            .thenComparingInt(ChunkKey::chunkZ);

    private TerminalChunkLoader() {}

    /** Registers a live terminal and acquires its chunk's shared ticket when policy permits. */
    public static void ensureLoaded(ServerLevel level, BlockPos terminalPos, UUID terminalId) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(terminalPos, "terminalPos");
        Objects.requireNonNull(terminalId, "terminalId");
        requireServerThread(level);

        ServerState state = state(level);
        ChunkPos chunk = new ChunkPos(terminalPos);
        ChunkKey key = new ChunkKey(level.dimension().location().toString(), chunk.x, chunk.z);
        moveTerminalIfNeeded(level.getServer(), state, terminalId, key);

        ChunkOwners owners = state.chunks.computeIfAbsent(key, ignored -> new ChunkOwners(ticketOwner(chunk)));
        owners.terminals.add(terminalId);
        owners.restoredPersistent = false;
        state.terminalChunks.put(terminalId, key);

        TerminalChunkTicketPolicy policy = Config.terminalChunkTicketPolicy();
        applyPolicyIfChanged(level.getServer(), state, policy);
        if (!policy.enabled()) return;

        long gameTime = level.getGameTime();
        if (owners.ownsTicket && gameTime >= owners.nextOverrideCheck) {
            owners.nextOverrideCheck = gameTime + OVERRIDE_CHECK_TICKS;
            releaseTicket(level, key, owners);
            if (!isExternallyForced(level, chunk)) {
                acquireIfAllowed(level, state, key, owners, policy);
            }
        } else if (!owners.ownsTicket && !isExternallyForced(level, chunk)) {
            acquireIfAllowed(level, state, key, owners, policy);
        }
    }

    /** Releases ownership after an actual terminal block removal, not a routine block-entity unload. */
    public static void terminalRemoved(ServerLevel level, UUID terminalId) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(terminalId, "terminalId");
        requireServerThread(level);

        ServerState state = state(level);
        ChunkKey key = state.terminalChunks.remove(terminalId);
        if (key == null) return;
        removeTerminalOwner(level.getServer(), state, key, terminalId);
    }

    /**
     * Validates persisted Forge tickets against shape and current quotas before Forge restores them.
     * Non-ticking or malformed records are always discarded because TerminalCraft only issues
     * ticking tickets with a deterministic owner position.
     */
    public static void validatePersistentTickets(ServerLevel level, ForgeChunkManager.TicketHelper tickets) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(tickets, "tickets");
        ServerState state = state(level);
        TerminalChunkTicketPolicy policy = Config.terminalChunkTicketPolicy();
        applyPolicyIfChanged(level.getServer(), state, policy);

        List<PersistedTicket> candidates = new ArrayList<>();
        for (Map.Entry<BlockPos, Pair<LongSet, LongSet>> entry : tickets.getBlockTickets().entrySet()) {
            for (long packed : entry.getValue().getFirst()) {
                tickets.removeTicket(entry.getKey(), packed, false);
            }
            for (long packed : entry.getValue().getSecond()) {
                ChunkPos chunk = new ChunkPos(packed);
                if (!entry.getKey().equals(ticketOwner(chunk))) {
                    tickets.removeTicket(entry.getKey(), packed, true);
                } else {
                    candidates.add(new PersistedTicket(entry.getKey(), packed, chunk));
                }
            }
        }
        candidates.sort(Comparator.comparingInt((PersistedTicket ticket) -> ticket.chunk.x)
                .thenComparingInt(ticket -> ticket.chunk.z));

        for (PersistedTicket ticket : candidates) {
            ChunkKey key = new ChunkKey(level.dimension().location().toString(), ticket.chunk.x, ticket.chunk.z);
            ChunkOwners existing = state.chunks.get(key);
            if (existing != null && existing.ownsTicket) continue;
            if (!policy.allows(ownedTickets(state, key.dimension), ownedTickets(state))) {
                tickets.removeTicket(ticket.owner, ticket.packed, true);
                state.quotaReleased++;
                continue;
            }
            ChunkOwners owners = state.chunks.computeIfAbsent(key, ignored -> new ChunkOwners(ticket.owner));
            owners.ownsTicket = true;
            owners.restoredPersistent = true;
            owners.restoredAt = level.getGameTime();
            owners.nextOverrideCheck = level.getGameTime() + OVERRIDE_CHECK_TICKS;
        }
    }

    /** Reconciles config changes and removes restored tickets that never find a live terminal. */
    public static void reconcile(MinecraftServer server) {
        if (server == null || !server.isSameThread()) return;
        ServerState state;
        synchronized (SERVERS) {
            state = SERVERS.get(server);
        }
        if (state == null) return;

        TerminalChunkTicketPolicy policy = Config.terminalChunkTicketPolicy();
        boolean policyChanged = applyPolicyIfChanged(server, state, policy);
        if (!policyChanged && server.getTickCount() < state.nextReconcileTick) return;
        state.nextReconcileTick = server.getTickCount() + 20;

        List<ChunkKey> stale = state.chunks.entrySet().stream()
                .filter(entry -> entry.getValue().ownsTicket)
                .filter(entry -> entry.getValue().restoredPersistent)
                .filter(entry -> entry.getValue().terminals.isEmpty())
                .filter(entry -> {
                    ServerLevel level = findLevel(server, entry.getKey().dimension);
                    return level != null && level.getGameTime() - entry.getValue().restoredAt >= RESTORE_GRACE_TICKS;
                })
                .map(Map.Entry::getKey)
                .sorted(KEY_ORDER)
                .toList();
        for (ChunkKey key : stale) {
            ChunkOwners owners = state.chunks.remove(key);
            ServerLevel level = findLevel(server, key.dimension);
            if (owners != null && level != null) {
                releaseTicket(level, key, owners);
                state.staleReleased++;
                LOGGER.warn("Released stale TerminalCraft chunk ticket for {} [{}, {}]",
                        key.dimension, key.chunkX, key.chunkZ);
            }
        }
    }

    /** Drops runtime accounting on server shutdown; persistent Forge tickets remain authoritative. */
    public static void clear(MinecraftServer server) {
        if (server == null) return;
        synchronized (SERVERS) {
            SERVERS.remove(server);
        }
    }

    /** Current bounded diagnostics suitable for logs, tests, and future admin commands. */
    public static Snapshot snapshot(MinecraftServer server) {
        synchronized (SERVERS) {
            ServerState state = SERVERS.get(server);
            if (state == null) {
                return new Snapshot(Config.terminalChunkTicketPolicy().enabled(), 0, 0, 0, 0, 0, 0);
            }
            return new Snapshot(Config.terminalChunkTicketPolicy().enabled(), state.terminalChunks.size(),
                    state.chunks.size(), ownedTickets(state), state.deniedRequests,
                    state.quotaReleased, state.staleReleased);
        }
    }

    static BlockPos ticketOwner(ChunkPos chunk) {
        return new BlockPos(chunk.getMinBlockX(), 0, chunk.getMinBlockZ());
    }

    static boolean tracksTerminal(ServerLevel level, UUID terminalId) {
        synchronized (SERVERS) {
            ServerState state = SERVERS.get(level.getServer());
            return state != null && state.terminalChunks.containsKey(terminalId);
        }
    }

    static int trackedOwners(ServerLevel level, BlockPos position) {
        ChunkPos chunk = new ChunkPos(position);
        ChunkKey key = new ChunkKey(level.dimension().location().toString(), chunk.x, chunk.z);
        synchronized (SERVERS) {
            ServerState state = SERVERS.get(level.getServer());
            ChunkOwners owners = state == null ? null : state.chunks.get(key);
            return owners == null ? 0 : owners.terminals.size();
        }
    }

    static boolean ownsTicket(ServerLevel level, BlockPos position) {
        ChunkPos chunk = new ChunkPos(position);
        ChunkKey key = new ChunkKey(level.dimension().location().toString(), chunk.x, chunk.z);
        synchronized (SERVERS) {
            ServerState state = SERVERS.get(level.getServer());
            ChunkOwners owners = state == null ? null : state.chunks.get(key);
            return owners != null && owners.ownsTicket;
        }
    }

    private static void acquireIfAllowed(ServerLevel level, ServerState state, ChunkKey key,
                                         ChunkOwners owners, TerminalChunkTicketPolicy policy) {
        if (!policy.allows(ownedTickets(state, key.dimension), ownedTickets(state))) {
            if (!owners.denialRecorded) {
                state.deniedRequests++;
                owners.denialRecorded = true;
            }
            long now = level.getGameTime();
            if (now >= owners.nextDenialLog) {
                owners.nextDenialLog = now + OVERRIDE_CHECK_TICKS;
                LOGGER.warn("Denied TerminalCraft chunk ticket for {} [{}, {}]: dimension={}/{}, server={}/{}",
                        key.dimension, key.chunkX, key.chunkZ,
                        ownedTickets(state, key.dimension), policy.maxPerDimension(),
                        ownedTickets(state), policy.maxPerServer());
            }
            return;
        }
        owners.ownsTicket = ForgeChunkManager.forceChunk(level, TerminalCraftMod.MODID, owners.ticketOwner,
                key.chunkX, key.chunkZ, true, true);
        if (owners.ownsTicket) owners.denialRecorded = false;
        if (!owners.ownsTicket) {
            if (!owners.denialRecorded) {
                state.deniedRequests++;
                owners.denialRecorded = true;
            }
            long now = level.getGameTime();
            if (now >= owners.nextDenialLog) {
                owners.nextDenialLog = now + OVERRIDE_CHECK_TICKS;
                LOGGER.warn("Forge rejected TerminalCraft chunk ticket for {} [{}, {}]",
                        key.dimension, key.chunkX, key.chunkZ);
            }
        }
    }

    private static boolean applyPolicyIfChanged(MinecraftServer server, ServerState state,
                                                TerminalChunkTicketPolicy policy) {
        if (policy.equals(state.appliedPolicy)) return false;
        enforcePolicy(server, state, policy);
        state.appliedPolicy = policy;
        return true;
    }

    private static void enforcePolicy(MinecraftServer server, ServerState state,
                                      TerminalChunkTicketPolicy policy) {
        int serverKept = 0;
        Map<String, Integer> dimensionKept = new HashMap<>();
        List<ChunkKey> owned = state.chunks.entrySet().stream()
                .filter(entry -> entry.getValue().ownsTicket)
                .map(Map.Entry::getKey)
                .sorted(KEY_ORDER)
                .toList();
        for (ChunkKey key : owned) {
            int dimensionCount = dimensionKept.getOrDefault(key.dimension, 0);
            boolean keep = policy.enabled()
                    && dimensionCount < policy.maxPerDimension()
                    && serverKept < policy.maxPerServer();
            if (keep) {
                dimensionKept.put(key.dimension, dimensionCount + 1);
                serverKept++;
                continue;
            }
            ServerLevel level = findLevel(server, key.dimension);
            ChunkOwners owners = state.chunks.get(key);
            if (level != null && owners != null) {
                releaseTicket(level, key, owners);
                state.quotaReleased++;
            }
        }
    }

    private static void moveTerminalIfNeeded(MinecraftServer server, ServerState state,
                                             UUID terminalId, ChunkKey newKey) {
        ChunkKey previous = state.terminalChunks.get(terminalId);
        if (previous != null && !previous.equals(newKey)) {
            removeTerminalOwner(server, state, previous, terminalId);
        }
    }

    private static void removeTerminalOwner(MinecraftServer server, ServerState state,
                                            ChunkKey key, UUID terminalId) {
        ChunkOwners owners = state.chunks.get(key);
        if (owners == null || !owners.terminals.remove(terminalId) || !owners.terminals.isEmpty()) return;
        state.chunks.remove(key);
        ServerLevel level = findLevel(server, key.dimension);
        if (level != null) releaseTicket(level, key, owners);
    }

    private static void releaseTicket(ServerLevel level, ChunkKey key, ChunkOwners owners) {
        if (!owners.ownsTicket) return;
        ForgeChunkManager.forceChunk(level, TerminalCraftMod.MODID, owners.ticketOwner,
                key.chunkX, key.chunkZ, false, true);
        owners.ownsTicket = false;
    }

    private static int ownedTickets(ServerState state) {
        return (int) state.chunks.values().stream().filter(owners -> owners.ownsTicket).count();
    }

    private static int ownedTickets(ServerState state, String dimension) {
        return (int) state.chunks.entrySet().stream()
                .filter(entry -> entry.getKey().dimension.equals(dimension))
                .filter(entry -> entry.getValue().ownsTicket)
                .count();
    }

    private static ServerLevel findLevel(MinecraftServer server, String dimension) {
        for (ServerLevel level : server.getAllLevels()) {
            ResourceLocation location = level.dimension().location();
            if (location.toString().equals(dimension)) return level;
        }
        return null;
    }

    /** True when vanilla or another mod owns a force-load ticket for this chunk. */
    private static boolean isExternallyForced(ServerLevel level, ChunkPos chunk) {
        long packed = chunk.toLong();
        ForcedChunksSavedData data = level.getDataStorage().get(ForcedChunksSavedData::load, ForcedChunksSavedData.FILE_ID);
        if (data == null) return level.getForcedChunks().contains(packed);
        if (data.getChunks().contains(packed)) return true;

        boolean externalBlockTicket = data.getBlockForcedChunks().getChunks().values().stream()
                .anyMatch(chunks -> chunks.contains(packed));
        if (externalBlockTicket) return true;
        externalBlockTicket = data.getBlockForcedChunks().getTickingChunks().values().stream()
                .anyMatch(chunks -> chunks.contains(packed));
        if (externalBlockTicket) return true;
        return data.getEntityForcedChunks().getChunks().values().stream().anyMatch(chunks -> chunks.contains(packed))
                || data.getEntityForcedChunks().getTickingChunks().values().stream()
                .anyMatch(chunks -> chunks.contains(packed));
    }

    private static ServerState state(ServerLevel level) {
        synchronized (SERVERS) {
            return SERVERS.computeIfAbsent(level.getServer(), ignored -> new ServerState());
        }
    }

    private static void requireServerThread(ServerLevel level) {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("terminal chunk tickets must be changed on the logical server thread");
        }
    }

    public record Snapshot(boolean enabled, int trackedTerminals, int trackedChunks, int ownedTickets,
                           long deniedRequests, long quotaReleased, long staleReleased) {}

    private static final class ServerState {
        private final Map<ChunkKey, ChunkOwners> chunks = new HashMap<>();
        private final Map<UUID, ChunkKey> terminalChunks = new HashMap<>();
        private long deniedRequests;
        private long quotaReleased;
        private long staleReleased;
        private long nextReconcileTick;
        private TerminalChunkTicketPolicy appliedPolicy;
    }

    private static final class ChunkOwners {
        private final BlockPos ticketOwner;
        private final Set<UUID> terminals = new HashSet<>();
        private boolean ownsTicket;
        private boolean restoredPersistent;
        private long restoredAt;
        private long nextOverrideCheck;
        private long nextDenialLog;
        private boolean denialRecorded;

        private ChunkOwners(BlockPos ticketOwner) {
            this.ticketOwner = ticketOwner;
        }
    }

    private record ChunkKey(String dimension, int chunkX, int chunkZ) {}

    private record PersistedTicket(BlockPos owner, long packed, ChunkPos chunk) {}
}
