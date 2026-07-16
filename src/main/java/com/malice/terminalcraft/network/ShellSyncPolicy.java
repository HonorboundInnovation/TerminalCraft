package com.malice.terminalcraft.network;

import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/** Application-level byte budget for authoritative shell synchronization snapshots. */
public final class ShellSyncPolicy {
    /** Leaves headroom below Minecraft's two-megabyte default NBT accounting ceiling. */
    public static final int MAX_ENCODED_BYTES = 1_900_000;

    private ShellSyncPolicy() {}

    public static boolean isAdmissible(CompoundTag shellTag) {
        if (shellTag == null) return false;
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeNbt(shellTag);
            return buffer.readableBytes() <= MAX_ENCODED_BYTES;
        } catch (RuntimeException exception) {
            return false;
        } finally {
            buffer.release();
        }
    }
}
