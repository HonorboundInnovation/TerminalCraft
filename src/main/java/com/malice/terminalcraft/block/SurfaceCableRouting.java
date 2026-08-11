package com.malice.terminalcraft.block;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Shared directional-port model for one independently routed surface-cable lane. */
public final class SurfaceCableRouting {
    public static final int ALL_PORTS = (1 << Direction.values().length) - 1;

    private SurfaceCableRouting() {}

    public static int port(Direction direction) {
        if (direction == null) return 0;
        return 1 << direction.ordinal();
    }

    public static boolean hasPort(int mask, Direction direction) {
        return direction != null && (mask & port(direction)) != 0;
    }

    public static int planeMask(Direction face) {
        int mask = 0;
        for (Direction direction : SurfaceCableSupport.planeDirections(face)) mask |= port(direction);
        return mask;
    }

    public static int sanitize(Direction face, int mask) {
        return mask & planeMask(face) & ALL_PORTS;
    }

    public static int straight(Direction face, Direction travel) {
        Direction direction = inPlane(face, travel) ? travel : defaultDirection(face);
        return port(direction) | port(direction.getOpposite());
    }

    /** Builds a straight continuation or an in-block turn from reciprocal incoming ports. */
    public static int forPlacement(Direction face, Direction desired, int incomingMask) {
        Direction exit = inPlane(face, desired) ? desired : defaultDirection(face);
        int route = sanitize(face, incomingMask);
        if (route == 0) return straight(face, exit);
        route |= port(exit);
        if (Integer.bitCount(route) == 1) {
            Direction only = directions(route).get(0);
            route |= port(only.getOpposite());
        }
        return sanitize(face, route);
    }

    public static boolean inPlane(Direction face, Direction direction) {
        return face != null && direction != null && face.getAxis() != direction.getAxis();
    }

    public static Direction defaultDirection(Direction face) {
        return face != null && face.getAxis() == Direction.Axis.Y ? Direction.NORTH : Direction.UP;
    }

    /** Stable travel axis for a four-lane bank already configured on a face. */
    public static Direction bankDirection(Direction face, int routeMask, Direction fallback) {
        int route = sanitize(face, routeMask);
        if (route != 0) {
            for (Direction direction : SurfaceCableSupport.planeDirections(face)) {
                if (hasPort(route, direction)) return direction;
            }
        }
        return inPlane(face, fallback) ? fallback : defaultDirection(face);
    }

    /** Projects the player's view into the mounting plane; wall-normal views default vertically. */
    public static Direction preferredDirection(Direction face, @Nullable LivingEntity player) {
        if (player == null) return defaultDirection(face);
        Vec3 look = player.getLookAngle();
        Direction best = null;
        double bestDot = -Double.MAX_VALUE;
        for (Direction direction : SurfaceCableSupport.planeDirections(face)) {
            double dot = look.x * direction.getStepX()
                    + look.y * direction.getStepY()
                    + look.z * direction.getStepZ();
            if (dot > bestDot) {
                bestDot = dot;
                best = direction;
            }
        }
        if (bestDot >= 0.15D && best != null) return best;
        Direction horizontal = player.getDirection();
        return inPlane(face, horizontal) ? horizontal : defaultDirection(face);
    }

    public static List<Direction> directions(int mask) {
        List<Direction> result = new ArrayList<>();
        for (Direction direction : Direction.values()) if (hasPort(mask, direction)) result.add(direction);
        return List.copyOf(result);
    }

    public static String label(int mask) {
        List<String> names = directions(mask).stream().map(Direction::getName).sorted().toList();
        return names.isEmpty() ? "none" : String.join(",", names);
    }

    public static boolean isStraight(int mask) {
        List<Direction> ports = directions(mask);
        return ports.size() == 2 && ports.get(0).getOpposite() == ports.get(1);
    }

    public static boolean isTurn(int mask) {
        List<Direction> ports = directions(mask);
        return ports.size() == 2 && ports.get(0).getAxis() != ports.get(1).getAxis();
    }

    /** Returns the lane offset perpendicular to every travel axis used by this route. */
    public static Vec3 laneOffset(Direction face, int lane, int routeMask) {
        double offset = SurfaceCableSupport.laneDistance(lane);
        int sanitized = sanitize(face, routeMask);
        if (sanitized == 0) sanitized = straight(face, defaultDirection(face));

        boolean offsetX = false;
        boolean offsetY = false;
        boolean offsetZ = false;
        for (Direction direction : directions(sanitized)) {
            switch (remainingAxis(face.getAxis(), direction.getAxis())) {
                case X -> offsetX = true;
                case Y -> offsetY = true;
                case Z -> offsetZ = true;
            }
        }

        // The generic clauses above deliberately support turns, but straight runs need exactly one
        // perpendicular offset. Resolve that explicitly to keep the four tracks truly parallel.
        if (Integer.bitCount(axisMask(sanitized)) == 1) {
            Direction.Axis travelAxis = directions(sanitized).get(0).getAxis();
            Direction.Axis laneAxis = remainingAxis(face.getAxis(), travelAxis);
            return axisVector(laneAxis, offset);
        }
        return new Vec3(offsetX ? offset : 0.0D,
                offsetY ? offset : 0.0D,
                offsetZ ? offset : 0.0D);
    }

    private static int axisMask(int routeMask) {
        int result = 0;
        for (Direction direction : directions(routeMask)) result |= 1 << direction.getAxis().ordinal();
        return result;
    }

    public static Direction.Axis laneAxis(Direction face, Direction travel) {
        Direction direction = inPlane(face, travel) ? travel : defaultDirection(face);
        return remainingAxis(face.getAxis(), direction.getAxis());
    }

    private static Direction.Axis remainingAxis(Direction.Axis first, Direction.Axis second) {
        for (Direction.Axis axis : Direction.Axis.values()) if (axis != first && axis != second) return axis;
        throw new IllegalArgumentException("surface route axes must be perpendicular");
    }

    private static Vec3 axisVector(Direction.Axis axis, double value) {
        return switch (axis) {
            case X -> new Vec3(value, 0.0D, 0.0D);
            case Y -> new Vec3(0.0D, value, 0.0D);
            case Z -> new Vec3(0.0D, 0.0D, value);
        };
    }
}
