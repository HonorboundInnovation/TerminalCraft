package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.DurableCraftingLedger;
import com.malice.terminalcraft.device.GenericCraftingService;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;
import java.util.UUID;

/** Dedicated Refined Storage attachment with bounded durable crafting correlation state. */
public final class RefinedStorageBridgeBlockEntity extends BlockEntity {
    private static final String CRAFTING_LEDGER_TAG = "terminalcraftCraftingLedger";

    private DurableCraftingLedger craftingLedger = new DurableCraftingLedger();

    public RefinedStorageBridgeBlockEntity(BlockPos position, BlockState state) {
        this(ModRegistries.REFINED_STORAGE_BRIDGE_BLOCK_ENTITY.get(), position, state);
    }

    /** Package-private construction seam for deterministic persistence tests. */
    public RefinedStorageBridgeBlockEntity(BlockEntityType<?> type, BlockPos position, BlockState state) {
        super(type, position, state);
    }

    /** Reserves and dirties submission intent before an adapter invokes non-idempotent native work. */
    public synchronized DurableCraftingLedger.ReserveResult reserveCrafting(
            DeviceCallContext caller, GenericCraftingService.Submission submission, long updatedAt) {
        DurableCraftingLedger.ReserveResult result = craftingLedger.reserve(caller, submission, updatedAt);
        if (result.disposition() == DurableCraftingLedger.ReserveDisposition.RESERVED) setChanged();
        return result;
    }

    public synchronized DurableCraftingLedger.Entry confirmNativeCrafting(
            UUID jobId, UUID nativeTaskId, long updatedAt) {
        DurableCraftingLedger.Entry result = craftingLedger.confirmNative(jobId, nativeTaskId, updatedAt);
        setChanged();
        return result;
    }

    public synchronized DurableCraftingLedger.Entry markCraftingAmbiguous(
            UUID jobId, String error, long updatedAt) {
        DurableCraftingLedger.Entry result = craftingLedger.markAmbiguous(jobId, error, updatedAt);
        setChanged();
        return result;
    }

    public synchronized DurableCraftingLedger.Entry updateCrafting(
            UUID jobId, GenericCraftingService.State state, long completed, long total,
            String terminalResult, String error, long updatedAt) {
        DurableCraftingLedger.Entry result = craftingLedger.update(
                jobId, state, completed, total, terminalResult, error, updatedAt);
        setChanged();
        return result;
    }

    public synchronized Optional<DurableCraftingLedger.Entry> findOwnedCrafting(
            UUID jobId, com.malice.terminalcraft.device.PrincipalIdentity principal) {
        return craftingLedger.findOwned(jobId, principal);
    }

    public synchronized int craftingJobCount() {
        return craftingLedger.size();
    }

    @Override
    protected synchronized void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put(CRAFTING_LEDGER_TAG, craftingLedger.save());
    }

    @Override
    public synchronized void load(CompoundTag tag) {
        super.load(tag);
        craftingLedger = tag.contains(CRAFTING_LEDGER_TAG, CompoundTag.TAG_COMPOUND)
                ? DurableCraftingLedger.load(tag.getCompound(CRAFTING_LEDGER_TAG))
                : new DurableCraftingLedger();
    }

    public static void serverTick(Level level, BlockPos position, BlockState state,
                                  RefinedStorageBridgeBlockEntity bridge) {
        // Optional adapters reacquire fresh RS topology; native task polling is request-driven.
    }
}
