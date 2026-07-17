package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.network.RednetNetworkName;
import com.malice.terminalcraft.persistence.PersistedDataVersions;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/** Persistent logical-network assignments for the six interfaces of a standalone RedNet router. */
public class NetworkRouterBlockEntity extends BlockEntity {
    private final EnumMap<Direction, String> interfaceNetworks = new EnumMap<>(Direction.class);
    private final EnumSet<Direction> disabledInterfaces = EnumSet.noneOf(Direction.class);

    public NetworkRouterBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistries.NETWORK_ROUTER_BLOCK_ENTITY.get(), pos, state);
    }

    /** Empty means automatic legacy routing for this face. */
    public String getInterfaceNetwork(Direction face) {
        return face == null ? "" : interfaceNetworks.getOrDefault(face, "");
    }

    /** Assigns a validated logical network, or clears the face when the requested value is blank. */
    public boolean setInterfaceNetwork(Direction face, String requested) {
        if (face == null) return false;
        if (requested == null || requested.isBlank()) {
            if (interfaceNetworks.remove(face) != null) setChangedAndSync();
            return true;
        }
        String canonical = RednetNetworkName.normalize(requested).orElse("");
        if (canonical.isEmpty()) return false;
        if (!canonical.equals(interfaceNetworks.put(face, canonical))) setChangedAndSync();
        return true;
    }

    public Map<Direction, String> configuredInterfaces() {
        return Map.copyOf(interfaceNetworks);
    }

    /** Interfaces default to enabled so legacy worlds retain their existing forwarding behavior. */
    public boolean isInterfaceEnabled(Direction face) {
        return face != null && !disabledInterfaces.contains(face);
    }

    /** Administratively enables or disables one physical router face. */
    public boolean setInterfaceEnabled(Direction face, boolean enabled) {
        if (face == null) return false;
        boolean changed = enabled ? disabledInterfaces.remove(face) : disabledInterfaces.add(face);
        if (changed) setChangedAndSync();
        return true;
    }

    private void setChangedAndSync() {
        setChanged();
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            com.malice.terminalcraft.network.WiredNetworkTopology.invalidate(serverLevel, worldPosition);
        }
        if (level != null) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        PersistedDataVersions.stampCurrent(tag);
        CompoundTag interfaces = new CompoundTag();
        interfaceNetworks.forEach((face, network) -> interfaces.putString(face.getName(), network));
        if (!interfaces.isEmpty()) tag.put("Interfaces", interfaces);
        CompoundTag disabled = new CompoundTag();
        disabledInterfaces.forEach(face -> disabled.putBoolean(face.getName(), true));
        if (!disabled.isEmpty()) tag.put("DisabledInterfaces", disabled);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        interfaceNetworks.clear();
        disabledInterfaces.clear();
        if (tag.contains("Interfaces", Tag.TAG_COMPOUND)) {
            CompoundTag interfaces = tag.getCompound("Interfaces");
            for (Direction face : Direction.values()) {
                if (!interfaces.contains(face.getName(), Tag.TAG_STRING)) continue;
                RednetNetworkName.normalize(interfaces.getString(face.getName()))
                        .ifPresent(network -> interfaceNetworks.put(face, network));
            }
        }
        if (tag.contains("DisabledInterfaces", Tag.TAG_COMPOUND)) {
            CompoundTag disabled = tag.getCompound("DisabledInterfaces");
            for (Direction face : Direction.values()) {
                if (disabled.contains(face.getName(), Tag.TAG_BYTE) && disabled.getBoolean(face.getName())) {
                    disabledInterfaces.add(face);
                }
            }
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            com.malice.terminalcraft.network.WiredNetworkTopology.invalidate(serverLevel, worldPosition);
        }
    }

    @Override
    public void setRemoved() {
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            com.malice.terminalcraft.network.WiredNetworkTopology.remove(serverLevel, worldPosition);
        }
        super.setRemoved();
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
    public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) {
        CompoundTag tag = packet.getTag();
        if (tag != null) load(tag);
    }
}
