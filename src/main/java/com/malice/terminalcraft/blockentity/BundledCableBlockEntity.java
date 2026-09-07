package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.block.BundledCableBlock;
import com.malice.terminalcraft.block.RedAlloyWireBlock;
import com.malice.terminalcraft.block.SurfaceCableSupport;
import com.malice.terminalcraft.persistence.PersistedDataVersions;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Persistent multipart occupancy and sixteen-channel state for one bundled-cable space. */
public class BundledCableBlockEntity extends BlockEntity {
    public static final int CHANNELS = 16;
    public static final int MAX_COMPONENT_NODES = 4096;

    private final EnumSet<Direction> faces = EnumSet.noneOf(Direction.class);
    private final int[] localOutput = new int[CHANNELS];
    private final int[] effectiveSignal = new int[CHANNELS];
    /** Legacy persisted bridge value. Strict channel isolation keeps this at zero from 1.0.62. */
    private int vanillaInput;
    /** Color-matched shielded Red Alloy breakout sources, indexed by dye/channel ID. */
    private final int[] breakoutInput = new int[CHANNELS];
    private boolean recomputing;

    public BundledCableBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistries.BUNDLED_CABLE_BLOCK_ENTITY.get(), pos, state);
        if (state.hasProperty(BundledCableBlock.FACE)) faces.add(state.getValue(BundledCableBlock.FACE));
    }

    public Set<Direction> faces() { return Set.copyOf(faces); }
    public boolean hasFace(Direction face) { return faces.contains(face); }
    public int faceCount() { return faces.size(); }

    public boolean addFace(Direction face) {
        if (!faces.add(face)) return false;
        changedAndSync();
        return true;
    }

    public boolean removeFace(Direction face) {
        if (!faces.remove(face)) return false;
        changedAndSync();
        return true;
    }

    public int getSignal(int channel) { return effectiveSignal[requireChannel(channel)]; }
    public int getLocalOutput(int channel) { return localOutput[requireChannel(channel)]; }
    public int getBreakoutInput(int channel) { return breakoutInput[requireChannel(channel)]; }
    public int getVanillaInput() { return vanillaInput; }

    /**
     * Returns the signal entering this segment from physical sources and other bundled segments.
     * The segment's own computer-driven output is deliberately excluded so a terminal can inspect
     * input and output independently instead of reading its output back as input.
     */
    public int getExternalInput(int channel) {
        int index = requireChannel(channel);
        int result = breakoutInput[index];
        if (level == null) return result;
        for (BundledCableBlockEntity cable : collectComponent(level, worldPosition)) {
            int source = cable.breakoutInput[index];
            if (cable != this) source = Math.max(source, cable.localOutput[index]);
            result = Math.max(result, source);
        }
        return result;
    }

    /** Sets one local channel source and deterministically recomputes the connected component. */
    public void setLocalOutput(int channel, int strength) {
        int index = requireChannel(channel);
        int bounded = Math.max(0, Math.min(15, strength));
        if (localOutput[index] == bounded) return;
        localOutput[index] = bounded;
        setChanged();
        recomputeComponent();
    }

    /**
     * Applies several channel sources as one Minecraft-world transaction.  PLC scans use this so
     * an interlocked pair cannot be observed halfway through a scan and the connected component is
     * traversed at most once for the complete output snapshot.
     *
     * @return true when at least one local channel changed
     */
    public boolean setLocalOutputs(Map<Integer, Integer> strengths) {
        if (strengths == null || strengths.isEmpty()) return false;
        boolean changed = false;
        for (Map.Entry<Integer, Integer> entry : strengths.entrySet()) {
            int channel = requireChannel(entry.getKey());
            int bounded = Math.max(0, Math.min(15, entry.getValue()));
            if (localOutput[channel] == bounded) continue;
            localOutput[channel] = bounded;
            changed = true;
        }
        if (changed) {
            setChanged();
            recomputeComponent();
        }
        return changed;
    }

    /** Updates the sixteen color-selected breakout sources; direct uncolored redstone is isolated. */
    public void refreshVanillaInput() {
        if (level == null || level.isClientSide || recomputing) return;
        int input = 0;
        int[] nextBreakout = new int[CHANNELS];
        for (Direction face : faces) {
            for (Direction direction : SurfaceCableSupport.planeDirections(face)) {
                BlockPos adjacent = worldPosition.relative(direction);
                if (!(level.getBlockEntity(adjacent) instanceof RedAlloyWireBlockEntity wire)) continue;
                for (RedAlloyWireBlockEntity.Run run : wire.runs()) {
                    if (run.face() != face || !run.shielded()) continue;
                    int nativePower = RedAlloyWireBlock.nativePowerAt(level, adjacent,
                            run.face(), run.lane(), run.color());
                    nextBreakout[run.color()] = Math.max(nextBreakout[run.color()], nativePower);
                }
            }
        }
        if (vanillaInput != input || !Arrays.equals(breakoutInput, nextBreakout)) {
            vanillaInput = input;
            System.arraycopy(nextBreakout, 0, breakoutInput, 0, CHANNELS);
            setChanged();
            recomputeComponent();
        }
    }

    public void recomputeComponent() {
        if (level == null || level.isClientSide || recomputing) return;
        List<BundledCableBlockEntity> component = collectComponent(level, worldPosition);
        int[] aggregate = new int[CHANNELS];
        for (BundledCableBlockEntity cable : component) {
            for (int channel = 0; channel < CHANNELS; channel++) {
                int source = cable.localOutput[channel];
                source = Math.max(source, cable.breakoutInput[channel]);
                aggregate[channel] = Math.max(aggregate[channel], source);
            }
        }
        List<BundledCableBlockEntity> changed = new ArrayList<>();
        for (BundledCableBlockEntity cable : component) {
            if (cable.applyEffective(aggregate)) changed.add(cable);
        }
        for (BundledCableBlockEntity cable : changed) cable.refreshBreakoutOutputs();
    }

    private boolean applyEffective(int[] aggregate) {
        boolean changed = false;
        for (int channel = 0; channel < CHANNELS; channel++) {
            if (effectiveSignal[channel] != aggregate[channel]) {
                effectiveSignal[channel] = aggregate[channel];
                changed = true;
            }
        }
        if (level == null) return changed;
        recomputing = true;
        try {
            BlockState current = getBlockState();
            Direction primary = current.getValue(BundledCableBlock.FACE);
            BlockState rendered = BundledCableBlock.renderState(level, worldPosition, primary)
                    .setValue(BundledCableBlock.POWER, effectiveSignal[0]);
            if (!current.equals(rendered)) {
                level.setBlock(worldPosition, rendered, 2);
                level.updateNeighborsAt(worldPosition, current.getBlock());
            }
            if (changed) changedAndSync();
        } finally {
            recomputing = false;
        }
        return changed;
    }

    /** Recomputes only adjacent colored breakouts after an effective channel actually changes. */
    private void refreshBreakoutOutputs() {
        if (level == null || level.isClientSide) return;
        Set<BlockPos> refreshed = new HashSet<>();
        for (Direction face : faces) {
            for (Direction direction : SurfaceCableSupport.planeDirections(face)) {
                BlockPos adjacent = worldPosition.relative(direction);
                if (refreshed.add(adjacent)
                        && level.getBlockEntity(adjacent) instanceof RedAlloyWireBlockEntity) {
                    RedAlloyWireBlock.recomputeAt(level, adjacent);
                }
            }
        }
    }

    private static List<BundledCableBlockEntity> collectComponent(Level level, BlockPos start) {
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        Set<BlockPos> visited = new HashSet<>();
        List<BundledCableBlockEntity> result = new ArrayList<>();
        pending.add(start.immutable());
        while (!pending.isEmpty() && visited.size() < MAX_COMPONENT_NODES) {
            BlockPos current = pending.removeFirst();
            if (!visited.add(current)) continue;
            if (!(level.getBlockEntity(current) instanceof BundledCableBlockEntity cable)) continue;
            result.add(cable);
            for (BlockPos next : BundledCableBlock.connectedCablePositions(level, current)) {
                if (!visited.contains(next) && level.hasChunkAt(next)) pending.addLast(next.immutable());
            }
        }
        return result;
    }

    private void changedAndSync() {
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
    }

    private static int requireChannel(int channel) {
        if (channel < 0 || channel >= CHANNELS) throw new IllegalArgumentException("channel must be 0..15");
        return channel;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        PersistedDataVersions.stampCurrent(tag);
        int mask = 0;
        for (Direction face : faces) mask |= 1 << face.ordinal();
        tag.putInt("FaceMask", mask);
        tag.putIntArray("LocalOutput", localOutput);
        tag.putIntArray("EffectiveSignal", effectiveSignal);
        tag.putIntArray("BreakoutInput", breakoutInput);
        tag.putInt("VanillaInput", vanillaInput);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        faces.clear();
        int mask = tag.getInt("FaceMask");
        for (Direction face : Direction.values()) {
            if ((mask & 1 << face.ordinal()) != 0 && !faces.contains(face.getOpposite())) faces.add(face);
        }
        if (faces.isEmpty() && getBlockState().hasProperty(BundledCableBlock.FACE)) {
            faces.add(getBlockState().getValue(BundledCableBlock.FACE));
        }
        loadArray(tag, "LocalOutput", localOutput);
        loadArray(tag, "EffectiveSignal", effectiveSignal);
        loadArray(tag, "BreakoutInput", breakoutInput);
        if (tag.contains("VanillaInput", Tag.TAG_INT)) {
            vanillaInput = Math.max(0, Math.min(15, tag.getInt("VanillaInput")));
        } else {
            vanillaInput = localOutput[0];
            localOutput[0] = 0;
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level == null || level.isClientSide) return;

        // A chunk may be saved after support disappears while it is unloaded.
        // Reuse the normal face-removal path so stale multipart occupancy cannot
        // resurrect unsupported cable faces or retain their channel state.
        for (Direction face : Set.copyOf(faces)) {
            if (!BundledCableBlock.canFaceSurvive(level, worldPosition, face)) {
                BundledCableBlock.removeFace(level, worldPosition, face, true);
            }
        }
        if (level.getBlockEntity(worldPosition) == this && !faces.isEmpty()) {
            refreshVanillaInput();
            recomputeComponent();
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

    private static void loadArray(CompoundTag tag, String key, int[] target) {
        Arrays.fill(target, 0);
        if (!tag.contains(key, Tag.TAG_INT_ARRAY)) return;
        int[] stored = tag.getIntArray(key);
        for (int i = 0; i < Math.min(stored.length, target.length); i++) {
            target[i] = Math.max(0, Math.min(15, stored[i]));
        }
    }
}
