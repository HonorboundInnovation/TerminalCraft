package com.malice.terminalcraft.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Headless coverage for logical-server, dimension, and modem runtime isolation keys. */
public final class RednetRuntimeScopeTest {
    private RednetRuntimeScopeTest() {}

    public static void main(String[] args) {
        Object firstServer = new Object();
        Object secondServer = new Object();
        RednetRuntimeScope overworld = new RednetRuntimeScope(firstServer, "minecraft:overworld");
        RednetRuntimeScope sameScope = new RednetRuntimeScope(firstServer, "minecraft:overworld");
        RednetRuntimeScope nether = new RednetRuntimeScope(firstServer, "minecraft:the_nether");
        RednetRuntimeScope otherServer = new RednetRuntimeScope(secondServer, "minecraft:overworld");

        check(overworld.equals(sameScope), "same server identity and dimension must share a scope");
        check(overworld.hashCode() == sameScope.hashCode(), "equal scopes must share a hash code");
        check(!overworld.equals(nether), "dimensions on one server must remain isolated");
        check(!overworld.equals(otherServer), "identically named dimensions on separate servers must remain isolated");
        check(overworld.belongsTo(firstServer) && !overworld.belongsTo(secondServer),
                "server cleanup matching must use identity");

        UUID modem = UUID.randomUUID();
        RednetRuntimeScope.Endpoint firstEndpoint = overworld.endpoint(modem);
        RednetRuntimeScope.Endpoint sameEndpoint = sameScope.endpoint(modem);
        RednetRuntimeScope.Endpoint netherEndpoint = nether.endpoint(modem);
        check(firstEndpoint.equals(sameEndpoint), "same modem in one scope must resolve one queue");
        check(!firstEndpoint.equals(netherEndpoint), "same modem UUID in another dimension needs a separate queue");

        Map<RednetRuntimeScope.Endpoint, String> queues = new HashMap<>();
        queues.put(firstEndpoint, "overworld");
        queues.put(netherEndpoint, "nether");
        queues.put(otherServer.endpoint(modem), "other-server");
        check(queues.size() == 3, "queue keys must isolate server and dimension scopes");

        System.out.println("RednetRuntimeScopeTest: all tests passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
