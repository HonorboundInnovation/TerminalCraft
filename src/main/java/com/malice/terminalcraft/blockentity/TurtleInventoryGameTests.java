package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.items.IItemHandler;

@GameTestHolder("terminalcraft")
public final class TurtleInventoryGameTests {
    private static net.minecraft.world.level.block.entity.HopperBlockEntity hopper(
            GameTestHelper helper, BlockPos pos, net.minecraft.core.Direction facing) {
        helper.setBlock(pos, Blocks.HOPPER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.HopperBlock.FACING, facing));
        return (net.minecraft.world.level.block.entity.HopperBlockEntity) helper.getBlockEntity(pos);
    }

    private static int hopperCount(net.minecraft.world.level.block.entity.HopperBlockEntity hopper,
                                   net.minecraft.world.item.Item item) {
        int count = 0;
        for (int slot = 0; slot < hopper.getContainerSize(); slot++) {
            if (hopper.getItem(slot).is(item)) count += hopper.getItem(slot).getCount();
        }
        return count;
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void tickingHoppersTransferThroughTurtleExactlyOnce(GameTestHelper helper) {
        TurtleBlockEntity turtle = create(helper);
        var input = hopper(helper, LOCAL.above(), net.minecraft.core.Direction.DOWN);
        var output = hopper(helper, LOCAL.below(), net.minecraft.core.Direction.EAST);
        input.setItem(0, new ItemStack(Items.DIAMOND, 8));
        helper.runAfterDelay(90, () -> {
            helper.assertTrue(hopperCount(input, Items.DIAMOND) == 0, "input hopper delivers all eight items");
            helper.assertTrue(hopperCount(output, Items.DIAMOND) == 8, "output hopper extracts exactly eight items");
            for (int slot = 0; slot < 16; slot++) helper.assertTrue(items(turtle).getStackInSlot(slot).isEmpty(),
                    "no duplicate items remain in turtle");
            helper.assertTrue(turtle.fuelBalance() == 0, "hopper transfers consume no turtle fuel");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void tickingHopperRetriesAfterTurtleCapacityIsFreed(GameTestHelper helper) {
        TurtleBlockEntity turtle = create(helper);
        for (int slot = 0; slot < 16; slot++) items(turtle).insertItem(slot, new ItemStack(Items.COBBLESTONE, 64), false);
        var input = hopper(helper, LOCAL.above(), net.minecraft.core.Direction.DOWN);
        input.setItem(0, new ItemStack(Items.DIAMOND, 3));
        helper.runAfterDelay(40, () -> {
            helper.assertTrue(hopperCount(input, Items.DIAMOND) == 3, "full turtle does not consume rejected items");
            helper.assertTrue(items(turtle).extractItem(0, 64, false).getCount() == 64, "free one slot");
            helper.runAfterDelay(40, () -> {
                helper.assertTrue(hopperCount(input, Items.DIAMOND) == 0, "hopper retries without manual reset");
                helper.assertTrue(items(turtle).getStackInSlot(0).is(Items.DIAMOND)
                        && items(turtle).getStackInSlot(0).getCount() == 3, "freed slot receives exactly three items");
                for (int slot = 1; slot < 16; slot++) helper.assertTrue(items(turtle).getStackInSlot(slot).getCount() == 64,
                        "existing full slots stay unchanged");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void tickingHoppersFollowWorldPositionAfterTurtleMoves(GameTestHelper helper) {
        TurtleBlockEntity turtle = create(helper);
        var input = hopper(helper, LOCAL.above(), net.minecraft.core.Direction.DOWN);
        var output = hopper(helper, LOCAL.north().below(), net.minecraft.core.Direction.EAST);
        input.setItem(0, new ItemStack(Items.EMERALD, 3));
        items(turtle).insertItem(0, new ItemStack(Items.DIAMOND, 4), false);
        CompoundTag data = turtle.saveWithoutMetadata();
        data.putInt("Fuel", 2);
        turtle.load(data);
        IItemHandler stale = items(turtle);
        helper.assertTrue(turtle.turtleForward(), "turtle moves over destination hopper");
        var moved = (TurtleBlockEntity) helper.getBlockEntity(LOCAL.north());
        helper.runAfterDelay(70, () -> {
            helper.assertTrue(hopperCount(input, Items.EMERALD) == 3, "old input cannot feed a moved turtle");
            helper.assertTrue(hopperCount(output, Items.DIAMOND) == 4, "new output extracts moved inventory exactly once");
            helper.assertTrue(stale.extractItem(0, 64, false).isEmpty(), "cached old handler cannot duplicate moved items");
            helper.assertTrue(items(moved).getStackInSlot(0).isEmpty() && moved.fuelBalance() == 1,
                    "moved inventory drains without additional fuel cost");
            helper.succeed();
        });
    }

    private static TurtleBlockEntity placementTurtle(GameTestHelper helper, ItemStack stack) {
        TurtleBlockEntity turtle = create(helper);
        CompoundTag data = turtle.saveWithoutMetadata();
        data.putInt("Fuel", 5);
        turtle.load(data);
        items(turtle).insertItem(0, stack, false);
        return turtle;
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void placementOrientsHorizontalAndVerticalBlocks(GameTestHelper helper) {
        TurtleBlockEntity turtle = placementTurtle(helper, new ItemStack(Items.FURNACE));
        helper.assertTrue(turtle.turtlePlace("front"), "furnace placement succeeds");
        helper.assertTrue(helper.getBlockState(LOCAL.north()).getValue(net.minecraft.world.level.block.FurnaceBlock.FACING)
                == net.minecraft.core.Direction.SOUTH, "furnace faces back toward turtle");
        items(turtle).insertItem(1, new ItemStack(Items.PISTON), false);
        turtle.turtleSelect(2);
        helper.setBlock(LOCAL.above(), Blocks.AIR);
        helper.assertTrue(turtle.turtlePlace("up"), "vertical piston placement succeeds");
        helper.assertTrue(helper.getBlockState(LOCAL.above()).getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)
                == net.minecraft.core.Direction.DOWN, "vertical look direction orients piston toward turtle");
        helper.assertTrue(turtle.fuelBalance() == 3 && items(turtle).getStackInSlot(0).isEmpty()
                && items(turtle).getStackInSlot(1).isEmpty(), "two placements consume exactly two items and fuel");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void doorPlacementChecksSupportAndUpperSpace(GameTestHelper helper) {
        TurtleBlockEntity turtle = placementTurtle(helper, new ItemStack(Items.OAK_DOOR));
        helper.setBlock(LOCAL.north().below(), Blocks.AIR);
        helper.setBlock(LOCAL.north().above(), Blocks.AIR);
        CompoundTag before = turtle.saveWithoutMetadata();
        helper.assertTrue(!turtle.turtlePlace("front") && before.equals(turtle.saveWithoutMetadata()), "unsupported door consumes nothing");
        helper.setBlock(LOCAL.north().below(), Blocks.STONE);
        helper.setBlock(LOCAL.north().above(), Blocks.STONE);
        helper.assertTrue(!turtle.turtlePlace("front") && before.equals(turtle.saveWithoutMetadata()), "blocked upper half consumes nothing");
        helper.assertTrue(helper.getBlockState(LOCAL.north()).isAir(), "failed door leaves no partial lower half");
        helper.setBlock(LOCAL.north().above(), Blocks.AIR);
        helper.assertTrue(turtle.turtlePlace("front"), "valid door placement succeeds");
        helper.assertTrue(helper.getBlockState(LOCAL.north()).is(Blocks.OAK_DOOR)
                && helper.getBlockState(LOCAL.north().above()).is(Blocks.OAK_DOOR), "both door halves are placed");
        helper.assertTrue(turtle.fuelBalance() == 4 && items(turtle).getStackInSlot(0).isEmpty(), "multi-block item charged once");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void slabPlacementCombinesWithoutOverwritingSolidBlocks(GameTestHelper helper) {
        TurtleBlockEntity turtle = placementTurtle(helper, new ItemStack(Items.STONE_SLAB, 3));
        helper.setBlock(LOCAL.below(), Blocks.AIR);
        helper.assertTrue(turtle.turtlePlace("down") && turtle.turtlePlace("down"), "slab item supports combining");
        helper.assertTrue(helper.getBlockState(LOCAL.below()).getValue(net.minecraft.world.level.block.SlabBlock.TYPE)
                == net.minecraft.world.level.block.state.properties.SlabType.DOUBLE, "two halves form double slab");
        CompoundTag before = turtle.saveWithoutMetadata();
        helper.assertTrue(!turtle.turtlePlace("down") && before.equals(turtle.saveWithoutMetadata()), "solid double slab is not overwritten or charged");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void placementPreservesContainerItemData(GameTestHelper helper) {
        ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
        CompoundTag data = new CompoundTag();
        var entries = new net.minecraft.nbt.ListTag();
        CompoundTag contents = new ItemStack(Items.DIAMOND, 5).save(new CompoundTag());
        contents.putByte("Slot", (byte) 0);
        entries.add(contents);
        data.put("Items", entries);
        net.minecraft.world.item.BlockItem.setBlockEntityData(shulker,
                net.minecraft.world.level.block.entity.BlockEntityType.SHULKER_BOX, data);
        shulker.setHoverName(net.minecraft.network.chat.Component.literal("turtle cargo"));
        TurtleBlockEntity turtle = placementTurtle(helper, shulker);
        helper.assertTrue(turtle.turtlePlace("front"), "container item placement succeeds");
        var placed = (net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity) helper.getBlockEntity(LOCAL.north());
        helper.assertTrue(placed.getItem(0).is(Items.DIAMOND) && placed.getItem(0).getCount() == 5,
                "container contents survive placement");
        helper.assertTrue(placed.getCustomName() != null && placed.getCustomName().getString().equals("turtle cargo"), "custom name survives placement");
        helper.assertTrue(items(turtle).getStackInSlot(0).isEmpty(), "placed container item is consumed exactly once");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void diggingCollectsOnlyFreshDropsAndLeavesOverflow(GameTestHelper helper) {
        TurtleBlockEntity turtle = create(helper);
        CompoundTag saved = turtle.saveWithoutMetadata();
        saved.putInt("Fuel", 5);
        turtle.load(saved);
        BlockPos target = turtle.getBlockPos().north();
        ItemEntity existing = new ItemEntity(helper.getLevel(), target.getX() + .5, target.getY() + .5,
                target.getZ() + .5, new ItemStack(Items.DIAMOND, 5));
        helper.getLevel().addFreshEntity(existing);
        helper.setBlock(LOCAL.north(), Blocks.COBBLESTONE);
        helper.assertTrue(turtle.turtleDig("front"), "dig succeeds");
        helper.assertTrue(items(turtle).getStackInSlot(0).is(Items.COBBLESTONE)
                && items(turtle).getStackInSlot(0).getCount() == 1, "fresh drop enters inventory");
        helper.assertTrue(existing.isAlive() && existing.getItem().getCount() == 5, "unrelated loose items are untouched");
        for (int slot = 0; slot < 16; slot++) items(turtle).insertItem(slot, new ItemStack(Items.COBBLESTONE, 64), false);
        helper.setBlock(LOCAL.north(), Blocks.COBBLESTONE);
        helper.assertTrue(turtle.turtleDig("front"), "full inventory still allows digging with world overflow");
        int overflow = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(target).inflate(.5)).stream()
                .map(ItemEntity::getItem).filter(s -> s.is(Items.COBBLESTONE)).mapToInt(ItemStack::getCount).sum();
        helper.assertTrue(overflow == 1 && items(turtle).getStackInSlot(0).getCount() == 64,
                "full inventory leaves exactly one new drop in world");
        helper.assertTrue(turtle.fuelBalance() == 3 && existing.getItem().getCount() == 5, "two successful digs charge twice without vacuuming old drops");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void diggingContainerCollectsContentsWithoutDuplicatingOverflow(GameTestHelper helper) {
        TurtleBlockEntity turtle = create(helper);
        CompoundTag saved = turtle.saveWithoutMetadata();
        saved.putInt("Fuel", 5);
        turtle.load(saved);
        for (int slot = 0; slot < 16; slot++) items(turtle).insertItem(slot, new ItemStack(Items.DIAMOND, 64), false);
        items(turtle).extractItem(0, 2, false);
        helper.setBlock(LOCAL.north(), Blocks.CHEST);
        var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) helper.getBlockEntity(LOCAL.north());
        chest.setItem(0, new ItemStack(Items.DIAMOND, 5));
        helper.assertTrue(turtle.turtleDig("front"), "container dig succeeds");
        helper.assertTrue(items(turtle).getStackInSlot(0).getCount() == 64, "available capacity receives exactly two items");
        var drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(turtle.getBlockPos().north()).inflate(.5));
        int diamonds = drops.stream().map(ItemEntity::getItem).filter(s -> s.is(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum();
        int chests = drops.stream().map(ItemEntity::getItem).filter(s -> s.is(Items.CHEST)).mapToInt(ItemStack::getCount).sum();
        helper.assertTrue(diamonds == 3 && chests == 1, "remaining container contents and block drop stay in world exactly once");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void inventoryMenuTransfersSelectsRefuelsAndRejectsStaleAccess(GameTestHelper helper) {
        TurtleBlockEntity turtle = create(helper);
        var player = helper.makeMockPlayer();
        var pos = turtle.getBlockPos();
        player.setPos(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
        player.getInventory().setItem(9, new ItemStack(Items.COAL, 5));
        var menu = new com.malice.terminalcraft.menu.TurtleInventoryMenu(4, player.getInventory(), turtle);
        helper.assertTrue(menu.stillValid(player) && menu.slots.size() == 52, "sixteen turtle and thirty-six player slots");
        helper.assertTrue(!menu.quickMoveStack(player, 16).isEmpty(), "shift-click inserts player items");
        helper.assertTrue(player.getInventory().getItem(9).isEmpty() && items(turtle).getStackInSlot(0).getCount() == 5,
                "insertion neither loses nor duplicates items");
        helper.assertTrue(menu.clickMenuButton(player, 2), "refuel button consumes selected fuel");
        helper.assertTrue(menu.fuel() == 80 && items(turtle).getStackInSlot(0).getCount() == 4, "fuel result is synchronized");
        helper.assertTrue(menu.clickMenuButton(player, 0) && menu.selectedSlot() == 15, "previous wraps selection");
        helper.assertTrue(menu.clickMenuButton(player, 1) && menu.selectedSlot() == 0, "next wraps selection");
        helper.assertTrue(!menu.clickMenuButton(player, 99), "unknown button rejected");
        helper.assertTrue(!menu.quickMoveStack(player, 0).isEmpty() && items(turtle).getStackInSlot(0).isEmpty(), "shift-click extracts turtle items");
        int coal = 0;
        for (int index = 0; index < 36; index++) {
            ItemStack stack = player.getInventory().getItem(index);
            if (stack.is(Items.COAL)) coal += stack.getCount();
        }
        helper.assertTrue(coal == 4, "extraction preserves exactly the remaining four coal");
        CompoundTag saved = turtle.saveWithoutMetadata();
        saved.putInt("Fuel", 70000);
        turtle.load(saved);
        helper.assertTrue(menu.fuel() == 70000, "fuel data retains high bits beyond sixteen-bit menu packets");
        helper.assertTrue(turtle.turtleForward(), "another session may move the turtle");
        helper.assertTrue(!menu.stillValid(player), "inventory session closes after movement");
        helper.assertTrue(!menu.clickMenuButton(player, 2) && menu.quickMoveStack(player, 16).isEmpty(), "stale session cannot mutate items or fuel");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void fullInventoryAndDistantMenuPreservePlayerItems(GameTestHelper helper) {
        TurtleBlockEntity turtle = create(helper);
        for (int slot = 0; slot < 16; slot++) items(turtle).insertItem(slot, new ItemStack(Items.COBBLESTONE, 64), false);
        var player = helper.makeMockPlayer();
        var pos = turtle.getBlockPos();
        player.setPos(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5);
        player.getInventory().setItem(9, new ItemStack(Items.COAL, 7));
        var menu = new com.malice.terminalcraft.menu.TurtleInventoryMenu(5, player.getInventory(), turtle);
        helper.assertTrue(menu.quickMoveStack(player, 16).isEmpty(), "full inventory rejects transfer");
        helper.assertTrue(player.getInventory().getItem(9).getCount() == 7, "full destination consumes no items");
        player.setPos(pos.getX() + 20, pos.getY(), pos.getZ());
        helper.assertTrue(!menu.stillValid(player) && menu.quickMoveStack(player, 0).isEmpty(), "distance rejects transfer");
        helper.assertTrue(items(turtle).getStackInSlot(0).getCount() == 64, "rejected extraction preserves turtle items");
        helper.succeed();
    }

    private static final BlockPos LOCAL = new BlockPos(3, 2, 3);

    private static TurtleBlockEntity create(GameTestHelper helper) {
        helper.setBlock(LOCAL, ModRegistries.TURTLE_BLOCK.get());
        helper.setBlock(LOCAL.north(), Blocks.AIR);
        return (TurtleBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(LOCAL));
    }

    private static IItemHandler items(TurtleBlockEntity turtle) {
        return turtle.getCapability(ForgeCapabilities.ITEM_HANDLER).orElseThrow(IllegalStateException::new);
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void freshInventoryRefuelSelectAndPlace(GameTestHelper helper) {
        TurtleBlockEntity turtle = create(helper);
        IItemHandler inventory = items(turtle);
        helper.assertTrue(inventory.getSlots() == 16 && turtle.turtleFuel().equals("0"), "new turtles are finite and empty");
        for (int slot = 0; slot < 16; slot++) helper.assertTrue(inventory.getStackInSlot(slot).isEmpty(), "no free starting items");
        helper.assertTrue(inventory.insertItem(0, new ItemStack(Items.COAL, 2), false).isEmpty(), "automation can insert fuel");
        inventory.insertItem(15, new ItemStack(Items.COBBLESTONE, 3), false);
        var shell = turtle.getShell();
        helper.assertTrue(shell.executeForResult("turtle refuel 0").exitCode() == 2, "invalid refuel count is rejected");
        helper.assertTrue(inventory.getStackInSlot(0).getCount() == 2, "invalid count consumes no fuel items");
        helper.assertTrue(shell.executeForResult("turtle refuel 1").exitCode() == 0, "refuel command consumes selected coal");
        helper.assertTrue(turtle.turtleFuel().equals("80") && inventory.getStackInSlot(0).getCount() == 1, "one coal yields eighty fuel");
        helper.assertTrue(shell.executeForResult("turtle select 16 && turtle place front").exitCode() == 0, "select and place use slot sixteen");
        helper.assertTrue(inventory.getStackInSlot(15).getCount() == 2 && turtle.turtleFuel().equals("79"), "placement consumes exact resources");
        helper.assertTrue(helper.getBlockState(LOCAL.north()).is(Blocks.COBBLESTONE), "selected block placed");
        helper.assertTrue(shell.executeForResult("turtle inventory").outputLines().stream().anyMatch(s -> s.contains("* 16: 2 minecraft:cobblestone")), "inventory reports selection and counts");
        helper.assertTrue(shell.executeForResult("turtle select 17").exitCode() == 2, "selection is bounded");
        CompoundTag saved = turtle.saveWithoutMetadata();
        turtle.load(saved.copy());
        helper.assertTrue(saved.equals(turtle.saveWithoutMetadata()), "all slots selection and fuel survive reload");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void containerFuelAndNonfuelPreserveItems(GameTestHelper helper) {
        TurtleBlockEntity turtle = create(helper);
        IItemHandler inventory = items(turtle);
        inventory.insertItem(0, new ItemStack(Items.LAVA_BUCKET), false);
        helper.assertTrue(turtle.turtleRefuel(1), "lava bucket is usable fuel");
        helper.assertTrue(turtle.turtleFuel().equals("1000") && inventory.getStackInSlot(0).is(Items.BUCKET), "empty container is retained");
        CompoundTag before = turtle.saveWithoutMetadata();
        helper.assertTrue(!turtle.turtleRefuel(1), "empty bucket cannot be burned");
        helper.assertTrue(before.equals(turtle.saveWithoutMetadata()), "nonfuel rejection does not mutate inventory");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void legacyInventoryMigrationPreservesFuelMode(GameTestHelper helper) {
        TurtleBlockEntity turtle = create(helper);
        CompoundTag legacy = new CompoundTag();
        legacy.put("Selected", new ItemStack(Items.DIAMOND, 12).save(new CompoundTag()));
        legacy.putBoolean("FuelUnlimited", true);
        legacy.putInt("Fuel", 123);
        turtle.load(legacy);
        helper.assertTrue(items(turtle).getStackInSlot(0).getCount() == 12 && turtle.turtleFuel().equals("unlimited"), "legacy stack and mode preserved");
        CompoundTag saved = turtle.saveWithoutMetadata();
        helper.assertTrue(!turtle.turtleRefuel(1), "unlimited turtle does not consume unnecessary items");
        turtle.load(saved);
        helper.assertTrue(saved.equals(turtle.saveWithoutMetadata()), "migration is stable on repeated load");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void movementTransfersSlotsWithoutDropsAndRemovalDropsOnce(GameTestHelper helper) {
        TurtleBlockEntity turtle = create(helper);
        var originalCapability = turtle.getCapability(ForgeCapabilities.ITEM_HANDLER);
        IItemHandler original = items(turtle);
        original.insertItem(0, new ItemStack(Items.DIAMOND, 4), false);
        original.insertItem(15, new ItemStack(Items.EMERALD, 3), false);
        CompoundTag data = turtle.saveWithoutMetadata();
        data.putInt("Fuel", 5);
        turtle.load(data);
        BlockPos source = turtle.getBlockPos();
        AABB area = new AABB(source).inflate(2);
        helper.assertTrue(countGemDrops(helper, area) == 0, "fixture begins without gem drops");
        helper.assertTrue(turtle.turtleForward(), "fueled inventory-bearing turtle moves");
        TurtleBlockEntity moved = (TurtleBlockEntity) helper.getLevel().getBlockEntity(source.north());
        helper.assertTrue(items(moved).getStackInSlot(0).getCount() == 4 && items(moved).getStackInSlot(15).getCount() == 3, "all occupied slots transfer");
        helper.assertTrue(countGemDrops(helper, area) == 0, "movement drops no copied inventory");
        helper.assertTrue(!originalCapability.isPresent() && original.extractItem(0, 64, false).isEmpty(), "stale capability cannot duplicate moved contents");
        helper.getLevel().setBlock(source.north(), Blocks.AIR.defaultBlockState(), 3);
        helper.assertTrue(countGemDrops(helper, area) == 7, "normal removal drops all contents once");
        moved.dropInventory();
        helper.assertTrue(countGemDrops(helper, area) == 7, "repeated cleanup cannot duplicate drops");
        helper.succeed();
    }

    private static int countGemDrops(GameTestHelper helper, AABB area) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, area).stream()
                .map(ItemEntity::getItem).filter(s -> s.is(Items.DIAMOND) || s.is(Items.EMERALD))
                .mapToInt(ItemStack::getCount).sum();
    }
}
