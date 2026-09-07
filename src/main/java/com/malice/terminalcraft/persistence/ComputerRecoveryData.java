package com.malice.terminalcraft.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.UUID;

/** One world-local recovery record per computer, loaded lazily from overworld saved data. */
public final class ComputerRecoveryData extends SavedData {
    private UUID ticket;
    private String item = "";
    private CompoundTag payload = new CompoundTag();
    private boolean consumed;

    public static ComputerRecoveryData get(ServerLevel level, UUID computer) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(
                ComputerRecoveryData::load, ComputerRecoveryData::new, "terminalcraft_computer_" + computer);
    }

    public static ComputerRecoveryData load(CompoundTag tag) {
        ComputerRecoveryData result = new ComputerRecoveryData();
        result.ticket = tag.hasUUID("Ticket") ? tag.getUUID("Ticket") : null;
        result.item = tag.getString("Item");
        result.payload = tag.getCompound("Payload").copy();
        result.consumed = tag.getBoolean("Consumed");
        return result;
    }

    public UUID capture(String item, CompoundTag payload) {
        this.ticket = UUID.randomUUID();
        this.item = item;
        this.payload = payload.copy();
        consumed = false;
        setDirty();
        return ticket;
    }

    public CompoundTag recover(UUID ticket, String item) {
        return !consumed && ticket != null && ticket.equals(this.ticket) && this.item.equals(item)
                ? payload.copy() : null;
    }

    public void consume() {
        consumed = true;
        // Retain the last snapshot for operator recovery, but do not honor copied tickets.
        setDirty();
    }

    @Override public CompoundTag save(CompoundTag tag) {
        if (ticket != null) tag.putUUID("Ticket", ticket);
        tag.putString("Item", item);
        tag.put("Payload", payload.copy());
        tag.putBoolean("Consumed", consumed);
        return tag;
    }
}
