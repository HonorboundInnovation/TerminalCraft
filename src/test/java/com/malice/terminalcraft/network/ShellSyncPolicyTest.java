package com.malice.terminalcraft.network;

import net.minecraft.nbt.CompoundTag;

/** Headless boundary checks for authoritative shell synchronization payloads. */
public final class ShellSyncPolicyTest {
    private ShellSyncPolicyTest() {}

    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();

        assertEquals(0, ModNetwork.RUN_COMMAND_PACKET_ID, "run command packet ID");
        assertEquals(1, ModNetwork.SHELL_SYNC_PACKET_ID, "shell sync packet ID");
        assertEquals(2, ModNetwork.EDITOR_ACTION_PACKET_ID, "editor action packet ID");
        assertEquals(3, ModNetwork.EDITOR_RESULT_PACKET_ID, "editor result packet ID");

        assertTrue(!ShellSyncPolicy.isAdmissible(null), "null snapshot rejected");
        assertTrue(ShellSyncPolicy.isAdmissible(new CompoundTag()), "empty snapshot accepted");

        CompoundTag normal = new CompoundTag();
        normal.putString("Output", "hello");
        normal.putByteArray("Data", new byte[64 * 1024]);
        assertTrue(ShellSyncPolicy.isAdmissible(normal), "normal snapshot accepted");

        CompoundTag oversized = new CompoundTag();
        oversized.putByteArray("Data", new byte[ShellSyncPolicy.MAX_ENCODED_BYTES + 1]);
        assertTrue(!ShellSyncPolicy.isAdmissible(oversized), "oversized snapshot rejected");

        System.out.println("Shell sync policy tests: OK");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
