package com.malice.terminalcraft.item;

import com.malice.terminalcraft.persistence.PersistedDataLimits;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Portable tiered media used by a TerminalCraft Network Access Storage block. */
public final class SolidStateDriveItem extends Item {
    public static final String TAG_LABEL = "NasLabel";
    public static final String TAG_ITEMS = "NasItems";
    public static final String TAG_FLUIDS = "NasFluids";
    private final SolidStateDriveTier tier;

    public SolidStateDriveItem(SolidStateDriveTier tier) {
        super(new Item.Properties().stacksTo(1));
        this.tier = tier;
    }

    public SolidStateDriveTier tier() { return tier; }

    public static SolidStateDriveTier tier(ItemStack stack) {
        return stack.getItem() instanceof SolidStateDriveItem drive ? drive.tier : null;
    }

    public static String label(ItemStack stack) {
        if (stack.isEmpty()) return "ssd";
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(TAG_LABEL)) {
            String value = PersistedDataLimits.readString(tag, TAG_LABEL,
                    PersistedDataLimits.MAX_LABEL_CHARS, "");
            if (!value.isBlank()) return value;
        }
        SolidStateDriveTier drive = tier(stack);
        return drive == null ? "ssd" : drive.id() + "-ssd";
    }

    public static void setLabel(ItemStack stack, String label) {
        if (stack.isEmpty()) return;
        stack.getOrCreateTag().putString(TAG_LABEL, PersistedDataLimits.truncate(
                label == null || label.isBlank() ? "ssd" : label.trim(), PersistedDataLimits.MAX_LABEL_CHARS));
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        SolidStateDriveTier drive = tier(stack);
        if (drive == null) return;
        tooltip.add(Component.literal("Tier: " + drive.id()).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Items: " + drive.itemCapacity()).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Fluids: " + drive.fluidCapacityMb() + " mB").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Insert into a Network Access Storage block").withStyle(ChatFormatting.DARK_GRAY));
    }
}
