package com.malice.terminalcraft.network;

import com.malice.terminalcraft.block.BundledCableBlock;
import com.malice.terminalcraft.block.RedAlloyWireBlock;
import com.malice.terminalcraft.blockentity.BundledCableBlockEntity;
import com.malice.terminalcraft.blockentity.ProgrammableLogicControllerBlockEntity;
import com.malice.terminalcraft.blockentity.RedAlloyWireBlockEntity;
import com.malice.terminalcraft.blockentity.ServerRackBlockEntity;
import com.malice.terminalcraft.blockentity.TurtleBlockEntity;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;

/** Live proof of independent deterministic bundled-control channels and partitions. */
@GameTestHolder("terminalcraft")
public final class BundledCableGameTests {
    private BundledCableGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void channelsPropagateIndependentlyAndPartition(GameTestHelper helper) {
        BlockPos leftPos = new BlockPos(2, 2, 2);
        BlockPos middlePos = new BlockPos(3, 2, 2);
        BlockPos rightPos = new BlockPos(4, 2, 2);
        helper.setBlock(leftPos.below(), Blocks.STONE);
        helper.setBlock(middlePos.below(), Blocks.STONE);
        helper.setBlock(rightPos.below(), Blocks.STONE);
        helper.setBlock(leftPos, cable(Direction.UP));
        helper.setBlock(middlePos, cable(Direction.UP));
        helper.setBlock(rightPos, cable(Direction.UP));
        BundledCableBlockEntity left = (BundledCableBlockEntity) helper.getBlockEntity(leftPos);
        BundledCableBlockEntity right = (BundledCableBlockEntity) helper.getBlockEntity(rightPos);
        helper.assertTrue(BundledCableBlock.isConnected(helper.getBlockState(leftPos), Direction.EAST)
                        && BundledCableBlock.isConnected(helper.getBlockState(middlePos), Direction.EAST),
                "neighbor placement must extend bundled cable model arms toward adjacent segments");

        left.setLocalOutput(0, 6);
        left.refreshVanillaInput();
        helper.assertTrue(left.getLocalOutput(0) == 6,
                "refreshing vanilla input must not overwrite computer-owned channel-zero output");
        left.setLocalOutput(3, 12);
        right.setLocalOutput(7, 9);
        helper.assertTrue(right.getSignal(3) == 12, "channel 3 must cross the connected component");
        helper.assertTrue(left.getSignal(7) == 9, "channel 7 must propagate independently");
        helper.assertTrue(left.getSignal(4) == 0 && right.getSignal(4) == 0,
                "unused channels must remain isolated at zero");

        helper.destroyBlock(middlePos);
        left.recomputeComponent();
        right.recomputeComponent();
        helper.assertTrue(left.getSignal(7) == 0 && right.getSignal(3) == 0,
                "segment removal must partition channel state immediately");
        helper.assertTrue(!BundledCableBlock.isConnected(helper.getBlockState(leftPos), Direction.EAST)
                        && !BundledCableBlock.isConnected(helper.getBlockState(rightPos), Direction.WEST),
                "removing a segment must retract neighboring rendered cable arms");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void externalCornerCarriesAllChannelsAndObstructionPartitions(GameTestHelper helper) {
        BlockPos support = new BlockPos(2, 1, 2);
        BlockPos floorCable = support.above();
        BlockPos wallCable = support.east();
        BlockPos bendVolume = floorCable.east();
        helper.setBlock(support, Blocks.STONE);
        helper.setBlock(floorCable, cable(Direction.UP));
        helper.setBlock(wallCable, cable(Direction.EAST));

        BundledCableBlockEntity floor = (BundledCableBlockEntity) helper.getBlockEntity(floorCable);
        BundledCableBlockEntity wall = (BundledCableBlockEntity) helper.getBlockEntity(wallCable);
        floor.setLocalOutput(5, 13);
        helper.assertTrue(wall.getSignal(5) == 13,
                "an unobstructed external corner must carry bundled channels");
        helper.assertTrue(BundledCableBlock.isConnected(helper.getBlockState(floorCable), Direction.EAST)
                        && BundledCableBlock.isConnected(helper.getBlockState(wallCable), Direction.UP),
                "external-corner pieces must render reciprocal arms");

        helper.setBlock(bendVolume, Blocks.STONE);
        floor.recomputeComponent();
        wall.recomputeComponent();
        helper.assertTrue(wall.getSignal(5) == 0,
                "a solid block in the bend volume must partition bundled propagation");
        helper.assertTrue(!BundledCableBlock.isConnected(helper.getBlockState(floorCable), Direction.EAST)
                        && !BundledCableBlock.isConnected(helper.getBlockState(wallCable), Direction.UP),
                "an obstructed corner must retract both rendered arms");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void multipartFacesShareSignalsAndLoseOnlyUnsupportedPart(GameTestHelper helper) {
        BlockPos space = new BlockPos(3, 2, 2);
        helper.setBlock(space.below(), Blocks.STONE);
        helper.setBlock(space.west(), Blocks.STONE);
        helper.setBlock(space, cable(Direction.UP));

        helper.assertTrue(BundledCableBlock.addFace(helper.getLevel(), helper.absolutePos(space), Direction.EAST),
                "a supported perpendicular bundled face must enter the occupied block space");
        BundledCableBlockEntity cable = (BundledCableBlockEntity) helper.getBlockEntity(space);
        helper.assertTrue(cable.getUpdatePacket() != null
                        && cable.getUpdateTag().getInt("FaceMask")
                        == ((1 << Direction.UP.ordinal()) | (1 << Direction.EAST.ordinal())),
                "multipart occupancy must be included in the client synchronization packet");
        cable.setLocalOutput(11, 8);
        helper.assertTrue(cable.faceCount() == 2 && cable.getSignal(11) == 8,
                "multipart faces must share the same sixteen-channel cable node");
        helper.assertTrue(BundledCableBlock.isConnected(
                        BundledCableBlock.renderState(helper.getLevel(), helper.absolutePos(space), Direction.UP),
                        Direction.WEST)
                        && BundledCableBlock.isConnected(
                        BundledCableBlock.renderState(helper.getLevel(), helper.absolutePos(space), Direction.EAST),
                        Direction.DOWN),
                "internal corner faces must expose reciprocal rendered arms");

        helper.destroyBlock(space.west());
        helper.runAfterDelay(3, () -> {
            helper.assertTrue(BundledCableBlock.hasFace(helper.getLevel(), helper.absolutePos(space), Direction.UP)
                            && !BundledCableBlock.hasFace(helper.getLevel(), helper.absolutePos(space), Direction.EAST),
                    "support loss must remove only the unsupported bundled face");
            helper.assertTrue(helper.getBlockState(space).getBlock() instanceof BundledCableBlock,
                    "the multipart container must remain while one supported face survives");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void uncoloredVanillaSourceCannotEnterAnyBundledChannel(GameTestHelper helper) {
        BlockPos cablePos = new BlockPos(2, 2, 2);
        helper.setBlock(cablePos.below(), Blocks.REDSTONE_BLOCK);
        helper.setBlock(cablePos, cable(Direction.UP));
        BundledCableBlockEntity cable = (BundledCableBlockEntity) helper.getBlockEntity(cablePos);
        cable.refreshVanillaInput();

        BlockPos worldPos = helper.absolutePos(cablePos);
        BlockState state = helper.getLevel().getBlockState(worldPos);
        int east = state.getBlock().getSignal(state, helper.getLevel(), worldPos, Direction.EAST);
        int up = state.getBlock().getSignal(state, helper.getLevel(), worldPos, Direction.UP);
        int down = state.getBlock().getDirectSignal(state, helper.getLevel(), worldPos, Direction.DOWN);
        helper.assertTrue(cable.getSignal(0) == 0 && east == 0 && up == 0 && down == 0,
                "uncolored vanilla redstone must not enter or leave an arbitrary bundled channel");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void shieldedBreakoutTracksRepeatedComputerOutputChanges(GameTestHelper helper) {
        int channel = net.minecraft.world.item.DyeColor.ORANGE.getId();
        BlockPos bundlePos = new BlockPos(2, 2, 2);
        BlockPos wirePos = bundlePos.east();
        for (BlockPos supported : java.util.List.of(bundlePos, wirePos)) {
            helper.setBlock(supported.below(), Blocks.STONE);
        }
        helper.setBlock(bundlePos, cable(Direction.UP));
        helper.setBlock(wirePos, wire(Direction.UP, channel));

        BundledCableBlockEntity bundle = (BundledCableBlockEntity) helper.getBlockEntity(bundlePos);
        bundle.setLocalOutput(channel, 15);
        assertBreakoutPower(helper, wirePos, 15, "initial channel output");

        helper.runAfterDelay(3, () -> {
            bundle.setLocalOutput(channel, 6);
            helper.runAfterDelay(3, () -> {
                assertBreakoutPower(helper, wirePos, 6, "lowered channel output");
                bundle.setLocalOutput(channel, 0);
                helper.runAfterDelay(3, () -> {
                    assertBreakoutPower(helper, wirePos, 0, "cleared channel output");
                    bundle.setLocalOutput(channel, 11);
                    helper.runAfterDelay(3, () -> {
                        assertBreakoutPower(helper, wirePos, 11, "restored channel output");
                        helper.succeed();
                    });
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void shieldedBreakoutNotifiesDeviceThroughPoweredSupport(GameTestHelper helper) {
        int channel = net.minecraft.world.item.DyeColor.ORANGE.getId();
        BlockPos bundlePos = new BlockPos(1, 2, 2);
        BlockPos wirePos = bundlePos.east();
        BlockPos poweredSupport = wirePos.east();
        BlockPos lampPos = poweredSupport.east();
        helper.setBlock(bundlePos.below(), Blocks.STONE);
        helper.setBlock(wirePos.below(), Blocks.STONE);
        helper.setBlock(bundlePos, cable(Direction.UP));
        helper.setBlock(wirePos, wire(Direction.UP, channel));
        helper.setBlock(poweredSupport, Blocks.STONE);
        helper.setBlock(lampPos, Blocks.REDSTONE_LAMP);

        BundledCableBlockEntity bundle = (BundledCableBlockEntity) helper.getBlockEntity(bundlePos);
        bundle.setLocalOutput(channel, 15);
        helper.runAfterDelay(2, () -> {
            assertBreakoutPower(helper, wirePos, 15, "powered endpoint transition");
            helper.assertTrue(helper.getBlockState(lampPos)
                            .getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT),
                    "shielded wire must wake a receiver reached through its strongly powered support block");

            bundle.setLocalOutput(channel, 0);
            helper.runAfterDelay(5, () -> {
                assertBreakoutPower(helper, wirePos, 0, "depowered endpoint transition");
                helper.assertTrue(!helper.getBlockState(lampPos)
                                .getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT),
                        "clearing a bundled channel must wake and release the support-mounted receiver");

                bundle.setLocalOutput(channel, 9);
                helper.runAfterDelay(2, () -> {
                    assertBreakoutPower(helper, wirePos, 9, "restored endpoint transition");
                    helper.assertTrue(helper.getBlockState(lampPos)
                                    .getValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT),
                            "restoring a bundled channel must wake the receiver without replacing the wire");
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void unshieldedRedAlloyCannotSelectABundledChannel(GameTestHelper helper) {
        int red = net.minecraft.world.item.DyeColor.RED.getId();
        BlockPos bundlePos = new BlockPos(3, 2, 3);
        BlockPos bareWirePos = bundlePos.west();
        BlockPos shieldedOutputPos = bundlePos.east();
        BlockPos sourcePos = bareWirePos.west();
        for (BlockPos supported : java.util.List.of(bundlePos, bareWirePos, shieldedOutputPos)) {
            helper.setBlock(supported.below(), Blocks.STONE);
        }
        helper.setBlock(bundlePos, cable(Direction.UP));
        helper.setBlock(bareWirePos, wire(Direction.UP, red));
        helper.setBlock(shieldedOutputPos, wire(Direction.UP, red));
        com.malice.terminalcraft.blockentity.RedAlloyWireBlockEntity bare =
                (com.malice.terminalcraft.blockentity.RedAlloyWireBlockEntity) helper.getBlockEntity(bareWirePos);
        bare.setShielded(Direction.UP, 0, false);
        helper.setBlock(sourcePos, Blocks.REDSTONE_BLOCK);

        RedAlloyWireBlock.recomputeAt(helper.getLevel(), helper.absolutePos(bareWirePos));
        BundledCableBlockEntity bundle = (BundledCableBlockEntity) helper.getBlockEntity(bundlePos);
        bundle.refreshVanillaInput();
        bundle.recomputeComponent();
        RedAlloyWireBlock.recomputeAt(helper.getLevel(), helper.absolutePos(shieldedOutputPos));

        com.malice.terminalcraft.blockentity.RedAlloyWireBlockEntity output =
                (com.malice.terminalcraft.blockentity.RedAlloyWireBlockEntity) helper.getBlockEntity(shieldedOutputPos);
        helper.assertTrue(bare.power(Direction.UP, 0) == 15,
                "unshielded Red Alloy must still carry ordinary redstone");
        helper.assertTrue(bundle.getSignal(red) == 0 && output.power(Direction.UP, 0) == 0,
                "unshielded Red Alloy has no color identity and must not enter bundled channel 14");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void coloredBreakoutsRemainStrictlyChannelIsolated(GameTestHelper helper) {
        int red = net.minecraft.world.item.DyeColor.RED.getId();
        int green = net.minecraft.world.item.DyeColor.GREEN.getId();
        BlockPos bundlePos = new BlockPos(3, 2, 3);
        BlockPos inputPos = bundlePos.west();
        BlockPos redOutputPos = bundlePos.east();
        BlockPos greenOutputPos = bundlePos.north();
        BlockPos sourcePos = inputPos.west();
        for (BlockPos supported : java.util.List.of(bundlePos, inputPos, redOutputPos, greenOutputPos)) {
            helper.setBlock(supported.below(), Blocks.STONE);
        }
        helper.setBlock(bundlePos, cable(Direction.UP));
        helper.setBlock(inputPos, wire(Direction.UP, red));
        helper.setBlock(redOutputPos, wire(Direction.UP, red));
        helper.setBlock(greenOutputPos, wire(Direction.UP, green));
        helper.setBlock(sourcePos, Blocks.REDSTONE_BLOCK);

        RedAlloyWireBlock.recomputeAt(helper.getLevel(), helper.absolutePos(inputPos));
        BundledCableBlockEntity bundle = (BundledCableBlockEntity) helper.getBlockEntity(bundlePos);
        bundle.refreshVanillaInput();
        bundle.recomputeComponent();
        RedAlloyWireBlock.recomputeAt(helper.getLevel(), helper.absolutePos(redOutputPos));
        RedAlloyWireBlock.recomputeAt(helper.getLevel(), helper.absolutePos(greenOutputPos));

        com.malice.terminalcraft.blockentity.RedAlloyWireBlockEntity redOutput =
                (com.malice.terminalcraft.blockentity.RedAlloyWireBlockEntity) helper.getBlockEntity(redOutputPos);
        com.malice.terminalcraft.blockentity.RedAlloyWireBlockEntity greenOutput =
                (com.malice.terminalcraft.blockentity.RedAlloyWireBlockEntity) helper.getBlockEntity(greenOutputPos);
        helper.assertTrue(bundle.getSignal(red) == 15 && bundle.getSignal(green) == 0,
                "a red breakout input must energize only red channel 14");
        helper.assertTrue(redOutput.power(Direction.UP, 0) == 15 && greenOutput.power(Direction.UP, 0) == 0,
                "red channel output must reach red wire without leaking to green channel 13");
        for (int channel = 0; channel < BundledCableBlockEntity.CHANNELS; channel++) {
            if (channel != red) helper.assertTrue(bundle.getSignal(channel) == 0,
                    "red input leaked into bundled channel " + channel);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void turtleBundledComputerApiReadsAndWritesAdjacentCable(GameTestHelper helper) {
        BlockPos cablePos = new BlockPos(2, 2, 2);
        BlockPos turtlePos = cablePos.south();
        helper.setBlock(cablePos.below(), Blocks.STONE);
        helper.setBlock(cablePos, cable(Direction.UP));
        helper.setBlock(turtlePos, ModRegistries.TURTLE_BLOCK.get());

        TurtleBlockEntity turtle = (TurtleBlockEntity) helper.getBlockEntity(turtlePos);
        BundledCableBlockEntity cable = (BundledCableBlockEntity) helper.getBlockEntity(cablePos);
        helper.assertTrue(turtle.hasBundledCable("north"),
                "turtle bundled API must discover the cable on the named side");
        helper.assertTrue(turtle.setBundledOutput("north", 4, 11),
                "turtle bundled API must set a bounded per-channel source");
        helper.assertTrue(turtle.bundledOutput("north", 4) == 11
                        && turtle.bundledSignal("north", 4) == 11
                        && turtle.bundledInput("north", 4) == 0
                        && cable.getSignal(4) == 11,
                "turtle input must remain separate from its local output and effective bus signal");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void serverAndPlcExposeIndependentBundledInputAndOutput(GameTestHelper helper) {
        BlockPos leftCablePos = new BlockPos(2, 2, 2);
        BlockPos rightCablePos = leftCablePos.east();
        BlockPos serverPos = leftCablePos.north();
        BlockPos plcPos = rightCablePos.south();
        helper.setBlock(leftCablePos.below(), Blocks.STONE);
        helper.setBlock(rightCablePos.below(), Blocks.STONE);
        helper.setBlock(leftCablePos, cable(Direction.UP));
        helper.setBlock(rightCablePos, cable(Direction.UP));
        helper.setBlock(serverPos, ModRegistries.SERVER_RACK_BLOCK.get());
        helper.setBlock(plcPos, ModRegistries.PROGRAMMABLE_LOGIC_CONTROLLER_BLOCK.get());

        ServerRackBlockEntity server = (ServerRackBlockEntity) helper.getBlockEntity(serverPos);
        ProgrammableLogicControllerBlockEntity plc =
                (ProgrammableLogicControllerBlockEntity) helper.getBlockEntity(plcPos);
        helper.assertTrue(server.hasBundledCable("south") && plc.hasBundledCable("north"),
                "server and PLC shells must discover adjacent bundled Red Alloy cable");
        helper.assertTrue(server.setBundledOutput("south", 2, 6)
                        && plc.setBundledOutput("north", 5, 11),
                "server and PLC must independently drive selected channels");
        helper.assertTrue(server.bundledOutput("south", 2) == 6
                        && server.bundledInput("south", 2) == 0
                        && plc.bundledInput("north", 2) == 6,
                "server channel 2 output must appear as external PLC input without echoing locally");
        helper.assertTrue(plc.bundledOutput("north", 5) == 11
                        && plc.bundledInput("north", 5) == 0
                        && server.bundledInput("south", 5) == 11,
                "PLC channel 5 output must appear as external server input without echoing locally");
        helper.assertTrue(server.bundledOutput("south", 1) == 0
                        && server.bundledOutput("south", 3) == 0
                        && plc.bundledOutput("north", 4) == 0
                        && plc.bundledOutput("north", 6) == 0,
                "server and PLC writes must not leak to neighboring channel numbers");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void persistedMultipartFacesSanitizeUnsupportedParts(GameTestHelper helper) {
        BlockPos space = new BlockPos(3, 2, 2);
        helper.setBlock(space.below(), Blocks.STONE);
        helper.setBlock(space.west(), Blocks.STONE);
        helper.setBlock(space, cable(Direction.UP));
        helper.assertTrue(BundledCableBlock.addFace(helper.getLevel(), helper.absolutePos(space), Direction.EAST),
                "test fixture must contain two persisted bundled faces");

        BundledCableBlockEntity cable =
                (BundledCableBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(space));
        net.minecraft.nbt.CompoundTag persisted = cable.saveWithoutMetadata();
        helper.destroyBlock(space.west());
        cable.load(persisted);
        cable.onLoad();
        helper.assertTrue(BundledCableBlock.hasFace(helper.getLevel(), helper.absolutePos(space), Direction.UP)
                        && !BundledCableBlock.hasFace(helper.getLevel(), helper.absolutePos(space), Direction.EAST),
                "reload sanitation must remove only the bundled face whose support disappeared");
        helper.succeed();
    }

    private static net.minecraft.world.level.block.state.BlockState cable(Direction face) {
        return ModRegistries.BUNDLED_CABLE_BLOCK.get().defaultBlockState()
                .setValue(BundledCableBlock.FACE, face);
    }

    private static net.minecraft.world.level.block.state.BlockState wire(Direction face, int color) {
        return ModRegistries.RED_ALLOY_WIRE_BLOCK.get().defaultBlockState()
                .setValue(RedAlloyWireBlock.FACE, face)
                .setValue(RedAlloyWireBlock.COLOR, color);
    }

    private static void assertBreakoutPower(GameTestHelper helper, BlockPos wirePos,
                                            int expected, String transition) {
        BlockPos worldPos = helper.absolutePos(wirePos);
        BlockState state = helper.getLevel().getBlockState(worldPos);
        RedAlloyWireBlockEntity wire = (RedAlloyWireBlockEntity) helper.getBlockEntity(wirePos);
        int emitted = state.getBlock().getSignal(state, helper.getLevel(), worldPos, Direction.WEST);
        helper.assertTrue(wire.power(Direction.UP, 0) == expected
                        && state.getValue(RedAlloyWireBlock.POWER) == expected
                        && emitted == expected,
                transition + " must update shielded wire storage, block state, and endpoint signal to " + expected);
    }

}
