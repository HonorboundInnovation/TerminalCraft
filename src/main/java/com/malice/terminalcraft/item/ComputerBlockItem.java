package com.malice.terminalcraft.item;

import com.malice.terminalcraft.blockentity.TerminalBlockEntity;
import com.malice.terminalcraft.blockentity.TurtleBlockEntity;
import com.malice.terminalcraft.blockentity.ServerRackBlockEntity;
import com.malice.terminalcraft.persistence.ComputerRecoveryData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.UUID;

/** Stateful computer drops carry only a small world-local recovery ticket over the network. */
public final class ComputerBlockItem extends BlockItem {
    public static final String COMPUTER = "RecoveryComputer";
    public static final String TICKET = "RecoveryTicket";

    public ComputerBlockItem(Block block, Properties properties) { super(block, properties.stacksTo(1)); }

    public static List<ItemStack> preserveDrops(List<ItemStack> drops, LootParams.Builder context) {
        BlockEntity entity = context.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        if (!(entity instanceof TerminalBlockEntity) && !(entity instanceof TurtleBlockEntity)
                && !(entity instanceof ServerRackBlockEntity)) return drops;
        UUID id = entity instanceof TerminalBlockEntity terminal ? terminal.getDeviceId()
                : entity instanceof TurtleBlockEntity turtle ? turtle.getDeviceId() : ((ServerRackBlockEntity) entity).getDeviceId();
        for (ItemStack stack : drops) {
            if (!stack.is(entity.getBlockState().getBlock().asItem())) continue;
            CompoundTag payload = entity.saveWithoutMetadata();
            // Turtle inventory drops separately during removal; never copy it into the ticket.
            payload.remove("Inventory");
            payload.remove("Selected");
            // Rack blades drop separately in onRemove, just like turtle contents.
            // An absent list means legacy monolithic rack and grants a migration blade.
            if (entity instanceof ServerRackBlockEntity) payload.put("Modules", new net.minecraft.nbt.ListTag());
            String item = ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
            UUID ticket = ComputerRecoveryData.get(context.getLevel(), id).capture(item, payload);
            stack.getOrCreateTag().putUUID(COMPUTER, id);
            stack.getOrCreateTag().putUUID(TICKET, ticket);
            break;
        }
        return drops;
    }

    private static boolean hasRecovery(ItemStack stack) {
        return stack.hasTag() && (stack.getTag().contains(COMPUTER) || stack.getTag().contains(TICKET));
    }

    @Nullable private CompoundTag recovery(ServerLevel level, ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.hasUUID(COMPUTER) || !tag.hasUUID(TICKET) || stack.getCount() != 1) return null;
        return ComputerRecoveryData.get(level, tag.getUUID(COMPUTER)).recover(tag.getUUID(TICKET),
                ForgeRegistries.ITEMS.getKey(this).toString());
    }

    @Override public InteractionResult place(BlockPlaceContext context) {
        ItemStack stack = context.getItemInHand();
        if (!hasRecovery(stack)) return super.place(context);
        if (!(context.getLevel() instanceof ServerLevel level)) return InteractionResult.SUCCESS;
        CompoundTag tag = stack.getTag();
        CompoundTag payload = recovery(level, stack);
        if (payload == null) {
            if (context.getPlayer() != null) context.getPlayer().displayClientMessage(
                    Component.literal("Computer recovery unavailable: use its original world and an unused item."), false);
            return InteractionResult.FAIL;
        }
        UUID computer = tag.getUUID(COMPUTER);
        BlockPos pos = context.getClickedPos();
        BlockState previous = level.getBlockState(pos);
        try {
            InteractionResult result = super.place(context);
            if (result.consumesAction()) ComputerRecoveryData.get(level, computer).consume();
            return result;
        } catch (RecoveryFailure failure) {
            level.setBlock(pos, previous, 3);
            return InteractionResult.FAIL;
        }
    }

    @Override protected boolean updateCustomBlockEntityTag(BlockPos pos, Level level, @Nullable Player player,
                                                          ItemStack stack, BlockState state) {
        if (!hasRecovery(stack)) return super.updateCustomBlockEntityTag(pos, level, player, stack, state);
        if (!(level instanceof ServerLevel server)) return false;
        CompoundTag payload = recovery(server, stack);
        BlockEntity entity = level.getBlockEntity(pos);
        if (payload == null || (!(entity instanceof TerminalBlockEntity) && !(entity instanceof TurtleBlockEntity)
                && !(entity instanceof ServerRackBlockEntity)))
            throw new RecoveryFailure();
        try {
            entity.load(payload);
            entity.setChanged();
            level.sendBlockUpdated(pos, state, state, 3);
            return true;
        } catch (RuntimeException failure) {
            com.mojang.logging.LogUtils.getLogger().error("Computer recovery payload failed to load", failure);
            throw new RecoveryFailure();
        }
    }

    @Override public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        if (hasRecovery(stack)) tooltip.add(Component.literal("Saved computer — place in its original world"));
    }

    private static final class RecoveryFailure extends RuntimeException { }
}
