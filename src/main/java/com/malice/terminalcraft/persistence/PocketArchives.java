package com.malice.terminalcraft.persistence;

import com.malice.terminalcraft.item.PocketTerminalItem;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

import java.io.*;
import java.nio.file.*;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/** Operator-mediated portable snapshots. Imports always allocate a new computer identity. */
public final class PocketArchives {
    private PocketArchives() {}

    public static Path directory(ServerLevel level) {
        return level.getServer().getWorldPath(LevelResource.ROOT).resolve("terminalcraft-pocket-archives");
    }

    private static Path archiveFile(ServerLevel level, UUID archive) throws IOException {
        Path directory = directory(level);
        if (Files.isSymbolicLink(directory)) throw new IOException("Pocket archive directory must not be a symbolic link");
        Files.createDirectories(directory);
        Path file = directory.resolve(archive + ".dat");
        if (Files.isSymbolicLink(file)) throw new IOException("Pocket archive must not be a symbolic link");
        return file;
    }

    public static UUID exportHeld(ServerLevel level, ItemStack item) throws IOException {
        PocketComputerData source = PocketStorage.open(level, item);
        UUID archive = UUID.randomUUID();
        Path file = archiveFile(level, archive);
        if (!Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Archive collision; retry export");
        CompoundTag metadata = new CompoundTag();
        metadata.putString("Label", PocketTerminalItem.getLabel(item));
        metadata.putInt("PocketArchiveFormat", 1);
        PocketComputerData snapshot = new PocketComputerData();
        snapshot.initialize(source.readShell(), metadata);
        snapshot.persistVerified(file);
        return archive;
    }

    public static void importHeld(ServerLevel level, ItemStack item, UUID archive) throws IOException {
        CompoundTag original = requireBlank(item);
        Path file = archiveFile(level, archive);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 16L * 1024 * 1024) {
            throw new IOException("Archive missing, invalid or too large");
        }
        CompoundTag root;
        try (var stream = new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
            root = NbtIo.read(stream, new NbtAccounter(32L * 1024 * 1024));
        } catch (RuntimeException malformed) {
            throw new IOException("Archive exceeds decoding limits or is malformed", malformed);
        }
        CompoundTag data = root.getCompound("data");
        CompoundTag metadata = data.getCompound("LegacyBackup");
        if (metadata.getInt("PocketArchiveFormat") != 1) throw new IOException("Unsupported pocket archive format");
        CompoundTag shell = PocketComputerData.load(data).readShell();
        restoreShell(level, item, original, shell, metadata.getString("Label"));
    }

    public static void recoverOriginal(ServerLevel level, ItemStack item, UUID computer) throws IOException {
        CompoundTag original = requireBlank(item);
        PocketComputerData source = PocketComputerData.find(level, computer);
        CompoundTag backup = source == null ? null : source.readLegacyBackup();
        if (backup == null || !backup.contains(PocketTerminalItem.TAG_SHELL, Tag.TAG_COMPOUND)) {
            throw new IOException("No readable original migration snapshot for this computer; current files were not changed");
        }
        restoreShell(level, item, original, backup.getCompound(PocketTerminalItem.TAG_SHELL), backup.getString("Label"));
    }

    public record FileRecovery(int files, int skipped, String reportPath) {}

    /** Explicit operator copy from a loaded standalone computer; never loads chunks or repairs in place. */
    public static FileRecovery recoverBlockFiles(ServerLevel level, ItemStack item, net.minecraft.core.BlockPos pos) throws IOException {
        CompoundTag original = requireBlank(item);
        if (!level.getServer().isSameThread() || !level.hasChunkAt(pos)) throw new IOException("Source computer must be loaded on the server");
        var entity = level.getBlockEntity(pos);
        UUID identity;
        if (entity instanceof com.malice.terminalcraft.blockentity.TerminalBlockEntity terminal) identity = terminal.getDeviceId();
        else if (entity instanceof com.malice.terminalcraft.blockentity.TurtleBlockEntity turtle) identity = turtle.getDeviceId();
        else if (entity instanceof com.malice.terminalcraft.blockentity.ServerRackBlockEntity rack) identity = rack.getDeviceId();
        else throw new IOException("Source must be a terminal, turtle or server rack");
        if (entity.isRemoved()) throw new IOException("Source computer was removed");
        // save() returns the preserved raw snapshot when the computer is in recovery state.
        var source = ((com.malice.terminalcraft.shell.ShellComputer) entity).getShell().save();
        var recovered = PocketFileRecovery.recover(source, identity);
        restoreShell(level, item, original, recovered.shell(), "Recovered block files");
        return new FileRecovery(recovered.files(), recovered.skipped(), recovered.reportPath());
    }

    public static FileRecovery recoverFiles(ServerLevel level, ItemStack item, UUID computer) throws IOException {
        CompoundTag original = requireBlank(item);
        PocketComputerData source = PocketComputerData.find(level, computer);
        var recovered = PocketFileRecovery.recover(source == null ? null : source.readShell(), computer);
        restoreShell(level, item, original, recovered.shell(), "Recovered files");
        return new FileRecovery(recovered.files(), recovered.skipped(), recovered.reportPath());
    }

    private static CompoundTag requireBlank(ItemStack item) throws IOException {
        if (!(item.getItem() instanceof PocketTerminalItem) || item.isEmpty()) throw new IOException("Hold a blank pocket terminal");
        CompoundTag original = item.getTag() == null ? new CompoundTag() : item.getTag().copy();
        if (original.contains(PocketStorage.IDENTITY) || original.contains(PocketTerminalItem.TAG_SHELL)) {
            throw new IOException("Import requires a blank, unopened pocket; existing files will not be overwritten");
        }
        return original;
    }

    private static void restoreShell(ServerLevel level, ItemStack item, CompoundTag original,
                                     CompoundTag shell, String label) throws IOException {
        PocketStorage.validateWritableShell(shell);
        // Migrate a detached candidate; disk or validation failure cannot alter the held item.
        ItemStack candidate = item.copy();
        CompoundTag candidateTag = original.copy();
        candidateTag.put("Shell", shell.copy());
        candidate.setTag(candidateTag);
        PocketTerminalItem.setLabel(candidate, label);
        PocketStorage.open(level, candidate);
        item.setTag(candidate.getTag().copy());
    }
}
