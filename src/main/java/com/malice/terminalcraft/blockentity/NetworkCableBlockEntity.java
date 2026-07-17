package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.block.NetworkCableBlock;
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

/** Persistent face occupancy for one multipart network-cable space. */
public final class NetworkCableBlockEntity extends BlockEntity {
    private final EnumSet<Direction> faces = EnumSet.noneOf(Direction.class);

    public NetworkCableBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistries.NETWORK_CABLE_BLOCK_ENTITY.get(), pos, state);
        if (state.hasProperty(NetworkCableBlock.FACE)) faces.add(state.getValue(NetworkCableBlock.FACE));
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

    private void changedAndSync() {
        setChanged();
        if (level != null) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        int mask = 0;
        for (Direction face : faces) mask |= 1 << face.ordinal();
        tag.putInt("FaceMask", mask);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        faces.clear();
        int mask = tag.getInt("FaceMask");
        for (Direction face : Direction.values()) if ((mask & 1 << face.ordinal()) != 0) faces.add(face);
        if (faces.isEmpty() && getBlockState().hasProperty(NetworkCableBlock.FACE)) {
            faces.add(getBlockState().getValue(NetworkCableBlock.FACE));
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        invalidateTopology();
    }

    @Override
    public void setRemoved() {
        invalidateTopology();
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
}
