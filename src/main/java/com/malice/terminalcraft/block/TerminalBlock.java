package com.malice.terminalcraft.block;

import com.malice.terminalcraft.blockentity.TerminalBlockEntity;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

/**
 * Placeable terminal computer. Right-click opens the bash shell GUI.
 * Emits and reads redstone like a ComputerCraft computer.
 */
public class TerminalBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    public TerminalBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_BLACK)
                .strength(2.5f, 6.0f)
                .requiresCorrectToolForDrops()
                .noOcclusion()
                .isRedstoneConductor((state, level, pos) -> false));
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TerminalBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(type, ModRegistries.TERMINAL_BLOCK_ENTITY.get(), TerminalBlockEntity::serverTick);
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                  InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof TerminalBlockEntity terminal && player instanceof ServerPlayer serverPlayer) {
            NetworkHooks.openScreen(serverPlayer, terminal, buf -> {
                buf.writeByte(com.malice.terminalcraft.menu.TerminalMenu.TYPE_BLOCK);
                buf.writeBlockPos(pos);
            });
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof TerminalBlockEntity terminal) {
            // Vanilla passes the source block face pointing toward the receiver. Terminal output
            // directions use that same world-space convention, so this must not be inverted.
            return terminal.getDirectSignal(side);
        }
        return 0;
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        return getSignal(state, level, pos, side);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos, boolean isMoving) {
        super.neighborChanged(state, level, pos, block, fromPos, isMoving);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            BlockEntity removed = level.getBlockEntity(pos);
            if (removed instanceof TerminalBlockEntity terminal) {
                terminal.onTerminalRemoved();
            }
            level.removeBlockEntity(pos);
            level.updateNeighborsAt(pos, this);
            for (Direction d : Direction.values()) {
                level.updateNeighborsAt(pos.relative(d), this);
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    public static Block getRegistered() {
        return ModRegistries.TERMINAL_BLOCK.get();
    }
}
