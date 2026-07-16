package com.malice.terminalcraft.device;

import java.util.List;
import java.util.Set;

/** Headless contract tests for bounded aggregate storage queries and stable cursors. */
public final class GenericItemStorageTest {
    private GenericItemStorageTest() {}

    public static void main(String[] args) {
        GenericItemStorage.ItemQuery exact = new GenericItemStorage.ItemQuery(
                "minecraft:iron_ingot", "", "", "", 8);
        assertTrue(exact.matches("minecraft:iron_ingot"), "exact resource matches");
        assertTrue(!exact.matches("minecraft:gold_ingot"), "different resource rejected");

        GenericItemStorage.ItemQuery namespace = new GenericItemStorage.ItemQuery(
                "", "minecraft", "INGOT", "2", 2);
        assertTrue(namespace.matches("minecraft:iron_ingot"), "namespace and case-insensitive text match");
        assertTrue(!namespace.matches("test:iron_ingot"), "namespace mismatch rejected");
        assertEquals(2, namespace.offset(), "cursor offset");

        GenericItemStorage.ItemQuery tag = new GenericItemStorage.ItemQuery(
                "", "", "", "", 8, "#forge:ingots/iron");
        assertTrue(tag.matches("minecraft:iron_ingot", Set.of("forge:ingots/iron")),
                "canonical tag matches");
        assertTrue(!tag.matches("minecraft:iron_ingot", Set.of("forge:ingots/gold")),
                "different tag rejected");
        assertTrue(!tag.matches("minecraft:iron_ingot"),
                "adapter without tag metadata cannot satisfy tag filter");

        long wideCount = 9_007_199_254_740_993L;
        GenericItemStorage.ItemPage page = new GenericItemStorage.ItemPage(List.of(
                new GenericItemStorage.ItemResource("minecraft:iron_ingot", wideCount,
                        Set.of("forge:ingots", "forge:ingots/iron"))), "1");
        assertEquals(wideCount, page.entries().get(0).count(), "wide logical count preserved");
        GenericItemStorage logical = query -> page;
        assertEquals(wideCount, logical.countItem("minecraft:iron_ingot"),
                "logical exact count uses query view without precision loss");
        assertTrue(page.hasMore(), "non-empty cursor reports more pages");
        assertEquals(Set.of("forge:ingots", "forge:ingots/iron"), page.entries().get(0).tags(),
                "sorted immutable tag metadata retained");

        assertThrows(() -> new GenericItemStorage.ItemQuery("", "", "", "bad", 1).offset(),
                "malformed cursor rejected");
        assertThrows(() -> new GenericItemStorage.ItemQuery("", "", "", "", 0),
                "zero page size rejected");
        assertThrows(() -> new GenericItemStorage.ItemQuery("bad id", "", "", "", 1),
                "invalid resource filter rejected");
        assertThrows(() -> new GenericItemStorage.ItemQuery("", "", "", "", 1, "bad tag"),
                "invalid tag filter rejected");
        assertThrows(() -> new GenericItemStorage.ItemResource("minecraft:stone", -1),
                "negative logical count rejected");
        assertThrows(() -> new GenericItemStorage.ItemPage(List.of(), "bad"),
                "provider cannot emit an unusable cursor");
        assertThrows(() -> new GenericItemStorage.ItemPage(
                java.util.Collections.nCopies(GenericItemStorage.MAX_PAGE_SIZE + 1,
                        new GenericItemStorage.ItemResource("minecraft:stone", 1)), ""),
                "oversized page rejected");

        System.out.println("Generic item storage tests: OK");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertThrows(Runnable action, String message) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError(message + ": expected exception");
    }
}
