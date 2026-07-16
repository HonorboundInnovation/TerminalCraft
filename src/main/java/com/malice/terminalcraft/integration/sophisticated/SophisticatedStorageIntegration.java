package com.malice.terminalcraft.integration.sophisticated;

import com.malice.terminalcraft.device.DeviceValue;
import com.malice.terminalcraft.integration.OptionalDeviceMetadata;
import com.malice.terminalcraft.integration.OptionalDeviceMetadataRegistry;
import com.malice.terminalcraft.integration.OptionalIntegration;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.p3pp3rf1y.sophisticatedstorage.block.StorageBlockEntity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Read-only metadata for placed Sophisticated Storage blocks. */
public final class SophisticatedStorageIntegration implements OptionalIntegration {
    @Override
    public void initialize() {
        OptionalDeviceMetadataRegistry.register(SophisticatedStorageIntegration::describe);
    }

    private static Optional<OptionalDeviceMetadata> describe(BlockEntity blockEntity) {
        if (!(blockEntity instanceof StorageBlockEntity storage)) return Optional.empty();
        Map<String, DeviceValue> properties = new LinkedHashMap<>(
                SophisticatedMetadata.properties(storage.getStorageWrapper()));
        properties.put("sophisticated_variant", DeviceValue.of(
                BuiltInRegistries.BLOCK.getKey(storage.getBlockState().getBlock()).toString()));
        properties.put("sophisticated_locked", DeviceValue.of(storage.isLocked()));
        properties.put("sophisticated_linked", DeviceValue.of(storage.isLinked()));
        return Optional.of(new OptionalDeviceMetadata(
                "sophisticatedstorage:placed_storage", "sophisticated_storage",
                Set.of("sophisticated_storage"), properties));
    }
}
