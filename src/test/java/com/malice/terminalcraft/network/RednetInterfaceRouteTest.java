package com.malice.terminalcraft.network;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Focused immutable-model tests for logical RedNet interfaces and routes. */
public final class RednetInterfaceRouteTest {
    private RednetInterfaceRouteTest() {}

    public static void main(String[] args) {
        RednetAddress sourceAddress = new RednetAddress(UUID.randomUUID(), "source");
        RednetAddress destinationAddress = new RednetAddress(UUID.randomUUID(), "destination");
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos(1, 64, 2);
        List<Integer> ports = new ArrayList<>(List.of(80, 7, 80));
        RednetInterface source = new RednetInterface(sourceAddress, RednetInterface.Transport.WIRELESS,
                "minecraft:overworld", mutable, 32, ports);
        mutable.set(9, 9, 9);
        ports.clear();
        check(source.position().equals(new BlockPos(1, 64, 2)), "interface position must be immutable");
        check(source.openPorts().equals(List.of(7, 80)), "ports must be sorted and deduplicated");

        RednetInterface destination = new RednetInterface(destinationAddress,
                RednetInterface.Transport.WIRELESS, "minecraft:overworld", BlockPos.ZERO, 16, List.of(80));
        RednetRoute route = new RednetRoute(source, destination, 0, List.of());
        check(RednetGatewayPolicy.POLICY_ID.equals("dimension-local-no-gateway-v1")
                        && !RednetGatewayPolicy.gatewayAvailable()
                        && RednetGatewayPolicy.permits(source, destination),
                "current gateway policy must explicitly permit only dimension-local routes");
        check(route.source().equals(source) && route.destination().equals(destination),
                "route must preserve typed endpoints");

        expectFailure(() -> new RednetInterface(sourceAddress, RednetInterface.Transport.WIRED,
                "minecraft:overworld", BlockPos.ZERO, 1, List.of()));
        expectFailure(() -> new RednetInterface(sourceAddress, RednetInterface.Transport.WIRELESS,
                "minecraft:overworld", BlockPos.ZERO, 1, List.of(-1)));
        RednetInterface netherDestination = new RednetInterface(destinationAddress,
                RednetInterface.Transport.WIRELESS, "minecraft:the_nether", BlockPos.ZERO, 16, List.of());
        check(!RednetGatewayPolicy.permits(source, netherDestination),
                "matching wireless interfaces in different dimensions must not imply a gateway");
        expectFailure(() -> new RednetRoute(source, netherDestination, 0, List.of()));
        expectFailure(() -> new RednetRoute(source, destination, 1, List.of()));

        System.out.println("RednetInterfaceRouteTest: all tests passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void expectFailure(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected failure");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
