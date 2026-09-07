package com.malice.terminalcraft.block;

import com.malice.terminalcraft.blockentity.WirelessDisplayLinkBlockEntity;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

/** Wireless display endpoint. Sneak-right-click source then receiver to pair them. */
public class WirelessDisplayLinkBlock extends BaseEntityBlock {
    private static final net.minecraft.world.phys.shapes.VoxelShape MODEL_SHAPE = net.minecraft.world.phys.shapes.Shapes.or(
            net.minecraft.world.level.block.Block.box(0, 0, 0, 16, 1, 16),
            net.minecraft.world.level.block.Block.box(2, 1, 2, 14, 4, 14),
            net.minecraft.world.level.block.Block.box(4, 4, 4, 12, 8, 12),
            net.minecraft.world.level.block.Block.box(5, 8, 5, 11, 12, 11),
            net.minecraft.world.level.block.Block.box(6, 12, 6, 10, 14, 10),
            net.minecraft.world.level.block.Block.box(7, 14, 7, 9, 15, 9),
            net.minecraft.world.level.block.Block.box(6, 15, 6, 10, 16, 10));

    @Override public net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState state,
            net.minecraft.world.level.BlockGetter level, BlockPos pos,
            net.minecraft.world.phys.shapes.CollisionContext context) {
        return MODEL_SHAPE;
    }

    public WirelessDisplayLinkBlock() {
        super(BlockBehaviour.Properties.of().mapColor(net.minecraft.world.level.material.MapColor.COLOR_LIGHT_BLUE)
                .strength(1.5f, 3.0f).requiresCorrectToolForDrops().noOcclusion());
    }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Nullable @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WirelessDisplayLinkBlockEntity(pos, state);
    }

    @Nullable @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) { return null; }

    @Override @SuppressWarnings("deprecation") public InteractionResult use(
            BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof WirelessDisplayLinkBlockEntity link)) return InteractionResult.PASS;
        if (player.isShiftKeyDown()) {
            if (link.attachedHost() != null) {
                link.armPair(player.getUUID());
                player.displayClientMessage(Component.literal("Display link source armed: " + link.channel()
                        + ". Sneak-right-click the receiver link."), true);
            } else if (link.attachedMonitor() != null) {
                if (link.acceptPair(player.getUUID())) {
                    player.displayClientMessage(Component.literal("Display link receiver paired: " + link.channel()), true);
                    WirelessDisplayLinkBlockEntity.clearPending(player.getUUID());
                } else {
                    player.displayClientMessage(Component.literal("No source is armed. Sneak-right-click the source link first."), true);
                }
            } else {
                player.displayClientMessage(Component.literal("Attach this link to a terminal, PLC, server, or monitor."), true);
            }
        } else if (player instanceof ServerPlayer serverPlayer) {
            NetworkHooks.openScreen(serverPlayer, link, buffer -> {
                buffer.writeByte(com.malice.terminalcraft.menu.DisplayDiagnosticsMenu.TYPE_LINK);
                buffer.writeBlockPos(pos);
            });
        }
        return InteractionResult.CONSUME;
    }
}
