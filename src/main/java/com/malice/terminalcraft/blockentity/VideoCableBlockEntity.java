package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** Stateless endpoint marker for one segment of a routed video-cable component. */
public class VideoCableBlockEntity extends BlockEntity implements MenuProvider {
    public VideoCableBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistries.VIDEO_CABLE_BLOCK_ENTITY.get(), pos, state);
    }

    @Override public void onLoad() {
        super.onLoad();
        DisplayTransportRuntime.register(this);
    }

    @Override public void setRemoved() {
        DisplayTransportRuntime.unregister(this);
        super.setRemoved();
    }

    @Override public Component getDisplayName() {
        return Component.translatable("block.terminalcraft.video_cable");
    }

    @Nullable
    @Override public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new com.malice.terminalcraft.menu.DisplayDiagnosticsMenu(containerId, inventory, this);
    }

    /** Human-readable local connection state for diagnostics and future wrench integrations. */
    public String connectionSummary() {
        StringBuilder result = new StringBuilder();
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            if (getBlockState().getValue(com.malice.terminalcraft.block.VideoCableBlock.property(direction))) {
                if (result.length() > 0) result.append(',');
                result.append(direction.getName());
            }
        }
        return result.length() == 0 ? "none" : result.toString();
    }

    @Override protected void saveAdditional(CompoundTag tag) { super.saveAdditional(tag); }
    @Override public CompoundTag getUpdateTag() { return super.getUpdateTag(); }
    @Nullable @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public void onDataPacket(Connection connection, ClientboundBlockEntityDataPacket packet) {}
}
