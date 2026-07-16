package com.malice.terminalcraft.item;

import com.malice.terminalcraft.persistence.PersistedDataLimits;
import com.malice.terminalcraft.persistence.PersistedDataVersions;
import com.malice.terminalcraft.shell.BashShell;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Headless characterization for standalone item-backed computer and media persistence. */
public final class StandaloneItemLifecycleCharacterizationTest {
    private StandaloneItemLifecycleCharacterizationTest() {}

    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        pocketTerminalRoundTripPreservesComputerAndModemState();
        pocketTerminalCorruptionAndOversizeAreBounded();
        floppyRoundTripPreservesLabelAndWritableFilesystem();
        floppyCorruptionAndOversizeAreCanonicalized();
        System.out.println("Standalone item lifecycle characterization tests: OK");
    }

    private static void pocketTerminalRoundTripPreservesComputerAndModemState() {
        ItemStack pocket = new ItemStack(Items.PAPER);
        PocketTerminalItem.setLabel(pocket, "field-unit");
        UUID modemId = PocketTerminalItem.getOrCreateModemId(pocket);

        List<Integer> requested = new ArrayList<>();
        requested.add(-20);
        for (int channel = 0; channel < PocketTerminalItem.MAX_MODEM_CHANNELS + 20; channel++) {
            requested.add(channel);
        }
        requested.add(70_000);
        PocketTerminalItem.setOpenChannels(pocket, requested);

        BashShell shell = new BashShell();
        shell.runScriptText("cd /tmp; POCKET_STATE=ready; echo portable > state.txt; false");
        PocketTerminalItem.saveShell(pocket, shell);

        ItemStack restoredStack = pocket.copy();
        BashShell restored = PocketTerminalItem.loadShell(restoredStack);
        assertEquals(PersistedDataVersions.CURRENT_VERSION,
                PersistedDataVersions.read(restoredStack.getTag()), "pocket root version");
        assertEquals("field-unit", PocketTerminalItem.getLabel(restoredStack), "pocket label");
        assertEquals(modemId, PocketTerminalItem.getOrCreateModemId(restoredStack), "pocket modem identity");
        assertEquals(PocketTerminalItem.MAX_MODEM_CHANNELS,
                PocketTerminalItem.getOpenChannels(restoredStack).size(), "bounded pocket channels");
        assertEquals(0, PocketTerminalItem.getOpenChannels(restoredStack).get(0), "negative channel clamp");
        assertEquals("/tmp", restored.getCwd(), "pocket cwd");
        assertEquals(1, restored.getLastExitCode(), "pocket exit status");
        assertEquals("ready", restored.executeForResult("echo $POCKET_STATE").outputLines().get(0),
                "pocket environment");
        assertEquals("portable\n", restored.getVfs().readFile("/tmp/state.txt"), "pocket VFS");
    }

    private static void pocketTerminalCorruptionAndOversizeAreBounded() {
        ItemStack pocket = new ItemStack(Items.PAPER);
        CompoundTag tag = pocket.getOrCreateTag();
        tag.putString(PocketTerminalItem.TAG_LABEL, "x".repeat(PersistedDataLimits.MAX_LABEL_CHARS + 10));
        int[] channels = new int[PocketTerminalItem.MAX_MODEM_CHANNELS + 500];
        for (int i = 0; i < channels.length; i++) channels[i] = i - 10;
        tag.put(PocketTerminalItem.TAG_MODEM_CHANNELS, new IntArrayTag(channels));
        tag.put(PocketTerminalItem.TAG_SHELL, StringTag.valueOf("wrong-type"));

        assertEquals(PersistedDataLimits.MAX_LABEL_CHARS,
                PocketTerminalItem.getLabel(pocket).length(), "oversized pocket label");
        assertEquals(PocketTerminalItem.MAX_MODEM_CHANNELS,
                PocketTerminalItem.getOpenChannels(pocket).size(), "oversized pocket channel array");
        assertEquals("/home/player", PocketTerminalItem.loadShell(pocket).getCwd(),
                "mistyped pocket shell fallback");
    }

    private static void floppyRoundTripPreservesLabelAndWritableFilesystem() {
        ItemStack floppy = new ItemStack(Items.PAPER);
        FloppyDiskItem.ensureInitialized(floppy);
        FloppyDiskItem.setDiskLabel(floppy, "automation");

        BashShell.VirtualFileSystem vfs = new BashShell.VirtualFileSystem();
        vfs.clearAll();
        vfs.mkdirs("/programs");
        vfs.writeFile("/programs/restart.sh", "echo restored\n");
        FloppyDiskItem.setVfsTag(floppy, vfs.save());

        ItemStack restoredStack = floppy.copy();
        BashShell.VirtualFileSystem restored = new BashShell.VirtualFileSystem();
        restored.load(FloppyDiskItem.getVfsTag(restoredStack));
        assertEquals(PersistedDataVersions.CURRENT_VERSION,
                PersistedDataVersions.read(restoredStack.getTag()), "floppy root version");
        assertEquals("automation", FloppyDiskItem.getDiskLabel(restoredStack), "floppy label");
        assertEquals("echo restored\n", restored.readFile("/programs/restart.sh"), "floppy VFS");

        restored.writeFile("/programs/after-reload.sh", "echo writable\n");
        FloppyDiskItem.setVfsTag(restoredStack, restored.save());
        BashShell.VirtualFileSystem secondReload = new BashShell.VirtualFileSystem();
        secondReload.load(FloppyDiskItem.getVfsTag(restoredStack.copy()));
        assertEquals("echo writable\n", secondReload.readFile("/programs/after-reload.sh"),
                "floppy remains writable after reload");
    }

    private static void floppyCorruptionAndOversizeAreCanonicalized() {
        ItemStack floppy = new ItemStack(Items.PAPER);
        CompoundTag rawVfs = new CompoundTag();
        rawVfs.put("/", directory());
        rawVfs.put("/000-huge", file("z".repeat(PersistedDataLimits.MAX_VFS_FILE_CHARS + 20)));
        rawVfs.putString("/wrong-type", "ignored");
        rawVfs.put("relative", file("ignored"));
        FloppyDiskItem.setVfsTag(floppy, rawVfs);

        CompoundTag stored = floppy.getOrCreateTag().getCompound(FloppyDiskItem.TAG_VFS);
        assertEquals(PersistedDataVersions.CURRENT_VERSION,
                PersistedDataVersions.read(stored), "nested floppy VFS version");
        assertTrue(!stored.contains("/wrong-type"), "wrong-type floppy node removed");
        assertTrue(!stored.contains("relative"), "relative floppy node removed");
        assertEquals(PersistedDataLimits.MAX_VFS_FILE_CHARS,
                stored.getCompound("/000-huge").getString("Data").length(),
                "oversized floppy file truncated");
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
