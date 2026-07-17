package com.malice.terminalcraft.shell;

import com.malice.terminalcraft.persistence.PersistedDataLimits;
import com.malice.terminalcraft.persistence.PersistedDataVersions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bounded in-memory hierarchical filesystem used by shell and removable media state. */
public class VirtualFileSystem {
    private final Map<String, Node> nodes = new HashMap<>();

    public VirtualFileSystem() {
        resetDefaults();
    }

    private void resetDefaults() {
        nodes.clear();
        mkdirRaw("/");
        mkdirRaw("/home");
        mkdirRaw("/home/player");
        mkdirRaw("/home/player/programs");
        mkdirRaw("/bin");
        mkdirRaw("/usr");
        mkdirRaw("/usr/bin");
        mkdirRaw("/tmp");
        writeRaw("/home/player/README.txt",
                "Welcome to TerminalCraft!\n" +
                "This is a bash-style shell running inside Minecraft.\n" +
                "Try:\n" +
                "  ls /bin\n" +
                "  hello\n" +
                "  source ~/programs/demo.sh\n" +
                "  if [ -f README.txt ]; then echo yes; fi\n" +
                "  for i in 1 2 3; do echo $i; done\n" +
                "  echo hi > note.txt && cat note.txt\n");
        writeRaw("/home/player/.bashrc", "export TERM=terminalcraft\n");
        installMachinePrograms();
        writeRaw("/home/player/programs/demo.sh",
                "#!/bin/bash\n" +
                "echo \"Demo script running as $USER\"\n" +
                "echo \"CWD=$PWD\"\n" +
                "if [ -d /bin ]; then\n" +
                "  echo \"/bin is present\"\n" +
                "fi\n" +
                "for f in README.txt .bashrc; do\n" +
                "  echo \"checking $f\"\n" +
                "done\n" +
                "echo demo-complete\n");
        writeRaw("/bin/hello",
                "#!/bin/bash\n" +
                "echo \"Hello from /bin/hello, $USER!\"\n" +
                "echo \"Args: $1 $2 $3\"\n");
        writeRaw("/bin/greet",
                "#!/bin/bash\n" +
                "name=$1\n" +
                "if [ -z \"$name\" ]; then\n" +
                "  name=world\n" +
                "fi\n" +
                "echo \"Greetings, $name.\"\n");
        writeRaw("/usr/bin/id",
                "#!/bin/bash\n" +
                "echo \"uid=1000($USER) gid=1000($USER)\"\n");
        mkdirRaw("/usr/share");
        mkdirRaw("/usr/share/doc");
        mkdirRaw("/mnt");
        writeRaw("/usr/share/doc/terminalcraft.txt",
                "TerminalCraft handbook\n" +
                "=====================\n" +
                "Blocks: terminal, turtle, monitor, modem, disk_drive\n" +
                "Items: floppy_disk, pocket_terminal\n" +
                "Mount floppies with: mount / umount / disk\n" +
                "Pocket terminal: right-click item to open bash\n");
        writeRaw("/home/player/programs/lib_demo.sh",
                "#!/bin/bash\n" +
                "echo \"library demo\"\n" +
                "if [ -d /disk ]; then\n" +
                "  echo \"disk mounted\"\n" +
                "  ls /disk\n" +
                "else\n" +
                "  echo \"no disk; insert floppy + mount\"\n" +
                "fi\n");
    }


    /**
     * Add bundled machine programs without replacing scripts edited by the player.
     * Called after loading a terminal so existing worlds receive newly shipped diagnostics.
     */
    void installMachinePrograms() {
        mkdirs("/home/player/programs");
        String adaptive =
                "#!/bin/bash\n" +
                "# Detects and tests any rectangular monitor wall from 1x1 through 8x6.\n" +
                "monitor demo any\n";
        writeRawIfMissing("/home/player/programs/monitor_wall_auto.sh", adaptive);

        // Upgrade only recognizable stock versions. Player-created replacements are preserved.
        installOrUpgradeBundledProgram("/home/player/programs/monitor_demo.sh", adaptive,
                "# General 2x2 monitor-wall smoke test");
        installOrUpgradeBundledProgram("/home/player/programs/monitor_wall_horizontal.sh", adaptive,
                "# Best viewed on a 2x1 wall");
        installOrUpgradeBundledProgram("/home/player/programs/monitor_wall_vertical.sh", adaptive,
                "# Best viewed on a 1x2 wall");
        installOrUpgradeBundledProgram("/home/player/programs/monitor_wall_grid.sh", adaptive,
                "# Full 2x2 wall test");
    }

    /** Wipe all nodes (used for portable media construction). */
    public void clearAll() {
        nodes.clear();
        mkdirRaw("/");
    }

    /**
     * Import a portable VFS snapshot under mountPoint (typically /disk).
     * Paths on media are re-rooted under the mount point.
     */
    public boolean mountDisk(String mountPoint, CompoundTag media) {
        if (media == null) {
            return false;
        }
        String mount = normalize(mountPoint);
        if (nodes.containsKey(mount)) {
            return false;
        }
        mkdirs(mount);
        VirtualFileSystem tmp = new VirtualFileSystem();
        tmp.clearAll();
        tmp.load(media);
        for (Map.Entry<String, Node> e : tmp.nodes.entrySet()) {
            String src = e.getKey();
            if ("/".equals(src)) {
                continue;
            }
            String dest = mount + src;
            Node n = e.getValue();
            if (n.directory) {
                mkdirs(dest);
            } else {
                ensureParent(dest);
                nodes.put(normalize(dest), Node.file(n.content));
            }
        }
        return true;
    }

    /** Remove a mount point and all nested nodes. */
    public void unmountDisk(String mountPoint) {
        String mount = normalize(mountPoint);
        String prefix = mount.equals("/") ? "/" : mount + "/";
        List<String> toRemove = new ArrayList<>();
        for (String key : nodes.keySet()) {
            if (key.equals(mount) || key.startsWith(prefix)) {
                toRemove.add(key);
            }
        }
        for (String key : toRemove) {
            nodes.remove(key);
        }
    }

    /**
     * Export the subtree at mountPoint as a portable VFS snapshot
     * with the mount prefix stripped (so media root is /).
     */
    public CompoundTag exportMount(String mountPoint) {
        String mount = normalize(mountPoint);
        if (!isDirectory(mount)) {
            return null;
        }
        VirtualFileSystem out = new VirtualFileSystem();
        out.clearAll();
        String prefix = mount.equals("/") ? "" : mount;
        for (Map.Entry<String, Node> e : nodes.entrySet()) {
            String key = e.getKey();
            if (key.equals(mount)) {
                continue;
            }
            if (!key.startsWith(mount + "/") && !mount.equals("/")) {
                continue;
            }
            String rel = mount.equals("/") ? key : key.substring(mount.length());
            if (rel.isEmpty()) {
                continue;
            }
            if (!rel.startsWith("/")) {
                rel = "/" + rel;
            }
            Node n = e.getValue();
            if (n.directory) {
                out.mkdirs(rel);
            } else {
                out.writeFile(rel, n.content);
            }
        }
        return out.save();
    }

    public String resolve(String cwd, String path) {
        if (path == null || path.isEmpty()) {
            return cwd;
        }
        if (path.startsWith("~/")) {
            path = "/home/player/" + path.substring(2);
        } else if ("~".equals(path)) {
            path = "/home/player";
        }
        String raw = path.startsWith("/") ? path : (cwd.endsWith("/") ? cwd + path : cwd + "/" + path);
        return normalize(raw);
    }

    private String normalize(String path) {
        String[] parts = path.split("/");
        List<String> stack = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                if (!stack.isEmpty()) {
                    stack.remove(stack.size() - 1);
                }
                continue;
            }
            stack.add(part);
        }
        if (stack.isEmpty()) {
            return "/";
        }
        return "/" + String.join("/", stack);
    }

    public boolean isDirectory(String path) {
        Node n = nodes.get(normalize(path));
        return n != null && n.directory;
    }

    public List<String> list(String path) {
        String dir = normalize(path);
        Node n = nodes.get(dir);
        if (n == null || !n.directory) {
            return null;
        }
        String prefix = dir.equals("/") ? "/" : dir + "/";
        List<String> names = new ArrayList<>();
        for (String key : nodes.keySet()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            String rest = key.substring(prefix.length());
            if (rest.isEmpty() || rest.contains("/")) {
                continue;
            }
            Node child = nodes.get(key);
            names.add(child.directory ? rest + "/" : rest);
        }
        Collections.sort(names);
        return names;
    }

    public String readFile(String path) {
        Node n = nodes.get(normalize(path));
        if (n == null || n.directory) {
            return null;
        }
        return n.content;
    }

    public boolean writeFile(String path, String content) {
        path = normalize(path);
        if ("/".equals(path)) {
            return false;
        }
        ensureParent(path);
        Node n = nodes.get(path);
        if (n != null && n.directory) {
            return false;
        }
        nodes.put(path, Node.file(content == null ? "" : content));
        return true;
    }

    public boolean touch(String path) {
        path = normalize(path);
        if (nodes.containsKey(path)) {
            return !nodes.get(path).directory;
        }
        return writeFile(path, "");
    }

    public boolean mkdir(String path) {
        path = normalize(path);
        if (nodes.containsKey(path)) {
            return nodes.get(path).directory;
        }
        int idx = path.lastIndexOf('/');
        if (idx > 0) {
            String parent = path.substring(0, idx);
            if (!nodes.containsKey(parent) || !nodes.get(parent).directory) {
                return false;
            }
        }
        nodes.put(path, Node.dir());
        return true;
    }

    public boolean mkdirs(String path) {
        path = normalize(path);
        if ("/".equals(path)) {
            return true;
        }
        String[] parts = path.split("/");
        StringBuilder cur = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            cur.append('/').append(part);
            String p = cur.toString();
            Node n = nodes.get(p);
            if (n == null) {
                nodes.put(p, Node.dir());
            } else if (!n.directory) {
                return false;
            }
        }
        return true;
    }

    public boolean rm(String path) {
        path = normalize(path);
        if ("/".equals(path)) {
            return false;
        }
        Node n = nodes.get(path);
        if (n == null) {
            return false;
        }
        if (n.directory) {
            String prefix = path + "/";
            boolean hasChildren = nodes.keySet().stream().anyMatch(k -> k.startsWith(prefix));
            if (hasChildren) {
                return false;
            }
        }
        nodes.remove(path);
        return true;
    }

    public boolean rmrf(String path) {
        path = normalize(path);
        if ("/".equals(path)) {
            return false;
        }
        if (!nodes.containsKey(path)) {
            return false;
        }
        String prefix = path + "/";
        List<String> toRemove = new ArrayList<>();
        for (String key : nodes.keySet()) {
            if (key.equals(path) || key.startsWith(prefix)) {
                toRemove.add(key);
            }
        }
        for (String key : toRemove) {
            nodes.remove(key);
        }
        return true;
    }

    private void ensureParent(String path) {
        int idx = path.lastIndexOf('/');
        if (idx <= 0) {
            mkdirRaw("/");
            return;
        }
        String parent = path.substring(0, idx);
        if (parent.isEmpty()) {
            parent = "/";
        }
        if (!nodes.containsKey(parent)) {
            mkdirs(parent);
        }
    }

    private void mkdirRaw(String path) {
        nodes.put(normalize(path), Node.dir());
    }

    private void writeRaw(String path, String content) {
        nodes.put(normalize(path), Node.file(content));
    }

    private void writeRawIfMissing(String path, String content) {
        nodes.putIfAbsent(normalize(path), Node.file(content));
    }

    private void installOrUpgradeBundledProgram(String path, String content, String legacyMarker) {
        String normalized = normalize(path);
        Node existing = nodes.get(normalized);
        if (existing == null || (!existing.directory && existing.content.contains(legacyMarker))) {
            nodes.put(normalized, Node.file(content));
        }
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        PersistedDataVersions.stampCurrent(tag);
        int totalChars = 0;
        int nodeCount = 0;
        List<String> keys = new ArrayList<>(nodes.keySet());
        keys.sort(String::compareTo);
        for (String key : keys) {
            if (nodeCount >= PersistedDataLimits.MAX_VFS_NODES) break;
            if (key.length() > PersistedDataLimits.MAX_PATH_CHARS) continue;
            Node node = nodes.get(key);
            CompoundTag n = new CompoundTag();
            n.putBoolean("Dir", node.directory);
            if (!node.directory) {
                int remaining = PersistedDataLimits.MAX_VFS_TOTAL_CHARS - totalChars;
                if (remaining <= 0) continue;
                String data = PersistedDataLimits.truncate(node.content,
                        Math.min(PersistedDataLimits.MAX_VFS_FILE_CHARS, remaining));
                totalChars += data.length();
                n.putString("Data", data);
            }
            tag.put(key, n);
            nodeCount++;
        }
        return tag;
    }

    public void load(CompoundTag tag) {
        nodes.clear();
        int totalChars = 0;
        for (String storedKey : PersistedDataLimits.boundedSortedKeys(tag,
                PersistedDataLimits.MAX_VFS_NODES + 1)) {
            if (PersistedDataVersions.TAG_DATA_VERSION.equals(storedKey)
                    || !tag.contains(storedKey, Tag.TAG_COMPOUND)
                    || storedKey.length() > PersistedDataLimits.MAX_PATH_CHARS
                    || !storedKey.startsWith("/")) {
                continue;
            }
            String key = normalize(storedKey);
            if (key.length() > PersistedDataLimits.MAX_PATH_CHARS) continue;
            CompoundTag n = tag.getCompound(storedKey);
            if (n.getBoolean("Dir")) {
                nodes.putIfAbsent(key, Node.dir());
            } else if (n.contains("Data", Tag.TAG_STRING)) {
                int remaining = PersistedDataLimits.MAX_VFS_TOTAL_CHARS - totalChars;
                if (remaining <= 0) continue;
                String data = PersistedDataLimits.readString(n, "Data",
                        Math.min(PersistedDataLimits.MAX_VFS_FILE_CHARS, remaining), "");
                totalChars += data.length();
                nodes.putIfAbsent(key, Node.file(data));
            }
        }
        if (!nodes.containsKey("/")) {
            resetDefaults();
        }
    }

    private static final class Node {
        final boolean directory;
        final String content;

        private Node(boolean directory, String content) {
            this.directory = directory;
            this.content = content;
        }

        static Node dir() {
            return new Node(true, "");
        }

        static Node file(String content) {
            return new Node(false, content == null ? "" : content);
        }
    }
}
