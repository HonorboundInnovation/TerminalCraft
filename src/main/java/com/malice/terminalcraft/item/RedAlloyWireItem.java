package com.malice.terminalcraft.item;

import com.malice.terminalcraft.block.RedAlloyWireBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Unshielded or dye-colored shielded Red Alloy wire; one centered cable may occupy each face. */
public final class RedAlloyWireItem extends BlockItem {
    private static final String COLOR_TAG = "CableColor";
    public static final DyeColor DEFAULT_COLOR = DyeColor.RED;

    public RedAlloyWireItem(Block block, Properties properties) {
        super(block, properties);
    }

    public static DyeColor color(ItemStack stack) {
        return stack.hasTag() && stack.getTag().contains(COLOR_TAG)
                ? DyeColor.byId(stack.getTag().getInt(COLOR_TAG)) : DEFAULT_COLOR;
    }

    /** A color tag is the durable distinction between the base wire and an insulated channel. */
    public static boolean isShielded(ItemStack stack) {
        return stack.hasTag() && stack.getTag().contains(COLOR_TAG);
    }

    public static ItemStack colored(ItemStack stack, DyeColor color) {
        ItemStack result = stack.copy();
        result.getOrCreateTag().putInt(COLOR_TAG, color.getId());
        return result;
    }

    @Override
    public Component getName(ItemStack stack) {
        if (!isShielded(stack)) return Component.translatable("item.terminalcraft.red_alloy_wire");
        return Component.translatable("item.terminalcraft.red_alloy_wire.colored",
                Component.translatable("color.minecraft." + color(stack).getName()));
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                                TooltipFlag flag) {
        tooltip.add(Component.translatable(isShielded(stack)
                ? "item.terminalcraft.red_alloy_wire.tooltip"
                : "item.terminalcraft.red_alloy_wire.unshielded.tooltip"));
        tooltip.add(Component.translatable("item.terminalcraft.surface_cable.lanes"));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        BlockPlaceContext placement = new BlockPlaceContext(context);
        Level level = context.getLevel();
        BlockPos clicked = context.getClickedPos();
        boolean existing = level.getBlockState(clicked).getBlock() instanceof RedAlloyWireBlock;
        BlockPos target = existing ? clicked : placement.getClickedPos();
        Direction face = context.getClickedFace();
        if (existing) {
            face = RedAlloyWireBlock.placementFace(level, target, context.getPlayer(),
                    context.getClickLocation(), face);
        }
        if (level.getBlockState(target).getBlock() instanceof RedAlloyWireBlock) {
            if (level.isClientSide) return InteractionResult.SUCCESS;
            int cableColor = color(context.getItemInHand()).getId();
            if (!RedAlloyWireBlock.addRun(level, target, face, 0, cableColor,
                    isShielded(context.getItemInHand()))) {
                return InteractionResult.FAIL;
            }
            if (context.getPlayer() != null && !context.getPlayer().getAbilities().instabuild) {
                context.getItemInHand().shrink(1);
            }
            return InteractionResult.CONSUME;
        }
        return super.useOn(context);
    }
}
