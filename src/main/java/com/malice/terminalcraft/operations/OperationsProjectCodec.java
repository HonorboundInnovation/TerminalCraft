package com.malice.terminalcraft.operations;

import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.Objects;

/** Public, strict, size-bounded codec for Operations Project network documents. */
public final class OperationsProjectCodec {
    /** Keeps a complete project packet comfortably below Minecraft's NBT accounting ceiling. */
    public static final int MAX_ENCODED_BYTES = 512 * 1024;

    private OperationsProjectCodec() {}

    public static CompoundTag encode(OperationsProject project) {
        CompoundTag tag = OperationsProjectNbt.save(Objects.requireNonNull(project, "project"));
        if (!isAdmissible(tag)) throw new IllegalArgumentException("operations project exceeds packet budget");
        return tag;
    }

    public static OperationsProject decode(CompoundTag tag) {
        if (!isAdmissible(tag)) throw new IllegalArgumentException("operations project packet is invalid or oversized");
        return OperationsProjectNbt.load(tag);
    }

    public static boolean isAdmissible(CompoundTag tag) {
        if (tag == null) return false;
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeNbt(tag);
            return buffer.readableBytes() <= MAX_ENCODED_BYTES;
        } catch (RuntimeException exception) {
            return false;
        } finally {
            buffer.release();
        }
    }
}
