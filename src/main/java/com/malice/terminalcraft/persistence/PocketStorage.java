package com.malice.terminalcraft.persistence;

import com.malice.terminalcraft.item.PocketTerminalItem;
import com.malice.terminalcraft.shell.BashShell;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.util.UUID;

/** Server-only identity resolution and verified, source-preserving legacy migration. */
public final class PocketStorage {
    public static final String IDENTITY = "PocketComputerId";
    private PocketStorage() {}

    /** Refuse a writable session if the bounded loader would discard or rewrite saved files. */
    public static void validateWritableShell(CompoundTag shell) throws IOException {
        if (shell == null || PersistedDataVersions.classify(shell) == PersistedDataVersions.Schema.FUTURE
                || !shell.contains("Vfs", Tag.TAG_COMPOUND)) throw new IOException("Unsupported pocket shell format; original files retained");
        BashShell.VirtualFileSystem checked = new BashShell.VirtualFileSystem();
        checked.load(shell.getCompound("Vfs"));
        CompoundTag expectedFiles = shell.getCompound("Vfs").copy();
        if (PersistedDataVersions.classify(expectedFiles) == PersistedDataVersions.Schema.LEGACY) {
            PersistedDataVersions.stampCurrent(expectedFiles);
        }
        if (!expectedFiles.equals(checked.save())) {
            throw new IOException("Pocket filesystem needs recovery or upgrade; original files retained");
        }
    }

    public static PocketComputerData open(ServerLevel level, ItemStack stack) throws IOException {
        if (!(stack.getItem() instanceof PocketTerminalItem)) throw new IOException("Not a pocket terminal");
        CompoundTag original = stack.getTag() == null ? new CompoundTag() : stack.getTag().copy();
        if (original.contains(IDENTITY)) {
            if (!original.hasUUID(IDENTITY)) throw new IOException("Invalid pocket identity; original item retained");
            if (original.contains(PocketTerminalItem.TAG_SHELL)) {
                throw new IOException("Pocket contains conflicting legacy files; original item retained");
            }
            PocketComputerData existing = PocketComputerData.find(level, original.getUUID(IDENTITY));
            if (existing == null || existing.readShell() == null) {
                throw new IOException("Pocket files unavailable in this world; restore its world-data backup");
            }
            if (PersistedDataVersions.classify(existing.readShell()) == PersistedDataVersions.Schema.FUTURE) {
                throw new IOException("Pocket files use a newer format; update the mod before opening");
            }
            return existing;
        }
        validateLegacy(original);
        CompoundTag shell = original.contains(PocketTerminalItem.TAG_SHELL)
                ? original.getCompound(PocketTerminalItem.TAG_SHELL).copy() : new BashShell().save();
        UUID identity = UUID.randomUUID();
        PocketComputerData record;
        try {
            record = PocketComputerData.allocate(level, identity);
            if (!record.initialize(shell, original)) throw new IOException("Pocket identity collision; retry opening");
        } catch (IllegalStateException failure) {
            throw new IOException("Pocket record cannot be allocated; original item retained", failure);
        }
        try {
            record.persistVerified(PocketComputerData.file(level, identity));
        } catch (IOException failure) {
            record.abandonUnpublishedMigration();
            throw failure;
        }
        // Do not alter the source item until the full legacy backup was verified on disk.
        CompoundTag migrated = original.copy();
        migrated.remove(PocketTerminalItem.TAG_SHELL);
        migrated.putUUID(IDENTITY, identity);
        PersistedDataVersions.stampCurrent(migrated);
        stack.setTag(migrated);
        return record;
    }

    /** Pure preflight: no record allocation, filesystem writes or item mutation. */
    static void validateLegacy(CompoundTag original) throws IOException {
        if (original.contains(IDENTITY)) throw new IOException("Pocket contains conflicting legacy identity; original retained");
        if (PersistedDataVersions.classify(original) == PersistedDataVersions.Schema.FUTURE) {
            throw new IOException("Pocket was saved by a newer format; original item retained");
        }
        if (original.contains(PocketTerminalItem.TAG_SHELL)) {
            if (!original.contains(PocketTerminalItem.TAG_SHELL, Tag.TAG_COMPOUND)) {
                throw new IOException("Invalid legacy pocket files; original item retained");
            }
            if (PersistedDataVersions.classify(original.getCompound(PocketTerminalItem.TAG_SHELL)) == PersistedDataVersions.Schema.FUTURE) {
                throw new IOException("Legacy pocket files use a newer format; original item retained");
            }
        }
        validateEncoding(original, 0, new int[] {65_536});
    }

    private static void validateEncoding(Tag tag, int depth, int[] budget) throws IOException {
        if (depth > 128 || --budget[0] < 0) throw new IOException("Pocket backup exceeds validation traversal limits; original retained");
        if (tag instanceof net.minecraft.nbt.StringTag string) validateUtf(string.getAsString());
        else if (tag instanceof CompoundTag compound) {
            for (String key : compound.getAllKeys()) {
                validateUtf(key);
                validateEncoding(compound.get(key), depth + 1, budget);
            }
        } else if (tag instanceof net.minecraft.nbt.ListTag list) {
            for (Tag child : list) validateEncoding(child, depth + 1, budget);
        }
    }

    private static void validateUtf(String text) throws IOException {
        int bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            bytes += value >= 1 && value <= 127 ? 1 : value <= 2047 ? 2 : 3;
            if (bytes > 65_535) throw new IOException("Pocket backup contains an unencodable NBT string; original retained");
        }
    }
}
