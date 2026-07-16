package com.malice.terminalcraft.item;

import com.malice.terminalcraft.menu.TerminalMenu;
import com.malice.terminalcraft.network.RednetNetwork;
import com.malice.terminalcraft.persistence.PersistedDataLimits;
import com.malice.terminalcraft.persistence.PersistedDataVersions;
import com.malice.terminalcraft.shell.BashShell;
import com.malice.terminalcraft.shell.PocketShellComputer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/** Handheld bash computer with shell state and a built-in wireless modem stored on item NBT. */
public class PocketTerminalItem extends Item {
    public static final String TAG_SHELL = "Shell";
    public static final String TAG_LABEL = "Label";
    public static final String TAG_MODEM_ID = "ModemId";
    public static final String TAG_MODEM_CHANNELS = "ModemChannels";
    public static final int MODEM_RANGE = 64;
    public static final int MAX_MODEM_CHANNELS = 128;

    public PocketTerminalItem() {
        super(new Item.Properties().stacksTo(1));
    }

    public static String getLabel(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(TAG_LABEL)) {
            String label = PersistedDataLimits.readString(tag, TAG_LABEL, PersistedDataLimits.MAX_LABEL_CHARS, "");
            if (!label.isBlank()) return label.trim();
        }
        return "pocket";
    }

    public static void setLabel(ItemStack stack, String label) {
        String clean = label == null || label.isBlank() ? "pocket"
                : PersistedDataLimits.truncate(label.trim(), PersistedDataLimits.MAX_LABEL_CHARS);
        CompoundTag tag = stack.getOrCreateTag();
        PersistedDataVersions.stampCurrent(tag);
        tag.putString(TAG_LABEL, clean);
    }

    public static UUID getOrCreateModemId(ItemStack stack) {
        CompoundTag tag = stack.getOrCreateTag();
        if (!tag.hasUUID(TAG_MODEM_ID)) {
            tag.putUUID(TAG_MODEM_ID, UUID.randomUUID());
            PersistedDataVersions.stampCurrent(tag);
        }
        return tag.getUUID(TAG_MODEM_ID);
    }

    public static List<Integer> getOpenChannels(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_MODEM_CHANNELS, Tag.TAG_INT_ARRAY)) return List.of();
        return PersistedDataLimits.readBoundedIntArray(tag, TAG_MODEM_CHANNELS, 0, 65535,
                MAX_MODEM_CHANNELS);
    }

    public static void setOpenChannels(ItemStack stack, List<Integer> channels) {
        int[] bounded = channels.stream().mapToInt(PocketTerminalItem::clampChannel)
                .distinct().sorted().limit(MAX_MODEM_CHANNELS).toArray();
        CompoundTag tag = stack.getOrCreateTag();
        PersistedDataVersions.stampCurrent(tag);
        tag.put(TAG_MODEM_CHANNELS, new IntArrayTag(bounded));
    }

    public static BashShell loadShell(ItemStack stack) {
        BashShell shell = new BashShell();
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(TAG_SHELL, CompoundTag.TAG_COMPOUND)) {
            shell.load(tag.getCompound(TAG_SHELL));
        }
        return shell;
    }

    public static void saveShell(ItemStack stack, BashShell shell) {
        CompoundTag tag = stack.getOrCreateTag();
        PersistedDataVersions.stampCurrent(tag);
        tag.put(TAG_SHELL, shell.save());
        if (!tag.contains(TAG_LABEL)) tag.putString(TAG_LABEL, "pocket");
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        super.inventoryTick(stack, level, entity, slot, selected);
        if (!level.isClientSide && level.getGameTime() % 40 == Math.floorMod(entity.getId(), 40)) {
            RednetNetwork.rebind(level, getOrCreateModemId(stack), entity.blockPosition(),
                    getOpenChannels(stack), true, MODEM_RANGE);
        }
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) return InteractionResultHolder.success(stack);
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResultHolder.pass(stack);

        MenuProvider provider = new MenuProvider() {
            @Override public Component getDisplayName() {
                return Component.translatable("item.terminalcraft.pocket_terminal");
            }

            @Override public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player p) {
                return new TerminalMenu(containerId, inventory, new PocketShellComputer(p, hand));
            }
        };

        NetworkHooks.openScreen(serverPlayer, provider, buf -> {
            buf.writeByte(TerminalMenu.TYPE_POCKET);
            buf.writeEnum(hand);
        });
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("Label: " + getLabel(stack)));
        tooltip.add(Component.literal("Built-in wireless modem: " + getOpenChannels(stack).size() + " channel(s) open"));
        tooltip.add(Component.literal("Right-click to open bash"));
    }

    private static int clampChannel(int channel) {
        return Math.max(0, Math.min(65535, channel));
    }
}
