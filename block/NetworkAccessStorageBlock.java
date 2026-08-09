package com.malice.terminalcraft.block;

import com.malice.terminalcraft.blockentity.NetworkAccessStorageBlockEntity;
import com.malice.terminalcraft.item.SolidStateDriveItem;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** Eight-drive TerminalCraft network storage controller. */
public final class NetworkAccessStorageBlock extends BaseEntityBlock {
    public NetworkAccessStorageBlock() {
        super(BlockBehaviour.Properties.of().mapColor(MapColor.METAL)
                .strength(3.0f, 6.0f).requiresCorrectToolForDrops()
                .isRedstoneConductor((state, level, pos) -> false));
    }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Nullable @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new NetworkAccessStorageBlockEntity(pos, state);
    }

    @Nullable @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return createTickerHelper(type, ModRegistries.NETWORK_ACCESS_STORAGE_BLOCK_ENTITY.get(),
                NetworkAccessStorageBlockEntity::serverTick);
    }

    @Override @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof NetworkAccessStorageBlockEntity nas)) return InteractionResult.PASS;
        ItemStack held = player.getItemInHand(hand);
        if (SolidStateDriveItem.tier(held) != null) {
            if (!nas.insertDrive(held)) {
                player.displayClientMessage(Component.literal("NAS drive bays are full"), true);
                return InteractionResult.CONSUME;
            }
            held.shrink(1);
            player.displayClientMessage(Component.literal("Inserted " + SolidStateDriveItem.label(held)), true);
            return InteractionResult.CONSUME;
        }
        if (held.isEmpty()) {
            ItemStack drive = nas.ejectLastDrive();
            if (!drive.isEmpty()) {
                if (!player.addItem(drive)) player.drop(drive, false);
                player.displayClientMessage(Component.literal("Ejected " + SolidStateDriveItem.label(drive)), true);
            } else {
                player.displayClientMessage(Component.literal("NAS has no drives installed"), true);
            }
            return InteractionResult.CONSUME;
        }
        nas.summary().forEach(line -> player.displayClientMessage(Component.literal(line), false));
        return InteractionResult.CONSUME;
    }

    @Override @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        if (!state.is(replacement.getBlock()) && level.getBlockEntity(pos) instanceof NetworkAccessStorageBlockEntity nas) {
            nas.dropContents();
            nas.setRemoved();
            level.removeBlockEntity(pos);
        }
        super.onRemove(state, level, pos, replacement, moving);
    }
}
