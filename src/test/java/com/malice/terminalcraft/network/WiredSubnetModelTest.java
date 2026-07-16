package com.malice.terminalcraft.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/** Headless coverage for deterministic physical-subnet identity and immutable router views. */
public final class WiredSubnetModelTest {
    private WiredSubnetModelTest() {}

    public static void main(String[] args) {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(12, 64, -9);
        WiredNetworkTopology.SubnetId id = new WiredNetworkTopology.SubnetId("minecraft:overworld", mutable);
        mutable.set(0, 0, 0);
        check(id.anchor().equals(new BlockPos(12, 64, -9)), "subnet identity must snapshot mutable positions");
        check(id.displayName().equals("minecraft:overworld@12,64,-9"),
                "subnet display name must be deterministic and dimension-qualified");
        check(id.equals(new WiredNetworkTopology.SubnetId(
                        "minecraft:overworld", new BlockPos(12, 64, -9))),
                "equal dimension and anchor must produce equal subnet identities");
        check(!id.equals(new WiredNetworkTopology.SubnetId(
                        "minecraft:the_nether", new BlockPos(12, 64, -9))),
                "identical anchors in separate dimensions must remain isolated");

        List<WiredNetworkTopology.RouterInterface> interfaces = new ArrayList<>();
        interfaces.add(new WiredNetworkTopology.RouterInterface(id, 2));
        WiredNetworkTopology.RouterView view = new WiredNetworkTopology.RouterView(1, interfaces, false);
        interfaces.clear();
        check(view.interfaces().size() == 1 && view.interfaces().get(0).attachmentCount() == 2,
                "router views must defensively snapshot interface lists");
        List<BlockPos> mutablePath = new ArrayList<>();
        BlockPos.MutableBlockPos mutableRouter = new BlockPos.MutableBlockPos(3, 64, 4);
        mutablePath.add(mutableRouter);
        WiredNetworkTopology.RouterPass pass = new WiredNetworkTopology.RouterPass(
                mutableRouter, Direction.WEST, mutableRouter, Direction.EAST, mutablePath);
        mutableRouter.set(99, 99, 99);
        mutablePath.clear();
        check(pass.ingressRouter().equals(new BlockPos(3, 64, 4))
                        && pass.traversedRouters().equals(List.of(new BlockPos(3, 64, 4))),
                "router passes must defensively snapshot mutable positions and path lists");
        WiredNetworkTopology.Route route = new WiredNetworkTopology.Route(
                true, 1, 4, false, List.of(pass));
        check(route.routerPasses().size() == 1, "reachable routes must retain immutable path transitions");
        expectFailure(() -> new WiredNetworkTopology.Route(true, 2, 4, false, List.of(pass)),
                "route hop count must match recorded transitions");
        expectFailure(() -> new WiredNetworkTopology.RouterPass(
                        BlockPos.ZERO, Direction.WEST, BlockPos.ZERO, Direction.EAST, List.of()),
                "router passes require a non-empty internal path");
        expectFailure(() -> new WiredNetworkTopology.RouterPass(
                        BlockPos.ZERO, Direction.WEST, BlockPos.ZERO, Direction.EAST,
                        List.of(BlockPos.ZERO, BlockPos.ZERO.east())),
                "router pass endpoints must match the internal path");
        expectFailure(() -> new WiredNetworkTopology.Route(false, -1, 4, false, List.of(pass)),
                "unreachable routes must not expose partial path evidence");

        expectFailure(() -> new WiredNetworkTopology.SubnetId("", BlockPos.ZERO),
                "blank dimensions must fail");
        expectFailure(() -> new WiredNetworkTopology.SubnetId("minecraft:overworld", null),
                "null anchors must fail");

        System.out.println("WiredSubnetModelTest: all tests passed");
    }

    private static void expectFailure(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
