package com.malice.terminalcraft.device;

import net.minecraft.nbt.CompoundTag;

import java.util.Set;
import java.util.UUID;

/** Headless crash-window and durable replay coverage for native crafting correlation. */
public final class DurableCraftingLedgerTest {
    private DurableCraftingLedgerTest() {}

    public static void main(String[] args) {
        DeviceCallContext alice = new DeviceCallContext(UUID.randomUUID(), "alice",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        UUID operation = UUID.randomUUID();
        GenericCraftingService.Submission request =
                new GenericCraftingService.Submission(operation, "minecraft:diamond", 8);

        DurableCraftingLedger ledger = new DurableCraftingLedger();
        DurableCraftingLedger.ReserveResult reserved = ledger.reserve(alice, request, 1);
        require(reserved.disposition() == DurableCraftingLedger.ReserveDisposition.RESERVED,
                "first operation reserves durable intent");
        UUID job = reserved.entry().jobId();

        DurableCraftingLedger ambiguous = DurableCraftingLedger.load(ledger.save());
        DurableCraftingLedger.Entry ambiguousEntry = ambiguous
                .findOwned(job, alice.principalId()).orElseThrow();
        require(ambiguousEntry.state() == GenericCraftingService.State.RECONCILING,
                "unconfirmed submission becomes reconciling after reload");
        require(ambiguous.reserve(alice, request, 2).disposition()
                        == DurableCraftingLedger.ReserveDisposition.REPLAYED,
                "ambiguous operation replays instead of resubmitting");
        require(ambiguous.size() == 1, "replay does not duplicate durable work");

        require(ambiguous.reserve(alice, new GenericCraftingService.Submission(operation,
                        "minecraft:emerald", 8), 2).disposition()
                        == DurableCraftingLedger.ReserveDisposition.CONFLICT,
                "operation payload mismatch conflicts");

        UUID nativeTask = UUID.randomUUID();
        ambiguous.confirmNative(job, nativeTask, 3);
        DurableCraftingLedger restored = DurableCraftingLedger.load(ambiguous.save());
        DurableCraftingLedger.Entry correlated = restored.findOwned(job, alice.principalId()).orElseThrow();
        require(nativeTask.equals(correlated.nativeTaskId()), "native task correlation survives reload");
        require(correlated.state() == GenericCraftingService.State.QUEUED,
                "confirmed native task remains queued after reload");

        restored.update(job, GenericCraftingService.State.COMPLETED, 8, 8,
                "completed", "", 4);
        DurableCraftingLedger terminal = DurableCraftingLedger.load(restored.save());
        require(terminal.findOwned(job, alice.principalId()).orElseThrow().state()
                        == GenericCraftingService.State.COMPLETED,
                "terminal result survives reload");
        terminal.update(job, GenericCraftingService.State.RUNNING, 1, 8, "", "", 5);
        require(terminal.findOwned(job, alice.principalId()).orElseThrow().state()
                        == GenericCraftingService.State.COMPLETED,
                "terminal state cannot regress");

        DeviceCallContext bob = new DeviceCallContext(UUID.randomUUID(), "bob",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        require(terminal.findOwned(job, bob.principalId()).isEmpty(),
                "another principal cannot discover persisted job");
        DeviceCallContext aliceService = DeviceCallContext.service(alice.principalId(), "alice-service",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        require(terminal.findOwned(job, aliceService.principal()).isEmpty(),
                "same UUID service cannot discover player-owned job");
        require(terminal.reserve(aliceService, request, 6).disposition()
                        == DurableCraftingLedger.ReserveDisposition.RESERVED,
                "same operation ID is independently scoped by principal kind");

        CompoundTag malformed = terminal.save();
        malformed.getList("jobs", net.minecraft.nbt.Tag.TAG_COMPOUND)
                .add(new CompoundTag());
        require(DurableCraftingLedger.load(malformed).size() == 2,
                "malformed record is isolated without discarding valid records");

        CompoundTag badKind = terminal.save();
        badKind.getList("jobs", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0)
                .putString("principalKind", "administrator");
        require(DurableCraftingLedger.load(badKind).size() == 1,
                "malformed typed principal is isolated without elevation");

        CompoundTag legacy = ledger.save();
        legacy.putInt("version", 1);
        CompoundTag legacyEntry = legacy.getList("jobs", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0);
        legacyEntry.remove("principalKind");
        legacyEntry.remove("principalName");
        DurableCraftingLedger migrated = DurableCraftingLedger.load(legacy);
        require(migrated.findOwned(job, alice.principalId()).isPresent(),
                "legacy UUID-only crafting owner migrates explicitly as PLAYER");
        require(migrated.findOwned(job, aliceService.principal()).isEmpty(),
                "legacy player migration cannot be claimed by same-UUID service");

        DurableCraftingLedger bounded = new DurableCraftingLedger();
        for (int index = 0; index < DurableCraftingLedger.MAX_JOBS; index++) {
            require(bounded.reserve(alice, new GenericCraftingService.Submission(
                            UUID.randomUUID(), "minecraft:stone", 1), index).disposition()
                            == DurableCraftingLedger.ReserveDisposition.RESERVED,
                    "bounded ledger accepts capacity");
        }
        require(bounded.reserve(alice, new GenericCraftingService.Submission(
                        UUID.randomUUID(), "minecraft:stone", 1), 999).disposition()
                        == DurableCraftingLedger.ReserveDisposition.FULL,
                "bounded ledger rejects excess jobs");

        System.out.println("Durable crafting ledger tests: OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
