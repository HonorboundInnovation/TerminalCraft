package com.malice.terminalcraft.integration.sophisticated;

import com.malice.terminalcraft.device.DeviceValue;
import com.malice.terminalcraft.integration.OptionalDeviceMetadata;
import com.malice.terminalcraft.integration.OptionalDeviceMetadataRegistry;
import com.malice.terminalcraft.integration.OptionalIntegration;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackBlockEntity;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Read-only metadata for backpacks placed as blocks; carried-player access is intentionally absent. */
public final class SophisticatedBackpacksIntegration implements OptionalIntegration {
    @Override
    public void initialize() {
        OptionalDeviceMetadataRegistry.register(SophisticatedBackpacksIntegration::describe);
    }

    private static Optional<OptionalDeviceMetadata> describe(BlockEntity blockEntity) {
        if (!(blockEntity instanceof BackpackBlockEntity backpack)) return Optional.empty();
        IBackpackWrapper wrapper = backpack.getBackpackWrapper();
        Map<String, DeviceValue> properties = new LinkedHashMap<>(SophisticatedMetadata.properties(wrapper));
        ItemStack stack = wrapper.getBackpack();
        properties.put("sophisticated_variant", DeviceValue.of(
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()));
        return Optional.of(new OptionalDeviceMetadata(
                "sophisticatedbackpacks:placed_backpack", "sophisticated_backpack",
                Set.of("sophisticated_backpack"), properties));
    }
}
