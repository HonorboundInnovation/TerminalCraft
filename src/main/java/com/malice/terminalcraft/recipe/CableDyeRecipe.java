package com.malice.terminalcraft.recipe;

import com.malice.terminalcraft.item.NetworkCableItem;
import com.malice.terminalcraft.item.RedAlloyWireItem;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/** Recolors either surface cable family with exactly one vanilla dye while preserving its item type. */
public final class CableDyeRecipe extends CustomRecipe {
    public CableDyeRecipe(ResourceLocation id, CraftingBookCategory category) {
        super(id, category);
    }

    @Override
    public boolean matches(CraftingContainer container, Level level) {
        return ingredients(container) != null;
    }

    @Override
    public ItemStack assemble(CraftingContainer container, RegistryAccess registries) {
        Ingredients ingredients = ingredients(container);
        if (ingredients == null) return ItemStack.EMPTY;
        ItemStack cable = ingredients.cable().copy();
        cable.setCount(1);
        if (cable.getItem() instanceof NetworkCableItem) {
            return NetworkCableItem.colored(cable, ingredients.dye().getDyeColor());
        }
        return RedAlloyWireItem.colored(cable, ingredients.dye().getDyeColor());
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRegistries.CABLE_DYE_RECIPE.get();
    }

    private static Ingredients ingredients(CraftingContainer container) {
        ItemStack cable = ItemStack.EMPTY;
        DyeItem dye = null;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) continue;
            if ((stack.getItem() instanceof NetworkCableItem || stack.getItem() instanceof RedAlloyWireItem)
                    && cable.isEmpty()) {
                cable = stack;
            } else if (stack.getItem() instanceof DyeItem found && dye == null) {
                dye = found;
            } else {
                return null;
            }
        }
        return cable.isEmpty() || dye == null ? null : new Ingredients(cable, dye);
    }

    private record Ingredients(ItemStack cable, DyeItem dye) {}
}
