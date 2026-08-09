package com.malice.terminalcraft.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.HashSet;
import java.util.Set;

/**
 * Pure geometry and admission rules for one-face red-alloy wire topology.
 *
 * <p>Each block position owns at most one mounted wire. Connections are either directly adjacent
 * on the same mounting face, or an unobstructed convex bend around one shared sturdy support
 * block. Concave/internal transitions deliberately belong to the later multipart slice.</p>
 */
public final class RedAlloyWireTopology {
    private RedAlloyWireTopology() {}

    /** World-dependent facts required by the otherwise pure topology resolver. */
    public interface View {
        boolean hasWire(Node node);

        boolean hasValidSupport(Node node);

        /** True only when the swept convex-bend volume is air or an unblocked water block. */
        boolean isOpenBend(BlockPos pos);
    }

    /** A mounted wire is identified by its immutable position and its outward mounting face. */
    public record Node(BlockPos pos, Direction face) {
        public Node {
            if (pos == null || face == null) throw new IllegalArgumentException("wire node is required");
            pos = pos.immutable();
        }
    }

    /**
     * Resolves direct and convex-corner edges for one mounted wire.
     *
     * <p>Every accepted edge is reciprocal because both direct and external candidates are
     * derived from the same plane and shared-support geometry. Invalid support immediately
     * removes an edge even before the normal scheduled support-removal tick runs.</p>
     */
    public static Set<Node> connectedNodes(View view, Node node) {
        if (view == null || node == null || !view.hasWire(node) || !view.hasValidSupport(node)) {
            return Set.of();
        }

        Set<Node> result = new HashSet<>();
        for (Direction direction : planeDirections(node.face())) {
            Node direct = new Node(node.pos().relative(direction), node.face());
            if (view.hasWire(direct) && view.hasValidSupport(direct)) {
                result.add(direct);
                continue;
            }

            BlockPos bend = node.pos().relative(direction);
            if (!view.isOpenBend(bend)) continue;
            Node around = new Node(bend.relative(node.face().getOpposite()), direction);
            if (!view.hasWire(around) || !view.hasValidSupport(around) || !sharesSupport(node, around)) {
                continue;
            }
            result.add(around);
        }
        return Set.copyOf(result);
    }

    /** Returns the visible in-plane arm direction from {@code from} toward a connected neighbor. */
    public static Direction armDirection(Node from, Node to) {
        if (from == null || to == null) return null;
        for (Direction direction : planeDirections(from.face())) {
            if (from.pos().relative(direction).equals(to.pos())) return direction;
            if (from.pos().relative(direction).relative(from.face().getOpposite()).equals(to.pos())) {
                return direction;
            }
        }
        return null;
    }

    /** The four directions in a wire's mounting plane. */
    public static Direction[] planeDirections(Direction face) {
        if (face == null) return new Direction[0];
        return switch (face.getAxis()) {
            case X -> new Direction[]{Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH};
            case Y -> new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
            case Z -> new Direction[]{Direction.DOWN, Direction.UP, Direction.WEST, Direction.EAST};
        };
    }

    private static boolean sharesSupport(Node first, Node second) {
        return first.pos().relative(first.face().getOpposite())
                .equals(second.pos().relative(second.face().getOpposite()));
    }
}
