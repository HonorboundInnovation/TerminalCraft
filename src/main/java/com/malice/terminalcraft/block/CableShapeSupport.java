package com.malice.terminalcraft.block;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Shared six-way connection state and compact cable collision geometry. */
final class CableShapeSupport {
    static final BooleanProperty DOWN = BooleanProperty.create("down");
    static final BooleanProperty UP = BooleanProperty.create("up");
    static final BooleanProperty NORTH = BooleanProperty.create("north");
    static final BooleanProperty SOUTH = BooleanProperty.create("south");
    static final BooleanProperty WEST = BooleanProperty.create("west");
    static final BooleanProperty EAST = BooleanProperty.create("east");

    private CableShapeSupport() {}

    static BooleanProperty property(Direction direction) {
        return switch (direction) {
            case DOWN -> DOWN;
            case UP -> UP;
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            case EAST -> EAST;
        };
    }

    static BlockState disconnected(BlockState state) {
        for (Direction direction : Direction.values()) {
            state = state.setValue(property(direction), false);
        }
        return state;
    }

    static VoxelShape shape(BlockState state, double min, double max) {
        VoxelShape shape = Block.box(min, min, min, max, max, max);
        if (state.getValue(DOWN)) shape = Shapes.or(shape, Block.box(min, 0, min, max, min, max));
        if (state.getValue(UP)) shape = Shapes.or(shape, Block.box(min, max, min, max, 16, max));
        if (state.getValue(NORTH)) shape = Shapes.or(shape, Block.box(min, min, 0, max, max, min));
        if (state.getValue(SOUTH)) shape = Shapes.or(shape, Block.box(min, min, max, max, max, 16));
        if (state.getValue(WEST)) shape = Shapes.or(shape, Block.box(0, min, min, min, max, max));
        if (state.getValue(EAST)) shape = Shapes.or(shape, Block.box(max, min, min, 16, max, max));
        return shape.optimize();
    }
}
