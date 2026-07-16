package com.malice.terminalcraft.device;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Exact-stack transfer port for a side-aware Forge item capability.
 *
 * <p>The capability is reacquired for every coordinator operation and every stack crossing the
 * port boundary is copied. Item identity includes the complete item tag, not only the registry ID.</p>
 */
final class ForgeItemStackTransferPort implements ExactItemTransferCoordinator.Port<ItemStack> {
    private final Supplier<IItemHandler> handlerSupplier;

    ForgeItemStackTransferPort(ServerLevel level, BlockPos target, Direction accessSide) {
        this(level, target, accessSide, null);
    }

    /** Binds an operation to the block-entity incarnation observed during endpoint resolution. */
    ForgeItemStackTransferPort(ServerLevel level, BlockPos target, Direction accessSide,
                               BlockEntity expectedBlockEntity) {
        Objects.requireNonNull(level, "level");
        BlockPos immutableTarget = Objects.requireNonNull(target, "target").immutable();
        this.handlerSupplier = () -> {
            if (!level.hasChunkAt(immutableTarget)) return null;
            BlockEntity blockEntity = level.getBlockEntity(immutableTarget);
            IItemHandler current = blockEntity == null ? null : blockEntity
                    .getCapability(ForgeCapabilities.ITEM_HANDLER, accessSide)
                    .resolve().orElse(null);
            return expectedBlockEntity == null || blockEntity == expectedBlockEntity ? current : null;
        };
    }

    /** Package-private constructor used by focused headless adapter tests. */
    ForgeItemStackTransferPort(Supplier<IItemHandler> handlerSupplier) {
        this.handlerSupplier = Objects.requireNonNull(handlerSupplier, "handlerSupplier");
    }

    @Override
    public List<ItemStack> extract(String resourceId, int count, int maxParts) {
        IItemHandler handler = requireHandler();
        Item item = requireItem(resourceId);
        int remaining = count;
        int slots = Math.min(handler.getSlots(), GenericCapabilityDevice.MAX_INVENTORY_SLOTS);
        int boundedParts = Math.max(0, Math.min(maxParts,
                ExactItemTransferCoordinator.MAX_EXTRACTED_PARTS));
        List<ItemStack> extracted = new ArrayList<>();

        for (int slot = 0; slot < slots && remaining > 0 && extracted.size() < boundedParts; slot++) {
            ItemStack visible = handler.getStackInSlot(slot);
            if (visible.isEmpty() || !visible.is(item)) continue;
            ItemStack expectedVariant = visible.copy();

            ItemStack result = Objects.requireNonNull(
                    handler.extractItem(slot, remaining, false), "item handler extraction result");
            if (result.isEmpty()) continue;
            if (!result.is(item) || result.getCount() < 1 || result.getCount() > remaining
                    || !ItemStack.isSameItemSameTags(expectedVariant, result)) {
                throw new IllegalStateException("item handler returned an invalid exact-stack extraction");
            }
            ItemStack copy = result.copy();
            extracted.add(copy);
            remaining -= copy.getCount();
        }
        return List.copyOf(extracted);
    }

    @Override
    public ItemStack insert(ItemStack payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.isEmpty()) return ItemStack.EMPTY;
        IItemHandler handler = requireHandler();
        ItemStack remainder = payload.copy();
        int slots = Math.min(handler.getSlots(), GenericCapabilityDevice.MAX_INVENTORY_SLOTS);

        for (int slot = 0; slot < slots && !remainder.isEmpty(); slot++) {
            ItemStack offered = remainder.copy();
            ItemStack returned = Objects.requireNonNull(
                    handler.insertItem(slot, offered, false), "item handler insertion remainder");
            if (!returned.isEmpty() && (!ItemStack.isSameItemSameTags(payload, returned)
                    || returned.getCount() > remainder.getCount())) {
                throw new IllegalStateException("item handler returned an invalid exact-stack remainder");
            }
            remainder = returned.copy();
        }
        return remainder.isEmpty() ? ItemStack.EMPTY : remainder.copy();
    }

    @Override
    public int amount(ItemStack payload) {
        Objects.requireNonNull(payload, "payload");
        return payload.isEmpty() ? 0 : payload.getCount();
    }

    @Override
    public boolean sameVariant(ItemStack left, ItemStack right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        return left.isEmpty() ? right.isEmpty()
                : !right.isEmpty() && ItemStack.isSameItemSameTags(left, right);
    }

    private IItemHandler requireHandler() {
        IItemHandler handler = handlerSupplier.get();
        if (handler == null) throw new IllegalStateException("item capability is unavailable");
        return handler;
    }

    private static Item requireItem(String resourceId) {
        ResourceLocation key = ResourceLocation.tryParse(resourceId);
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) {
            throw new IllegalArgumentException("unknown item resource: " + resourceId);
        }
        return BuiltInRegistries.ITEM.get(key);
    }
}
