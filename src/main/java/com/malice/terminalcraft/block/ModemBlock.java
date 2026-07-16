package com.malice.terminalcraft.block;

import com.malice.terminalcraft.blockentity.ModemBlockEntity;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Wireless rednet modem. Adjacent terminals use `modem` / `rednet` shell commands.
 */
public class ModemBlock extends BaseEntityBlock {
    public ModemBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_LIGHT_BLUE)
                .strength(1.5f, 3.0f)
                .noOcclusion());
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ModemBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(type, ModRegistries.MODEM_BLOCK_ENTITY.get(), ModemBlockEntity::serverTick);
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                  InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ModemBlockEntity modem) {
            if (player.isShiftKeyDown()) {
                modem.setWireless(!modem.isWireless());
                player.displayClientMessage(Component.literal(
                        "Modem mode: " + (modem.isWireless() ? "wireless" : "wired")), false);
                return InteractionResult.CONSUME;
            }
            String channels = modem.getOpenChannels().isEmpty()
                    ? "(none)"
                    : modem.getOpenChannels().toString();
            player.displayClientMessage(Component.literal(
                    "Modem " + modem.getLabel()
                            + " wireless=" + modem.isWireless()
                            + " range=" + modem.getRange()
                            + " network=" + (modem.getNetworkName().isEmpty() ? "automatic" : modem.getNetworkName())
                            + " open=" + channels
                            + " pending=" + modem.pendingCount()), false);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof ModemBlockEntity modem) {
                modem.closeAll();
            }
            level.removeBlockEntity(pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}
