package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.block.RedAlloyWireBlock;
import com.malice.terminalcraft.block.SurfaceCableSupport;
import com.malice.terminalcraft.block.SurfaceCableRouting;
import com.malice.terminalcraft.registry.ModRegistries;
import com.malice.terminalcraft.persistence.PersistedDataVersions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Persistent one-run-per-face RedPower-style cable with insulation state, color, and signal. */
public final class RedAlloyWireBlockEntity extends BlockEntity {
    public record Run(Direction face, int lane, int color, boolean shielded, int power, int ports) {}

    private static final int RUN_SLOTS = Direction.values().length * SurfaceCableSupport.POINTS_PER_FACE;
    private static final int EMPTY = -1;
    private static final int LEGACY_COLOR = net.minecraft.world.item.DyeColor.RED.getId();

    private final int[] colors = new int[RUN_SLOTS];
    private final boolean[] shielded = new boolean[RUN_SLOTS];
    private final int[] power = new int[RUN_SLOTS];
    private final int[] routes = new int[RUN_SLOTS];

    public RedAlloyWireBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistries.RED_ALLOY_WIRE_BLOCK_ENTITY.get(), pos, state);
        Arrays.fill(colors, EMPTY);
        if (state.hasProperty(RedAlloyWireBlock.FACE)) {
            Direction face = state.getValue(RedAlloyWireBlock.FACE);
            int color = state.hasProperty(RedAlloyWireBlock.COLOR)
                    ? state.getValue(RedAlloyWireBlock.COLOR) : LEGACY_COLOR;
            colors[index(face, 0)] = SurfaceCableSupport.boundedColor(color);
            // Existing placed colored wires predate the explicit insulation flag and must retain
            // their channel behavior. setPlacedBy overrides this for a newly placed base wire.
            shielded[index(face, 0)] = true;
            routes[index(face, 0)] = SurfaceCableRouting.planeMask(face);
        }
    }

    public List<Run> runs() {
        List<Run> result = new ArrayList<>();
        for (Direction face : Direction.values()) {
            for (int lane = 0; lane < SurfaceCableSupport.POINTS_PER_FACE; lane++) {
                int index = index(face, lane);
                if (colors[index] >= 0) {
                    result.add(new Run(face, lane, colors[index], shielded[index], power[index], route(face, lane)));
                }
            }
        }
        return List.copyOf(result);
    }

    public Set<Direction> faces() {
        EnumSet<Direction> result = EnumSet.noneOf(Direction.class);
        for (Run run : runs()) result.add(run.face());
        return Set.copyOf(result);
    }

    public boolean hasFace(Direction face) {
        return runCount(face) > 0;
    }

    public boolean hasRun(Direction face, int lane) {
        return colors[index(face, lane)] >= 0;
    }

    public boolean hasRun(Direction face, int lane, int color) {
        return colors[index(face, lane)] == SurfaceCableSupport.boundedColor(color);
    }

    public int runCount(Direction face) {
        int count = 0;
        for (int lane = 0; lane < SurfaceCableSupport.POINTS_PER_FACE; lane++) {
            if (hasRun(face, lane)) count++;
        }
        return count;
    }

    public int runCount() {
        int count = 0;
        for (int color : colors) if (color >= 0) count++;
        return count;
    }

    /** Compatibility name retained for old multipart callers and diagnostics. */
    public int faceCount() { return faces().size(); }

    public int firstFreeLane(Direction face, int preferred) {
        return hasFace(face) ? -1 : 0;
    }

    public int color(Direction face, int lane) {
        return colors[index(face, lane)];
    }

    public boolean shielded(Direction face, int lane) {
        int index = index(face, lane);
        return colors[index] >= 0 && shielded[index];
    }

    public int power(Direction face, int lane) {
        int index = index(face, lane);
        return colors[index] < 0 ? 0 : power[index];
    }

    public int route(Direction face, int lane) {
        int index = index(face, lane);
        return colors[index] < 0 ? 0 : SurfaceCableRouting.sanitize(face, routes[index]);
    }

    public boolean hasPort(Direction face, int lane, Direction direction) {
        return SurfaceCableRouting.hasPort(route(face, lane), direction);
    }

    /** Maximum is only a blockstate compatibility projection; runs remain electrically isolated. */
    public int maximumPower() {
        int maximum = 0;
        for (Run run : runs()) maximum = Math.max(maximum, run.power());
        return maximum;
    }

    public boolean addRun(Direction face, int lane, int color) {
        return addRun(face, 0, color, true, SurfaceCableRouting.planeMask(face));
    }

    public boolean addRun(Direction face, int lane, int color, int route) {
        return addRun(face, lane, color, true, route);
    }

    public boolean addRun(Direction face, int lane, int color, boolean insulated, int route) {
        if (hasFace(face)) return false;
        int index = index(face, 0);
        colors[index] = SurfaceCableSupport.boundedColor(color);
        shielded[index] = insulated;
        power[index] = 0;
        routes[index] = SurfaceCableRouting.planeMask(face);
        changedAndSync();
        return true;
    }

    public void setShielded(Direction face, int lane, boolean insulated) {
        int index = index(face, lane);
        if (colors[index] < 0 || shielded[index] == insulated) return;
        shielded[index] = insulated;
        changedAndSync();
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
        shielded[index] = false;
        power[index] = 0;
        routes[index] = 0;
        changedAndSync();
        return true;
    }

    /** Removes every parallel run on one unsupported mounting face. */
    public List<Run> removeFace(Direction face) {
        List<Run> removed = runs().stream().filter(run -> run.face() == face).toList();
        if (removed.isEmpty()) return List.of();
        for (Run run : removed) {
            int index = index(run.face(), run.lane());
            colors[index] = EMPTY;
            shielded[index] = false;
            power[index] = 0;
            routes[index] = 0;
        }
        changedAndSync();
        return removed;
    }

    public void setPower(Direction face, int lane, int value) {
        int index = index(face, lane);
        if (colors[index] < 0) return;
        int bounded = Math.max(0, Math.min(15, value));
        if (power[index] == bounded) return;
        power[index] = bounded;
        changedAndSync();
    }

    private void changedAndSync() {
        setChanged();
        if (level != null) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 2);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        PersistedDataVersions.stampCurrent(tag);
        tag.putIntArray("RunColors", colors);
        int[] insulation = new int[RUN_SLOTS];
        for (int i = 0; i < RUN_SLOTS; i++) insulation[i] = shielded[i] ? 1 : 0;
        tag.putIntArray("RunShielded", insulation);
        tag.putIntArray("RunPower", power);
        tag.putIntArray("RunRoutes", routes);
        int mask = 0;
        for (Direction face : faces()) mask |= 1 << face.ordinal();
        tag.putInt("FaceMask", mask);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        Arrays.fill(colors, EMPTY);
        Arrays.fill(shielded, false);
        Arrays.fill(power, 0);
        Arrays.fill(routes, 0);
        if (tag.contains("RunColors", Tag.TAG_INT_ARRAY)) {
            int[] storedColors = tag.getIntArray("RunColors");
            int[] storedShielded = tag.getIntArray("RunShielded");
            int[] storedPower = tag.getIntArray("RunPower");
            int[] storedRoutes = tag.getIntArray("RunRoutes");
            if (storedColors.length == RUN_SLOTS) {
                for (int i = 0; i < RUN_SLOTS; i++) {
                    if (storedColors[i] >= 0 && storedColors[i] < 16) {
                        colors[i] = storedColors[i];
                        // RunColors without RunShielded is a 1.0.49-1.0.62 colored-wire save.
                        shielded[i] = storedShielded.length == RUN_SLOTS
                                ? storedShielded[i] != 0 : true;
                        power[i] = i < storedPower.length ? Math.max(0, Math.min(15, storedPower[i])) : 0;
                        Direction face = Direction.values()[i / SurfaceCableSupport.POINTS_PER_FACE];
                        routes[i] = i < storedRoutes.length
                                ? normalizedRoute(face, storedRoutes[i])
                                : SurfaceCableSupport.pointPortMask(face, i % SurfaceCableSupport.POINTS_PER_FACE);
                    }
                }
            } else {
                int legacySlots = Direction.values().length * SurfaceCableSupport.LANES_PER_FACE;
                for (int i = 0; i < Math.min(storedColors.length, legacySlots); i++) {
                    if (storedColors[i] < 0 || storedColors[i] >= 16) continue;
                    Direction face = Direction.values()[i / SurfaceCableSupport.LANES_PER_FACE];
                    int lane = i % SurfaceCableSupport.LANES_PER_FACE;
                    int legacyRoute = i < storedRoutes.length
                            ? storedRoutes[i] : SurfaceCableRouting.planeMask(face);
                    int points = SurfaceCableSupport.latticeMask(face, lane, legacyRoute);
                    int legacyPower = i < storedPower.length ? Math.max(0, Math.min(15, storedPower[i])) : 0;
                    for (int point = 0; point < SurfaceCableSupport.POINTS_PER_FACE; point++) {
                        if ((points & 1 << point) == 0 || hasRun(face, point)) continue;
                        int index = index(face, point);
                        colors[index] = storedColors[i];
                        shielded[index] = storedShielded.length > i ? storedShielded[i] != 0 : true;
                        power[index] = legacyPower;
                        routes[index] = SurfaceCableSupport.pointPortMask(face, point);
                    }
                }
            }
        } else {
            // Migrate the original one-run-per-face payload without deleting existing worlds.
            int mask = tag.getInt("FaceMask");
            int[] legacyPower = tag.getIntArray("FacePower");
            for (Direction face : Direction.values()) {
                if ((mask & 1 << face.ordinal()) == 0) continue;
                int index = index(face, 0);
                colors[index] = LEGACY_COLOR;
                // Original FaceMask payloads represent the pre-insulation Red Alloy conductor.
                shielded[index] = false;
                routes[index] = SurfaceCableSupport.pointPortMask(face, 0);
                power[index] = face.ordinal() < legacyPower.length
                        ? Math.max(0, Math.min(15, legacyPower[face.ordinal()])) : 0;
            }
        }
        if (runCount() == 0 && getBlockState().hasProperty(RedAlloyWireBlock.FACE)) {
            Direction face = getBlockState().getValue(RedAlloyWireBlock.FACE);
            int color = getBlockState().hasProperty(RedAlloyWireBlock.COLOR)
                    ? getBlockState().getValue(RedAlloyWireBlock.COLOR) : LEGACY_COLOR;
            colors[index(face, 0)] = SurfaceCableSupport.boundedColor(color);
            shielded[index(face, 0)] = true;
            routes[index(face, 0)] = SurfaceCableRouting.planeMask(face);
            power[index(face, 0)] = getBlockState().getValue(RedAlloyWireBlock.POWER);
        }
        collapseToSingleRuns();
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level == null || level.isClientSide) return;
        for (Direction face : Set.copyOf(faces())) {
            if (!RedAlloyWireBlock.canFaceSurvive(level, worldPosition, face)) {
                RedAlloyWireBlock.removeFace(level, worldPosition, face, true);
            }
        }
        if (level.getBlockEntity(worldPosition) == this && runCount() > 0) {
            RedAlloyWireBlock.recomputeAt(level, worldPosition);
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
            boolean selectedShielded = false;
            int maximumPower = 0;
            for (int lane = 0; lane < SurfaceCableSupport.POINTS_PER_FACE; lane++) {
                int slot = index(face, lane);
                if (colors[slot] >= 0 && selectedColor == EMPTY) {
                    selectedColor = colors[slot];
                    selectedShielded = shielded[slot];
                }
                if (colors[slot] >= 0) maximumPower = Math.max(maximumPower, power[slot]);
                colors[slot] = EMPTY;
                shielded[slot] = false;
                power[slot] = 0;
                routes[slot] = 0;
            }
            if (selectedColor >= 0) {
                colors[index(face, 0)] = selectedColor;
                shielded[index(face, 0)] = selectedShielded;
                power[index(face, 0)] = maximumPower;
                routes[index(face, 0)] = SurfaceCableRouting.planeMask(face);
            }
        }
    }
}
