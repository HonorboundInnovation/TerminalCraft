package com.malice.terminalcraft.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.List;

/** Shared point-lattice, color, and face-plane rules for RedPower-style surface wiring. */
public final class SurfaceCableSupport {
    public static final int LANES_PER_FACE = 4;
    public static final int POINTS_PER_FACE = 16;
    public static final int COLOR_COUNT = 16;
    private static final double[] LANE_OFFSETS = {
            -4.5D / 16.0D, -1.5D / 16.0D, 1.5D / 16.0D, 4.5D / 16.0D
    };
    /** Point centers span the whole face; the outer two touch neighboring block boundaries. */
    private static final double[] GRID_CENTERS = {1.0D, 17.0D / 3.0D, 31.0D / 3.0D, 15.0D};

    private SurfaceCableSupport() {}

    public static int requireLane(int lane) {
        if (lane < 0 || lane >= LANES_PER_FACE) {
            throw new IllegalArgumentException("surface cable lane must be 0..3");
        }
        return lane;
    }

    public static int requirePoint(int point) {
        if (point < 0 || point >= POINTS_PER_FACE) {
            throw new IllegalArgumentException("surface cable point must be 0..15");
        }
        return point;
    }

    public static int pointU(int point) { return requirePoint(point) & 3; }
    public static int pointV(int point) { return requirePoint(point) >> 2; }
    public static int point(int u, int v) {
        if (u < 0 || u > 3 || v < 0 || v > 3) return -1;
        return v * 4 + u;
    }

    /** Selects one exact cell in the four-by-four lattice on the mounted face. */
    public static int pointForHit(Direction face, BlockPos cablePos, Vec3 hit) {
        double x = hit.x - cablePos.getX();
        double y = hit.y - cablePos.getY();
        double z = hit.z - cablePos.getZ();
        double first = switch (face.getAxis()) {
            case X -> z;
            case Y, Z -> x;
        };
        double second = switch (face.getAxis()) {
            case X, Z -> y;
            case Y -> z;
        };
        int u = Math.max(0, Math.min(3, (int) Math.floor(first * 4.0D)));
        int v = Math.max(0, Math.min(3, (int) Math.floor(second * 4.0D)));
        return point(u, v);
    }

    public static String pointLabel(int point) {
        return "R" + (pointV(point) + 1) + "C" + (pointU(point) + 1);
    }

    /**
     * Projects a point into existing compatibility state fields without adding a 0..15 property.
     * LANE stores the column; the two face-normal connection bits (unused by surface geometry) store
     * the row. This prevents a sixteen-fold explosion in Minecraft's precomputed block-state table.
     */
    public static BlockState withPoint(BlockState state, Direction face, int point) {
        int checked = requirePoint(point);
        int row = pointV(checked);
        IntegerProperty lane = state.hasProperty(NetworkCableBlock.LANE)
                ? NetworkCableBlock.LANE : RedAlloyWireBlock.LANE;
        return state.setValue(lane, pointU(checked))
                .setValue(CableShapeSupport.property(face), (row & 1) != 0)
                .setValue(CableShapeSupport.property(face.getOpposite()), (row & 2) != 0);
    }

    public static int pointFromState(BlockState state, Direction face) {
        IntegerProperty lane = state.hasProperty(NetworkCableBlock.LANE)
                ? NetworkCableBlock.LANE : RedAlloyWireBlock.LANE;
        int row = (state.getValue(CableShapeSupport.property(face)) ? 1 : 0)
                | (state.getValue(CableShapeSupport.property(face.getOpposite())) ? 2 : 0);
        return point(state.getValue(lane), row);
    }

    public static int pointPortMask(Direction face, int point) {
        int u = pointU(point);
        int v = pointV(point);
        int mask = 0;
        if (u == 0) mask |= SurfaceCableRouting.port(negativeU(face));
        if (u == 3) mask |= SurfaceCableRouting.port(positiveU(face));
        if (v == 0) mask |= SurfaceCableRouting.port(negativeV(face));
        if (v == 3) mask |= SurfaceCableRouting.port(positiveV(face));
        return mask;
    }

    /** Corresponding lattice point in the directly adjacent block across one face-plane edge. */
    public static int oppositeEdgePoint(Direction face, int point, Direction direction) {
        int u = pointU(point);
        int v = pointV(point);
        if (direction == negativeU(face) && u == 0) return point(3, v);
        if (direction == positiveU(face) && u == 3) return point(0, v);
        if (direction == negativeV(face) && v == 0) return point(u, 3);
        if (direction == positiveV(face) && v == 3) return point(u, 0);
        return -1;
    }

    public static boolean neighboringPoints(int first, int second) {
        int du = Math.abs(pointU(first) - pointU(second));
        int dv = Math.abs(pointV(first) - pointV(second));
        return (du != 0 || dv != 0) && du <= 1 && dv <= 1;
    }

    /** Two neighboring blocks share a lane when their points have the same cross-travel coordinate. */
    public static boolean pointsAlignAcross(Direction face, int first, int second, Direction travel) {
        requirePoint(first);
        requirePoint(second);
        if (travel == negativeU(face) || travel == positiveU(face)) {
            return pointV(first) == pointV(second);
        }
        if (travel == negativeV(face) || travel == positiveV(face)) {
            return pointU(first) == pointU(second);
        }
        return false;
    }

    /** True when two points mounted on different faces of one block meet at the shared edge. */
    public static boolean touchingFacePoints(Direction firstFace, int firstPoint,
                                             Direction secondFace, int secondPoint) {
        if (firstFace == secondFace || firstFace == secondFace.getOpposite()) return false;
        return Shapes.joinIsNotEmpty(pointRunShape(firstFace, firstPoint),
                pointRunShape(secondFace, secondPoint), BooleanOp.AND);
    }

    public static int boundedColor(int color) {
        return Math.max(0, Math.min(COLOR_COUNT - 1, color));
    }

    public static DyeColor dyeColor(int color) {
        return DyeColor.byId(boundedColor(color));
    }

    /** Minecraft dye IDs are also TerminalCraft's default network channels 0..15. */
    public static int defaultChannel(DyeColor color) {
        return color == null ? 0 : color.getId();
    }

    /** Compatibility selector using the default travel direction for the mounted face. */
    public static int laneForHit(Direction face, BlockPos cablePos, Vec3 hit) {
        return laneForHit(face, SurfaceCableRouting.defaultDirection(face), cablePos, hit);
    }

    /** Selects one of four bands perpendicular to the proposed local travel direction. */
    public static int laneForHit(Direction face, Direction travel, BlockPos cablePos, Vec3 hit) {
        double x = hit.x - cablePos.getX();
        double y = hit.y - cablePos.getY();
        double z = hit.z - cablePos.getZ();
        double across = switch (SurfaceCableRouting.laneAxis(face, travel)) {
            case X -> x;
            case Y -> y;
            case Z -> z;
        };
        return Math.max(0, Math.min(LANES_PER_FACE - 1, (int) Math.floor(across * LANES_PER_FACE)));
    }

    /** World-space translation for four side-by-side runs rather than a two-by-two quadrant grid. */
    public static Vec3 laneOffset(Direction face, int lane) {
        return SurfaceCableRouting.laneOffset(face, lane,
                SurfaceCableRouting.straight(face, SurfaceCableRouting.defaultDirection(face)));
    }

    static double laneDistance(int lane) {
        return LANE_OFFSETS[requireLane(lane)];
    }

    public static Vec3 laneOffset(Direction face, int lane, int routeMask) {
        return SurfaceCableRouting.laneOffset(face, lane, routeMask);
    }

    public static VoxelShape moveToLane(VoxelShape shape, Direction face, int lane) {
        Vec3 offset = laneOffset(face, lane);
        return shape.move(offset.x, offset.y, offset.z);
    }

    public static VoxelShape moveToLane(VoxelShape shape, Direction face, int lane, int routeMask) {
        Vec3 offset = laneOffset(face, lane, routeMask);
        return shape.move(offset.x, offset.y, offset.z);
    }

    /**
     * Broad selection volume for a cable mounted on one face of a block space. Floor and ceiling
     * cables select like horizontal slabs; wall cables select like vertical half blocks. Rendering
     * remains thin and is intentionally independent from this interaction shape.
     */
    public static VoxelShape faceHalfShape(Direction face) {
        return switch (face) {
            case UP -> Block.box(0, 0, 0, 16, 8, 16);
            case DOWN -> Block.box(0, 8, 0, 16, 16, 16);
            case NORTH -> Block.box(0, 0, 8, 16, 16, 16);
            case SOUTH -> Block.box(0, 0, 0, 16, 16, 8);
            case WEST -> Block.box(8, 0, 0, 16, 16, 16);
            case EAST -> Block.box(0, 0, 0, 8, 16, 16);
        };
    }

    /** Four-point lattice route used by the world-space placement preview. */
    public static VoxelShape laneMarkerShape(Direction face, int lane) {
        return laneMarkerShape(face, lane,
                SurfaceCableRouting.straight(face, SurfaceCableRouting.defaultDirection(face)));
    }

    /** Direction-aware row, column, diagonal turn, endpoint, or junction preview. */
    public static VoxelShape laneMarkerShape(Direction face, int lane, int routeMask) {
        return latticeShape(face, lane, routeMask, 0.02D, 0.62D);
    }

    /** Exact 4x4 point-lattice geometry used by ordinary Red Alloy and Network Cable runs. */
    public static VoxelShape routedRunShape(Direction face, int lane, int routeMask) {
        return latticeShape(face, lane, routeMask, 0.0D, 2.0D);
    }

    public static VoxelShape pointMarkerShape(Direction face, int point) {
        return pointShape(face, point, 0.02D, 0.62D);
    }

    public static VoxelShape pointRunShape(Direction face, int point) {
        return pointShape(face, point, 0.0D, 2.0D);
    }

    /** Continuous short segment between two adjacent points in one face lattice. */
    public static VoxelShape pointLinkShape(Direction face, int first, int second) {
        if (!neighboringPoints(first, second)) return Shapes.empty();
        return latticeLine(face, GRID_CENTERS[pointU(first)], GRID_CENTERS[pointV(first)],
                GRID_CENTERS[pointU(second)], GRID_CENTERS[pointV(second)], 0.0D, 2.0D);
    }

    /** Arm from a point to one block boundary, used when a lane continues into an adjacent block. */
    public static VoxelShape pointArmShape(Direction face, int point, Direction travel) {
        double first = GRID_CENTERS[pointU(point)];
        double second = GRID_CENTERS[pointV(point)];
        double endFirst = first;
        double endSecond = second;
        // The line core is two pixels wide, so centering its last sample one pixel inside the
        // block makes its outer edge meet (but not overrun) the neighboring block boundary.
        if (travel == negativeU(face)) endFirst = 1.0D;
        else if (travel == positiveU(face)) endFirst = 15.0D;
        else if (travel == negativeV(face)) endSecond = 1.0D;
        else if (travel == positiveV(face)) endSecond = 15.0D;
        else return Shapes.empty();
        return latticeLine(face, first, second, endFirst, endSecond, 0.0D, 2.0D);
    }

    private static VoxelShape pointShape(Direction face, int point,
                                         double shallowMin, double shallowMax) {
        return latticePoint(face, pointU(point), pointV(point), shallowMin, shallowMax);
    }

    private static VoxelShape latticeLine(Direction face, double firstStart, double secondStart,
                                          double firstEnd, double secondEnd,
                                          double shallowMin, double shallowMax) {
        double distance = Math.max(Math.abs(firstEnd - firstStart), Math.abs(secondEnd - secondStart));
        int steps = Math.max(1, (int) Math.ceil(distance));
        VoxelShape shape = Shapes.empty();
        for (int step = 0; step <= steps; step++) {
            double progress = step / (double) steps;
            double first = firstStart + (firstEnd - firstStart) * progress;
            double second = secondStart + (secondEnd - secondStart) * progress;
            shape = Shapes.or(shape, surfacePoint(face, first, second, 1.0D, shallowMin, shallowMax));
        }
        return shape.optimize();
    }

    /** Continuous lane-offset geometry retained for bundled trunks and old-save migration. */
    public static VoxelShape continuousRunShape(Direction face, int lane, int routeMask) {
        return continuousShape(face, lane, routeMask, routeMask, 0.0D, 2.0D);
    }

    /** Centered RedPower-style cable preview with no lane or lattice offset. */
    public static VoxelShape centeredMarkerShape(Direction face) {
        return centeredShape(face, 0, 0.02D, 0.62D);
    }

    /** One centered cable on a face, with arms only for live automatically discovered links. */
    public static VoxelShape centeredRunShape(Direction face, int connectionMask) {
        return centeredShape(face, connectionMask, 0.0D, 2.0D);
    }

    private static VoxelShape centeredShape(Direction face, int connectionMask,
                                            double shallowMin, double shallowMax) {
        VoxelShape shape = core(face, 8.0D, 8.0D, 8.0D, shallowMin, shallowMax);
        int connections = SurfaceCableRouting.sanitize(face, connectionMask);
        for (Direction direction : planeDirections(face)) {
            if (SurfaceCableRouting.hasPort(connections, direction)) {
                shape = Shapes.or(shape, arm(face, direction, 8.0D, 8.0D, 8.0D,
                        shallowMin, shallowMax));
            }
        }
        return shape.optimize();
    }

    /**
     * Sixteen-bit occupancy map for the confirmed four-by-four face lattice. Straight lanes contain
     * four points. A perpendicular two-port route is rasterized between its edge points, producing
     * a four-point corner-to-corner diagonal or the corresponding shorter inner turn.
     */
    public static int latticeMask(Direction face, int lane, int routeMask) {
        requireLane(lane);
        int ports = SurfaceCableRouting.sanitize(face, routeMask);
        if (ports == 0) ports = SurfaceCableRouting.straight(face, SurfaceCableRouting.defaultDirection(face));
        List<Direction> directions = SurfaceCableRouting.directions(ports);
        if (directions.size() == 1) {
            GridPoint point = edgePoint(face, directions.get(0), lane);
            return point == null ? 0 : bit(point.u(), point.v());
        }
        if (directions.size() == 2) {
            GridPoint first = edgePoint(face, directions.get(0), lane);
            GridPoint second = edgePoint(face, directions.get(1), lane);
            return first == null || second == null ? 0 : rasterLine(first, second);
        }
        GridPoint hub = new GridPoint(lane, lane);
        int mask = bit(hub.u(), hub.v());
        for (Direction direction : directions) {
            GridPoint edge = edgePoint(face, direction, lane);
            if (edge != null) mask |= rasterLine(edge, hub);
        }
        return mask;
    }

    private static VoxelShape latticeShape(Direction face, int lane, int routeMask,
                                            double shallowMin, double shallowMax) {
        int points = latticeMask(face, lane, routeMask);
        VoxelShape shape = Shapes.empty();
        for (int v = 0; v < 4; v++) {
            for (int u = 0; u < 4; u++) {
                if ((points & bit(u, v)) != 0) {
                    shape = Shapes.or(shape, latticePoint(face, u, v, shallowMin, shallowMax));
                }
            }
        }
        return shape.optimize();
    }

    private static int rasterLine(GridPoint start, GridPoint end) {
        int x0 = start.u();
        int y0 = start.v();
        int x1 = end.u();
        int y1 = end.v();
        int dx = Math.abs(x1 - x0);
        int sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0);
        int sy = y0 < y1 ? 1 : -1;
        int error = dx + dy;
        int mask = 0;
        while (true) {
            mask |= bit(x0, y0);
            if (x0 == x1 && y0 == y1) return mask;
            int twice = error * 2;
            if (twice >= dy) {
                error += dy;
                x0 += sx;
            }
            if (twice <= dx) {
                error += dx;
                y0 += sy;
            }
        }
    }

    private static GridPoint edgePoint(Direction face, Direction direction, int lane) {
        if (direction == negativeU(face)) return new GridPoint(0, lane);
        if (direction == positiveU(face)) return new GridPoint(3, lane);
        if (direction == negativeV(face)) return new GridPoint(lane, 0);
        if (direction == positiveV(face)) return new GridPoint(lane, 3);
        return null;
    }

    private static Direction negativeU(Direction face) {
        return face.getAxis() == Direction.Axis.X ? Direction.NORTH : Direction.WEST;
    }

    private static Direction positiveU(Direction face) {
        return negativeU(face).getOpposite();
    }

    private static Direction negativeV(Direction face) {
        return face.getAxis() == Direction.Axis.Y ? Direction.NORTH : Direction.DOWN;
    }

    private static Direction positiveV(Direction face) {
        return negativeV(face).getOpposite();
    }

    private static int bit(int u, int v) {
        return 1 << (v * 4 + u);
    }

    private static VoxelShape latticePoint(Direction face, int u, int v,
                                           double shallowMin, double shallowMax) {
        return surfacePoint(face, GRID_CENTERS[u], GRID_CENTERS[v], 1.0D, shallowMin, shallowMax);
    }

    private static VoxelShape surfacePoint(Direction face, double first, double second, double radius,
                                           double shallowMin, double shallowMax) {
        return switch (face) {
            case UP -> Block.box(first - radius, shallowMin, second - radius,
                    first + radius, shallowMax, second + radius);
            case DOWN -> Block.box(first - radius, 16 - shallowMax, second - radius,
                    first + radius, 16 - shallowMin, second + radius);
            case NORTH -> Block.box(first - radius, second - radius, 16 - shallowMax,
                    first + radius, second + radius, 16 - shallowMin);
            case SOUTH -> Block.box(first - radius, second - radius, shallowMin,
                    first + radius, second + radius, shallowMax);
            case WEST -> Block.box(16 - shallowMax, second - radius, first - radius,
                    16 - shallowMin, second + radius, first + radius);
            case EAST -> Block.box(shallowMin, second - radius, first - radius,
                    shallowMax, second + radius, first + radius);
        };
    }

    /** Centered face outline for either sixteen-channel bundled cable family. */
    public static VoxelShape bundledMarkerShape(Direction face) {
        return switch (face) {
            case UP -> Block.box(4, 0.02, 4, 12, 0.55, 12);
            case DOWN -> Block.box(4, 15.45, 4, 12, 15.98, 12);
            case NORTH -> Block.box(4, 4, 15.45, 12, 12, 15.98);
            case SOUTH -> Block.box(4, 4, 0.02, 12, 12, 0.55);
            case WEST -> Block.box(15.45, 4, 4, 15.98, 12, 12);
            case EAST -> Block.box(0.02, 4, 4, 0.55, 12, 12);
        };
    }

    public static Direction[] planeDirections(Direction face) {
        return switch (face.getAxis()) {
            case X -> new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH};
            case Y -> new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
            case Z -> new Direction[]{Direction.DOWN, Direction.UP, Direction.WEST, Direction.EAST};
        };
    }

    private static VoxelShape continuousShape(Direction face, int lane, int routeMask, int connectedMask,
                                              double shallowMin, double shallowMax) {
        requireLane(lane);
        int ports = SurfaceCableRouting.sanitize(face, routeMask);
        if (ports == 0) ports = SurfaceCableRouting.straight(face, SurfaceCableRouting.defaultDirection(face));
        int connected = SurfaceCableRouting.sanitize(face, connectedMask) & ports;
        double laneCenter = 8.0D + laneDistance(lane) * 16.0D;
        double x = 8.0D;
        double y = 8.0D;
        double z = 8.0D;
        if (face.getAxis() != Direction.Axis.X
                && (SurfaceCableRouting.hasPort(ports, Direction.NORTH)
                || SurfaceCableRouting.hasPort(ports, Direction.SOUTH)
                || SurfaceCableRouting.hasPort(ports, Direction.UP)
                || SurfaceCableRouting.hasPort(ports, Direction.DOWN))) x = laneCenter;
        if (face.getAxis() != Direction.Axis.Y
                && (SurfaceCableRouting.hasPort(ports, Direction.EAST)
                || SurfaceCableRouting.hasPort(ports, Direction.WEST)
                || (face.getAxis() == Direction.Axis.X && (SurfaceCableRouting.hasPort(ports, Direction.NORTH)
                || SurfaceCableRouting.hasPort(ports, Direction.SOUTH))))) y = laneCenter;
        if (face.getAxis() != Direction.Axis.Z
                && (SurfaceCableRouting.hasPort(ports, Direction.EAST)
                || SurfaceCableRouting.hasPort(ports, Direction.WEST)
                || (face.getAxis() == Direction.Axis.X && (SurfaceCableRouting.hasPort(ports, Direction.UP)
                || SurfaceCableRouting.hasPort(ports, Direction.DOWN))))) z = laneCenter;

        VoxelShape shape = core(face, x, y, z, shallowMin, shallowMax);
        for (Direction direction : planeDirections(face)) {
            if (SurfaceCableRouting.hasPort(connected, direction)) {
                shape = net.minecraft.world.phys.shapes.Shapes.or(shape,
                        arm(face, direction, x, y, z, shallowMin, shallowMax));
            }
        }
        return shape.optimize();
    }

    private record GridPoint(int u, int v) {}

    private static VoxelShape core(Direction face, double x, double y, double z,
                                   double shallowMin, double shallowMax) {
        return switch (face) {
            case UP -> Block.box(x - 1, shallowMin, z - 1, x + 1, shallowMax, z + 1);
            case DOWN -> Block.box(x - 1, 16 - shallowMax, z - 1, x + 1, 16 - shallowMin, z + 1);
            case NORTH -> Block.box(x - 1, y - 1, 16 - shallowMax, x + 1, y + 1, 16 - shallowMin);
            case SOUTH -> Block.box(x - 1, y - 1, shallowMin, x + 1, y + 1, shallowMax);
            case WEST -> Block.box(16 - shallowMax, y - 1, z - 1, 16 - shallowMin, y + 1, z + 1);
            case EAST -> Block.box(shallowMin, y - 1, z - 1, shallowMax, y + 1, z + 1);
        };
    }

    private static VoxelShape arm(Direction face, Direction direction, double x, double y, double z,
                                  double shallowMin, double shallowMax) {
        return switch (face) {
            case UP -> switch (direction) {
                case NORTH -> Block.box(x - 1, shallowMin, 0, x + 1, shallowMax, z);
                case SOUTH -> Block.box(x - 1, shallowMin, z, x + 1, shallowMax, 16);
                case WEST -> Block.box(0, shallowMin, z - 1, x, shallowMax, z + 1);
                case EAST -> Block.box(x, shallowMin, z - 1, 16, shallowMax, z + 1);
                default -> net.minecraft.world.phys.shapes.Shapes.empty();
            };
            case DOWN -> switch (direction) {
                case NORTH -> Block.box(x - 1, 16 - shallowMax, 0, x + 1, 16 - shallowMin, z);
                case SOUTH -> Block.box(x - 1, 16 - shallowMax, z, x + 1, 16 - shallowMin, 16);
                case WEST -> Block.box(0, 16 - shallowMax, z - 1, x, 16 - shallowMin, z + 1);
                case EAST -> Block.box(x, 16 - shallowMax, z - 1, 16, 16 - shallowMin, z + 1);
                default -> net.minecraft.world.phys.shapes.Shapes.empty();
            };
            case NORTH -> switch (direction) {
                case DOWN -> Block.box(x - 1, 0, 16 - shallowMax, x + 1, y, 16 - shallowMin);
                case UP -> Block.box(x - 1, y, 16 - shallowMax, x + 1, 16, 16 - shallowMin);
                case WEST -> Block.box(0, y - 1, 16 - shallowMax, x, y + 1, 16 - shallowMin);
                case EAST -> Block.box(x, y - 1, 16 - shallowMax, 16, y + 1, 16 - shallowMin);
                default -> net.minecraft.world.phys.shapes.Shapes.empty();
            };
            case SOUTH -> switch (direction) {
                case DOWN -> Block.box(x - 1, 0, shallowMin, x + 1, y, shallowMax);
                case UP -> Block.box(x - 1, y, shallowMin, x + 1, 16, shallowMax);
                case WEST -> Block.box(0, y - 1, shallowMin, x, y + 1, shallowMax);
                case EAST -> Block.box(x, y - 1, shallowMin, 16, y + 1, shallowMax);
                default -> net.minecraft.world.phys.shapes.Shapes.empty();
            };
            case WEST -> switch (direction) {
                case DOWN -> Block.box(16 - shallowMax, 0, z - 1, 16 - shallowMin, y, z + 1);
                case UP -> Block.box(16 - shallowMax, y, z - 1, 16 - shallowMin, 16, z + 1);
                case NORTH -> Block.box(16 - shallowMax, y - 1, 0, 16 - shallowMin, y + 1, z);
                case SOUTH -> Block.box(16 - shallowMax, y - 1, z, 16 - shallowMin, y + 1, 16);
                default -> net.minecraft.world.phys.shapes.Shapes.empty();
            };
            case EAST -> switch (direction) {
                case DOWN -> Block.box(shallowMin, 0, z - 1, shallowMax, y, z + 1);
                case UP -> Block.box(shallowMin, y, z - 1, shallowMax, 16, z + 1);
                case NORTH -> Block.box(shallowMin, y - 1, 0, shallowMax, y + 1, z);
                case SOUTH -> Block.box(shallowMin, y - 1, z, shallowMax, y + 1, 16);
                default -> net.minecraft.world.phys.shapes.Shapes.empty();
            };
        };
    }
}
