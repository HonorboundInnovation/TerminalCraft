package com.malice.terminalcraft.block;

import com.malice.terminalcraft.blockentity.ProgrammableLogicControllerBlockEntity;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BaseEntityBlock;
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

/** Placeable PLC cabinet. Right-click opens the same terminal/editor interface as a computer. */
public class ProgrammableLogicControllerBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    public ProgrammableLogicControllerBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_GRAY)
                .strength(3.0f, 6.0f)
                .requiresCorrectToolForDrops()
                .isRedstoneConductor((state, level, pos) -> false));
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable @Override public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Nullable @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ProgrammableLogicControllerBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof ServerPlayer player
                && level.getBlockEntity(pos) instanceof ProgrammableLogicControllerBlockEntity plc) {
            plc.setOwner(player.getUUID());
        }
    }

    @Nullable @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return createTickerHelper(type, ModRegistries.PROGRAMMABLE_LOGIC_CONTROLLER_BLOCK_ENTITY.get(),
                ProgrammableLogicControllerBlockEntity::serverTick);
    }

    @Override @SuppressWarnings("deprecation") public InteractionResult use(
            BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof ProgrammableLogicControllerBlockEntity plc
                && player instanceof ServerPlayer serverPlayer) {
            NetworkHooks.openScreen(serverPlayer, plc, buffer -> {
                buffer.writeBlockPos(pos);
            });
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    @Override @SuppressWarnings("deprecation") public boolean isSignalSource(BlockState state) { return true; }

    @Override @SuppressWarnings("deprecation") public int getSignal(
            BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        if (level.getBlockEntity(pos) instanceof ProgrammableLogicControllerBlockEntity plc) {
            return plc.getRedstoneOutput(side.getName());
        }
        return 0;
    }

    @Override @SuppressWarnings("deprecation") public int getDirectSignal(
            BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        return getSignal(state, level, pos, side);
    }

    @Override @SuppressWarnings("deprecation") public void onRemove(
            BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        if (!state.is(replacement.getBlock()) && level.getBlockEntity(pos) instanceof ProgrammableLogicControllerBlockEntity plc) {
            plc.stop();
            level.removeBlockEntity(pos);
            level.updateNeighborsAt(pos, this);
            for (Direction direction : Direction.values()) level.updateNeighborsAt(pos.relative(direction), this);
        }
        super.onRemove(state, level, pos, replacement, moving);
    }
}
