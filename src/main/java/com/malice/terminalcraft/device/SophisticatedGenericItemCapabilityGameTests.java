package com.malice.terminalcraft.device;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackBlockEntity;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.settings.memory.MemorySettingsCategory;
import net.p3pp3rf1y.sophisticatedstorage.block.StorageBlockEntity;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Focused live checks that placed Sophisticated containers honor the generic Forge item contract. */
@GameTestHolder("terminalcraft")
public final class SophisticatedGenericItemCapabilityGameTests {
    private static final BlockPos HOST = new BlockPos(2, 2, 2);
    private static final BlockPos SOURCE = HOST.relative(Direction.WEST);
    private static final BlockPos DESTINATION = HOST.relative(Direction.EAST);

    private SophisticatedGenericItemCapabilityGameTests() {}

    @GameTest(template = "empty")
    public static void placedSophisticatedStorageUsesGenericItemHandler(GameTestHelper helper) {
        verifyPlacedContainer(helper, "sophisticatedstorage", "sophisticatedstorage:chest");
    }

    @GameTest(template = "empty")
    public static void placedSophisticatedBackpackUsesGenericItemHandler(GameTestHelper helper) {
        verifyPlacedContainer(helper, "sophisticatedbackpacks", "sophisticatedbackpacks:backpack");
    }

    @GameTest(template = "empty")
    public static void placedSophisticatedStorageReacquiresAfterBreakAndReplace(GameTestHelper helper) {
        verifyReacquisition(helper, "sophisticatedstorage", "sophisticatedstorage:chest",
                "sophisticatedstorage:placed_storage");
    }

    @GameTest(template = "empty")
    public static void placedSophisticatedBackpackReacquiresAfterBreakAndReplace(GameTestHelper helper) {
        verifyReacquisition(helper, "sophisticatedbackpacks", "sophisticatedbackpacks:backpack",
                "sophisticatedbackpacks:placed_backpack");
    }

    @GameTest(template = "empty")
    public static void carriedSophisticatedBackpackIsNotAddressableByPlayerIdentity(GameTestHelper helper) {
        if (!ModList.get().isLoaded("sophisticatedbackpacks")) {
            helper.succeed();
            return;
        }

        Item backpackItem = requiredItem(helper, "sophisticatedbackpacks:backpack");
        Player player = helper.makeMockPlayer();
        helper.assertTrue(player.getInventory().add(new ItemStack(backpackItem)),
                "test player must carry a real Sophisticated Backpack item");

        AdjacentForgeDeviceAccess access = new AdjacentForgeDeviceAccess(
                new DeviceRegistry().access(readOnlyContext("carried-backpack-denial")),
                helper.getLevel(), helper.absolutePos(HOST));
        helper.assertTrue(access.descriptors(DeviceRegistry.MAX_ENUMERATION_RESULTS).isEmpty(),
                "carried player inventory must never be enumerated as adjacent block storage");
        helper.assertTrue(access.descriptor(player.getUUID()).isEmpty(),
                "knowing a player's authenticated UUID must not resolve carried inventory");
        DeviceResult byPlayerId = access.call(player.getUUID(), "storage.query", List.of());
        helper.assertTrue(byPlayerId.error().orElseThrow().code() == DeviceErrorCode.NOT_FOUND,
                "player UUID access must fail outside the six-position adjacency authority");

        UUID nameIdentity = UUID.nameUUIDFromBytes(player.getName().getString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        DeviceResult byKnownName = access.call(nameIdentity, "storage.query", List.of());
        helper.assertTrue(byKnownName.error().orElseThrow().code() == DeviceErrorCode.NOT_FOUND,
                "a UUID derived from a known player name must not grant carried inventory access");
        StorageShellCommand.Outcome shellByName = StorageShellCommand.execute(access,
                List.of("query", player.getName().getString()));
        helper.assertTrue(shellByName.exitCode() != 0,
                "the storage shell must not accept a player name as a storage selector");
        helper.assertTrue(player.getInventory().contains(new ItemStack(backpackItem)),
                "denied identity probes must not mutate the carried backpack");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sophisticatedMetadataTracksStablePublicState(GameTestHelper helper) {
        if (!ModList.get().isLoaded("sophisticatedstorage")) {
            helper.succeed();
            return;
        }

        helper.setBlock(SOURCE, requiredBlock(helper, "sophisticatedstorage:chest"));
        BlockEntity blockEntity = helper.getBlockEntity(SOURCE);
        IStorageWrapper wrapper = storageWrapper(blockEntity);
        BlockPos absoluteHost = helper.absolutePos(HOST);
        AdjacentForgeEndpointResolver.Candidate candidate = AdjacentForgeEndpointResolver.adjacent(
                helper.getLevel().dimension().location().toString(), absoluteHost, Direction.WEST);
        AdjacentForgeDeviceAccess access = new AdjacentForgeDeviceAccess(
                new DeviceRegistry().access(readOnlyContext("sophisticated-metadata")),
                helper.getLevel(), absoluteHost);

        DeviceDescriptor baseline = access.descriptor(candidate.id()).orElseThrow();
        long baselineCapacity = longStringProperty(baseline, "sophisticated_effective_slot_capacity");
        helper.assertTrue(baselineCapacity > 0,
                "stable public slot limits must expose positive effective capacity");
        helper.assertTrue("items".equals(stringProperty(baseline, "sophisticated_capacity_unit")),
                "effective capacity must declare item units");
        helper.assertTrue(baseline.properties().containsKey("sophisticated_sort_mode"),
                "stable public sort mode must be namespaced metadata");
        helper.assertTrue(((DeviceValue.ListValue) baseline.properties().get(
                        "sophisticated_slot_limits")).values().size() <= 64,
                "slot-limit metadata must remain bounded");

        MemorySettingsCategory memory = wrapper.getSettingsHandler()
                .getTypeCategory(MemorySettingsCategory.class);
        memory.setFilter(0, new ItemStack(Items.DIAMOND));
        DeviceDescriptor filtered = access.descriptor(candidate.id()).orElseThrow();
        DeviceValue.ListValue filters = (DeviceValue.ListValue) filtered.properties().get(
                "sophisticated_filters");
        helper.assertTrue(filters.values().stream().anyMatch(value -> {
                    DeviceValue.MapValue filter = (DeviceValue.MapValue) value;
                    return number(filter, "slot") == 0
                            && "minecraft:diamond".equals(string(filter, "resource"));
                }), "public memory filter changes must refresh bounded metadata");

        String upgradeId = "sophisticatedstorage:stack_upgrade_tier_1";
        Item upgrade = requiredItem(helper, upgradeId);
        ItemStack upgradeRemainder = wrapper.getUpgradeHandler().insertItem(
                0, new ItemStack(upgrade), false);
        helper.assertTrue(upgradeRemainder.isEmpty(), "test storage must accept the public stack upgrade");
        DeviceDescriptor upgraded = access.descriptor(candidate.id()).orElseThrow();
        helper.assertTrue(longStringProperty(upgraded, "sophisticated_effective_slot_capacity")
                        > baselineCapacity,
                "stack upgrade must increase effective capacity reported through public slot limits");
        DeviceValue.ListValue upgrades = (DeviceValue.ListValue) upgraded.properties().get(
                "sophisticated_upgrades");
        helper.assertTrue(upgrades.values().stream().anyMatch(value ->
                        upgradeId.equals(string((DeviceValue.MapValue) value, "resource"))),
                "installed public upgrade identity must refresh in bounded metadata");

        ItemStack removed = wrapper.getUpgradeHandler().extractItem(0, 1, false);
        helper.assertTrue(removed.is(upgrade), "test upgrade must be removable through its public handler");
        DeviceDescriptor restored = access.descriptor(candidate.id()).orElseThrow();
        helper.assertTrue(longStringProperty(restored, "sophisticated_effective_slot_capacity")
                        == baselineCapacity,
                "removing the stack upgrade must restore public effective-capacity metadata");
        helper.assertTrue(((DeviceValue.ListValue) restored.properties().get(
                        "sophisticated_upgrades")).values().isEmpty(),
                "removed upgrades must disappear from fresh metadata");
        helper.succeed();
    }

    private static void verifyReacquisition(GameTestHelper helper, String requiredModId,
                                            String blockId, String adapterId) {
        if (!ModList.get().isLoaded(requiredModId)) {
            helper.succeed();
            return;
        }

        ResourceLocation blockKey = ResourceLocation.tryParse(blockId);
        Block block = requiredBlock(helper, blockId);
        helper.setBlock(SOURCE, block);
        BlockEntity initial = helper.getBlockEntity(SOURCE);
        if ("sophisticatedbackpacks".equals(requiredModId)) initializePlacedBackpack(initial, blockKey);
        String dimension = helper.getLevel().dimension().location().toString();
        BlockPos absoluteHost = helper.absolutePos(HOST);
        AdjacentForgeEndpointResolver.Candidate candidate =
                AdjacentForgeEndpointResolver.adjacent(dimension, absoluteHost, Direction.WEST);
        AdjacentForgeEndpointResolver resolver = new AdjacentForgeEndpointResolver(
                helper.getLevel(), absoluteHost);
        AdjacentForgeEndpointResolver.ResolvedItemEndpoint stale = resolver
                .resolveItem(candidate.id()).endpointOptional().orElseThrow();
        DeviceCallContext reader = readOnlyContext(requiredModId + "-lifecycle");
        AdjacentForgeDeviceAccess access = new AdjacentForgeDeviceAccess(
                new DeviceRegistry().access(reader), helper.getLevel(), absoluteHost);
        helper.assertTrue(adapterId.equals(access.descriptor(candidate.id()).orElseThrow().adapterId()),
                blockId + " must initially use its specialized adapter");

        helper.setBlock(SOURCE, Blocks.AIR);
        DeviceResult removed = access.call(candidate.id(), "storage.query", List.of());
        helper.assertTrue(removed.error().orElseThrow().code() == DeviceErrorCode.REMOVED,
                "removed " + blockId + " must return a structured removed result");

        helper.setBlock(SOURCE, block);
        BlockEntity replacement = helper.getBlockEntity(SOURCE);
        helper.assertTrue(replacement != null, "replacement " + blockId + " must create a block entity");
        if ("sophisticatedbackpacks".equals(requiredModId)) initializePlacedBackpack(replacement, blockKey);
        DeviceDescriptor replacementDescriptor = access.descriptor(candidate.id()).orElseThrow();
        helper.assertTrue(adapterId.equals(replacementDescriptor.adapterId()),
                "re-placed " + blockId + " must be rediscovered through the specialized adapter");
        helper.assertTrue(candidate.id().equals(replacementDescriptor.deviceId()),
                "coordinate-and-side endpoint identity must remain stable after re-placement");
        assertThrows(() -> stale.port().extract("minecraft:stone", 1, 1),
                "a port bound to the old block entity must reject the replacement incarnation");

        IItemHandler replacementHandler = replacement
                .getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.EAST)
                .resolve().orElseThrow();
        ItemStack remainder = replacementHandler.insertItem(0, new ItemStack(Items.EMERALD, 3), false);
        helper.assertTrue(remainder.isEmpty(), "replacement " + blockId + " must accept a fresh item");
        DeviceResult count = access.call(candidate.id(), "storage.count",
                List.of(DeviceValue.of("minecraft:emerald")));
        helper.assertTrue("3".equals(stringResult(count)),
                "fresh calls must use the replacement " + blockId + " capability");
        helper.succeed();
    }

    private static void verifyPlacedContainer(GameTestHelper helper, String requiredModId,
                                               String blockId) {
        // The default minimal profile must remain loadable. The focused profile supplies these mods.
        if (!ModList.get().isLoaded(requiredModId)) {
            helper.succeed();
            return;
        }

        ResourceLocation blockKey = ResourceLocation.tryParse(blockId);
        Block block = requiredBlock(helper, blockId);

        helper.setBlock(SOURCE, block);
        helper.setBlock(DESTINATION, Blocks.CHEST);
        BlockEntity sourceEntity = helper.getBlockEntity(SOURCE);
        ChestBlockEntity destination = (ChestBlockEntity) helper.getBlockEntity(DESTINATION);
        helper.assertTrue(sourceEntity != null, blockId + " must create a block entity");
        if ("sophisticatedbackpacks".equals(requiredModId)) {
            initializePlacedBackpack(sourceEntity, blockKey);
        }

        IItemHandler handler = sourceEntity
                .getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.EAST)
                .resolve().orElse(null);
        helper.assertTrue(handler != null, blockId + " must expose a side-aware IItemHandler");
        helper.assertTrue(handler.getSlots() > 0, blockId + " must expose at least one item slot");

        ItemStack exact = new ItemStack(Items.DIAMOND, 7);
        exact.getOrCreateTag().putString("terminalcraft_test", requiredModId);
        ItemStack simulatedRemainder = handler.insertItem(0, exact.copy(), true);
        helper.assertTrue(simulatedRemainder.getCount() < exact.getCount(),
                blockId + " must accept the test item in simulation");
        helper.assertTrue(handler.getStackInSlot(0).isEmpty(),
                blockId + " simulated insertion must not mutate storage");
        ItemStack remainder = handler.insertItem(0, exact.copy(), false);
        helper.assertTrue(remainder.isEmpty(), blockId + " must accept the test item");

        String dimension = helper.getLevel().dimension().location().toString();
        BlockPos absoluteHost = helper.absolutePos(HOST);
        AdjacentForgeEndpointResolver resolver = new AdjacentForgeEndpointResolver(
                helper.getLevel(), absoluteHost);
        AdjacentForgeEndpointResolver.Candidate sourceCandidate =
                AdjacentForgeEndpointResolver.adjacent(dimension, absoluteHost, Direction.WEST);
        AdjacentForgeEndpointResolver.Candidate destinationCandidate =
                AdjacentForgeEndpointResolver.adjacent(dimension, absoluteHost, Direction.EAST);
        AdjacentForgeEndpointResolver.ResolvedItemEndpoint source = resolver
                .resolveItem(sourceCandidate.id()).endpointOptional().orElseThrow();
        AdjacentForgeEndpointResolver.ResolvedItemEndpoint target = resolver
                .resolveItem(destinationCandidate.id()).endpointOptional().orElseThrow();

        DeviceCallContext writer = new DeviceCallContext(UUID.randomUUID(), "gametest",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        AdjacentForgeDeviceAccess access = new AdjacentForgeDeviceAccess(
                new DeviceRegistry().access(writer), helper.getLevel(), absoluteHost);
        DeviceDescriptor descriptor = access.descriptor(sourceCandidate.id()).orElseThrow();
        String expectedAdapter = requiredModId + ("sophisticatedstorage".equals(requiredModId)
                ? ":placed_storage" : ":placed_backpack");
        helper.assertTrue(expectedAdapter.equals(descriptor.adapterId()),
                blockId + " must select its specialized read-only metadata adapter");
        helper.assertTrue(descriptor.capabilities().contains("inventory"),
                blockId + " must retain the generic inventory capability");
        helper.assertTrue(descriptor.capabilities().contains("sophisticatedstorage".equals(requiredModId)
                        ? "sophisticated_storage" : "sophisticated_backpack"),
                blockId + " must advertise its specialized capability");
        helper.assertTrue(numberProperty(descriptor, "sophisticated_inventory_slots") == handler.getSlots(),
                blockId + " metadata must report the public wrapper slot count");
        helper.assertTrue(numberProperty(descriptor, "sophisticated_upgrade_slots") >= 0,
                blockId + " metadata must report bounded upgrade slots");
        helper.assertTrue(descriptor.properties().containsKey("sophisticated_upgrades"),
                blockId + " metadata must expose a bounded read-only upgrade list");
        helper.assertTrue(descriptor.properties().containsKey("sophisticated_sort_mode"),
                blockId + " metadata must expose the public sort mode");
        helper.assertTrue(longStringProperty(descriptor, "sophisticated_effective_slot_capacity") > 0,
                blockId + " metadata must expose bounded effective slot-limit capacity");
        helper.assertTrue(((DeviceValue.ListValue) descriptor.properties().get(
                        "sophisticated_slot_limits")).values().size() <= 64,
                blockId + " slot-limit metadata must remain bounded");
        helper.assertTrue(descriptor.properties().containsKey("sophisticated_filters")
                        && descriptor.properties().containsKey("sophisticated_filters_complete"),
                blockId + " metadata must expose bounded public memory-filter state");
        helper.assertTrue(descriptor.properties().containsKey("sophisticated_upgrades_complete"),
                blockId + " upgrade metadata must report completeness");

        ExactItemTransferCoordinator<ItemStack> coordinator = new ExactItemTransferCoordinator<>();
        ExactItemTransferCoordinator.TransferResult result = coordinator.transfer(writer,
                UUID.randomUUID(), source.id(), source.port(), target.id(), target.port(),
                "minecraft:diamond", 7);

        helper.assertTrue(result.complete(), blockId + " exact generic transfer should complete");
        helper.assertTrue(handler.getStackInSlot(0).isEmpty(), blockId + " source should be empty");
        ItemStack received = destination.getItem(0);
        helper.assertTrue(received.getCount() == 7, "destination should receive all items");
        helper.assertTrue(received.hasTag() && requiredModId.equals(
                        received.getTag().getString("terminalcraft_test")),
                "generic transfer must preserve exact item metadata from " + blockId);

        verifyPlacedContainerContract(helper, access, sourceCandidate.id(), handler, exact, blockId);
        helper.succeed();
    }

    private static void verifyPlacedContainerContract(GameTestHelper helper,
                                                       AdjacentForgeDeviceAccess writerAccess,
                                                       UUID endpointId, IItemHandler handler,
                                                       ItemStack exact, String blockId) {
        ItemStack remainder = handler.insertItem(0, exact.copy(), false);
        helper.assertTrue(remainder.isEmpty(),
                "placed Sophisticated container must accept contract test contents");

        AdjacentForgeDeviceAccess shellAccess = new AdjacentForgeDeviceAccess(
                new DeviceRegistry().access(readOnlyContext("sophisticated-shell")),
                helper.getLevel(), helper.absolutePos(HOST));
        StorageShellCommand.Outcome shellQuery = StorageShellCommand.execute(shellAccess, List.of(
                "query", endpointId.toString(), "--resource", "minecraft:diamond", "--limit", "1"));
        helper.assertTrue(shellQuery.exitCode() == 0
                        && shellQuery.lines().equals(List.of("7  minecraft:diamond")),
                "the generic storage command must query " + blockId);

        AdjacentForgeDeviceAccess readerAccess = new AdjacentForgeDeviceAccess(
                new DeviceRegistry().access(readOnlyContext("sophisticated-reader")),
                helper.getLevel(), helper.absolutePos(HOST));
        DeviceResult pageResult = readerAccess.call(endpointId, "storage.query", List.of(
                DeviceValue.of("minecraft:diamond"), DeviceValue.of("minecraft"),
                DeviceValue.of("diamond"), DeviceValue.of(""), DeviceValue.of(1),
                DeviceValue.of("forge:gems/diamond")));
        DeviceValue.MapValue page = mapResult(pageResult);
        DeviceValue.ListValue entries = (DeviceValue.ListValue) page.values().get("entries");
        helper.assertTrue(entries.values().size() == 1,
                "generic query must find the placed container item through bounded filters");
        DeviceValue.MapValue entry = (DeviceValue.MapValue) entries.values().get(0);
        helper.assertTrue("7".equals(string(entry, "count")),
                "generic query must expose a lossless aggregate count");

        DeviceResult count = readerAccess.call(endpointId, "storage.count",
                List.of(DeviceValue.of("minecraft:diamond")));
        helper.assertTrue("7".equals(stringResult(count)),
                "generic count must read " + blockId);
        DeviceValue.MapValue rejected = mapResult(readerAccess.call(endpointId,
                "storage.extract.simulate", List.of(DeviceValue.of("minecraft:emerald"), DeviceValue.of(4))));
        helper.assertTrue(number(rejected, "accepted") == 0 && "partial".equals(string(rejected, "status")),
                "unsupported contents must be rejected without mutation");
        DeviceResult denied = readerAccess.call(endpointId, "storage.extract",
                List.of(DeviceValue.of("minecraft:diamond"), DeviceValue.of(1)));
        helper.assertTrue(denied.error().orElseThrow().code() == DeviceErrorCode.PERMISSION_DENIED,
                "generic mutation must require device.write");
        helper.assertTrue(handler.getStackInSlot(0).getCount() == 7,
                "simulation and denied mutation must not change " + blockId);

        DeviceValue.MapValue partial = mapResult(writerAccess.call(endpointId, "storage.extract",
                List.of(DeviceValue.of("minecraft:diamond"), DeviceValue.of(10))));
        helper.assertTrue(number(partial, "requested") == 10
                        && number(partial, "executed") == 7
                        && "partial".equals(string(partial, "status")),
                "authorized extraction must report the native handler's partial result");
        helper.assertTrue(handler.getStackInSlot(0).isEmpty(),
                "authorized extraction must mutate only through the supported " + blockId + " handler");
    }

    private static IStorageWrapper storageWrapper(BlockEntity blockEntity) {
        if (blockEntity instanceof StorageBlockEntity storage) return storage.getStorageWrapper();
        if (blockEntity instanceof BackpackBlockEntity backpack) return backpack.getBackpackWrapper();
        throw new AssertionError("expected a placed Sophisticated container");
    }

    private static long longStringProperty(DeviceDescriptor descriptor, String name) {
        return Long.parseLong(((DeviceValue.StringValue) descriptor.properties().get(name)).value());
    }

    private static String stringProperty(DeviceDescriptor descriptor, String name) {
        return ((DeviceValue.StringValue) descriptor.properties().get(name)).value();
    }

    private static DeviceCallContext readOnlyContext(String name) {
        return new DeviceCallContext(UUID.randomUUID(), name, Set.of(DeviceCallContext.READ));
    }

    private static Block requiredBlock(GameTestHelper helper, String blockId) {
        ResourceLocation key = ResourceLocation.tryParse(blockId);
        Block block = key == null ? null : ForgeRegistries.BLOCKS.getValue(key);
        helper.assertTrue(block != null && block != Blocks.AIR,
                "focused profile is missing expected block " + blockId);
        return block;
    }

    private static Item requiredItem(GameTestHelper helper, String itemId) {
        ResourceLocation key = ResourceLocation.tryParse(itemId);
        Item item = key == null ? null : ForgeRegistries.ITEMS.getValue(key);
        helper.assertTrue(item != null && item != Items.AIR,
                "focused profile is missing expected item " + itemId);
        return item;
    }

    private static DeviceValue.MapValue mapResult(DeviceResult result) {
        if (!result.isSuccess()) throw new AssertionError("expected successful device result: " + result.error());
        return (DeviceValue.MapValue) result.value().orElseThrow();
    }

    private static String stringResult(DeviceResult result) {
        if (!result.isSuccess()) throw new AssertionError("expected successful device result: " + result.error());
        return ((DeviceValue.StringValue) result.value().orElseThrow()).value();
    }

    private static String string(DeviceValue.MapValue value, String key) {
        return ((DeviceValue.StringValue) value.values().get(key)).value();
    }

    private static long number(DeviceValue.MapValue value, String key) {
        return (long) ((DeviceValue.NumberValue) value.values().get(key)).value();
    }

    private static void assertThrows(Runnable action, String message) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static double numberProperty(DeviceDescriptor descriptor, String name) {
        return ((DeviceValue.NumberValue) descriptor.properties().get(name)).value();
    }

    /** Mirrors the mod's normal placement handoff through its public block-entity API. */
    private static void initializePlacedBackpack(BlockEntity blockEntity, ResourceLocation itemId) {
        Item backpackItem = ForgeRegistries.ITEMS.getValue(itemId);
        if (backpackItem == null || backpackItem == Items.AIR) {
            throw new AssertionError("focused profile is missing expected item " + itemId);
        }
        if (!(blockEntity instanceof BackpackBlockEntity backpack)) {
            throw new AssertionError("expected a Sophisticated Backpack block entity");
        }
        backpack.setBackpack(new ItemStack(backpackItem));
    }
}
