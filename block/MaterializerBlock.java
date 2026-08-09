package com.malice.terminalcraft.block;

import com.malice.terminalcraft.blockentity.MaterializerBlockEntity;
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

/** Player-facing and automation-facing output port for an adjacent TerminalCraft NAS. */
public final class MaterializerBlock extends BaseEntityBlock {
    public MaterializerBlock() {
        super(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE)
                .strength(2.0f, 4.0f).requiresCorrectToolForDrops()
                .isRedstoneConductor((state, level, pos) -> false));
    }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Nullable @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MaterializerBlockEntity(pos, state);
    }

    @Nullable @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        return null;
    }

    @Override @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof MaterializerBlockEntity materializer)) return InteractionResult.PASS;
        ItemStack result = materializer.materialize(player.getItemInHand(hand));
        if (!result.isEmpty()) {
            player.setItemInHand(hand, result);
            return InteractionResult.CONSUME;
        }
        if (player.getItemInHand(hand).isEmpty()) {
            ItemStack output = materializer.extractFirstItem(64);
            if (!output.isEmpty()) {
                if (!player.addItem(output)) player.drop(output, false);
                return InteractionResult.CONSUME;
            }
        }
        player.displayClientMessage(Component.literal("Materializer has no compatible NAS output"), true);
        return InteractionResult.CONSUME;
    }

    @Override @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        if (!state.is(replacement.getBlock())) {
            if (level.getBlockEntity(pos) instanceof MaterializerBlockEntity materializer) materializer.setRemoved();
            level.removeBlockEntity(pos);
        }
        super.onRemove(state, level, pos, replacement, moving);
    }
}
