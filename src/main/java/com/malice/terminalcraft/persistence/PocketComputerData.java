package com.malice.terminalcraft.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import java.util.Objects;
import java.util.UUID;

/** World-local pocket files. All access and compare-and-save operations run on the server thread. */
public final class PocketComputerData extends SavedData {
    private static final int FORMAT = 1;
    private CompoundTag stored = new CompoundTag();
    private boolean pristine = true;
    private UUID revision;

    /** Lookup must not create an empty replacement for a missing imported item's files. */
    public static PocketComputerData find(ServerLevel level, UUID identity) {
        return level.getServer().overworld().getDataStorage().get(
                PocketComputerData::load, key(identity));
    }

    /** Only for allocating a new identity or migrating legacy data; initialize still rejects collisions. */
    public static PocketComputerData allocate(ServerLevel level, UUID identity) {
        PocketComputerData existing = find(level, identity);
        if (existing != null) return existing;
        // SavedData lookup also returns null on decode failure. Never overwrite that file.
        if (!Files.notExists(file(level, identity), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("Existing pocket record could not be read");
        }
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                PocketComputerData::load, PocketComputerData::new, key(identity));
    }

    public static Path file(ServerLevel level, UUID identity) {
        return level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                .resolve("data").resolve(key(identity) + ".dat");
    }

    private static String key(UUID identity) {
        return "terminalcraft_pocket_" + Objects.requireNonNull(identity);
    }

    public static PocketComputerData load(CompoundTag tag) {
        PocketComputerData result = new PocketComputerData();
        result.pristine = false;
        // Keep malformed/future records intact for recovery, not an empty shell that can be saved over them.
        result.stored = tag.copy();
        if (tag.contains("Format", Tag.TAG_INT) && tag.getInt("Format") == FORMAT
                && tag.hasUUID("Revision") && tag.contains("Shell", Tag.TAG_COMPOUND)) {
            result.revision = tag.getUUID("Revision");
        }
        return result;
    }

    public boolean initialize(CompoundTag shell, CompoundTag legacyBackup) {
        Objects.requireNonNull(shell);
        if (!pristine) return false;
        stored.putInt("Format", FORMAT);
        stored.put("Shell", shell.copy());
        if (legacyBackup != null) stored.put("LegacyBackup", legacyBackup.copy());
        revision = UUID.randomUUID();
        stored.putUUID("Revision", revision);
        pristine = false;
        setDirty();
        return true;
    }

    public UUID revision() { return revision; }

    /** Release an unpublished failed migration's potentially large in-memory copy. Source item is intact. */
    void abandonUnpublishedMigration() {
        stored = new CompoundTag();
        revision = null;
        pristine = false;
        setDirty(false);
    }

    /**
     * A migration may remove its source only after this returns successfully.
     * No non-atomic replacement fallback: unsupported storage leaves the source recoverable.
     */
    public void persistVerified(Path target) throws IOException {
        if (revision == null) throw new IOException("Pocket record is unavailable or unsupported");
        // A failed explicit flush must remain retryable even if the record was previously clean.
        setDirty();
        Path destination = target.toAbsolutePath();
        Path parent = destination.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".terminalcraft-pocket-", ".tmp");
        try {
            CompoundTag envelope = new CompoundTag();
            envelope.put("data", save(new CompoundTag()));
            NbtUtils.addCurrentDataVersion(envelope);
            NbtIo.writeCompressed(envelope, temporary.toFile());
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            // StringTag can silently encode an oversized modified-UTF string as empty.
            // Compare the complete decoded record before replacing the last good file.
            if (!envelope.equals(NbtIo.readCompressed(temporary.toFile()))) {
                throw new IOException("Pocket record failed lossless NBT verification");
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel directory = FileChannel.open(parent, StandardOpenOption.READ)) {
                directory.force(true);
            }
            setDirty(false);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Vanilla SavedData clears dirty even on failure; pocket records must retain retry state. */
    @Override public void save(File file) {
        if (!isDirty()) return;
        try {
            persistVerified(file.toPath());
        } catch (IOException exception) {
            com.mojang.logging.LogUtils.getLogger().error("Could not safely save pocket record {}", file, exception);
        }
    }

    /** Null means unavailable/unsupported; callers must not substitute a writable blank shell. */
    public CompoundTag readShell() {
        return revision == null ? null : stored.getCompound("Shell").copy();
    }

    /** Detached migration-time item tag; never exposes a mutable reference to the recovery copy. */
    public CompoundTag readLegacyBackup() {
        return revision == null ? null : stored.getCompound("LegacyBackup").copy();
    }

    /** A second screen holding an older revision cannot replace the latest files. */
    public UUID compareAndSave(UUID expectedRevision, CompoundTag shell) {
        Objects.requireNonNull(shell);
        if (revision == null || !revision.equals(expectedRevision)) return null;
        stored.put("Shell", shell.copy());
        revision = UUID.randomUUID();
        stored.putUUID("Revision", revision);
        setDirty();
        return revision;
    }

    @Override public CompoundTag save(CompoundTag tag) {
        for (String key : stored.getAllKeys()) tag.put(key, stored.get(key).copy());
        return tag;
    }
}
