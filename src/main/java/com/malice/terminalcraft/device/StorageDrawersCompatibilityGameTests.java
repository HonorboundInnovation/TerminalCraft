package com.malice.terminalcraft.device;

import com.jaquadro.minecraft.storagedrawers.api.storage.IDrawer;
import com.jaquadro.minecraft.storagedrawers.api.storage.IDrawerGroup;
import com.jaquadro.minecraft.storagedrawers.api.storage.IFractionalDrawer;
import com.jaquadro.minecraft.storagedrawers.api.storage.attribute.IProtectable;
import com.jaquadro.minecraft.storagedrawers.block.tile.BlockEntityDrawers;
import com.malice.terminalcraft.integration.OptionalIntegrations;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Set;
import java.util.UUID;

/** Focused live checks for public Storage Drawers metadata plus generic item transfer. */
@GameTestHolder("terminalcraft")
public final class StorageDrawersCompatibilityGameTests {
    private static final BlockPos HOST = new BlockPos(2, 2, 2);
    private static final BlockPos SOURCE = HOST.relative(Direction.WEST);
    private static final BlockPos DESTINATION = HOST.relative(Direction.EAST);

    private StorageDrawersCompatibilityGameTests() {}

    @GameTest(template = "empty")
    public static void standardDrawerUsesLogicalMetadataAndGenericTransfer(GameTestHelper helper) {
        if (!ModList.get().isLoaded("storagedrawers")) {
            helper.succeed();
            return;
        }
        OptionalIntegrations.initialize();

        Block block = requiredBlock(helper, "storagedrawers:oak_full_drawers_1");
        helper.setBlock(SOURCE, block);
        helper.setBlock(DESTINATION, Blocks.CHEST);

        BlockEntity sourceEntity = helper.getBlockEntity(SOURCE);
        helper.assertTrue(sourceEntity instanceof IDrawerGroup,
                "Storage Drawers block entity must expose the public IDrawerGroup API");
        IDrawerGroup group = (IDrawerGroup) sourceEntity;
        helper.assertTrue(group.getDrawerCount() == 1, "single drawer must expose one logical drawer");
        IDrawer drawer = group.getDrawer(0);
        ItemStack exact = new ItemStack(Items.DIAMOND, 7);
        exact.getOrCreateTag().putString("terminalcraft_test", "storagedrawers");
        drawer.setStoredItem(exact.copy(), exact.getCount());

        AdjacentContext context = adjacentContext(helper);
        DeviceDescriptor descriptor = context.access().descriptor(context.source().id()).orElseThrow();
        helper.assertTrue("storagedrawers:drawer_group".equals(descriptor.adapterId()),
                "individual drawer must select the Storage Drawers adapter");
        helper.assertTrue("storage_drawer".equals(descriptor.typeName()),
                "individual drawer must expose the storage_drawer type");
        helper.assertTrue(descriptor.capabilities().containsAll(
                        Set.of("inventory", "storage_drawers")),
                "drawer must retain generic inventory and add Storage Drawers metadata");
        helper.assertTrue(number(descriptor, "storage_drawers_drawer_count") == 1,
                "logical drawer count must be reported");
        helper.assertTrue(!bool(descriptor, "storage_drawers_compacting"),
                "standard drawer must use independent count semantics");
        helper.assertTrue("independent_drawers".equals(
                        string(descriptor, "storage_drawers_count_semantics")),
                "standard drawer count semantics must be explicit");
        DeviceValue.MapValue logicalDrawer = firstDrawer(descriptor);
        helper.assertTrue("minecraft:diamond".equals(string(logicalDrawer, "resource")),
                "logical resource must be reported");
        helper.assertTrue("7".equals(string(logicalDrawer, "count")),
                "logical count must use lossless decimal-string encoding");
        helper.assertTrue(bool(logicalDrawer, "count_independent"),
                "standard drawer count must be independently aggregatable");
        helper.assertTrue(!bool(logicalDrawer, "void"),
                "ordinary drawer must report voiding as disabled");
        net.minecraftforge.items.IItemHandler forgeHandler = sourceEntity
                .getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.EAST).resolve().orElseThrow();
        ItemStack visible = forgeHandler.getStackInSlot(0);
        ItemStack simulated = forgeHandler.extractItem(0, 7, true);
        helper.assertTrue(ItemStack.isSameItemSameTags(visible, simulated),
                "drawer simulation must preserve the visible exact item variant: visible="
                        + visible + " simulated=" + simulated);

        DeviceResult transfer = context.access().transferExactItems(UUID.randomUUID(), context.source().id(),
                context.destination().id(), "minecraft:diamond", 7);
        helper.assertTrue(transfer.isSuccess(),
                "exact transfer through drawer IItemHandler must succeed: " + transfer.error());
        helper.assertTrue(drawer.getStoredItemCount() == 0, "drawer source count must be conserved");
        ChestBlockEntity destination = (ChestBlockEntity) helper.getBlockEntity(DESTINATION);
        ItemStack received = destination.getItem(0);
        helper.assertTrue(received.getCount() == 7, "destination must receive all drawer items");
        helper.assertTrue(received.hasTag() && "storagedrawers".equals(
                        received.getTag().getString("terminalcraft_test")),
                "exact transfer must preserve item metadata");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void controllerExposesBoundedLogicalAggregate(GameTestHelper helper) {
        if (!ModList.get().isLoaded("storagedrawers")) {
            helper.succeed();
            return;
        }
        OptionalIntegrations.initialize();

        helper.setBlock(SOURCE, requiredBlock(helper, "storagedrawers:controller"));
        BlockPos drawerPosition = SOURCE.relative(Direction.WEST);
        helper.setBlock(drawerPosition, requiredBlock(helper, "storagedrawers:oak_full_drawers_1"));
        BlockEntity drawerEntity = helper.getBlockEntity(drawerPosition);
        helper.assertTrue(drawerEntity instanceof IDrawerGroup,
                "controller test drawer must expose IDrawerGroup");
        ((IDrawerGroup) drawerEntity).getDrawer(0).setStoredItem(new ItemStack(Items.GOLD_INGOT), 37);

        helper.runAfterDelay(20, () -> {
            BlockEntity controllerEntity = helper.getBlockEntity(SOURCE);
            helper.assertTrue(controllerEntity instanceof com.jaquadro.minecraft.storagedrawers.api.storage.IControlGroup,
                    "controller must expose the public IControlGroup API");
            AdjacentContext context = adjacentContext(helper);
            DeviceDescriptor descriptor = context.access().descriptor(context.source().id()).orElseThrow();
            helper.assertTrue("storagedrawers:controller".equals(descriptor.adapterId()),
                    "controller must select the controller adapter");
            helper.assertTrue("storage_drawer_controller".equals(descriptor.typeName()),
                    "controller must expose a distinct endpoint type");
            helper.assertTrue(descriptor.capabilities().containsAll(
                            Set.of("inventory", "storage_drawers", "storage_drawers_controller")),
                    "controller must retain inventory access and advertise controller semantics");
            helper.assertTrue("controller".equals(
                            string(descriptor, "storage_drawers_endpoint_kind")),
                    "controller endpoint kind must be explicit");
            helper.assertTrue(bool(descriptor, "storage_drawers_aggregate_complete"),
                    "small loaded controller network must provide a complete bounded snapshot");
            DeviceValue.ListValue aggregate = (DeviceValue.ListValue) descriptor.properties()
                    .get("storage_drawers_aggregate_entries");
            helper.assertTrue(aggregate.values().size() == 1,
                    "one network resource must produce one aggregate entry");
            DeviceValue.MapValue entry = (DeviceValue.MapValue) aggregate.values().get(0);
            helper.assertTrue("minecraft:gold_ingot".equals(string(entry, "resource")),
                    "aggregate must report the network resource");
            helper.assertTrue("37".equals(string(entry, "count")),
                    "aggregate count must use exact decimal-string encoding");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void controllerStorageQueryPaginatesDeterministically(GameTestHelper helper) {
        if (!ModList.get().isLoaded("storagedrawers")) {
            helper.succeed();
            return;
        }
        OptionalIntegrations.initialize();

        helper.setBlock(SOURCE, requiredBlock(helper, "storagedrawers:controller"));
        BlockPos westDrawer = SOURCE.relative(Direction.WEST);
        BlockPos northDrawer = SOURCE.relative(Direction.NORTH);
        helper.setBlock(westDrawer, requiredBlock(helper, "storagedrawers:oak_full_drawers_1"));
        helper.setBlock(northDrawer, requiredBlock(helper, "storagedrawers:oak_full_drawers_1"));
        ((IDrawerGroup) helper.getBlockEntity(westDrawer)).getDrawer(0)
                .setStoredItem(new ItemStack(Items.IRON_INGOT), 23);
        ((IDrawerGroup) helper.getBlockEntity(northDrawer)).getDrawer(0)
                .setStoredItem(new ItemStack(Items.GOLD_INGOT), 17);

        helper.runAfterDelay(20, () -> {
            AdjacentContext context = adjacentContext(helper);
            DeviceValue.MapValue first = successfulMap(helper, context.access().call(
                    context.source().id(), "storage.query", queryArguments("", 1)));
            DeviceValue.ListValue firstEntries = (DeviceValue.ListValue) first.values().get("entries");
            helper.assertTrue(firstEntries.values().size() == 1,
                    "controller query must honor a one-entry page limit");
            DeviceValue.MapValue firstEntry = (DeviceValue.MapValue) firstEntries.values().get(0);
            helper.assertTrue("minecraft:gold_ingot".equals(string(firstEntry, "resource")),
                    "controller query must sort resources deterministically");
            helper.assertTrue("17".equals(string(firstEntry, "count")),
                    "first controller query page must preserve the exact logical count");
            helper.assertTrue(bool(first, "has_more"),
                    "first controller query page must advertise another page");
            String cursor = string(first, "next_cursor");

            DeviceValue.MapValue second = successfulMap(helper, context.access().call(
                    context.source().id(), "storage.query", queryArguments(cursor, 1)));
            DeviceValue.ListValue secondEntries = (DeviceValue.ListValue) second.values().get("entries");
            helper.assertTrue(secondEntries.values().size() == 1,
                    "controller cursor must select exactly the next resource");
            DeviceValue.MapValue secondEntry = (DeviceValue.MapValue) secondEntries.values().get(0);
            helper.assertTrue("minecraft:iron_ingot".equals(string(secondEntry, "resource")),
                    "controller cursor must advance through the stable sorted order");
            helper.assertTrue("23".equals(string(secondEntry, "count")),
                    "second controller query page must preserve the exact logical count");
            helper.assertTrue(!bool(second, "has_more") && string(second, "next_cursor").isEmpty(),
                    "final controller query page must terminate the cursor chain");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 260)
    public static void controllerReacquiresNetworkAfterDrawerRemovalAndReplacement(GameTestHelper helper) {
        if (!ModList.get().isLoaded("storagedrawers")) {
            helper.succeed();
            return;
        }
        OptionalIntegrations.initialize();

        helper.setBlock(SOURCE, requiredBlock(helper, "storagedrawers:controller"));
        BlockPos drawerPosition = SOURCE.relative(Direction.WEST);
        helper.setBlock(drawerPosition, requiredBlock(helper, "storagedrawers:oak_full_drawers_1"));
        ((IDrawerGroup) helper.getBlockEntity(drawerPosition)).getDrawer(0)
                .setStoredItem(new ItemStack(Items.GOLD_INGOT), 11);

        helper.runAfterDelay(30, () -> {
            AdjacentContext initial = adjacentContext(helper);
            assertSingleQueryEntry(helper, initial, "minecraft:gold_ingot", "11");
            helper.setBlock(drawerPosition, Blocks.AIR);

            helper.runAfterDelay(40, () -> {
                AdjacentContext detached = adjacentContext(helper);
                DeviceValue.MapValue empty = successfulMap(helper, detached.access().call(
                        detached.source().id(), "storage.query", queryArguments("", 8)));
                DeviceValue.ListValue entries = (DeviceValue.ListValue) empty.values().get("entries");
                helper.assertTrue(entries.values().isEmpty(),
                        "controller query must drop a removed drawer instead of retaining stale contents");

                helper.setBlock(drawerPosition, requiredBlock(helper, "storagedrawers:oak_full_drawers_1"));
                ((IDrawerGroup) helper.getBlockEntity(drawerPosition)).getDrawer(0)
                        .setStoredItem(new ItemStack(Items.DIAMOND), 5);
                helper.runAfterDelay(80, () -> {
                    AdjacentContext replaced = adjacentContext(helper);
                    assertSingleQueryEntry(helper, replaced, "minecraft:diamond", "5");
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 260)
    public static void controllerReplacementInvalidatesAndReacquiresEndpoint(GameTestHelper helper) {
        if (!ModList.get().isLoaded("storagedrawers")) {
            helper.succeed();
            return;
        }
        OptionalIntegrations.initialize();

        helper.setBlock(SOURCE, requiredBlock(helper, "storagedrawers:controller"));
        BlockPos drawerPosition = SOURCE.relative(Direction.WEST);
        helper.setBlock(drawerPosition, requiredBlock(helper, "storagedrawers:oak_full_drawers_1"));
        ((IDrawerGroup) helper.getBlockEntity(drawerPosition)).getDrawer(0)
                .setStoredItem(new ItemStack(Items.EMERALD), 9);

        helper.runAfterDelay(30, () -> {
            AdjacentContext initial = adjacentContext(helper);
            assertSingleQueryEntry(helper, initial, "minecraft:emerald", "9");
            UUID stableAddressId = initial.source().id();
            helper.setBlock(SOURCE, Blocks.AIR);

            helper.runAfterDelay(2, () -> {
                DeviceResult absent = adjacentContext(helper).access().call(stableAddressId,
                        "storage.query", queryArguments("", 8));
                helper.assertTrue(!absent.isSuccess()
                                && absent.error().orElseThrow().code() == DeviceErrorCode.REMOVED,
                        "removed controller endpoint must report removal instead of serving a stale snapshot");
                helper.setBlock(SOURCE, requiredBlock(helper, "storagedrawers:controller"));

                helper.runAfterDelay(80, () -> {
                    AdjacentContext replacement = adjacentContext(helper);
                    helper.assertTrue(replacement.source().id().equals(stableAddressId),
                            "replacement keeps the stable address identity but must resolve fresh state");
                    assertSingleQueryEntry(helper, replacement, "minecraft:emerald", "9");
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void controllerAggregatesMultipleCompactingPoolsOnce(GameTestHelper helper) {
        if (!ModList.get().isLoaded("storagedrawers")) {
            helper.succeed();
            return;
        }
        OptionalIntegrations.initialize();

        helper.setBlock(SOURCE, requiredBlock(helper, "storagedrawers:controller"));
        BlockPos ironPosition = SOURCE.relative(Direction.WEST);
        BlockPos goldPosition = SOURCE.relative(Direction.NORTH);
        helper.setBlock(ironPosition, requiredBlock(helper, "storagedrawers:compacting_drawers_3"));
        helper.setBlock(goldPosition, requiredBlock(helper, "storagedrawers:compacting_drawers_3"));
        IDrawerGroup iron = (IDrawerGroup) helper.getBlockEntity(ironPosition);
        IDrawerGroup gold = (IDrawerGroup) helper.getBlockEntity(goldPosition);
        iron.getDrawer(0).setStoredItem(new ItemStack(Items.IRON_BLOCK), 3);
        gold.getDrawer(0).setStoredItem(new ItemStack(Items.GOLD_BLOCK), 2);
        IFractionalDrawer ironSmallest = smallestFractional(iron);
        IFractionalDrawer goldSmallest = smallestFractional(gold);

        helper.runAfterDelay(30, () -> {
            AdjacentContext context = adjacentContext(helper);
            DeviceValue.MapValue page = successfulMap(helper, context.access().call(
                    context.source().id(), "storage.query", queryArguments("", 8)));
            DeviceValue.ListValue entries = (DeviceValue.ListValue) page.values().get("entries");
            helper.assertTrue(entries.values().size() == 2,
                    "two compacting pools must produce exactly two canonical resources");
            assertAggregateEntry(helper, entries, resource(ironSmallest.getStoredItemPrototype()),
                    Integer.toString(ironSmallest.getStoredItemCount()));
            assertAggregateEntry(helper, entries, resource(goldSmallest.getStoredItemPrototype()),
                    Integer.toString(goldSmallest.getStoredItemCount()));
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void upgradeEffectsAreReportedThroughPublicAttributes(GameTestHelper helper) {
        if (!ModList.get().isLoaded("storagedrawers")) {
            helper.succeed();
            return;
        }
        OptionalIntegrations.initialize();

        helper.setBlock(SOURCE, requiredBlock(helper, "storagedrawers:oak_full_drawers_1"));
        BlockEntity entity = helper.getBlockEntity(SOURCE);
        helper.assertTrue(entity instanceof BlockEntityDrawers,
                "focused upgrade fixture requires a Storage Drawers block entity");
        BlockEntityDrawers drawers = (BlockEntityDrawers) entity;
        ItemStack voidUpgrade = new ItemStack(requiredItem(helper, "storagedrawers:void_upgrade"));
        helper.assertTrue(drawers.upgrades().addUpgrade(voidUpgrade), "void upgrade fixture must install");

        DeviceDescriptor descriptor = adjacentContext(helper).access().descriptor(
                adjacentContext(helper).source().id()).orElseThrow();
        DeviceValue.MapValue drawer = firstDrawer(descriptor);
        helper.assertTrue(bool(drawer, "void"), "void upgrade effect must be reported");
        helper.assertTrue(!bool(drawer, "unlimited_storage") && !bool(drawer, "unlimited_vending"),
                "void upgrade must not be mislabeled as a creative upgrade");
        helper.assertTrue(bool(descriptor, "storage_drawers_mutation_supported"),
                "an unprotected individual drawer remains available through its native handler");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void creativeUpgradeEffectsAreReported(GameTestHelper helper) {
        if (!ModList.get().isLoaded("storagedrawers")) {
            helper.succeed();
            return;
        }
        OptionalIntegrations.initialize();

        helper.setBlock(SOURCE, requiredBlock(helper, "storagedrawers:oak_full_drawers_1"));
        helper.setBlock(DESTINATION, requiredBlock(helper, "storagedrawers:oak_full_drawers_1"));
        BlockEntityDrawers storage = (BlockEntityDrawers) helper.getBlockEntity(SOURCE);
        BlockEntityDrawers vending = (BlockEntityDrawers) helper.getBlockEntity(DESTINATION);
        helper.assertTrue(storage.upgrades().addUpgrade(new ItemStack(requiredItem(
                        helper, "storagedrawers:creative_storage_upgrade"))),
                "creative storage fixture must install");
        helper.assertTrue(vending.upgrades().addUpgrade(new ItemStack(requiredItem(
                        helper, "storagedrawers:creative_vending_upgrade"))),
                "creative vending fixture must install");

        AdjacentContext context = adjacentContext(helper);
        DeviceValue.MapValue storageDrawer = firstDrawer(
                context.access().descriptor(context.source().id()).orElseThrow());
        DeviceValue.MapValue vendingDrawer = firstDrawer(
                context.access().descriptor(context.destination().id()).orElseThrow());
        helper.assertTrue(bool(storageDrawer, "unlimited_storage"),
                "creative storage effect must be reported");
        helper.assertTrue(bool(vendingDrawer, "unlimited_vending"),
                "creative vending effect must be reported");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void protectedDrawerDeniesGenericAndExactMutation(GameTestHelper helper) {
        if (!ModList.get().isLoaded("storagedrawers")) {
            helper.succeed();
            return;
        }
        OptionalIntegrations.initialize();

        helper.setBlock(SOURCE, requiredBlock(helper, "storagedrawers:oak_full_drawers_1"));
        helper.setBlock(DESTINATION, Blocks.CHEST);
        BlockEntity entity = helper.getBlockEntity(SOURCE);
        helper.assertTrue(entity instanceof IProtectable, "drawer must expose public protection state");
        UUID owner = UUID.randomUUID();
        helper.assertTrue(((IProtectable) entity).setOwner(owner), "test owner must be accepted");
        ((IDrawerGroup) entity).getDrawer(0).setStoredItem(new ItemStack(Items.DIAMOND), 4);

        AdjacentContext context = adjacentContext(helper);
        DeviceDescriptor descriptor = context.access().descriptor(context.source().id()).orElseThrow();
        helper.assertTrue(bool(descriptor, "storage_drawers_protected"),
                "descriptor must report protection without exposing the owner UUID");
        helper.assertTrue(!bool(descriptor, "storage_drawers_mutation_supported"),
                "protected drawer mutation must be disabled");
        DeviceResult generic = context.access().call(context.source().id(), "inventory.extract",
                java.util.List.of(DeviceValue.of("minecraft:diamond"), DeviceValue.of(1)));
        helper.assertTrue(!generic.isSuccess() && generic.error().orElseThrow().code() == DeviceErrorCode.PERMISSION_DENIED,
                "generic mutation must deny protected drawers");
        DeviceResult exact = context.access().transferExactItems(UUID.randomUUID(), context.source().id(),
                context.destination().id(), "minecraft:diamond", 1);
        helper.assertTrue(!exact.isSuccess() && exact.error().orElseThrow().code() == DeviceErrorCode.PERMISSION_DENIED,
                "exact mutation must deny protected drawers");
        helper.assertTrue(((IDrawerGroup) entity).getDrawer(0).getStoredItemCount() == 4,
                "denied mutations must leave protected contents unchanged");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void compactingDrawerExposesOneCanonicalSharedPoolCount(GameTestHelper helper) {
        if (!ModList.get().isLoaded("storagedrawers")) {
            helper.succeed();
            return;
        }
        OptionalIntegrations.initialize();

        helper.setBlock(SOURCE, requiredBlock(helper, "storagedrawers:compacting_drawers_3"));
        BlockEntity sourceEntity = helper.getBlockEntity(SOURCE);
        helper.assertTrue(sourceEntity instanceof IDrawerGroup,
                "compacting drawer must expose the public IDrawerGroup API");
        IDrawerGroup group = (IDrawerGroup) sourceEntity;
        helper.assertTrue(group.getDrawerCount() == 3,
                "three-slot compacting drawer must expose three conversion views");
        group.getDrawer(0).setStoredItem(new ItemStack(Items.IRON_BLOCK), 3);

        IFractionalDrawer smallest = null;
        int maxConversionRate = 1;
        for (int slot = 0; slot < group.getDrawerCount(); slot++) {
            helper.assertTrue(group.getDrawer(slot) instanceof IFractionalDrawer,
                    "every compacting view must implement IFractionalDrawer");
            IFractionalDrawer fractional = (IFractionalDrawer) group.getDrawer(slot);
            maxConversionRate = Math.max(maxConversionRate, fractional.getConversionRate());
            if (fractional.isSmallestUnit()) smallest = fractional;
        }
        helper.assertTrue(smallest != null, "initialized compacting drawer must identify a smallest unit");

        AdjacentContext context = adjacentContext(helper);
        DeviceDescriptor descriptor = context.access().descriptor(context.source().id()).orElseThrow();
        helper.assertTrue(bool(descriptor, "storage_drawers_compacting"),
                "compacting drawer must be identified explicitly");
        helper.assertTrue("shared_pool_views".equals(
                        string(descriptor, "storage_drawers_count_semantics")),
                "compacting views must be marked as one shared pool");
        helper.assertTrue(resource(smallest.getStoredItemPrototype()).equals(
                        string(descriptor, "storage_drawers_canonical_resource")),
                "canonical resource must be the smallest-unit view");
        helper.assertTrue(Integer.toString(smallest.getStoredItemCount()).equals(
                        string(descriptor, "storage_drawers_canonical_count")),
                "canonical count must equal the non-duplicating smallest-unit pool count");

        DeviceValue.ListValue views = drawers(descriptor);
        helper.assertTrue(views.values().size() == 3, "all three conversion views must be bounded metadata");
        int smallestMarkers = 0;
        for (DeviceValue raw : views.values()) {
            DeviceValue.MapValue view = (DeviceValue.MapValue) raw;
            helper.assertTrue(!bool(view, "count_independent"),
                    "compacting view counts must never be advertised as independently summable");
            int rate = (int) number(view, "conversion_rate");
            helper.assertTrue((int) number(view, "smallest_units_per_item") == maxConversionRate / rate,
                    "normalization must expose each representation in smallest-unit terms");
            if (bool(view, "smallest_unit")) smallestMarkers++;
        }
        helper.assertTrue(smallestMarkers == 1,
                "exactly one compacting view must be the canonical smallest unit");
        helper.succeed();
    }

    private static java.util.List<DeviceValue> queryArguments(String cursor, int limit) {
        return java.util.List.of(DeviceValue.of(""), DeviceValue.of(""), DeviceValue.of(""),
                DeviceValue.of(cursor), DeviceValue.of(limit));
    }

    private static DeviceValue.MapValue successfulMap(GameTestHelper helper, DeviceResult result) {
        helper.assertTrue(result.isSuccess(), "storage.query must succeed: " + result.error());
        DeviceValue value = result.value().orElseThrow();
        helper.assertTrue(value instanceof DeviceValue.MapValue,
                "storage.query must return a structured map");
        return (DeviceValue.MapValue) value;
    }

    private static void assertSingleQueryEntry(GameTestHelper helper, AdjacentContext context,
                                               String resource, String count) {
        DeviceValue.MapValue page = successfulMap(helper, context.access().call(
                context.source().id(), "storage.query", queryArguments("", 8)));
        DeviceValue.ListValue entries = (DeviceValue.ListValue) page.values().get("entries");
        helper.assertTrue(entries.values().size() == 1,
                "controller query must expose exactly one current resource; expected "
                        + resource + " x" + count + " but received " + entries.values());
        DeviceValue.MapValue entry = (DeviceValue.MapValue) entries.values().get(0);
        helper.assertTrue(resource.equals(string(entry, "resource")),
                "controller query resource must reflect the current network");
        helper.assertTrue(count.equals(string(entry, "count")),
                "controller query count must reflect the current network");
    }

    private static IFractionalDrawer smallestFractional(IDrawerGroup group) {
        for (int slot = 0; slot < group.getDrawerCount(); slot++) {
            if (group.getDrawer(slot) instanceof IFractionalDrawer fractional && fractional.isSmallestUnit()) {
                return fractional;
            }
        }
        throw new IllegalStateException("compacting drawer has no smallest-unit view");
    }

    private static void assertAggregateEntry(GameTestHelper helper, DeviceValue.ListValue entries,
                                             String resource, String count) {
        for (DeviceValue raw : entries.values()) {
            DeviceValue.MapValue entry = (DeviceValue.MapValue) raw;
            if (resource.equals(string(entry, "resource"))) {
                helper.assertTrue(count.equals(string(entry, "count")),
                        "aggregate count mismatch for " + resource);
                return;
            }
        }
        helper.assertTrue(false, "aggregate is missing " + resource);
    }

    private static net.minecraft.world.item.Item requiredItem(GameTestHelper helper, String id) {
        ResourceLocation itemId = new ResourceLocation(id);
        net.minecraft.world.item.Item item = ForgeRegistries.ITEMS.getValue(itemId);
        helper.assertTrue(item != null && item != Items.AIR,
                "focused profile is missing expected item " + itemId);
        return item;
    }

    private static Block requiredBlock(GameTestHelper helper, String id) {
        ResourceLocation blockId = new ResourceLocation(id);
        Block block = ForgeRegistries.BLOCKS.getValue(blockId);
        helper.assertTrue(block != null && block != Blocks.AIR,
                "focused profile is missing expected block " + blockId);
        return block;
    }

    private static AdjacentContext adjacentContext(GameTestHelper helper) {
        String dimension = helper.getLevel().dimension().location().toString();
        BlockPos absoluteHost = helper.absolutePos(HOST);
        AdjacentForgeEndpointResolver.Candidate source =
                AdjacentForgeEndpointResolver.adjacent(dimension, absoluteHost, Direction.WEST);
        AdjacentForgeEndpointResolver.Candidate destination =
                AdjacentForgeEndpointResolver.adjacent(dimension, absoluteHost, Direction.EAST);
        DeviceCallContext writer = new DeviceCallContext(UUID.randomUUID(), "gametest",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        AdjacentForgeDeviceAccess access = new AdjacentForgeDeviceAccess(
                new DeviceRegistry().access(writer), helper.getLevel(), absoluteHost);
        return new AdjacentContext(access, source, destination);
    }

    private static DeviceValue.MapValue firstDrawer(DeviceDescriptor descriptor) {
        return (DeviceValue.MapValue) drawers(descriptor).values().get(0);
    }

    private static DeviceValue.ListValue drawers(DeviceDescriptor descriptor) {
        return (DeviceValue.ListValue) descriptor.properties().get("storage_drawers_drawers");
    }

    private static double number(DeviceDescriptor descriptor, String key) {
        return ((DeviceValue.NumberValue) descriptor.properties().get(key)).value();
    }

    private static double number(DeviceValue.MapValue value, String key) {
        return ((DeviceValue.NumberValue) value.values().get(key)).value();
    }

    private static String string(DeviceDescriptor descriptor, String key) {
        return ((DeviceValue.StringValue) descriptor.properties().get(key)).value();
    }

    private static String string(DeviceValue.MapValue value, String key) {
        return ((DeviceValue.StringValue) value.values().get(key)).value();
    }

    private static boolean bool(DeviceDescriptor descriptor, String key) {
        return ((DeviceValue.BooleanValue) descriptor.properties().get(key)).value();
    }

    private static boolean bool(DeviceValue.MapValue value, String key) {
        return ((DeviceValue.BooleanValue) value.values().get(key)).value();
    }

    private static String resource(ItemStack stack) {
        return stack.isEmpty() ? "minecraft:air"
                : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private record AdjacentContext(AdjacentForgeDeviceAccess access,
                                   AdjacentForgeEndpointResolver.Candidate source,
                                   AdjacentForgeEndpointResolver.Candidate destination) {}
}
