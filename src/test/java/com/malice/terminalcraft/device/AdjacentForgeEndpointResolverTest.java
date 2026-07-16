package com.malice.terminalcraft.device;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.UUID;

/** Headless characterization tests for deterministic adjacent Forge endpoint authority. */
public final class AdjacentForgeEndpointResolverTest {
    private AdjacentForgeEndpointResolverTest() {}

    public static void main(String[] args) {
        identitiesMatchEstablishedAddressFormat();
        allSixSidesAreDistinctAndResolvable();
        nonAdjacentIdsAreRejected();
        dimensionsSeparateEndpointIdentity();
        System.out.println("Adjacent Forge endpoint resolver tests: OK");
    }

    private static void identitiesMatchEstablishedAddressFormat() {
        BlockPos host = new BlockPos(4, 64, 2);
        AdjacentForgeEndpointResolver.Candidate east = AdjacentForgeEndpointResolver.adjacent(
                "minecraft:overworld", host, Direction.EAST);
        assertEquals(new BlockPos(5, 64, 2), east.target(), "east target");
        assertEquals(Direction.WEST, east.accessSide(), "capability side faces host");
        assertEquals("minecraft:overworld:5,64,2", east.positionAddress(), "physical address");
        assertEquals("minecraft:overworld:5,64,2@west", east.address(), "legacy address format");
        assertEquals(UUID.fromString("ac2f5d21-83bc-3b12-9f43-a50dd671dd48"), east.id(),
                "stable deterministic UUID");
    }

    private static void allSixSidesAreDistinctAndResolvable() {
        BlockPos host = new BlockPos(-8, 70, 11);
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        for (Direction direction : Direction.values()) {
            AdjacentForgeEndpointResolver.Candidate candidate = AdjacentForgeEndpointResolver.adjacent(
                    "minecraft:the_nether", host, direction);
            assertTrue(ids.add(candidate.id()), "direction IDs are unique");
            assertEquals(candidate, AdjacentForgeEndpointResolver.candidate(
                    "minecraft:the_nether", host, candidate.id()).orElseThrow(),
                    "candidate resolves from adjacent UUID");
        }
        assertEquals(6, ids.size(), "six adjacent identities");
    }

    private static void nonAdjacentIdsAreRejected() {
        assertTrue(AdjacentForgeEndpointResolver.candidate("minecraft:overworld", BlockPos.ZERO,
                UUID.fromString("00000000-0000-0000-0000-000000000499")).isEmpty(),
                "arbitrary UUID is outside adjacency authority");
    }

    private static void dimensionsSeparateEndpointIdentity() {
        BlockPos host = new BlockPos(1, 2, 3);
        UUID overworld = AdjacentForgeEndpointResolver.adjacent(
                "minecraft:overworld", host, Direction.UP).id();
        UUID end = AdjacentForgeEndpointResolver.adjacent(
                "minecraft:the_end", host, Direction.UP).id();
        assertTrue(!overworld.equals(end), "dimension participates in endpoint identity");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
