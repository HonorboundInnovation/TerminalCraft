package com.malice.terminalcraft.item;

import com.malice.terminalcraft.block.BundledCableBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/** Places a bundled cable or inserts another supported face into its multipart space. */
public final class BundledCableItem extends BlockItem {
    public BundledCableItem(Block block, Properties properties) { super(block, properties); }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        BlockPlaceContext placement = new BlockPlaceContext(context);
        Level level = context.getLevel();
        BlockPos target = placement.getClickedPos();
        Direction face = context.getClickedFace();
        if (level.getBlockState(target).getBlock() instanceof BundledCableBlock) {
            if (level.isClientSide) return InteractionResult.SUCCESS;
            if (BundledCableBlock.hasFace(level, target, face)) return InteractionResult.SUCCESS;
            if (!BundledCableBlock.addFace(level, target, face)) return InteractionResult.FAIL;
            if (context.getPlayer() != null && !context.getPlayer().getAbilities().instabuild) {
                context.getItemInHand().shrink(1);
            }
            return InteractionResult.CONSUME;
        }
        return super.useOn(context);
    }
}
