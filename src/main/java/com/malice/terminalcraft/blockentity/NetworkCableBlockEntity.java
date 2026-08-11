package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.block.NetworkCableBlock;
import com.malice.terminalcraft.block.BundledNetworkCableBlock;
import com.malice.terminalcraft.block.SurfaceCableSupport;
import com.malice.terminalcraft.block.SurfaceCableRouting;
import com.malice.terminalcraft.persistence.PersistedDataVersions;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Persistent one-run-per-face RedPower-style data/control cable. */
public final class NetworkCableBlockEntity extends BlockEntity {
    public record Run(Direction face, int lane, int color, int ports) {
        public int channel() { return color; }
    }

    private static final int RUN_SLOTS = Direction.values().length * SurfaceCableSupport.POINTS_PER_FACE;
    private static final int EMPTY = -1;
    private static final int LEGACY_COLOR = DyeColor.CYAN.getId();
    private final int[] colors = new int[RUN_SLOTS];
    private final int[] routes = new int[RUN_SLOTS];

    public NetworkCableBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistries.NETWORK_CABLE_BLOCK_ENTITY.get(), pos, state);
        Arrays.fill(colors, EMPTY);
        if (state.hasProperty(NetworkCableBlock.FACE)) {
            Direction face = state.getValue(NetworkCableBlock.FACE);
            int color = state.hasProperty(NetworkCableBlock.COLOR)
                    ? state.getValue(NetworkCableBlock.COLOR) : LEGACY_COLOR;
            colors[index(face, 0)] = SurfaceCableSupport.boundedColor(color);
            routes[index(face, 0)] = SurfaceCableRouting.planeMask(face);
        }
    }

    public List<Run> runs() {
        List<Run> result = new ArrayList<>();
        for (Direction face : Direction.values()) {
            for (int lane = 0; lane < SurfaceCableSupport.POINTS_PER_FACE; lane++) {
                int color = colors[index(face, lane)];
                if (color >= 0) result.add(new Run(face, lane, color, route(face, lane)));
            }
        }
        return List.copyOf(result);
    }

    public Set<Direction> faces() {
        EnumSet<Direction> result = EnumSet.noneOf(Direction.class);
        for (Run run : runs()) result.add(run.face());
        return Set.copyOf(result);
    }

    public boolean hasFace(Direction face) { return runCount(face) > 0; }
    public boolean hasRun(Direction face, int lane) { return colors[index(face, lane)] >= 0; }
    public boolean hasRun(Direction face, int lane, int color) {
        return colors[index(face, lane)] == SurfaceCableSupport.boundedColor(color);
    }
    public int color(Direction face, int lane) { return colors[index(face, lane)]; }
    public int route(Direction face, int lane) {
        int index = index(face, lane);
        return colors[index] < 0 ? 0 : SurfaceCableRouting.sanitize(face, routes[index]);
    }
    public boolean hasPort(Direction face, int lane, Direction direction) {
        return SurfaceCableRouting.hasPort(route(face, lane), direction);
    }

    public int runCount(Direction face) {
        int count = 0;
        for (int lane = 0; lane < SurfaceCableSupport.POINTS_PER_FACE; lane++) if (hasRun(face, lane)) count++;
        return count;
    }

    public int runCount() {
        int count = 0;
        for (int color : colors) if (color >= 0) count++;
        return count;
    }

    public int faceCount() { return faces().size(); }

    public int firstFreeLane(Direction face, int preferred) {
        return hasFace(face) ? -1 : 0;
    }

    public boolean addRun(Direction face, int lane, int color) {
        return addRun(face, 0, color, SurfaceCableRouting.planeMask(face));
    }

    public boolean addRun(Direction face, int lane, int color, int route) {
        if (hasFace(face)) return false;
        int index = index(face, 0);
        colors[index] = SurfaceCableSupport.boundedColor(color);
        routes[index] = SurfaceCableRouting.planeMask(face);
        changedAndSync();
        return true;
    }

    public boolean setRoute(Direction face, int lane, int route) {
        int index = index(face, lane);
        if (colors[index] < 0) return false;
        int normalized = normalizedRoute(face, route);
        if (routes[index] == normalized) return false;
        routes[index] = normalized;
        changedAndSync();
        return true;
    }

    public boolean removeRun(Direction face, int lane) {
        int index = index(face, lane);
        if (colors[index] < 0) return false;
        colors[index] = EMPTY;
        routes[index] = 0;
        changedAndSync();
        return true;
    }

    public List<Run> removeFace(Direction face) {
        List<Run> removed = runs().stream().filter(run -> run.face() == face).toList();
        if (removed.isEmpty()) return List.of();
        for (Run run : removed) {
            colors[index(run.face(), run.lane())] = EMPTY;
            routes[index(run.face(), run.lane())] = 0;
        }
        changedAndSync();
        return removed;
    }

    private void changedAndSync() {
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        PersistedDataVersions.stampCurrent(tag);
        tag.putIntArray("RunColors", colors);
        tag.putIntArray("RunRoutes", routes);
        int mask = 0;
        for (Direction face : faces()) mask |= 1 << face.ordinal();
        tag.putInt("FaceMask", mask);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        Arrays.fill(colors, EMPTY);
        Arrays.fill(routes, 0);
        if (tag.contains("RunColors", Tag.TAG_INT_ARRAY)) {
            int[] stored = tag.getIntArray("RunColors");
            int[] storedRoutes = tag.getIntArray("RunRoutes");
            if (stored.length == RUN_SLOTS) {
                for (int i = 0; i < RUN_SLOTS; i++) {
                    if (stored[i] >= 0 && stored[i] < 16) {
                        colors[i] = stored[i];
                        Direction face = Direction.values()[i / SurfaceCableSupport.POINTS_PER_FACE];
                        routes[i] = i < storedRoutes.length
                                ? normalizedRoute(face, storedRoutes[i])
                                : SurfaceCableSupport.pointPortMask(face, i % SurfaceCableSupport.POINTS_PER_FACE);
                    }
                }
            } else {
                int legacySlots = Direction.values().length * SurfaceCableSupport.LANES_PER_FACE;
                for (int i = 0; i < Math.min(stored.length, legacySlots); i++) {
                    if (stored[i] < 0 || stored[i] >= 16) continue;
                    Direction face = Direction.values()[i / SurfaceCableSupport.LANES_PER_FACE];
                    int lane = i % SurfaceCableSupport.LANES_PER_FACE;
                    int legacyRoute = i < storedRoutes.length
                            ? storedRoutes[i] : SurfaceCableRouting.planeMask(face);
                    int points = SurfaceCableSupport.latticeMask(face, lane, legacyRoute);
                    for (int point = 0; point < SurfaceCableSupport.POINTS_PER_FACE; point++) {
                        if ((points & 1 << point) == 0 || hasRun(face, point)) continue;
                        colors[index(face, point)] = stored[i];
                        routes[index(face, point)] = SurfaceCableSupport.pointPortMask(face, point);
                    }
                }
            }
        } else {
            int mask = tag.getInt("FaceMask");
            for (Direction face : Direction.values()) {
                if ((mask & 1 << face.ordinal()) != 0) {
                    colors[index(face, 0)] = LEGACY_COLOR;
                    routes[index(face, 0)] = SurfaceCableSupport.pointPortMask(face, 0);
                }
            }
        }
        if (runCount() == 0 && getBlockState().hasProperty(NetworkCableBlock.FACE)) {
            Direction face = getBlockState().getValue(NetworkCableBlock.FACE);
            int color = getBlockState().hasProperty(NetworkCableBlock.COLOR)
                    ? getBlockState().getValue(NetworkCableBlock.COLOR) : LEGACY_COLOR;
            colors[index(face, 0)] = SurfaceCableSupport.boundedColor(color);
            routes[index(face, 0)] = SurfaceCableRouting.planeMask(face);
        }
        collapseToSingleRuns();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level == null || level.isClientSide) return;
        for (Direction face : Set.copyOf(faces())) {
            if (!NetworkCableBlock.canFaceSurvive(level, worldPosition, face)) {
                NetworkCableBlock.removeFace(level, worldPosition, face, true);
            }
        }
        invalidateTopology();
    }

    @Override
    public void setRemoved() {
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            com.malice.terminalcraft.network.WiredNetworkTopology.remove(serverLevel, worldPosition);
        }
        super.setRemoved();
    }

    private void invalidateTopology() {
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            com.malice.terminalcraft.network.WiredNetworkTopology.invalidate(serverLevel, worldPosition);
        }
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

    private static int index(Direction face, int lane) {
        return face.ordinal() * SurfaceCableSupport.POINTS_PER_FACE + SurfaceCableSupport.requirePoint(lane);
    }

    private static int normalizedRoute(Direction face, int route) {
        return SurfaceCableRouting.sanitize(face, route);
    }

    /** Collapses 1.0.58-1.0.60 lane/matrix saves to one deterministic centered cable per face. */
    private void collapseToSingleRuns() {
        for (Direction face : Direction.values()) {
            int selectedColor = EMPTY;
            for (int lane = 0; lane < SurfaceCableSupport.POINTS_PER_FACE; lane++) {
                int slot = index(face, lane);
                if (colors[slot] >= 0 && selectedColor == EMPTY) selectedColor = colors[slot];
                colors[slot] = EMPTY;
                routes[slot] = 0;
            }
            if (selectedColor >= 0) {
                colors[index(face, 0)] = selectedColor;
                routes[index(face, 0)] = SurfaceCableRouting.planeMask(face);
            }
        }
    }
}
