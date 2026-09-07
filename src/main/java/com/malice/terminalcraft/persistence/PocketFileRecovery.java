package com.malice.terminalcraft.persistence;

import com.malice.terminalcraft.shell.BashShell;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.util.UUID;

/** Explicit, conservative file salvage into a detached shell, never a rewrite of its source. */
final class PocketFileRecovery {
    private PocketFileRecovery() {}
    record Result(CompoundTag shell, int files, int skipped, String reportPath) {}

    static Result recover(CompoundTag source, UUID identity) throws IOException {
        if (source == null || PersistedDataVersions.classify(source) == PersistedDataVersions.Schema.FUTURE
                || !source.contains("Vfs", Tag.TAG_COMPOUND)) throw new IOException("No supported filesystem to recover");
        CompoundTag files = source.getCompound("Vfs");
        if (PersistedDataVersions.classify(files) == PersistedDataVersions.Schema.FUTURE) {
            throw new IOException("Newer filesystem format; update the mod before recovery");
        }
        var recovered = new BashShell.VirtualFileSystem();
        recovered.clearAll();
        String reportPath;
        do { reportPath = "/recovery-" + UUID.randomUUID() + ".txt"; } while (files.contains(reportPath));
        // Reserve one node and enough character capacity for the report before selecting files.
        recovered.writeFile(reportPath, " ".repeat(60_000));
        var probe = new BashShell.VirtualFileSystem();
        var keys = PersistedDataLimits.boundedSortedKeys(files, PersistedDataLimits.MAX_VFS_NODES + 1);
        int accepted = 0, fileCount = 0;
        StringBuilder details = new StringBuilder();
        int omitted = 0;
        // Directories first: conflicts never cause a file to overwrite a recovered directory.
        for (boolean directories : new boolean[] {true, false}) {
            for (String path : keys) {
                if (PersistedDataVersions.TAG_DATA_VERSION.equals(path)) continue;
                boolean directory = files.contains(path, Tag.TAG_COMPOUND) && files.getCompound(path).getBoolean("Dir");
                if (directory != directories) continue;
                String reason = null;
                if (!path.startsWith("/") || path.length() > PersistedDataLimits.MAX_PATH_CHARS
                        || !path.equals(probe.resolve("/", path)) || !files.contains(path, Tag.TAG_COMPOUND)
                        || (path.equals("/") && !directory)) reason = "invalid path or entry";
                if (reason == null) {
                    CompoundTag isolated = new CompoundTag();
                    CompoundTag root = new CompoundTag();
                    root.putBoolean("Dir", true);
                    isolated.put("/", root);
                    isolated.put(path, files.getCompound(path).copy());
                    probe.load(isolated);
                    if (!files.getCompound(path).equals(probe.save().get(path))) reason = "unsupported or lossy file encoding";
                    else if (!(directory ? recovered.mkdirs(path) : recovered.writeFile(path, probe.readFile(path)))) {
                        reason = "capacity limit or parent conflict";
                    }
                }
                if (reason == null) {
                    accepted++;
                    if (!directory) fileCount++;
                } else {
                    String line = "Skipped " + PersistedDataLimits.truncate(path, 256).replace('\n', ' ').replace('\r', ' ')
                            + ": " + reason + "\n";
                    if (details.length() + line.length() <= 58_000) details.append(line);
                    else omitted++;
                }
            }
        }
        int total = files.size() - (files.contains(PersistedDataVersions.TAG_DATA_VERSION) ? 1 : 0);
        int skipped = total - accepted;
        // The schema marker is metadata, not an omitted filesystem entry.
        int selectedEntries = keys.size() - (keys.contains(PersistedDataVersions.TAG_DATA_VERSION) ? 1 : 0);
        int unexamined = Math.max(0, total - selectedEntries);
        String report = "Pocket file recovery\nSource computer: " + identity
                + "\nOriginal record and backup unchanged. This is a separate copy.\nRecovered files: " + fileCount
                + "\nSkipped entries: " + skipped + "\nUnexamined entries beyond selection limit: " + unexamined
                + "\nAdditional omitted diagnostic lines: " + omitted
                + "\nOnly exact readable files copied; no truncated fragments. Shell/editor/session state was reset.\n\n" + details;
        if (!recovered.writeFile(reportPath, report)) throw new IOException("Recovery report could not be preserved");
        CompoundTag shell = new BashShell().save();
        shell.put("Vfs", recovered.save());
        PocketStorage.validateWritableShell(shell);
        return new Result(shell, fileCount, skipped, reportPath);
    }
}
