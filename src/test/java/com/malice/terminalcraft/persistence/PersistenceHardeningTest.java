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
        vfsMutationsRespectPersistenceBudgets();
        vfsFileEncodingSurvivesActualNbt();
        pocketRecordsProtectRevisionsAndOriginals();
        pocketVerifiedWritesPreserveLastGoodFile();
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

    private static void vfsMutationsRespectPersistenceBudgets() {
        BashShell.VirtualFileSystem vfs = new BashShell.VirtualFileSystem();
        vfs.clearAll();
        assertTrue(vfs.writeFile("/original", "keep"), "seed original");
        CompoundTag before = vfs.save();
        assertTrue(!vfs.writeFile("/original", "x".repeat(65_537)), "oversized overwrite rejected");
        assertTrue(!vfs.writeFile("/new/sub/file", "x".repeat(65_537)), "oversized nested write rejected");
        assertTrue(!vfs.writeFile("/original/child", "no"), "file cannot be a parent");
        assertTrue(!vfs.mkdirs("/original/child"), "mkdir beneath file rejected");
        assertTrue(!vfs.writeFile("/" + "p".repeat(PersistedDataLimits.MAX_PATH_CHARS), "no"),
                "overlong path rejected");
        assertEquals(before, vfs.save(), "failed writes leave snapshot unchanged");

        vfs.clearAll();
        for (int i = 0; i < 64; i++) {
            assertTrue(vfs.writeFile(String.format("/f%02d", i), "x".repeat(65_536)), "fill total capacity");
        }
        assertTrue(vfs.touch("/z-empty"), "empty file accepted at exact character capacity");
        assertTrue(vfs.mkdirs("/z-dir/sub"), "directories consume no content capacity");
        before = vfs.save();
        assertTrue(!vfs.writeFile("/overflow/child", "x"), "aggregate overflow rejected atomically");
        assertEquals(before, vfs.save(), "aggregate rejection leaves no parent directories");
        BashShell.VirtualFileSystem restored = new BashShell.VirtualFileSystem();
        restored.load(before);
        assertEquals(before, restored.save(), "full capacity snapshot survives reload including empty file");
        BashShell.VirtualFileSystem fullMedia = new BashShell.VirtualFileSystem();
        fullMedia.clearAll();
        fullMedia.writeFile("/file", "one more character");
        assertTrue(!vfs.mountDisk("/disk", fullMedia.save()), "mount checks combined content budget");
        assertEquals(before, vfs.save(), "content-overflow mount is atomic");
        assertTrue(vfs.writeFile("/f00", "short"), "overwrite reclaims old content capacity");
        assertTrue(vfs.writeFile("/after", "available"), "reclaimed capacity reusable");

        vfs.clearAll();
        for (int i = 1; i < PersistedDataLimits.MAX_VFS_NODES; i++) {
            assertTrue(vfs.touch("/n" + i), "fill node capacity");
        }
        before = vfs.save();
        assertTrue(!vfs.mkdirs("/new/child"), "mkdirs node overflow rejected atomically");
        assertTrue(!vfs.mkdir("/new"), "mkdir node overflow rejected");
        assertTrue(!vfs.touch("/new"), "touch node overflow rejected");
        assertEquals(before, vfs.save(), "node overflow leaves original tree");
        assertTrue(vfs.writeFile("/n1", "overwrite"), "overwrite at full node capacity allowed");
        restored.load(vfs.save());
        assertEquals(vfs.save(), restored.save(), "full node snapshot round trips");

        BashShell.VirtualFileSystem media = new BashShell.VirtualFileSystem();
        media.clearAll();
        media.writeFile("/program", "payload");
        before = vfs.save();
        assertTrue(!vfs.mountDisk("/disk", media.save()), "mount cannot exceed combined node budget");
        assertEquals(before, vfs.save(), "failed mount leaves tree unchanged");
        assertTrue(vfs.rm("/n2") && vfs.rm("/n3"), "make room for mount and file");
        assertTrue(vfs.mountDisk("/disk", media.save()), "mount retries successfully after freeing space");
        assertEquals("payload", vfs.readFile("/disk/program"), "mounted payload exact");
        assertEquals(media.save(), vfs.exportMount("/disk"), "export retains media data");

        BashShell shell = new BashShell();
        String full = "x".repeat(65_536);
        shell.getVfs().writeFile("/home/player/full", full);
        assertEquals(1, shell.executeForResult("echo extra >> /home/player/full").exitCode(),
                "shell reports rejected append");
        assertEquals(full, shell.getVfs().readFile("/home/player/full"), "append rejection preserves original");
    }

    private static void vfsFileEncodingSurvivesActualNbt() {
        for (String content : java.util.List.of("x".repeat(65_536), "漢".repeat(65_536),
                "\u0000".repeat(65_536), "🙂".repeat(32_768), "legacy plain text")) {
            BashShell.VirtualFileSystem vfs = new BashShell.VirtualFileSystem();
            vfs.clearAll();
            assertTrue(vfs.writeFile("/text", content), "accepted encoded boundary file");
            try {
                var bytes = new java.io.ByteArrayOutputStream();
                net.minecraft.nbt.NbtIo.writeCompressed(vfs.save(), bytes);
                CompoundTag decoded = net.minecraft.nbt.NbtIo.readCompressed(
                        new java.io.ByteArrayInputStream(bytes.toByteArray()));
                BashShell.VirtualFileSystem restored = new BashShell.VirtualFileSystem();
                restored.load(decoded);
                assertEquals(content, restored.readFile("/text"), "real NBT encoding preserves every character");
                assertEquals(vfs.save(), restored.save(), "encoding canonical after reload");
            } catch (java.io.IOException exception) {
                throw new AssertionError("NBT encoding failed", exception);
            }
        }
    }

    private static void pocketRecordsProtectRevisionsAndOriginals() {
        BashShell.VirtualFileSystem vfs = new BashShell.VirtualFileSystem();
        vfs.clearAll();
        String content = "漢🙂".repeat(20_000);
        for (int i = 0; i < 40; i++) assertTrue(vfs.writeFile("/file" + i, content), "large pocket file");
        CompoundTag shell = new CompoundTag();
        shell.put("Vfs", vfs.save());
        CompoundTag original = shell.copy();
        PocketComputerData record = new PocketComputerData();
        assertEquals(null, record.readShell(), "uninitialized is unavailable");
        assertEquals(null, record.compareAndSave(null, shell), "uninitialized cannot save");
        assertTrue(record.initialize(shell, original), "initialize new record");
        java.util.UUID revision = record.revision();
        assertTrue(!record.initialize(new CompoundTag(), null), "identity collision cannot reset files");
        shell.putString("ExternalMutation", "must not leak");
        original.putString("ExternalMutation", "must not leak");
        assertTrue(!record.readShell().contains("ExternalMutation"), "input is copied");
        CompoundTag view = record.readShell();
        view.putString("ExternalMutation", "must not leak");
        assertTrue(!record.readShell().contains("ExternalMutation"), "reads are detached");
        CompoundTag next = record.readShell();
        next.putString("Cwd", "/new");
        java.util.UUID nextRevision = record.compareAndSave(revision, next);
        assertTrue(nextRevision != null && !nextRevision.equals(revision), "successful save changes revision");
        CompoundTag saved = record.save(new CompoundTag());
        record.setDirty(false);
        assertEquals(null, record.compareAndSave(revision, new CompoundTag()), "stale session rejected");
        assertEquals(null, record.compareAndSave(null, new CompoundTag()), "missing revision rejected");
        assertEquals(saved, record.save(new CompoundTag()), "rejected saves leave complete record intact");
        assertTrue(!record.isDirty(), "rejected save does not dirty record");
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            net.minecraft.nbt.NbtIo.writeCompressed(saved, bytes);
            CompoundTag decoded = net.minecraft.nbt.NbtIo.readCompressed(
                    new java.io.ByteArrayInputStream(bytes.toByteArray()));
            PocketComputerData restored = PocketComputerData.load(decoded);
            assertEquals(nextRevision, restored.revision(), "revision survives real NBT");
            assertEquals(saved, restored.save(new CompoundTag()), "snapshot and migration backup survive real NBT");
            BashShell.VirtualFileSystem files = new BashShell.VirtualFileSystem();
            files.load(restored.readShell().getCompound("Vfs"));
            for (int i = 0; i < 40; i++) assertEquals(content, files.readFile("/file" + i), "large Unicode pocket survives");
            assertEquals(null, restored.compareAndSave(revision, next), "stale revision remains rejected after reload");
            assertTrue(restored.compareAndSave(nextRevision, next) != null, "current revision writable after reload");
        } catch (java.io.IOException exception) {
            throw new AssertionError("pocket NBT round trip", exception);
        }
        for (String damagedKey : java.util.List.of("Format", "Revision", "Shell")) {
            CompoundTag damaged = saved.copy();
            damaged.putString(damagedKey, "retain this malformed original");
            assertPocketRecordLocked(damaged);
        }
        CompoundTag future = saved.copy();
        future.putInt("Format", 2);
        assertPocketRecordLocked(future);
        assertPocketRecordLocked(new CompoundTag());
    }

    private static void assertPocketRecordLocked(CompoundTag source) {
        PocketComputerData record = PocketComputerData.load(source);
        assertEquals(null, record.readShell(), "unsupported record unavailable");
        assertTrue(!record.initialize(new CompoundTag(), null), "loaded record cannot be reinitialized");
        assertEquals(null, record.compareAndSave(record.revision(), new CompoundTag()), "unsupported record cannot save");
        assertEquals(source, record.save(new CompoundTag()), "unsupported original retained exactly");
        assertTrue(!record.isDirty(), "unsupported record not dirtied");
    }

    private static void pocketVerifiedWritesPreserveLastGoodFile() {
        net.minecraft.SharedConstants.tryDetectVersion();
        java.nio.file.Path temporary = null;
        try {
            temporary = java.nio.file.Files.createTempDirectory("terminalcraft-pocket-persistence-");
            var destination = temporary.resolve("pocket.dat");
            CompoundTag shell = new CompoundTag();
            shell.putString("Original", "retained 漢🙂");
            BashShell.VirtualFileSystem files = new BashShell.VirtualFileSystem();
            files.clearAll();
            for (int i = 0; i < 40; i++) {
                assertTrue(files.writeFile("/file" + i, "漢🙂".repeat(20_000)), "large on-disk pocket fixture");
            }
            shell.put("Vfs", files.save());
            PocketComputerData record = new PocketComputerData();
            assertTrue(record.initialize(shell, shell), "initialize disk record");
            record.persistVerified(destination);
            assertTrue(!record.isDirty(), "verified success clears dirty");
            byte[] goodBytes = java.nio.file.Files.readAllBytes(destination);
            CompoundTag disk = net.minecraft.nbt.NbtIo.readCompressed(destination.toFile()).getCompound("data");
            assertEquals(record.save(new CompoundTag()), disk, "disk record verified exactly");
            assertEquals(shell, PocketComputerData.load(disk).readShell(), "actual file reopens");

            CompoundTag unencodable = shell.copy();
            unencodable.putString("TooLarge", "漢".repeat(65_536));
            record.compareAndSave(record.revision(), unencodable);
            boolean rejected = false;
            try { record.persistVerified(destination); }
            catch (java.io.IOException expected) { rejected = true; }
            assertTrue(rejected && record.isDirty(), "lossy encoding fails and remains dirty");
            assertTrue(java.util.Arrays.equals(goodBytes, java.nio.file.Files.readAllBytes(destination)),
                    "failed encoding leaves previous file byte-for-byte intact");

            record.compareAndSave(record.revision(), shell);
            var impossible = destination.resolve("child.dat"); // Parent is a regular file, not a directory.
            rejected = false;
            try { record.persistVerified(impossible); }
            catch (java.io.IOException expected) { rejected = true; }
            assertTrue(rejected && record.isDirty(), "disk write failure remains dirty");
            record.save(impossible.toFile());
            assertTrue(record.isDirty(), "automatic save failure also retains retry state");
            assertTrue(java.util.Arrays.equals(goodBytes, java.nio.file.Files.readAllBytes(destination)),
                    "write failure preserves previous file");
            record.persistVerified(destination);
            assertTrue(!record.isDirty(), "retry succeeds");
            // Exercise an actual denied write to the same existing destination, not
            // just a different invalid path. Never change world-directory permissions.
            if (java.nio.file.Files.getFileStore(temporary).supportsFileAttributeView("posix")) {
                var permissions = java.nio.file.Files.getPosixFilePermissions(temporary);
                var readOnly = java.util.EnumSet.copyOf(permissions);
                readOnly.remove(java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
                readOnly.remove(java.nio.file.attribute.PosixFilePermission.GROUP_WRITE);
                readOnly.remove(java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE);
                byte[] previousBytes = java.nio.file.Files.readAllBytes(destination);
                CompoundTag pending = shell.copy();
                pending.putString("AfterDeniedWrite", "retry retains new data 漢🙂");
                var pendingRevision = record.compareAndSave(record.revision(), pending);
                try {
                    java.nio.file.Files.setPosixFilePermissions(temporary, readOnly);
                    // Privileged processes may bypass POSIX permissions. Do not
                    // misreport such an environment as an exercised denial.
                    if (java.nio.file.Files.isWritable(temporary)) {
                        System.out.println("SKIP: POSIX write-denial drill (process bypasses directory permissions)");
                    } else {
                        record.save(destination.toFile());
                        assertTrue(record.isDirty(), "OS-denied automatic save remains retryable");
                        assertEquals(pendingRevision, record.revision(), "failed disk write retains pending revision");
                        assertEquals(pending, record.readShell(), "failed disk write retains pending contents");
                        assertTrue(java.util.Arrays.equals(previousBytes, java.nio.file.Files.readAllBytes(destination)),
                                "OS-denied replacement leaves last good file byte-for-byte intact");
                        var reopened = PocketComputerData.load(net.minecraft.nbt.NbtIo.readCompressed(destination.toFile()).getCompound("data"));
                        assertEquals(shell, reopened.readShell(), "last good disk file still independently loads after denial");
                        System.out.println("PASS: POSIX write-denial preserved last good disk file and pending changes");
                    }
                } finally {
                    java.nio.file.Files.setPosixFilePermissions(temporary, permissions);
                }
                record.save(destination.toFile());
                assertTrue(!record.isDirty(), "automatic save succeeds after permissions recover");
                var retried = PocketComputerData.load(net.minecraft.nbt.NbtIo.readCompressed(destination.toFile()).getCompound("data"));
                assertEquals(pending, retried.readShell(), "retried disk file contains pending changes exactly");
                assertEquals(pendingRevision, retried.revision(), "retried disk revision matches pending session");
            } else {
                System.out.println("SKIP: POSIX write-denial drill (filesystem does not support POSIX permissions)");
            }
            try (var entries = java.nio.file.Files.list(temporary)) {
                assertEquals(1L, entries.count(), "staging files cleaned after success and failure");
            }
        } catch (java.io.IOException exception) {
            throw new AssertionError("verified pocket disk persistence", exception);
        } finally {
            if (temporary != null) {
                try (var entries = java.nio.file.Files.walk(temporary)) {
                    for (var path : entries.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        java.nio.file.Files.delete(path);
                    }
                } catch (java.io.IOException exception) {
                    throw new AssertionError("test fixture cleanup", exception);
                }
            }
        }
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
