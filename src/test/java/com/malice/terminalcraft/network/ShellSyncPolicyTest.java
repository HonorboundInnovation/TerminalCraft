package com.malice.terminalcraft.network;

import net.minecraft.nbt.CompoundTag;

/** Headless boundary checks for authoritative shell synchronization payloads. */
public final class ShellSyncPolicyTest {
    private ShellSyncPolicyTest() {}

    public static void main(String[] args) {
        com.malice.terminalcraft.testsupport.HeadlessMinecraftBootstrap.initialize();

        assertEquals(0, ModNetwork.RUN_COMMAND_PACKET_ID, "run command packet ID");
        assertEquals(1, ModNetwork.SHELL_SYNC_PACKET_ID, "shell sync packet ID");
        assertEquals(2, ModNetwork.EDITOR_ACTION_PACKET_ID, "editor action packet ID");
        assertEquals(3, ModNetwork.EDITOR_RESULT_PACKET_ID, "editor result packet ID");
        assertEquals(7, ModNetwork.CONTROL_CENTER_ACTION_PACKET_ID, "control center action packet ID");
        assertTrue(ModNetwork.MONITOR_CONFIG_PACKET_ID != RemoteCompletionPackets.REQUEST_ID,
                "monitor config packet ID must not collide with completion requests");
        assertTrue(ModNetwork.MONITOR_CONFIG_PACKET_ID != RemoteCompletionPackets.RESPONSE_ID,
                "monitor config packet ID must not collide with completion responses");

        assertTrue(!ShellSyncPolicy.isAdmissible(null), "null snapshot rejected");
        assertTrue(ShellSyncPolicy.isAdmissible(new CompoundTag()), "empty snapshot accepted");

        CompoundTag normal = new CompoundTag();
        normal.putString("Output", "hello");
        normal.putByteArray("Data", new byte[64 * 1024]);
        assertTrue(ShellSyncPolicy.isAdmissible(normal), "normal snapshot accepted");

        CompoundTag oversized = new CompoundTag();
        oversized.putByteArray("Data", new byte[ShellSyncPolicy.MAX_ENCODED_BYTES + 1]);
        assertTrue(!ShellSyncPolicy.isAdmissible(oversized), "oversized snapshot rejected");

        var shell = new com.malice.terminalcraft.shell.BashShell();
        String large = "x".repeat(60_000);
        for (int i = 0; i < 40; i++) assertTrue(shell.getVfs().writeFile("/home/player/large-" + i, large), "fixture written");
        shell.executeForResult("echo view-ready");
        shell.getVfs().writeFile("/home/player/note.txt", "editable text\n");
        shell.executeForResult("edit /home/player/note.txt");
        CompoundTag persisted = shell.save();
        assertTrue(!ShellSyncPolicy.isAdmissible(persisted), "full filesystem exceeds packet limit");
        CompoundTag view = shell.saveClientView();
        assertTrue(!view.contains("Vfs") && view.getCompound("Env").isEmpty(), "view excludes filesystem and private environment");
        assertTrue(ShellSyncPolicy.isAdmissible(view), "large filesystem has a small display snapshot");
        assertEquals(persisted, shell.save(), "creating a view never mutates persistence");
        var client = new com.malice.terminalcraft.shell.BashShell();
        client.load(view);
        assertTrue(client.getOutputLines().stream().anyMatch(s -> s.contains("view-ready")), "output reaches view");
        assertTrue(client.isEditorActive() && client.getEditorText().contains("editable text"), "active editor text reaches view");
        assertTrue(client.getVfs().readFile("/home/player/large-39") == null, "private files are not copied into client shell");
        shell.closeEditorFromGui();
        String completionLine = "cat /home/player/large-3";
        assertTrue(shell.complete(completionLine, completionLine.length()).hasMatches(), "server retains filename completion outside editor mode");

        var response = new RemoteCompletionPackets.Response(4, "cat /ho", 7, 4, 7, java.util.List.of("/home/"));
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try {
            RemoteCompletionPackets.Response.encode(response, buffer);
            assertEquals(response, RemoteCompletionPackets.Response.decode(buffer), "completion packet round trip");
            buffer.clear();
            buffer.writeVarInt(4); buffer.writeUtf(""); buffer.writeVarInt(0);
            buffer.writeVarInt(0); buffer.writeVarInt(0); buffer.writeVarInt(RemoteCompletionPackets.MAX_CANDIDATES + 1);
            boolean rejected = false;
            try { RemoteCompletionPackets.Response.decode(buffer); } catch (IllegalArgumentException expected) { rejected = true; }
            assertTrue(rejected, "oversized completion candidate count rejected");
        } finally { buffer.release(); }

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
