package com.malice.terminalcraft.persistence;

import com.malice.terminalcraft.Config;
import com.malice.terminalcraft.shell.BashShell;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;

/** Headless regression coverage for corrupt, oversized, legacy, and future persisted state. */
public final class PersistenceHardeningTest {
    private PersistenceHardeningTest() {}

    public static void main(String[] args) {
        schemaClassificationIsExplicit();
        boundedPrimitiveReadersAreDeterministic();
        oversizedShellCollectionsAreBounded();
        malformedAndOversizedVfsIsSalvaged();
        System.out.println("Persistence hardening tests: OK");
    }

    private static void schemaClassificationIsExplicit() {
        assertEquals(PersistedDataVersions.Schema.LEGACY,
                PersistedDataVersions.classify(new CompoundTag()), "legacy schema");
        CompoundTag current = new CompoundTag();
        PersistedDataVersions.stampCurrent(current);
        assertEquals(PersistedDataVersions.Schema.CURRENT,
                PersistedDataVersions.classify(current), "current schema");
        current.putInt(PersistedDataVersions.TAG_DATA_VERSION,
                PersistedDataVersions.CURRENT_VERSION + 7);
        assertEquals(PersistedDataVersions.Schema.FUTURE,
                PersistedDataVersions.classify(current), "future schema");
    }

    private static void boundedPrimitiveReadersAreDeterministic() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Text", "abcdefgh");
        assertEquals("abcd", PersistedDataLimits.readString(tag, "Text", 4, "fallback"),
                "string truncation");
        tag.putInt("Text", 4);
        assertEquals("fallback", PersistedDataLimits.readString(tag, "Text", 4, "fallback"),
                "wrong string type");
        tag.put("Channels", new IntArrayTag(new int[] {99, -1, 4, 4, 1000, 2}));
        assertEquals(java.util.List.of(0, 2, 4),
                PersistedDataLimits.readBoundedIntArray(tag, "Channels", 0, 100, 3),
                "bounded integer array");
    }

    private static void oversizedShellCollectionsAreBounded() {
        CompoundTag root = new CompoundTag();
        root.putString("Cwd", "relative/corrupt");

        CompoundTag env = new CompoundTag();
        for (int i = 0; i < PersistedDataLimits.MAX_ENV_ENTRIES + 20; i++) {
            env.putString(String.format("K%03d", i), "v".repeat(5000));
        }
        env.putInt("WrongType", 1);
        root.put("Env", env);

        ListTag output = new ListTag();
        for (int i = 0; i < Config.maxHistoryLines + 40; i++) {
            output.add(StringTag.valueOf(("line-" + i) + "x".repeat(5000)));
        }
        root.put("Output", output);

        ListTag history = new ListTag();
        for (int i = 0; i < 80; i++) history.add(StringTag.valueOf("h".repeat(300) + i));
        root.put("History", history);

        BashShell shell = new BashShell();
        shell.load(root);
        assertEquals("/home/player", shell.getCwd(), "invalid cwd fallback");
        assertEquals(Config.maxHistoryLines, shell.getOutputLines().size(), "output line limit");
        assertTrue(shell.getOutputLines().stream().allMatch(
                line -> line.length() <= PersistedDataLimits.MAX_OUTPUT_LINE_CHARS),
                "output character limit");
        assertEquals(PersistedDataLimits.MAX_HISTORY_ENTRIES,
                shell.getCommandHistory().size(), "history entry limit");
        assertTrue(shell.getCommandHistory().stream().allMatch(
                line -> line.length() <= PersistedDataLimits.MAX_HISTORY_LINE_CHARS),
                "history character limit");
    }

    private static void malformedAndOversizedVfsIsSalvaged() {
        CompoundTag vfsTag = new CompoundTag();
        PersistedDataVersions.stampCurrent(vfsTag);
        vfsTag.put("/", directory());
        vfsTag.putString("/wrong-type", "ignored");
        vfsTag.put("relative", file("ignored"));
        vfsTag.put("/000-huge", file("z".repeat(PersistedDataLimits.MAX_VFS_FILE_CHARS + 20)));
        for (int i = 0; i < PersistedDataLimits.MAX_VFS_NODES + 50; i++) {
            vfsTag.put(String.format("/f%04d", i), file("ok"));
        }

        BashShell.VirtualFileSystem vfs = new BashShell.VirtualFileSystem();
        vfs.load(vfsTag);
        assertEquals(null, vfs.readFile("/wrong-type"), "wrong type skipped");
        assertEquals(null, vfs.readFile("/relative"), "relative path skipped");
        String huge = vfs.readFile("/000-huge");
        assertEquals(PersistedDataLimits.MAX_VFS_FILE_CHARS, huge.length(),
                "file content bound");
        CompoundTag repaired = vfs.save();
        assertEquals(PersistedDataVersions.Schema.CURRENT,
                PersistedDataVersions.classify(repaired), "repaired schema stamp");
        assertTrue(repaired.getAllKeys().size() <= PersistedDataLimits.MAX_VFS_NODES + 1,
                "VFS node limit");
    }

    private static CompoundTag directory() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Dir", true);
        return tag;
    }

    private static CompoundTag file(String contents) {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Dir", false);
        tag.putString("Data", contents);
        return tag;
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
