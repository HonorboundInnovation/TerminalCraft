package com.malice.terminalcraft.shell;

import java.util.List;

/** Headless characterization for composed host services and external command modules. */
public final class ShellArchitectureExtractionTest {
    private ShellArchitectureExtractionTest() {}

    public static void main(String[] args) {
        assertEquals(List.of("echo", "two words"), ShellSyntax.tokenize("echo \"two words\""),
                "quote-aware tokenization");
        assertEquals(List.of("echo \"a|b\" ", " cat"),
                ShellSyntax.splitTopLevel("echo \"a|b\" | cat", '|'),
                "top-level pipeline splitting");
        assertEquals("echo '# kept'", ShellSyntax.stripComment("echo '# kept' # removed"),
                "quote-aware comment stripping");

        FakeHost host = new FakeHost();
        TerminalHostServices services = host.services();
        assertEquals("alpha", services.identity().label(), "legacy identity adapter");
        assertEquals(7, services.redstone().input("left"), "legacy redstone adapter");
        assertTrue(services.turtle().available(), "legacy turtle adapter");
        assertTrue(services.disk().hasMedia(), "legacy disk adapter");

        BashShell shell = new BashShell();
        shell.setHost(host);
        assertOutcome(shell.executeForResult("peripheral list"), 0,
                List.of("left:monitor"), "extracted peripheral command");
        assertOutcome(shell.executeForResult("label beta"), 0,
                List.of("beta"), "extracted label command");
        assertEquals("beta", host.label, "label forwarded through composed identity service");

        shell.installCommandModule(registrar -> registrar.register("probe", (context, arguments) -> {
            TerminalHostServices current = context.hostServices();
            context.printLine(current.identity().label() + ":" + arguments.size());
            context.setExitCode(23);
        }));
        assertOutcome(shell.executeForResult("probe one two"), 23,
                List.of("beta:2"), "independent module dispatch");

        boolean duplicateRejected = false;
        try {
            shell.installCommandModule(registrar -> registrar.register("probe", (context, arguments) -> {}));
        } catch (IllegalArgumentException expected) {
            duplicateRejected = true;
        }
        assertTrue(duplicateRejected, "duplicate module commands fail closed");

        assertUnmountRetainsDataUntilMediaWriteSucceeds();
        assertEditorDataSafety();
        assertMalformedEditorCannotOverwriteOriginal();

        System.out.println("Shell architecture extraction tests: OK");
    }

    private static void assertMalformedEditorCannotOverwriteOriginal() {
        String path = "/home/player/recovery.txt";
        for (int mode = 0; mode < 5; mode++) {
            BashShell original = new BashShell();
            original.getVfs().writeFile(path, "precious original");
            original.executeForResult("edit " + path);
            var saved = original.save();
            var lines = new net.minecraft.nbt.ListTag();
            if (mode == 0) lines.add(net.minecraft.nbt.StringTag.valueOf("x".repeat(513)));
            if (mode == 1) for (int i = 0; i < 513; i++) lines.add(net.minecraft.nbt.StringTag.valueOf("x"));
            if (mode == 2) for (int i = 0; i < 512; i++) lines.add(net.minecraft.nbt.StringTag.valueOf("x".repeat(512)));
            if (mode == 3) lines.add(net.minecraft.nbt.StringTag.valueOf("embedded\nline"));
            saved.put("EditorLines", lines); // mode 4: malformed empty active buffer
            BashShell restored = new BashShell(); restored.load(saved);
            String view = restored.getEditorText();
            assertTrue(!restored.saveEditorFromGui("replacement", true), "malformed GUI save refused");
            assertEquals(view, restored.getEditorText(), "rejection preserves recovered view");
            assertEquals("precious original", restored.getVfs().readFile(path), "original file protected");
            BashShell again = new BashShell(); again.load(restored.save());
            again.execute(":wq");
            assertEquals(1, again.getLastExitCode(), "protection survives persistence and line-mode save");
            assertEquals("precious original", again.getVfs().readFile(path), "line-mode save cannot overwrite original");
            again.closeEditorFromGui();
            assertEquals(0, again.executeForResult("edit " + path).exitCode(), "original can be reopened after discard");
            assertTrue(again.saveEditorFromGui("intentional replacement", true), "fresh valid session can save");
        }
    }

    private static void assertEditorDataSafety() {
        String path = "/home/player/editor-test.txt";
        for (String content : List.of("", "plain", "one\n", "one\n\n", "\n",
                "x".repeat(512), "x\n".repeat(512), ("x".repeat(511) + "\n").repeat(128))) {
            BashShell shell = new BashShell();
            assertTrue(shell.getVfs().writeFile(path, content), "seed editor file");
            assertEquals(0, shell.executeForResult("edit " + path).exitCode(), "open supported file");
            assertEquals(content, shell.getEditorText(), "opening preserves LF content");
            BashShell restored = new BashShell();
            restored.load(shell.save());
            assertEquals(content, restored.getEditorText(), "editor snapshot preserves final newline");
            assertTrue(restored.saveEditorFromGui(restored.getEditorText(), true), "save and close");
            assertEquals(content, restored.getVfs().readFile(path), "no-op save preserves content");
            assertEquals("", restored.getEditorText(), "closed editor clears newline state");
        }
        for (String oversized : List.of("x".repeat(513), "x\n".repeat(513),
                ("x".repeat(511) + "\n").repeat(129))) {
            BashShell shell = new BashShell();
            if (oversized.length() <= 65_536) {
                assertTrue(shell.getVfs().writeFile(path, oversized), "seed editor-only limit fixture");
                assertEquals(1, shell.executeForResult("edit " + path).exitCode(), "oversized open rejected");
                assertEquals(oversized, shell.getVfs().readFile(path), "rejection leaves file intact");
                assertTrue(!shell.saveEditorFromGui("replacement", true), "rejected open stays inactive");
            } else {
                assertTrue(!shell.getVfs().writeFile(path, oversized), "VFS rejects oversized file before editor");
            }
            shell.getVfs().writeFile(path, "original");
            shell.executeForResult("edit " + path);
            assertTrue(!shell.saveEditorFromGui(oversized, true), "oversized GUI save rejected");
            assertEquals("original", shell.getEditorText(), "rejected save retains buffer");
            assertEquals("original", shell.getVfs().readFile(path), "rejected save retains file");
            assertTrue(shell.saveEditorFromGui("retry", true), "failed save leaves editor open for retry");
        }
        BashShell shell = new BashShell();
        shell.getVfs().writeFile(path, "original");
        shell.executeForResult("edit " + path);
        shell.execute("x".repeat(513));
        assertEquals("original", shell.getEditorText(), "overlong typed line not truncated into buffer");
        var legacy = shell.save();
        legacy.remove("EditorFinalNewline");
        BashShell restored = new BashShell();
        restored.load(legacy);
        assertEquals("original\n", restored.getEditorText(), "legacy editor keeps historical newline semantics");
        assertTrue(shell.saveEditorFromGui("one\r\ntwo\r", true), "CRLF input supported");
        assertEquals("one\ntwo\n", shell.getVfs().readFile(path), "line endings normalize to LF");
    }

    private static void assertUnmountRetainsDataUntilMediaWriteSucceeds() {
        FakeHost diskHost = new FakeHost();
        diskHost.media = new BashShell.VirtualFileSystem().save();
        BashShell shell = new BashShell();
        shell.setHost(diskHost);
        assertEquals(0, shell.executeForResult("mount").exitCode(), "mount fixture");
        shell.executeForResult("echo precious > /disk/unsaved.txt");
        shell.executeForResult("cd /disk");
        assertEquals(1, shell.executeForResult("umount").exitCode(), "rejected media write must fail unmount");
        assertEquals("precious\n", shell.getVfs().readFile("/disk/unsaved.txt"), "failed unmount retains data");
        assertEquals("/disk", shell.getCwd(), "failed unmount retains cwd");

        diskHost.present = false;
        assertEquals(1, shell.executeForResult("umount").exitCode(), "missing media must fail unmount");
        BashShell restored = new BashShell();
        restored.load(shell.save());
        restored.setHost(diskHost);
        assertEquals("precious\n", restored.getVfs().readFile("/disk/unsaved.txt"),
                "pending mounted data survives shell persistence");
        diskHost.present = true;
        diskHost.writable = true;
        var legacyImage = restored.save();
        legacyImage.remove("MountedMediaToken");
        BashShell legacy = new BashShell();
        legacy.load(legacyImage);
        legacy.setHost(diskHost);
        assertEquals(1, legacy.executeForResult("disk sync").exitCode(), "legacy unknown media cannot be overwritten");
        assertEquals("precious\n", legacy.getVfs().readFile("/disk/unsaved.txt"), "legacy files remain recoverable");
        assertEquals(2, legacy.executeForResult("umount --unknown").exitCode(), "unknown flags cannot discard data");
        assertEquals(0, restored.executeForResult("umount").exitCode(), "retry commits and unmounts");
        assertTrue(!restored.getVfs().isDirectory("/disk"), "successful unmount removes mount");
        BashShell.VirtualFileSystem disk = new BashShell.VirtualFileSystem();
        disk.load(diskHost.media);
        assertEquals("precious\n", disk.readFile("/unsaved.txt"), "retry saves original data");
    }

    private static final class FakeHost implements TerminalHost {
        private String label = "alpha";
        private boolean present = true;
        private boolean writable;
        private net.minecraft.nbt.CompoundTag media;
        private String mediaToken = "original:revision0";
        @Override public String diskMediaToken() { return present ? mediaToken : ""; }

        @Override public int getRedstoneInput(String side) { return 7; }
        @Override public int getRedstoneOutput(String side) { return 3; }
        @Override public boolean setRedstoneOutput(String side, int power) { return true; }
        @Override public List<String> redstoneSides() { return List.of("left"); }
        @Override public List<String> listPeripherals() { return List.of("left:monitor"); }
        @Override public String getLabel() { return label; }
        @Override public void setLabel(String label) { this.label = label; }
        @Override public boolean isTurtle() { return true; }
        @Override public boolean hasDiskMedia() { return present; }
        @Override public net.minecraft.nbt.CompoundTag readDiskMedia() { return media == null ? null : media.copy(); }
        @Override public boolean writeDiskMedia(net.minecraft.nbt.CompoundTag data) {
            if (!present || !writable) return false;
            media = data.copy();
            mediaToken = java.util.UUID.randomUUID().toString();
            return true;
        }
    }

    private static void assertOutcome(ShellCommandResult actual, int exitCode,
                                      List<String> lines, String message) {
        assertEquals(exitCode, actual.exitCode(), message + " exit code");
        assertEquals(lines, actual.outputLines(), message + " output");
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
