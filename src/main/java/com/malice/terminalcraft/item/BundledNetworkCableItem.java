package com.malice.terminalcraft.item;

import com.malice.terminalcraft.block.BundledNetworkCableBlock;
import com.malice.terminalcraft.block.NetworkCableBlock;
import com.malice.terminalcraft.block.SurfaceCableRouting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Places one sixteen-channel bundled network trunk on each supported block face. */
public final class BundledNetworkCableItem extends BlockItem {
    public BundledNetworkCableItem(Block block, Properties properties) { super(block, properties); }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable("item.terminalcraft.bundled_network_cable.tooltip"));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        BlockPlaceContext placement = new BlockPlaceContext(context);
        Level level = context.getLevel();
        BlockPos clicked = context.getClickedPos();
        BlockPos target = level.getBlockState(clicked).getBlock() instanceof BundledNetworkCableBlock
                ? clicked : placement.getClickedPos();
        Direction face = context.getClickedFace();
        if (level.getBlockState(target).getBlock() instanceof BundledNetworkCableBlock) {
            if (level.isClientSide) return InteractionResult.SUCCESS;
            if (NetworkCableBlock.hasFace(level, target, face)) return InteractionResult.SUCCESS;
            if (!NetworkCableBlock.addRun(level, target, face, 0, 0,
                    SurfaceCableRouting.planeMask(face))) return InteractionResult.FAIL;
            if (context.getPlayer() != null && !context.getPlayer().getAbilities().instabuild) {
                context.getItemInHand().shrink(1);
            }
            return InteractionResult.CONSUME;
        }
        return super.useOn(context);
    }
}
