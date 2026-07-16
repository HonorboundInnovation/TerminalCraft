package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.block.BundledCableBlock;
import com.malice.terminalcraft.persistence.PersistedDataVersions;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Persistent multipart occupancy and sixteen-channel state for one bundled-cable space. */
public class BundledCableBlockEntity extends BlockEntity {
    public static final int CHANNELS = 16;
    public static final int MAX_COMPONENT_NODES = 4096;

    private final EnumSet<Direction> faces = EnumSet.noneOf(Direction.class);
    private final int[] localOutput = new int[CHANNELS];
    private final int[] effectiveSignal = new int[CHANNELS];
    /** Vanilla redstone is an independent channel-zero source, not computer-owned output. */
    private int vanillaInput;
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

    /** Sets one local channel source and deterministically recomputes the connected component. */
    public void setLocalOutput(int channel, int strength) {
        int index = requireChannel(channel);
        int bounded = Math.max(0, Math.min(15, strength));
        if (localOutput[index] == bounded) return;
        localOutput[index] = bounded;
        setChanged();
        recomputeComponent();
    }

    /** Updates the vanilla bridge source on channel zero while ignoring connected cable positions. */
    public void refreshVanillaInput() {
        if (level == null || level.isClientSide || recomputing) return;
        int input = 0;
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = worldPosition.relative(direction);
            if (level.getBlockState(neighbor).getBlock() instanceof BundledCableBlock) continue;
            input = Math.max(input, level.getSignal(neighbor, direction.getOpposite()));
        }
        if (vanillaInput != input) {
            vanillaInput = input;
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
                if (channel == 0) source = Math.max(source, cable.vanillaInput);
                aggregate[channel] = Math.max(aggregate[channel], source);
            }
        }
        for (BundledCableBlockEntity cable : component) cable.applyEffective(aggregate);
    }

    private void applyEffective(int[] aggregate) {
        boolean changed = false;
        for (int channel = 0; channel < CHANNELS; channel++) {
            if (effectiveSignal[channel] != aggregate[channel]) {
                effectiveSignal[channel] = aggregate[channel];
                changed = true;
            }
        }
        if (level == null) return;
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
        tag.putInt("VanillaInput", vanillaInput);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        faces.clear();
        int mask = tag.getInt("FaceMask");
        for (Direction face : Direction.values()) if ((mask & 1 << face.ordinal()) != 0) faces.add(face);
        if (faces.isEmpty() && getBlockState().hasProperty(BundledCableBlock.FACE)) {
            faces.add(getBlockState().getValue(BundledCableBlock.FACE));
        }
        loadArray(tag, "LocalOutput", localOutput);
        loadArray(tag, "EffectiveSignal", effectiveSignal);
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
        if (level != null && !level.isClientSide) {
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

    private static void loadArray(CompoundTag tag, String key, int[] target) {
        if (!tag.contains(key, Tag.TAG_INT_ARRAY)) return;
        int[] stored = tag.getIntArray(key);
        for (int i = 0; i < Math.min(stored.length, target.length); i++) {
            target[i] = Math.max(0, Math.min(15, stored[i]));
        }
    }
}
