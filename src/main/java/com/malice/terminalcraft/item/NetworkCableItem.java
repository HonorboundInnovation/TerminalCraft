package com.malice.terminalcraft.item;

import com.malice.terminalcraft.block.NetworkCableBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/** Places a network cable or inserts another supported face into its multipart space. */
public final class NetworkCableItem extends BlockItem {
    public NetworkCableItem(Block block, Properties properties) { super(block, properties); }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        BlockPlaceContext placement = new BlockPlaceContext(context);
        Level level = context.getLevel();
        BlockPos target = placement.getClickedPos();
        Direction face = context.getClickedFace();
        if (level.getBlockState(target).getBlock() instanceof NetworkCableBlock) {
            if (level.isClientSide) return InteractionResult.SUCCESS;
            if (NetworkCableBlock.hasFace(level, target, face)) return InteractionResult.SUCCESS;
            if (!NetworkCableBlock.addFace(level, target, face)) return InteractionResult.FAIL;
            if (context.getPlayer() != null && !context.getPlayer().getAbilities().instabuild) {
                context.getItemInHand().shrink(1);
            }
            return InteractionResult.CONSUME;
        }
        return super.useOn(context);
    }
}
