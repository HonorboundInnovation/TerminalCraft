package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.block.RedAlloyWireBlock;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Set;

/** Persistent six-face occupancy and per-face signal for one multipart red-alloy wire space. */
public final class RedAlloyWireBlockEntity extends BlockEntity {
    private final EnumSet<Direction> faces = EnumSet.noneOf(Direction.class);
    private final int[] power = new int[Direction.values().length];

    public RedAlloyWireBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistries.RED_ALLOY_WIRE_BLOCK_ENTITY.get(), pos, state);
        if (state.hasProperty(RedAlloyWireBlock.FACE)) faces.add(state.getValue(RedAlloyWireBlock.FACE));
    }

    public Set<Direction> faces() {
        return Set.copyOf(faces);
    }

    public boolean hasFace(Direction face) {
        return faces.contains(face);
    }

    public int faceCount() {
        return faces.size();
    }

    public int power(Direction face) {
        return faces.contains(face) ? power[face.ordinal()] : 0;
    }

    public int maximumPower() {
        int maximum = 0;
        for (Direction face : faces) maximum = Math.max(maximum, power(face));
        return maximum;
    }

    public boolean addFace(Direction face) {
        if (!faces.add(face)) return false;
        power[face.ordinal()] = 0;
        changedAndSync();
        return true;
    }

    public boolean removeFace(Direction face) {
        if (!faces.remove(face)) return false;
        power[face.ordinal()] = 0;
        changedAndSync();
        return true;
    }

    public void setPower(Direction face, int value) {
        if (!faces.contains(face)) return;
        int bounded = Math.max(0, Math.min(15, value));
        if (power[face.ordinal()] == bounded) return;
        power[face.ordinal()] = bounded;
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
        int mask = 0;
        for (Direction face : faces) mask |= 1 << face.ordinal();
        tag.putInt("FaceMask", mask);
        tag.putIntArray("FacePower", power);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        faces.clear();
        int mask = tag.getInt("FaceMask");
        for (Direction face : Direction.values()) {
            if ((mask & 1 << face.ordinal()) != 0) faces.add(face);
        }
        int[] stored = tag.getIntArray("FacePower");
        for (int i = 0; i < power.length; i++) {
            power[i] = i < stored.length ? Math.max(0, Math.min(15, stored[i])) : 0;
        }
        // Legacy one-face worlds have no block-entity payload; retain the blockstate face.
        if (faces.isEmpty() && getBlockState().hasProperty(RedAlloyWireBlock.FACE)) {
            Direction legacyFace = getBlockState().getValue(RedAlloyWireBlock.FACE);
            faces.add(legacyFace);
            power[legacyFace.ordinal()] = getBlockState().getValue(RedAlloyWireBlock.POWER);
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
}
