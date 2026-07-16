package com.malice.terminalcraft.network;

import com.malice.terminalcraft.block.BundledCableBlock;
import com.malice.terminalcraft.blockentity.BundledCableBlockEntity;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
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

    private static net.minecraft.world.level.block.state.BlockState cable(Direction face) {
        return ModRegistries.BUNDLED_CABLE_BLOCK.get().defaultBlockState()
                .setValue(BundledCableBlock.FACE, face);
    }

}
