package com.malice.terminalcraft.persistence;

import com.malice.terminalcraft.shell.BashShell;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;

/**
 * Headless characterization of additive schema markers and legacy shell/VFS compatibility.
 */
public final class PersistenceVersionCharacterizationTest {
    private PersistenceVersionCharacterizationTest() {}

    public static void main(String[] args) {
        assertEquals(PersistedDataVersions.LEGACY_VERSION,
                PersistedDataVersions.read(new CompoundTag()), "missing version is legacy");

        CompoundTag malformed = new CompoundTag();
        malformed.put(PersistedDataVersions.TAG_DATA_VERSION, StringTag.valueOf("invalid"));
        assertEquals(PersistedDataVersions.LEGACY_VERSION,
                PersistedDataVersions.read(malformed), "mistyped version is legacy");

        CompoundTag negative = new CompoundTag();
        negative.putInt(PersistedDataVersions.TAG_DATA_VERSION, -10);
        assertEquals(PersistedDataVersions.LEGACY_VERSION,
                PersistedDataVersions.read(negative), "negative version is legacy");

        BashShell current = new BashShell();
        current.runScriptText("cd /tmp; VERSIONED=yes; echo payload > versioned.txt; false");
        CompoundTag currentTag = current.save();
        assertEquals(PersistedDataVersions.CURRENT_VERSION,
                PersistedDataVersions.read(currentTag), "shell root version");
        assertEquals(PersistedDataVersions.CURRENT_VERSION,
                PersistedDataVersions.read(currentTag.getCompound("Vfs")), "VFS root version");

        // Existing worlds have no marker. Removing both markers models the pre-versioning format.
        CompoundTag legacyTag = currentTag.copy();
        legacyTag.remove(PersistedDataVersions.TAG_DATA_VERSION);
        legacyTag.getCompound("Vfs").remove(PersistedDataVersions.TAG_DATA_VERSION);

        BashShell restored = new BashShell();
        restored.load(legacyTag);
        assertEquals("/tmp", restored.getCwd(), "legacy cwd");
        assertEquals(1, restored.getLastExitCode(), "legacy exit status");
        assertEquals("yes", restored.executeForResult("echo $VERSIONED").outputLines().get(0),
                "legacy environment");
        assertEquals("payload\n", restored.getVfs().readFile("/tmp/versioned.txt"), "legacy VFS");
        assertEquals(null, restored.getVfs().readFile("/DataVersion"),
                "version metadata is not exposed as a VFS node");

        // Additive readers keep loading known fields if a newer writer adds fields/version metadata.
        CompoundTag futureTag = currentTag.copy();
        futureTag.putInt(PersistedDataVersions.TAG_DATA_VERSION,
                PersistedDataVersions.CURRENT_VERSION + 1);
        futureTag.putString("FutureField", "ignored");
        BashShell futureRestored = new BashShell();
        futureRestored.load(futureTag);
        assertEquals("payload\n", futureRestored.getVfs().readFile("/tmp/versioned.txt"),
                "future additive field");

        System.out.println("Persistence version characterization tests: OK");
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
