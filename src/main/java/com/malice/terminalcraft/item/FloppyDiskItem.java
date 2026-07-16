package com.malice.terminalcraft.item;

import com.malice.terminalcraft.persistence.PersistedDataLimits;
import com.malice.terminalcraft.persistence.PersistedDataVersions;
import com.malice.terminalcraft.shell.BashShell;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Portable virtual filesystem media. Insert into a Disk Drive and {@code mount}
 * from an adjacent terminal/turtle shell.
 */
public class FloppyDiskItem extends Item {
    public static final String TAG_LABEL = "DiskLabel";
    public static final String TAG_VFS = "Vfs";
    public static final String DEFAULT_LABEL = "floppy";

    public FloppyDiskItem() {
        super(new Item.Properties().stacksTo(1));
    }

    public static String getDiskLabel(ItemStack stack) {
        if (stack.isEmpty()) {
            return DEFAULT_LABEL;
        }
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(TAG_LABEL)) {
            String label = PersistedDataLimits.readString(tag, TAG_LABEL, PersistedDataLimits.MAX_LABEL_CHARS, "");
            if (!label.isBlank()) {
                return label.trim();
            }
        }
        return DEFAULT_LABEL;
    }

    public static void setDiskLabel(ItemStack stack, String label) {
        if (stack.isEmpty()) {
            return;
        }
        String clean = label == null || label.isBlank() ? DEFAULT_LABEL
                : PersistedDataLimits.truncate(label.trim(), PersistedDataLimits.MAX_LABEL_CHARS);
        CompoundTag tag = stack.getOrCreateTag();
        PersistedDataVersions.stampCurrent(tag);
        tag.putString(TAG_LABEL, clean);
    }

    /** Ensure a crafted/fresh disk has a starter program library. */
    public static void ensureInitialized(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        CompoundTag tag = stack.getOrCreateTag();
        PersistedDataVersions.stampCurrent(tag);
        if (!tag.contains(TAG_LABEL)) {
            tag.putString(TAG_LABEL, DEFAULT_LABEL);
        }
        if (!tag.contains(TAG_VFS, CompoundTag.TAG_COMPOUND)) {
            tag.put(TAG_VFS, createDefaultLibrary());
        }
    }

    public static CompoundTag getVfsTag(ItemStack stack) {
        ensureInitialized(stack);
        return canonicalizeVfs(stack.getOrCreateTag().getCompound(TAG_VFS));
    }

    public static void setVfsTag(ItemStack stack, CompoundTag vfs) {
        if (stack.isEmpty()) {
            return;
        }
        CompoundTag tag = stack.getOrCreateTag();
        PersistedDataVersions.stampCurrent(tag);
        if (!tag.contains(TAG_LABEL)) {
            tag.putString(TAG_LABEL, DEFAULT_LABEL);
        }
        tag.put(TAG_VFS, canonicalizeVfs(vfs == null ? new CompoundTag() : vfs));
    }

    private static CompoundTag canonicalizeVfs(CompoundTag candidate) {
        BashShell.VirtualFileSystem bounded = new BashShell.VirtualFileSystem();
        bounded.clearAll();
        bounded.load(candidate);
        CompoundTag repaired = bounded.save();
        // Preserve bounded scalar media metadata used by public disk-drive callers. Path-shaped
        // entries remain owned exclusively by the VFS validator.
        for (String key : PersistedDataLimits.boundedSortedKeys(candidate,
                PersistedDataLimits.MAX_VFS_NODES)) {
            if (key.startsWith("/") || PersistedDataVersions.TAG_DATA_VERSION.equals(key)
                    || key.length() > PersistedDataLimits.MAX_PATH_CHARS) continue;
            if (candidate.contains(key, net.minecraft.nbt.Tag.TAG_STRING)) {
                repaired.putString(key, PersistedDataLimits.readString(candidate, key,
                        PersistedDataLimits.MAX_VFS_FILE_CHARS, ""));
            } else if (candidate.contains(key, net.minecraft.nbt.Tag.TAG_BYTE)) {
                repaired.putByte(key, candidate.getByte(key));
            } else if (candidate.contains(key, net.minecraft.nbt.Tag.TAG_SHORT)) {
                repaired.putShort(key, candidate.getShort(key));
            } else if (candidate.contains(key, net.minecraft.nbt.Tag.TAG_INT)) {
                repaired.putInt(key, candidate.getInt(key));
            } else if (candidate.contains(key, net.minecraft.nbt.Tag.TAG_LONG)) {
                repaired.putLong(key, candidate.getLong(key));
            } else if (candidate.contains(key, net.minecraft.nbt.Tag.TAG_FLOAT)) {
                repaired.putFloat(key, candidate.getFloat(key));
            } else if (candidate.contains(key, net.minecraft.nbt.Tag.TAG_DOUBLE)) {
                repaired.putDouble(key, candidate.getDouble(key));
            }
        }
        return repaired;
    }

    /**
     * Starter library shipped on every new floppy.
     * Kept as VFS NBT so mount/import reuses the shell filesystem format.
     */
    public static CompoundTag createDefaultLibrary() {
        BashShell.VirtualFileSystem vfs = new BashShell.VirtualFileSystem();
        // Clear machine defaults; floppies only carry portable programs/docs.
        vfs.clearAll();
        vfs.mkdirs("/");
        vfs.mkdirs("/programs");
        vfs.mkdirs("/docs");
        vfs.writeFile("/README.txt",
                "TerminalCraft Floppy Disk\n" +
                "------------------------\n" +
                "1. Place a Disk Drive next to a Terminal or Turtle\n" +
                "2. Insert this floppy into the drive (right-click)\n" +
                "3. In the shell:  mount\n" +
                "4. Browse:        ls /disk\n" +
                "5. Run:           source /disk/programs/hello.sh\n" +
                "6. Edit/create:   edit /disk/programs/mine.sh\n" +
                "                  type lines, then :wq to save+quit\n" +
                "7. Unmount:       umount\n");
        vfs.writeFile("/programs/hello.sh",
                "#!/bin/bash\n" +
                "echo \"Hello from floppy media!\"\n" +
                "echo \"User=$USER host label via: label\"\n");
        vfs.writeFile("/programs/blink.sh",
                "#!/bin/bash\n" +
                "# Toggle front redstone a few times (requires terminal redstone)\n" +
                "for i in 1 2 3 4; do\n" +
                "  rs set front 15\n" +
                "  rs set front 0\n" +
                "done\n" +
                "echo blink-done\n");
        vfs.writeFile("/programs/turtle_square.sh",
                "#!/bin/bash\n" +
                "# Walk a small square (turtle only)\n" +
                "for i in 1 2 3 4; do\n" +
                "  turtle forward\n" +
                "  turtle turn right\n" +
                "done\n" +
                "echo square-complete\n");
        vfs.writeFile("/programs/monitor_banner.sh",
                "#!/bin/bash\n" +
                "monitor clear\n" +
                "monitor write \"TerminalCraft\"\n" +
                "monitor write \"disk library online\"\n" +
                "echo banner-sent\n");
        vfs.writeFile("/programs/rednet_ping.sh",
                "#!/bin/bash\n" +
                "# Open channel 1, send a ping, show inbox\n" +
                "modem open 1\n" +
                "modem send 1 1 \"ping from $USER\"\n" +
                "modem recv\n");
        vfs.writeFile("/docs/commands.txt",
                "Useful TerminalCraft commands\n" +
                "=============================\n" +
                "help, ls, cd, cat, write, source\n" +
                "edit/nano <file>  graphical script editor\n" +
                "  Ctrl+S save; use the GUI buttons to save, close, or discard\n" +
                "redstone/rs, peripheral, label\n" +
                "turtle/tu, monitor, modem/rednet\n" +
                "mount, umount, disk\n" +
                "Operators: ; && || | > >> <\n" +
                "Control: if/then/else/fi for/do/done while/do/done\n");
        vfs.writeFile("/docs/editor.txt",
                "Writing your own scripts\n" +
                "------------------------\n" +
                "1. mount               # attach floppy at /disk\n" +
                "2. edit /disk/programs/myscript.sh\n" +
                "3. edit normally in the graphical multiline editor\n" +
                "4. press Ctrl+S         # save (auto-syncs floppy)\n" +
                "5. click Save & Close   # return to terminal\n" +
                "6. source /disk/programs/myscript.sh\n" +
                "7. umount              # flush + detach\n" +
                "\n" +
                "Editor shortcuts: Ctrl+S save, Ctrl+Shift+S save+close, Ctrl+Z/Y undo/redo\n");
        vfs.writeFile("/docs/gps_notes.txt",
                "GPS / positioning notes\n" +
                "-----------------------\n" +
                "TerminalCraft does not call real-world networks.\n" +
                "Use turtle inspect / redstone / rednet for in-world coordination.\n" +
                "A future GPS tower peripheral can publish coordinates over rednet.\n");
        return vfs.save();
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        ensureInitialized(stack);
        tooltip.add(Component.literal("Label: " + getDiskLabel(stack)).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.literal("Insert into a Disk Drive, then `mount`").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public Component getName(ItemStack stack) {
        String label = getDiskLabel(stack);
        if (DEFAULT_LABEL.equals(label)) {
            return super.getName(stack);
        }
        return Component.translatable(this.getDescriptionId(stack)).append(" (" + label + ")");
    }
}
