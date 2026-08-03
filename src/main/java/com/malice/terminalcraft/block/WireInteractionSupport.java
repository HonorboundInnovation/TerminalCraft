package com.malice.terminalcraft.block;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ToolAction;

/** Shared, optional-mod-safe recognition for wire inspection tools. */
public final class WireInteractionSupport {
    private static final ToolAction WRENCH = ToolAction.get("wrench");
    private static final ToolAction WRENCH_ROTATE = ToolAction.get("wrench_rotate");
    private static final TagKey<Item> FORGE_WRENCHES = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath("forge", "tools/wrench"));
    private static final TagKey<Item> COMMON_WRENCHES = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath("c", "tools/wrench"));

    private WireInteractionSupport() {}

    public static boolean isWrench(ItemStack stack) {
        return !stack.isEmpty() && acceptsWrenchSignals(stack.is(FORGE_WRENCHES), stack.is(COMMON_WRENCHES),
                stack.canPerformAction(WRENCH), stack.canPerformAction(WRENCH_ROTATE));
    }

    static boolean acceptsWrenchSignals(boolean forgeTag, boolean commonTag,
                                         boolean wrenchAction, boolean rotateAction) {
        return forgeTag || commonTag || wrenchAction || rotateAction;
    }
}
