package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;

@GameTestHolder("terminalcraft")
public final class TurtleMovementGameTests {
    @GameTest(template = "empty", timeoutTicks = 40)
    public static void menusFollowTurtleAndRejectReplacementOrDistance(GameTestHelper helper) {
        BlockPos local = new BlockPos(3, 2, 3);
        helper.setBlock(local, ModRegistries.TURTLE_BLOCK.get());
        helper.setBlock(local.north(), Blocks.AIR);
        BlockPos source = helper.absolutePos(local);
        TurtleBlockEntity turtle = (TurtleBlockEntity) helper.getLevel().getBlockEntity(source);
        CompoundTag fueled = turtle.saveWithoutMetadata();
        fueled.putInt("Fuel", 10);
        turtle.load(fueled);
        var player = helper.makeMockPlayer();
        var secondPlayer = helper.makeMockPlayer();
        player.setPos(source.getX() + 0.5, source.getY() + 0.5, source.getZ() + 0.5);
        secondPlayer.setPos(player.getX(), player.getY(), player.getZ());
        var menu = new com.malice.terminalcraft.menu.TerminalMenu(1, player.getInventory(), turtle);
        var secondMenu = new com.malice.terminalcraft.menu.TerminalMenu(2, secondPlayer.getInventory(), turtle);
        helper.assertTrue(menu.stillValid(player), "original session is valid");
        menu.submitCommand("turtle forward", com.malice.terminalcraft.device.DeviceCallContext.readOnly("test"));
        var moved = helper.getLevel().getBlockEntity(source.north());
        helper.assertTrue(menu.getComputer() == moved && secondMenu.getComputer() == moved,
                "both menus follow the same moved computer");
        helper.assertTrue(menu.stillValid(player) && secondMenu.stillValid(secondPlayer), "nearby viewers remain valid");
        secondMenu.submitCommand("echo session-ok > /home/player/session.txt",
                com.malice.terminalcraft.device.DeviceCallContext.readOnly("test"));
        helper.assertTrue(menu.getShell().executeForResult("cat /home/player/session.txt").outputLines().contains("session-ok"),
                "later commands from another viewer reach the moved shell");
        player.setPos(source.getX() + 20, source.getY(), source.getZ());
        helper.assertTrue(!menu.stillValid(player), "out-of-range session is rejected");
        player.setPos(secondPlayer.getX(), secondPlayer.getY(), secondPlayer.getZ());
        CompoundTag cloned = moved.saveWithoutMetadata();
        helper.setBlock(local.north(), Blocks.AIR);
        helper.setBlock(local.north(), ModRegistries.TURTLE_BLOCK.get());
        helper.getLevel().getBlockEntity(source.north()).load(cloned);
        helper.assertTrue(!menu.stillValid(player) && !secondMenu.stillValid(secondPlayer),
                "replacement even with copied identity cannot revive old sessions");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void terminalReplacementInvalidatesOldMenu(GameTestHelper helper) {
        BlockPos local = new BlockPos(3, 2, 3);
        helper.setBlock(local, ModRegistries.TERMINAL_BLOCK.get());
        BlockPos pos = helper.absolutePos(local);
        var terminal = (TerminalBlockEntity) helper.getLevel().getBlockEntity(pos);
        var player = helper.makeMockPlayer();
        player.setPos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        var menu = new com.malice.terminalcraft.menu.TerminalMenu(1, player.getInventory(), terminal);
        helper.assertTrue(menu.stillValid(player), "placed terminal has valid menu");
        helper.setBlock(local, Blocks.AIR);
        helper.setBlock(local, ModRegistries.TERMINAL_BLOCK.get());
        helper.assertTrue(!menu.stillValid(player), "same block type at same position is not the original computer");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void rejectedDestinationPreservesSource(GameTestHelper helper) { verify(helper, 1); }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void missingDestinationEntityRestoresWaterAndPreservesSource(GameTestHelper helper) { verify(helper, 2); }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void successfulMoveTransfersIdentityFilesItemsAndChargesOnce(GameTestHelper helper) { verify(helper, 0); }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void rejectedDigPreservesFuelAndItems(GameTestHelper helper) { verifyAction(helper, true, true, 7); }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void rejectedPlacePreservesFuelAndItems(GameTestHelper helper) { verifyAction(helper, false, true, 7); }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void successfulDigChargesOnce(GameTestHelper helper) { verifyAction(helper, true, false, 7); }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void successfulPlaceChargesOneFuelAndItem(GameTestHelper helper) { verifyAction(helper, false, false, 7); }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void emptyFuelPreventsDigMutation(GameTestHelper helper) { verifyAction(helper, true, false, 0); }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void emptyFuelPreventsPlaceMutation(GameTestHelper helper) { verifyAction(helper, false, false, 0); }

    private static void verifyAction(GameTestHelper helper, boolean dig, boolean reject, int fuel) {
        BlockPos local = new BlockPos(3, 2, 3);
        helper.setBlock(local, ModRegistries.TURTLE_BLOCK.get());
        helper.setBlock(local.north(), dig ? Blocks.COBBLESTONE : Blocks.AIR);
        BlockPos source = helper.absolutePos(local);
        ActionTurtle turtle = new ActionTurtle(source, helper.getBlockState(local), reject);
        helper.getLevel().removeBlockEntity(source);
        helper.getLevel().setBlockEntity(turtle);
        CompoundTag initial = turtle.saveWithoutMetadata();
        initial.putInt("Fuel", fuel);
        initial.putBoolean("FuelUnlimited", false);
        initial.put("Selected", new ItemStack(Items.COBBLESTONE, 3).save(new CompoundTag()));
        initial.remove("Inventory"); // Exercise legacy single-stack migration.
        turtle.load(initial);
        CompoundTag before = turtle.saveWithoutMetadata();
        boolean success = dig ? turtle.turtleDig("front") : turtle.turtlePlace("front");
        boolean expectedSuccess = !reject && fuel > 0;
        helper.assertTrue(success == expectedSuccess, "action result must reflect actual mutation");
        helper.assertTrue(turtle.calls == (fuel > 0 ? 1 : 0), "no-fuel actions must never call world mutation");
        CompoundTag expected = before.copy();
        if (expectedSuccess) {
            expected.putInt("Fuel", fuel - 1);
            int count = dig ? 4 : 2; // Digging collects the target's one cobblestone drop.
            expected.put("Selected", new ItemStack(Items.COBBLESTONE, count).save(new CompoundTag()));
            expected.getCompound("Inventory").getList("Items", CompoundTag.TAG_COMPOUND).getCompound(0).putByte("Count", (byte) count);
        }
        helper.assertTrue(expected.equals(turtle.saveWithoutMetadata()), "only successful actions spend expected resources");
        boolean expectedSolid = dig != expectedSuccess;
        helper.assertTrue(helper.getBlockState(local.north()).is(expectedSolid ? Blocks.COBBLESTONE : Blocks.AIR),
                "target changes only for successful actions");
        helper.succeed();
    }

    private static final class ActionTurtle extends TurtleBlockEntity {
        private final boolean reject;
        private int calls;
        private ActionTurtle(BlockPos pos, BlockState state, boolean reject) { super(pos, state); this.reject = reject; }
        @Override protected boolean destroyTurtleTarget(ServerLevel level, BlockPos pos) {
            calls++;
            return !reject && super.destroyTurtleTarget(level, pos);
        }
        @Override protected boolean placeTurtleTarget(ServerLevel level, BlockPos pos,
                net.minecraft.world.item.context.BlockPlaceContext context) {
            calls++;
            return !reject && super.placeTurtleTarget(level, pos, context);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void commandChainContinuesOnMovedComputer(GameTestHelper helper) {
        BlockPos local = new BlockPos(3, 2, 3);
        helper.setBlock(local, ModRegistries.TURTLE_BLOCK.get());
        helper.setBlock(local.north(), Blocks.AIR);
        helper.setBlock(local.north(2), Blocks.AIR);
        TurtleBlockEntity turtle = (TurtleBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(local));
        CompoundTag initial = turtle.saveWithoutMetadata();
        initial.putInt("Fuel", 7);
        initial.putBoolean("FuelUnlimited", false);
        turtle.load(initial);
        var runningShell = turtle.getShell();
        runningShell.executeForResult("turtle forward && turtle forward && echo arrived > /home/player/arrival.txt");
        var destination = helper.getLevel().getBlockEntity(helper.absolutePos(local.north(2)));
        helper.assertTrue(destination instanceof TurtleBlockEntity, "both commands must move the current turtle");
        TurtleBlockEntity moved = (TurtleBlockEntity) destination;
        helper.assertTrue(moved.getShell() == runningShell, "movement must retain the executing shell");
        helper.assertTrue(moved.saveWithoutMetadata().getInt("Fuel") == 5, "two moves charge two fuel");
        helper.assertTrue(helper.getBlockState(local).isAir() && helper.getBlockState(local.north()).isAir(),
                "movement leaves no duplicate computers");
        helper.assertTrue(moved.getShell().executeForResult("cat /home/player/arrival.txt").outputLines().contains("arrived"),
                "commands after movement persist on the destination");
        helper.succeed();
    }

    private static void verify(GameTestHelper helper, int fault) {
        BlockPos local = new BlockPos(3, 2, 3);
        helper.setBlock(local, ModRegistries.TURTLE_BLOCK.get());
        helper.setBlock(local.north(), Blocks.WATER);
        BlockPos source = helper.absolutePos(local);
        BlockPos destination = helper.absolutePos(local.north());
        BlockState state = helper.getBlockState(local);
        FaultyTurtle turtle = new FaultyTurtle(source, state, fault);
        helper.getLevel().removeBlockEntity(source);
        helper.getLevel().setBlockEntity(turtle);
        CompoundTag initial = turtle.saveWithoutMetadata();
        initial.putInt("Fuel", 7);
        initial.putBoolean("FuelUnlimited", false);
        initial.put("Selected", new ItemStack(Items.DIAMOND, 3).save(new CompoundTag()));
        initial.remove("Inventory"); // Exercise legacy single-stack migration.
        turtle.load(initial);
        turtle.setLabel("movement-proof");
        turtle.getShell().executeForResult("echo retained > /home/player/movement.txt");
        var id = turtle.getDeviceId();
        CompoundTag before = turtle.saveWithoutMetadata();
        boolean moved = turtle.turtleForward();
        helper.assertTrue(moved == (fault == 0), "move outcome must match placement outcome");
        if (fault != 0) {
            helper.assertTrue(helper.getLevel().getBlockEntity(source) == turtle, "failed move retains authoritative source object");
            helper.assertTrue(before.equals(turtle.saveWithoutMetadata()), "failed move preserves all persisted state including fuel");
            helper.assertTrue(helper.getLevel().getBlockState(destination).is(Blocks.WATER), "failed move restores original destination fluid");
        } else {
            helper.assertTrue(helper.getLevel().getBlockState(source).isAir(), "successful move removes source");
            TurtleBlockEntity restored = (TurtleBlockEntity) helper.getLevel().getBlockEntity(destination);
            helper.assertTrue(restored != null && restored.getDeviceId().equals(id), "identity transfers to destination");
            CompoundTag expected = before.copy();
            expected.putInt("Fuel", 6);
            helper.assertTrue(expected.equals(restored.saveWithoutMetadata()), "successful move preserves state and charges exactly one fuel");
        }
        helper.succeed();
    }

    private static final class FaultyTurtle extends TurtleBlockEntity {
        private final int fault;
        private FaultyTurtle(BlockPos pos, BlockState state, int fault) { super(pos, state); this.fault = fault; }
        @Override protected boolean placeMovementDestination(ServerLevel level, BlockPos pos, BlockState state) {
            if (fault == 1) return false;
            boolean result = super.placeMovementDestination(level, pos, state);
            // Removing only the entity lets the chunk lazily recreate it on lookup.
            if (fault == 2) level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
            return result;
        }
    }
}
