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
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** Read-only output port for pulling items and fluids from an adjacent NAS. */
public final class MaterializerBlock extends BaseEntityBlock {
    private static final net.minecraft.world.phys.shapes.VoxelShape SHAPE = net.minecraft.world.phys.shapes.Shapes.or(
            net.minecraft.world.level.block.Block.box(0, 0, 0, 16, 1, 16),
            net.minecraft.world.level.block.Block.box(1, 1, 1, 15, 3, 15),
            net.minecraft.world.level.block.Block.box(2, 3, 2, 4, 14, 4),
            net.minecraft.world.level.block.Block.box(12, 3, 2, 14, 14, 4),
            net.minecraft.world.level.block.Block.box(2, 3, 12, 4, 14, 14),
            net.minecraft.world.level.block.Block.box(12, 3, 12, 14, 14, 14),
            net.minecraft.world.level.block.Block.box(2, 14, 2, 14, 16, 4),
            net.minecraft.world.level.block.Block.box(2, 14, 12, 14, 16, 14),
            net.minecraft.world.level.block.Block.box(2, 14, 4, 4, 16, 12),
            net.minecraft.world.level.block.Block.box(12, 14, 4, 14, 16, 12),
            net.minecraft.world.level.block.Block.box(5, 5, 5, 11, 13, 11),
            net.minecraft.world.level.block.Block.box(6, 3, 6, 10, 5, 10));

    public MaterializerBlock() { super(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_PURPLE).strength(2.0f, 4.0f).requiresCorrectToolForDrops().noOcclusion().isRedstoneConductor((s,l,p)->false)); }
    @Override public net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState state,
            net.minecraft.world.level.BlockGetter level, BlockPos pos,
            net.minecraft.world.phys.shapes.CollisionContext context) {
        return SHAPE;
    }
    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Nullable @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new MaterializerBlockEntity(pos, state); }
    @Nullable @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) { return null; }
    @Override @SuppressWarnings("deprecation") public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof MaterializerBlockEntity materializer)) return InteractionResult.PASS;
        ItemStack held = player.getItemInHand(hand);
        ItemStack filled = materializer.fillContainer(held);
        if (!filled.isEmpty()) { player.setItemInHand(hand, filled); return InteractionResult.CONSUME; }
        ItemStack output = held.isEmpty() ? materializer.extractFirstItem(64) : materializer.extractMatchingItem(held, Math.min(held.getMaxStackSize(), 64));
        if (!output.isEmpty()) { if (!player.addItem(output)) player.drop(output, false); return InteractionResult.CONSUME; }
        player.displayClientMessage(Component.literal("Materializer has no compatible NAS output"), true);
        return InteractionResult.CONSUME;
    }
    @Override @SuppressWarnings("deprecation") public void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        if (!state.is(replacement.getBlock())) { if (level.getBlockEntity(pos) instanceof MaterializerBlockEntity entity) entity.setRemoved(); level.removeBlockEntity(pos); }
        super.onRemove(state, level, pos, replacement, moving);
    }
}
