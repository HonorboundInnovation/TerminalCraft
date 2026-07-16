package com.malice.terminalcraft.block;

import com.malice.terminalcraft.blockentity.DiskDriveBlockEntity;
import com.malice.terminalcraft.item.FloppyDiskItem;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
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
import org.jetbrains.annotations.Nullable;

/**
 * Single-slot drive for {@link FloppyDiskItem}. Right-click with a floppy to insert;
 * right-click empty-handed to eject. Adjacent terminals can {@code mount} the media.
 */
public class DiskDriveBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    public DiskDriveBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(2.0f, 4.0f)
                .requiresCorrectToolForDrops());
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
        return new DiskDriveBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return createTickerHelper(type, ModRegistries.DISK_DRIVE_BLOCK_ENTITY.get(), DiskDriveBlockEntity::serverTick);
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                  InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof DiskDriveBlockEntity drive)) {
            return InteractionResult.PASS;
        }

        ItemStack held = player.getItemInHand(hand);
        if (held.is(ModRegistries.FLOPPY_DISK.get())) {
            if (drive.hasDisk()) {
                player.displayClientMessage(Component.literal("Disk drive already has a floppy"), true);
                return InteractionResult.CONSUME;
            }
            ItemStack insert = held.split(1);
            FloppyDiskItem.ensureInitialized(insert);
            drive.setDisk(insert);
            player.displayClientMessage(
                    Component.literal("Inserted floppy: " + FloppyDiskItem.getDiskLabel(insert)), true);
            return InteractionResult.CONSUME;
        }

        if (drive.hasDisk()) {
            ItemStack ejected = drive.ejectDisk();
            if (!ejected.isEmpty()) {
                if (!player.addItem(ejected)) {
                    player.drop(ejected, false);
                }
                player.displayClientMessage(
                        Component.literal("Ejected floppy: " + FloppyDiskItem.getDiskLabel(ejected)), true);
            }
            return InteractionResult.CONSUME;
        }

        player.displayClientMessage(Component.literal("Disk drive is empty"), true);
        return InteractionResult.CONSUME;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof DiskDriveBlockEntity drive) {
                drive.dropContents();
            }
            level.removeBlockEntity(pos);
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }
}
