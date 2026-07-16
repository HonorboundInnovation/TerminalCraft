package com.malice.terminalcraft.block;

import com.malice.terminalcraft.blockentity.ServerRackBlockEntity;
import com.malice.terminalcraft.item.RackModuleItem;
import com.malice.terminalcraft.menu.TerminalMenu;
import com.malice.terminalcraft.registry.ModRegistries;
import com.malice.terminalcraft.server.RackModuleType;
import com.malice.terminalcraft.server.ServerCabinetTopology;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

/**
 * Two-bay server cabinet section. Up to three aligned sections connect vertically, yielding a
 * six-module cabinet. Compute and routing behavior comes from installed slab-height blades.
 */
public class ServerRackBlock extends BaseEntityBlock implements WiredNetworkNode {
    public static final int MAX_CONNECTED_HEIGHT = 3;
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    public static final EnumProperty<RackModuleType> LOWER_MODULE =
            EnumProperty.create("lower_module", RackModuleType.class);
    public static final EnumProperty<RackModuleType> UPPER_MODULE =
            EnumProperty.create("upper_module", RackModuleType.class);

    public ServerRackBlock() {
        super(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).strength(3.5f, 8.0f)
                .requiresCorrectToolForDrops());
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(LOWER_MODULE, RackModuleType.EMPTY)
                .setValue(UPPER_MODULE, RackModuleType.EMPTY));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LOWER_MODULE, UPPER_MODULE);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        if (connectedHeight(context.getLevel(), pos) >= MAX_CONNECTED_HEIGHT) return null;
        Direction facing = neighboringFacing(context.getLevel(), pos);
        if (facing == null) facing = context.getHorizontalDirection().getOpposite();
        return defaultBlockState().setValue(FACING, facing);
    }

    @Nullable
    private static Direction neighboringFacing(LevelReader level, BlockPos pos) {
        for (Direction direction : new Direction[]{Direction.DOWN, Direction.UP}) {
            BlockState adjacent = level.getBlockState(pos.relative(direction));
            if (adjacent.getBlock() instanceof ServerRackBlock) return adjacent.getValue(FACING);
        }
        return null;
    }

    /** Number of contiguous rack blocks above and below a position, excluding that position. */
    public static int connectedHeight(LevelReader level, BlockPos pos) {
        int count = 0;
        for (Direction direction : new Direction[]{Direction.DOWN, Direction.UP}) {
            BlockPos cursor = pos.relative(direction);
            while (count < MAX_CONNECTED_HEIGHT && level.getBlockState(cursor).getBlock() instanceof ServerRackBlock) {
                count++;
                cursor = cursor.relative(direction);
            }
        }
        return count;
    }

    @Override
    public boolean forwardsWiredTraffic(LevelReader level, BlockPos pos, BlockState state) {
        return ServerCabinetTopology.resolve(level, pos).hasRouter();
    }

    @Override
    public boolean routesWiredTraffic(LevelReader level, BlockPos pos, BlockState state) {
        return forwardsWiredTraffic(level, pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ServerRackBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                  BlockEntityType<T> type) {
        if (level.isClientSide) return null;
        return createTickerHelper(type, ModRegistries.SERVER_RACK_BLOCK_ENTITY.get(), ServerRackBlockEntity::serverTick);
    }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof ServerRackBlockEntity rack)) return InteractionResult.PASS;
        ItemStack held = player.getItemInHand(hand);
        int bay = hit.getLocation().y - pos.getY() < 0.5 ? 0 : 1;

        if (held.getItem() instanceof RackModuleItem) {
            if (level.isClientSide) return InteractionResult.SUCCESS;
            if (!rack.installModule(bay, held, player.getAbilities().instabuild)) {
                player.displayClientMessage(Component.literal("Rack bay " + (bay + 1) + " is occupied"), true);
            }
            return InteractionResult.CONSUME;
        }

        if (player.isShiftKeyDown() && held.isEmpty()) {
            if (level.isClientSide) return InteractionResult.SUCCESS;
            ItemStack removed = rack.removeModule(bay);
            if (removed.isEmpty()) {
                player.displayClientMessage(Component.literal("Rack bay " + (bay + 1) + " is empty"), true);
            } else if (!player.addItem(removed)) {
                player.drop(removed, false);
            }
            return InteractionResult.CONSUME;
        }

        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer serverPlayer && rack.hasServerModule()) {
            NetworkHooks.openScreen(serverPlayer, rack, buffer -> {
                buffer.writeByte(TerminalMenu.TYPE_BLOCK);
                buffer.writeBlockPos(pos);
            });
        } else {
            player.displayClientMessage(Component.literal(rack.cabinetStatus()), false);
        }
        return InteractionResult.CONSUME;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moving) {
        if (state.getBlock() != newState.getBlock()
                && level.getBlockEntity(pos) instanceof ServerRackBlockEntity rack) {
            for (ItemStack module : rack.removeAllModules()) {
                Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, module);
            }
        }
        super.onRemove(state, level, pos, newState, moving);
    }
}
