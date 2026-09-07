package com.malice.terminalcraft.persistence;

import com.malice.terminalcraft.item.PocketTerminalItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.io.IOException;

/** Known vanilla item-container traversal, not arbitrary mod NBT or capability rewriting. */
public final class PocketItemMigration {
    private PocketItemMigration() {}

    public static boolean supports(ItemStack stack) {
        return stack.getItem() instanceof PocketTerminalItem || isShulker(stack);
    }

    private static boolean isShulker(ItemStack stack) {
        return stack.getItem() instanceof BlockItem block && block.getBlock() instanceof ShulkerBoxBlock
                && "minecraft".equals(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace());
    }

    public static boolean migrate(ServerLevel level, ItemStack stack) throws IOException {
        if (!supports(stack) || stack.getTag() == null) return false;
        ItemStack candidate = stack.copy();
        if (!visit(level, candidate, 0, new int[] {1024}, false)) return false;
        visit(level, candidate, 0, new int[] {1024}, true);
        // All descendant migrations succeeded before publishing the containing item's tag.
        stack.setTag(candidate.getTag().copy());
        return true;
    }

    private static boolean visit(ServerLevel level, ItemStack stack, int depth, int[] budget, boolean apply) throws IOException {
        if (--budget[0] < 0 || depth > 8) throw new IOException("Nested pocket migration exceeds traversal limits; original container retained");
        CompoundTag tag = stack.getTag();
        if (tag == null) return false;
        if (stack.getItem() instanceof PocketTerminalItem) {
            if (!tag.contains(PocketTerminalItem.TAG_SHELL)) return false;
            if (apply) PocketStorage.open(level, stack);
            else PocketStorage.validateLegacy(tag);
            return true;
        }
        if (!isShulker(stack) || !tag.contains("BlockEntityTag", Tag.TAG_COMPOUND)) return false;
        CompoundTag block = tag.getCompound("BlockEntityTag");
        if (!block.contains("Items")) return false;
        if (!(block.get("Items") instanceof net.minecraft.nbt.ListTag items)
                || (!items.isEmpty() && items.getElementType() != Tag.TAG_COMPOUND)) {
            throw new IOException("Malformed shulker inventory; original container retained");
        }
        if (items.size() > budget[0]) throw new IOException("Nested pocket inventory exceeds entry limit");
        boolean changed = false;
        for (int index = 0; index < items.size(); index++) {
            CompoundTag entry = items.getCompound(index);
            ItemStack child = ItemStack.of(entry);
            if (visit(level, child, depth + 1, budget, apply)) {
                // Preserve Slot, Count, capabilities and unknown entry fields exactly.
                if (apply) entry.put("tag", child.getTag().copy());
                changed = true;
            }
        }
        return changed;
    }
}
