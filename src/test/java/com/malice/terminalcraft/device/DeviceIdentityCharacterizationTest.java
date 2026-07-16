package com.malice.terminalcraft.device;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;

import java.util.UUID;

/** Headless characterization tests for stable device identity persistence. */
public final class DeviceIdentityCharacterizationTest {
    private DeviceIdentityCharacterizationTest() {}

    public static void main(String[] args) {
        UUID original = UUID.fromString("11111111-2222-3333-4444-555555555555");
        CompoundTag saved = new CompoundTag();
        DeviceIdentity.save(saved, original);
        assertTrue(saved.hasUUID(DeviceIdentity.TAG_DEVICE_ID), "canonical UUID tag written");
        assertEquals(original, DeviceIdentity.loadOrRetain(saved, UUID.randomUUID()),
                "saved identity round trip");

        UUID legacyFallback = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        assertEquals(legacyFallback, DeviceIdentity.loadOrRetain(new CompoundTag(), legacyFallback),
                "legacy data retains generated identity");

        CompoundTag malformed = new CompoundTag();
        malformed.put(DeviceIdentity.TAG_DEVICE_ID, StringTag.valueOf("not-a-uuid"));
        assertEquals(legacyFallback, DeviceIdentity.loadOrRetain(malformed, legacyFallback),
                "malformed identity is non-fatal");

        UUID generatedA = DeviceIdentity.create();
        UUID generatedB = DeviceIdentity.create();
        assertTrue(!generatedA.equals(generatedB), "new devices receive distinct identities");

        // Turtle movement transfers saveWithoutMetadata() into the replacement block entity.
        CompoundTag movementPayload = new CompoundTag();
        DeviceIdentity.save(movementPayload, original);
        UUID replacementConstructorId = DeviceIdentity.create();
        assertEquals(original, DeviceIdentity.loadOrRetain(movementPayload, replacementConstructorId),
                "movement payload replaces constructor identity");

        System.out.println("Device identity characterization tests: OK");
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
