package com.malice.terminalcraft.device;

import com.malice.terminalcraft.persistence.PersistedDataVersions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fluids.FluidStack;
import net.minecraft.world.level.saveddata.SavedData;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Server-global durable replay and escrow state for exact FluidStack transfers. */
final class ExactFluidTransferSavedData extends SavedData {
    static final String FILE_ID = "terminalcraft_fluid_transfers";
    private final ExactFluidTransferCoordinator<FluidStack> coordinator;

    ExactFluidTransferSavedData() {
        coordinator = new ExactFluidTransferCoordinator<>(this::setDirty);
    }

    private ExactFluidTransferSavedData(ExactFluidTransferCoordinator.Snapshot<FluidStack> snapshot) {
        coordinator = new ExactFluidTransferCoordinator<>(snapshot, this::setDirty);
    }

    static ExactFluidTransferSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                ExactFluidTransferSavedData::load, ExactFluidTransferSavedData::new, FILE_ID);
    }

    ExactFluidTransferCoordinator<FluidStack> coordinator() { return coordinator; }

    @Override
    public CompoundTag save(CompoundTag root) {
        PersistedDataVersions.stampCurrent(root);
        ExactFluidTransferCoordinator.Snapshot<FluidStack> snapshot = coordinator.snapshot();
        ListTag replays = new ListTag();
        for (ExactFluidTransferCoordinator.ReplayEntry entry : snapshot.replays()) {
            CompoundTag tag = new CompoundTag();
            tag.putUUID("Principal", entry.principal().id());
            tag.putString("PrincipalKind", entry.principal().kind().serializedName());
            tag.putString("PrincipalName", entry.principal().name());
            tag.putUUID("Operation", entry.operationId());
            tag.putUUID("Source", entry.sourceId());
            tag.putUUID("Destination", entry.destinationId());
            tag.putString("Resource", entry.resourceId());
            tag.putInt("Amount", entry.amountMb());
            writeResult(tag, entry.result());
            replays.add(tag);
        }
        root.put("Replays", replays);

        ListTag escrow = new ListTag();
        for (ExactFluidTransferCoordinator.EscrowEntry<FluidStack> entry : snapshot.escrow()) {
            if (entry.payload().isEmpty()) continue;
            CompoundTag tag = new CompoundTag();
            tag.putUUID("EscrowId", entry.escrowId());
            tag.putUUID("Operation", entry.operationId());
            tag.putUUID("Source", entry.sourceId());
            tag.putUUID("Destination", entry.destinationId());
            tag.put("Fluid", entry.payload().copy().writeToNBT(new CompoundTag()));
            escrow.add(tag);
        }
        root.put("Escrow", escrow);
        return root;
    }

    static ExactFluidTransferSavedData load(CompoundTag root) {
        List<ExactFluidTransferCoordinator.ReplayEntry> replays = new ArrayList<>();
        ListTag replayTags = root.getList("Replays", Tag.TAG_COMPOUND);
        for (int index = 0; index < replayTags.size()
                && replays.size() < ExactFluidTransferCoordinator.MAX_REPLAY_RECORDS; index++) {
            try {
                CompoundTag tag = replayTags.getCompound(index);
                PrincipalIdentity principal = readPrincipal(tag);
                UUID operation = requireUuid(tag, "Operation");
                UUID source = requireUuid(tag, "Source");
                UUID destination = requireUuid(tag, "Destination");
                int amount = tag.getInt("Amount");
                replays.add(new ExactFluidTransferCoordinator.ReplayEntry(principal, operation,
                        source, destination, tag.getString("Resource"), amount, readResult(tag, amount)));
            } catch (RuntimeException ignored) {
                // Corrupt individual entries are skipped while valid custody remains available.
            }
        }

        List<ExactFluidTransferCoordinator.EscrowEntry<FluidStack>> escrow = new ArrayList<>();
        ListTag escrowTags = root.getList("Escrow", Tag.TAG_COMPOUND);
        for (int index = 0; index < escrowTags.size()
                && escrow.size() < ExactFluidTransferCoordinator.MAX_ESCROW_ENTRIES; index++) {
            try {
                CompoundTag tag = escrowTags.getCompound(index);
                FluidStack fluid = FluidStack.loadFluidStackFromNBT(tag.getCompound("Fluid"));
                if (fluid.isEmpty() || fluid.getAmount() < 1
                        || fluid.getAmount() > ExactFluidTransferCoordinator.MAX_TRANSFER_AMOUNT) continue;
                UUID operation = requireUuid(tag, "Operation");
                UUID source = requireUuid(tag, "Source");
                UUID destination = requireUuid(tag, "Destination");
                UUID escrowId = tag.hasUUID("EscrowId") ? tag.getUUID("EscrowId")
                        : legacyEscrowId(operation, source, destination, fluid, index);
                escrow.add(new ExactFluidTransferCoordinator.EscrowEntry<>(escrowId, operation,
                        source, destination, fluid.copy()));
            } catch (RuntimeException ignored) {
                // Preserve valid entries when one persisted payload is malformed.
            }
        }
        return new ExactFluidTransferSavedData(new ExactFluidTransferCoordinator.Snapshot<>(replays, escrow));
    }

    private static UUID legacyEscrowId(UUID operation, UUID source, UUID destination,
                                       FluidStack fluid, int index) {
        String identity = operation + ":" + source + ":" + destination + ":" + index + ":"
                + fluid.writeToNBT(new CompoundTag());
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeResult(CompoundTag tag, ExactFluidTransferCoordinator.TransferResult result) {
        tag.putString("Status", result.status().name());
        tag.putInt("Extracted", result.extractedMb());
        tag.putInt("Inserted", result.insertedMb());
        tag.putInt("RolledBack", result.rolledBackMb());
        tag.putInt("Escrowed", result.escrowedMb());
    }

    private static ExactFluidTransferCoordinator.TransferResult readResult(CompoundTag tag, int amount) {
        return new ExactFluidTransferCoordinator.TransferResult(
                ExactFluidTransferCoordinator.Status.valueOf(tag.getString("Status")), amount,
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
