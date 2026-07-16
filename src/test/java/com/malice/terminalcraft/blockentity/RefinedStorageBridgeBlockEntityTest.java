package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.DurableCraftingLedger;
import com.malice.terminalcraft.device.GenericCraftingService;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.Set;
import java.util.UUID;

/** Headless integration coverage for bridge-owned crafting ledger persistence. */
public final class RefinedStorageBridgeBlockEntityTest {
    private RefinedStorageBridgeBlockEntityTest() {}

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BlockEntityType<?> type = BlockEntityType.Builder.of(
                (position, state) -> { throw new UnsupportedOperationException("test-only type"); },
                Blocks.STONE).build(null);
        RefinedStorageBridgeBlockEntity source = new RefinedStorageBridgeBlockEntity(
                type, BlockPos.ZERO, Blocks.STONE.defaultBlockState());

        DeviceCallContext alice = new DeviceCallContext(UUID.randomUUID(), "alice",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        UUID operationId = UUID.randomUUID();
        GenericCraftingService.Submission submission = new GenericCraftingService.Submission(
                operationId, "minecraft:diamond", 12);
        DurableCraftingLedger.ReserveResult reserved = source.reserveCrafting(alice, submission, 10);
        require(reserved.disposition() == DurableCraftingLedger.ReserveDisposition.RESERVED,
                "bridge reserves durable intent");
        UUID jobId = reserved.entry().jobId();
        UUID nativeTaskId = UUID.randomUUID();
        source.confirmNativeCrafting(jobId, nativeTaskId, 11);
        source.updateCrafting(jobId, GenericCraftingService.State.RUNNING,
                3, 12, "", "", 12);

        CompoundTag saved = new CompoundTag();
        source.saveAdditional(saved);
        RefinedStorageBridgeBlockEntity restored = new RefinedStorageBridgeBlockEntity(
                type, BlockPos.ZERO, Blocks.STONE.defaultBlockState());
        restored.load(saved.copy());

        DurableCraftingLedger.Entry entry = restored.findOwnedCrafting(jobId, alice.principal())
                .orElseThrow();
        require(entry.operationId().equals(operationId), "operation ID survives bridge NBT round trip");
        require(entry.principalId().equals(alice.principalId()), "principal survives bridge NBT round trip");
        require(nativeTaskId.equals(entry.nativeTaskId()), "native task correlation survives bridge NBT round trip");
        require(entry.state() == GenericCraftingService.State.RUNNING, "state survives bridge NBT round trip");
        require(entry.completedWork() == 3 && entry.totalWork() == 12, "progress survives bridge NBT round trip");
        require(entry.updatedAt() == 12, "timestamp survives bridge NBT round trip");

        DeviceCallContext bob = new DeviceCallContext(UUID.randomUUID(), "bob",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        require(restored.findOwnedCrafting(jobId, bob.principal()).isEmpty(),
                "another principal cannot discover restored bridge work");

        restored.load(new CompoundTag());
        require(restored.craftingJobCount() == 0, "missing ledger tag resets bridge ledger safely");

        System.out.println("Refined Storage bridge block entity tests: OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
