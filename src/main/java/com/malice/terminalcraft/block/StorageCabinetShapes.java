package com.malice.terminalcraft.block;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.EnumMap;
import java.util.Map;

/** Cached horizontal collision/selection geometry for the visible storage cabinets. */
final class StorageCabinetShapes {
    private static final Map<Direction, VoxelShape> NAS = rotations(Shapes.or(
                Block.box(0, 0, 1, 16, 16, 16),
                Block.box(0, 0, 0, 1, 16, 1),
                Block.box(15, 0, 0, 16, 16, 1),
                Block.box(1, 1, 0.25, 7.5, 4, 1),
                Block.box(8, 1, 0.25, 14.5, 4, 1),
                Block.box(1, 4.5, 0.25, 7.5, 7.5, 1),
                Block.box(8, 4.5, 0.25, 14.5, 7.5, 1),
                Block.box(1, 8, 0.25, 7.5, 11, 1),
                Block.box(8, 8, 0.25, 14.5, 11, 1),
                Block.box(1, 11.5, 0.25, 7.5, 14.5, 1),
                Block.box(8, 11.5, 0.25, 14.5, 14.5, 1)));
    private static final Map<Direction, VoxelShape> BRIDGE = rotations(Shapes.or(
                Block.box(0, 0, 0, 16, 1, 16),
                Block.box(1, 1, 1, 15, 15, 15),
                Block.box(2, 2, 0.25, 14, 14, 1),
                Block.box(6, 15, 6, 10, 16, 10)));

    private StorageCabinetShapes() {}

    private static final Map<Direction, VoxelShape> DISK_DRIVE = rotations(Shapes.or(
            Block.box(0, 0, 1, 16, 16, 16),
            Block.box(0, 0, 0, 1, 16, 1),
            Block.box(15, 0, 0, 16, 16, 1),
            Block.box(1, 1, 0.25, 15, 15, 1)));

    static VoxelShape diskDrive(Direction facing) { return DISK_DRIVE.get(facing); }

    static VoxelShape nas(Direction facing) { return NAS.get(facing); }
    static VoxelShape bridge(Direction facing) { return BRIDGE.get(facing); }

    private static Map<Direction, VoxelShape> rotations(VoxelShape north) {
        var result = new EnumMap<Direction, VoxelShape>(Direction.class);
        VoxelShape current = north;
        for (Direction facing : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            result.put(facing, current);
            VoxelShape next = Shapes.empty();
            for (var box : current.toAabbs()) {
                next = Shapes.or(next, Shapes.box(1 - box.maxZ, box.minY, box.minX,
                        1 - box.minZ, box.maxY, box.maxX));
            }
            current = next;
        }
        return result;
    }
}
