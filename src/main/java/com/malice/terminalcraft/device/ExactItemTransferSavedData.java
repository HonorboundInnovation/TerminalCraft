package com.malice.terminalcraft.device;

import com.malice.terminalcraft.persistence.PersistedDataVersions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Server-global durable replay and escrow state for exact ItemStack transfers. */
final class ExactItemTransferSavedData extends SavedData {
    static final String FILE_ID = "terminalcraft_item_transfers";
    private final ExactItemTransferCoordinator<ItemStack> coordinator;

    ExactItemTransferSavedData() {
        coordinator = new ExactItemTransferCoordinator<>(this::setDirty);
    }

    private ExactItemTransferSavedData(ExactItemTransferCoordinator.Snapshot<ItemStack> snapshot) {
        coordinator = new ExactItemTransferCoordinator<>(snapshot, this::setDirty);
    }

    static ExactItemTransferSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                ExactItemTransferSavedData::load, ExactItemTransferSavedData::new, FILE_ID);
    }

    ExactItemTransferCoordinator<ItemStack> coordinator() {
        return coordinator;
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        PersistedDataVersions.stampCurrent(root);
        ExactItemTransferCoordinator.Snapshot<ItemStack> snapshot = coordinator.snapshot();
        ListTag replays = new ListTag();
        for (ExactItemTransferCoordinator.ReplayEntry entry : snapshot.replays()) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Principal", entry.principal().id());
            tag.putString("PrincipalKind", entry.principal().kind().serializedName());
            tag.putString("PrincipalName", entry.principal().name());
            tag.putUUID("Operation", entry.operationId());
            tag.putUUID("Source", entry.sourceId());
            tag.putUUID("Destination", entry.destinationId());
            tag.putString("Resource", entry.resourceId());
            tag.putInt("Count", entry.count());
            writeResult(tag, entry.result());
            replays.add(tag);
        }
        root.put("Replays", replays);

        ListTag escrow = new ListTag();
        for (ExactItemTransferCoordinator.EscrowEntry<ItemStack> entry : snapshot.escrow()) {
            if (entry.payload().isEmpty()) continue;
            CompoundTag tag = new CompoundTag();
            tag.putUUID("EscrowId", entry.escrowId());
            tag.putUUID("Operation", entry.operationId());
            tag.putUUID("Source", entry.sourceId());
            tag.putUUID("Destination", entry.destinationId());
            tag.put("Stack", entry.payload().copy().save(new CompoundTag()));
            escrow.add(tag);
        }
        root.put("Escrow", escrow);
        return root;
    }

    static ExactItemTransferSavedData load(CompoundTag root) {
        List<ExactItemTransferCoordinator.ReplayEntry> replays = new ArrayList<>();
        ListTag replayTags = root.getList("Replays", Tag.TAG_COMPOUND);
        for (int index = 0; index < replayTags.size()
                && replays.size() < ExactItemTransferCoordinator.MAX_REPLAY_RECORDS; index++) {
            try {
                CompoundTag tag = replayTags.getCompound(index);
                PrincipalIdentity principal = readPrincipal(tag);
                UUID operation = requireUuid(tag, "Operation");
                UUID source = requireUuid(tag, "Source");
                UUID destination = requireUuid(tag, "Destination");
                int count = tag.getInt("Count");
                replays.add(new ExactItemTransferCoordinator.ReplayEntry(principal, operation,
                        source, destination, tag.getString("Resource"), count, readResult(tag, count)));
            } catch (RuntimeException ignored) {
                // Corrupt individual entries are skipped; other recoverable state remains available.
            }
        }

        List<ExactItemTransferCoordinator.EscrowEntry<ItemStack>> escrow = new ArrayList<>();
        ListTag escrowTags = root.getList("Escrow", Tag.TAG_COMPOUND);
        for (int index = 0; index < escrowTags.size()
                && escrow.size() < ExactItemTransferCoordinator.MAX_ESCROW_PARTS; index++) {
            try {
                CompoundTag tag = escrowTags.getCompound(index);
                ItemStack stack = ItemStack.of(tag.getCompound("Stack"));
                if (stack.isEmpty()) continue;
                UUID operation = requireUuid(tag, "Operation");
                UUID source = requireUuid(tag, "Source");
                UUID destination = requireUuid(tag, "Destination");
                UUID escrowId = tag.hasUUID("EscrowId") ? tag.getUUID("EscrowId")
                        : legacyEscrowId(operation, source, destination, stack, index);
                escrow.add(new ExactItemTransferCoordinator.EscrowEntry<>(
                        escrowId, operation, source, destination, stack.copy()));
            } catch (RuntimeException ignored) {
                // Preserve valid entries even when one persisted payload is malformed.
            }
        }
        return new ExactItemTransferSavedData(new ExactItemTransferCoordinator.Snapshot<>(replays, escrow));
    }

    private static UUID legacyEscrowId(UUID operation, UUID source, UUID destination,
                                       ItemStack stack, int index) {
        String identity = operation + ":" + source + ":" + destination + ":" + index + ":"
                + stack.save(new CompoundTag());
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeResult(CompoundTag tag, ExactItemTransferCoordinator.TransferResult result) {
        tag.putString("Status", result.status().name());
        tag.putInt("Extracted", result.extracted());
        tag.putInt("Inserted", result.inserted());
        tag.putInt("RolledBack", result.rolledBack());
        tag.putInt("Escrowed", result.escrowed());
    }

    private static ExactItemTransferCoordinator.TransferResult readResult(CompoundTag tag, int count) {
        return new ExactItemTransferCoordinator.TransferResult(
                ExactItemTransferCoordinator.Status.valueOf(tag.getString("Status")), count,
                tag.getInt("Extracted"), tag.getInt("Inserted"), tag.getInt("RolledBack"),
                tag.getInt("Escrowed"), false);
    }

    private static PrincipalIdentity readPrincipal(CompoundTag tag) {
        UUID id = requireUuid(tag, "Principal");
        if (!tag.contains("PrincipalKind", Tag.TAG_STRING)) {
            return PrincipalIdentity.player(id, "legacy-player");
        }
        PrincipalIdentity.Kind kind = PrincipalIdentity.Kind.parse(tag.getString("PrincipalKind"));
        String name = tag.getString("PrincipalName");
        if (name.isBlank()) throw new IllegalArgumentException("missing principal name");
        return new PrincipalIdentity(kind, id, name);
    }

    private static UUID requireUuid(CompoundTag tag, String key) {
        if (!tag.hasUUID(key)) throw new IllegalArgumentException("missing UUID: " + key);
        return tag.getUUID(key);
    }
}
