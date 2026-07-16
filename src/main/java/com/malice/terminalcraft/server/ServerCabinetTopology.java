package com.malice.terminalcraft.server;

import com.malice.terminalcraft.block.ServerRackBlock;
import com.malice.terminalcraft.blockentity.ServerRackBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves one aligned, vertically contiguous server cabinet.
 *
 * <p>A cabinet contains at most three rack sections and six module bays. All sections share the
 * same passive backplane, so a router blade in any section can connect every section to RedNet.</p>
 */
public final class ServerCabinetTopology {
    private ServerCabinetTopology() {}

    public record Cabinet(List<BlockPos> racks, int serverModules, int routerModules) {
        public Cabinet {
            racks = List.copyOf(racks);
        }

        public int moduleCount() {
            return serverModules + routerModules;
        }

        public int bayCount() {
            return racks.size() * ServerRackBlockEntity.BAY_COUNT;
        }

        public boolean hasServer() {
            return serverModules > 0;
        }

        public boolean hasRouter() {
            return routerModules > 0;
        }
    }

    public static Cabinet resolve(LevelReader level, BlockPos member) {
        if (level == null || member == null) return new Cabinet(List.of(), 0, 0);
        BlockState origin = level.getBlockState(member);
        if (!(origin.getBlock() instanceof ServerRackBlock)) return new Cabinet(List.of(), 0, 0);

        Direction facing = origin.getValue(ServerRackBlock.FACING);
        BlockPos bottom = member.immutable();
        for (int i = 1; i < ServerRackBlock.MAX_CONNECTED_HEIGHT; i++) {
            BlockPos below = bottom.below();
            if (!isAlignedRack(level, below, facing)) break;
            bottom = below;
        }

        List<BlockPos> racks = new ArrayList<>(ServerRackBlock.MAX_CONNECTED_HEIGHT);
        int servers = 0;
        int routers = 0;
        for (int i = 0; i < ServerRackBlock.MAX_CONNECTED_HEIGHT; i++) {
            BlockPos pos = bottom.above(i);
            if (!isAlignedRack(level, pos, facing)) break;
            racks.add(pos.immutable());
            if (level.getBlockEntity(pos) instanceof ServerRackBlockEntity rack) {
                for (int bay = 0; bay < ServerRackBlockEntity.BAY_COUNT; bay++) {
                    RackModuleType type = rack.moduleType(bay);
                    if (type == RackModuleType.SERVER) servers++;
                    if (type == RackModuleType.ROUTER) routers++;
                }
            }
        }
        return new Cabinet(racks, servers, routers);
    }

    private static boolean isAlignedRack(LevelReader level, BlockPos pos, Direction facing) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof ServerRackBlock
                && state.getValue(ServerRackBlock.FACING) == facing;
    }
}
