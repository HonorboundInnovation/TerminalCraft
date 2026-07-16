package com.malice.terminalcraft.network;

import com.malice.terminalcraft.block.RedAlloyWireBlock;
import com.malice.terminalcraft.block.TerminalBlock;
import com.malice.terminalcraft.blockentity.TerminalBlockEntity;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;

/** Live proof for support-mounted red-alloy wire connectivity and bounded attenuation. */
@GameTestHolder("terminalcraft")
public final class RedAlloyWireGameTests {
    private RedAlloyWireGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void coplanarWireAttenuatesAndLosesUnsupportedSegments(GameTestHelper helper) {
        BlockPos source = new BlockPos(1, 2, 2);
        BlockPos first = new BlockPos(2, 2, 2);
        BlockPos second = new BlockPos(3, 2, 2);
        BlockPos third = new BlockPos(4, 2, 2);
        for (int x = 2; x <= 4; x++) helper.setBlock(new BlockPos(x, 1, 2), Blocks.STONE);
        helper.setBlock(source, Blocks.REDSTONE_BLOCK);
        helper.setBlock(first, wire(Direction.UP));
        helper.setBlock(second, wire(Direction.UP));
        helper.setBlock(third, wire(Direction.UP));

        RedAlloyWireBlock.recomputeAt(helper.getLevel(), helper.absolutePos(first));
        helper.assertTrue(power(helper, first) == 15 && power(helper, second) == 14 && power(helper, third) == 13,
                "red-alloy wire must carry vanilla input and attenuate once per coplanar segment");
        helper.assertTrue(RedAlloyWireBlock.isConnected(helper.getBlockState(first), Direction.EAST)
                        && RedAlloyWireBlock.isConnected(helper.getBlockState(second), Direction.WEST),
                "adjacent wires on the same support face must expose reciprocal rendered connections");

        helper.destroyBlock(source);
        RedAlloyWireBlock.recomputeAt(helper.getLevel(), helper.absolutePos(first));
        helper.assertTrue(power(helper, first) == 0 && power(helper, second) == 0 && power(helper, third) == 0,
                "removing the external source must depower the complete component without latching");

        helper.destroyBlock(new BlockPos(3, 1, 2));
        helper.runAfterDelay(3, () -> {
            helper.assertTrue(helper.getBlockState(second).isAir(),
                    "wire must break on its scheduled support-validation tick");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void supportSideRedstoneSourcesEnergizeWire(GameTestHelper helper) {
        BlockPos directWire = new BlockPos(2, 2, 2);
        BlockPos poweredSupportWire = new BlockPos(4, 2, 2);
        BlockPos poweredSupport = poweredSupportWire.below();
        BlockPos lever = poweredSupport.west();

        helper.setBlock(directWire.below(), Blocks.REDSTONE_BLOCK);
        helper.setBlock(directWire, wire(Direction.UP));
        helper.setBlock(poweredSupport, Blocks.STONE);
        helper.setBlock(lever, Blocks.LEVER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LeverBlock.FACE,
                        net.minecraft.world.level.block.state.properties.AttachFace.WALL)
                .setValue(net.minecraft.world.level.block.LeverBlock.FACING, Direction.WEST)
                .setValue(net.minecraft.world.level.block.LeverBlock.POWERED, true));
        helper.setBlock(poweredSupportWire, wire(Direction.UP));

        RedAlloyWireBlock.recomputeAt(helper.getLevel(), helper.absolutePos(directWire));
        RedAlloyWireBlock.recomputeAt(helper.getLevel(), helper.absolutePos(poweredSupportWire));
        helper.assertTrue(power(helper, directWire) == 15,
                "wire must accept power directly from the block supporting its mounted face");
        helper.assertTrue(power(helper, poweredSupportWire) == 15,
                "wire must accept strong power conducted through its supporting solid block");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void terminalDirectionalOutputEnergizesAdjacentWire(GameTestHelper helper) {
        BlockPos terminal = new BlockPos(2, 2, 2);
        BlockPos wire = terminal.east();
        helper.setBlock(terminal, ModRegistries.TERMINAL_BLOCK.get().defaultBlockState()
                .setValue(TerminalBlock.FACING, Direction.NORTH));
        helper.setBlock(wire.below(), Blocks.STONE);
        helper.setBlock(wire, wire(Direction.UP));

        helper.assertTrue(helper.getBlockEntity(terminal) instanceof TerminalBlockEntity,
                "terminal fixture must create its block entity");
        TerminalBlockEntity terminalEntity = (TerminalBlockEntity) helper.getBlockEntity(terminal);
        helper.assertTrue(terminalEntity.setRedstoneOutput("east", 12),
                "terminal must accept an east-side redstone output");

        helper.runAfterDelay(3, () -> {
            helper.assertTrue(power(helper, wire) == 12,
                    "wire east of a terminal must read the terminal's east output without direction inversion");
            helper.assertTrue(terminalEntity.setRedstoneOutput("east", 0),
                    "terminal must accept clearing its east-side output");
            helper.runAfterDelay(3, () -> {
                helper.assertTrue(power(helper, wire) == 0,
                        "terminal neighbor notifications must depower the adjacent wire after output is cleared");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void externalCornerConnectsReciprocallyAndHonorsObstruction(GameTestHelper helper) {
        BlockPos support = new BlockPos(2, 1, 2);
        BlockPos floorWire = support.above();
        BlockPos wallWire = support.east();
        BlockPos source = floorWire.west();
        BlockPos bendVolume = floorWire.east();
        helper.setBlock(support, Blocks.STONE);
        helper.setBlock(source, Blocks.REDSTONE_BLOCK);
        helper.setBlock(floorWire, wire(Direction.UP));
        helper.setBlock(wallWire, wire(Direction.EAST));

        RedAlloyWireBlock.recomputeAt(helper.getLevel(), helper.absolutePos(floorWire));
        helper.assertTrue(power(helper, floorWire) == 15 && power(helper, wallWire) == 14,
                "an external corner must carry power and attenuate once across its wire edge");
        helper.assertTrue(RedAlloyWireBlock.isConnected(helper.getBlockState(floorWire), Direction.EAST)
                        && RedAlloyWireBlock.isConnected(helper.getBlockState(wallWire), Direction.UP),
                "both external-corner pieces must render arms toward their shared support edge");

        helper.setBlock(bendVolume, Blocks.STONE);
        RedAlloyWireBlock.recomputeAt(helper.getLevel(), helper.absolutePos(floorWire));
        RedAlloyWireBlock.recomputeAt(helper.getLevel(), helper.absolutePos(wallWire));
        helper.assertTrue(power(helper, floorWire) == 15 && power(helper, wallWire) == 0,
                "a solid block in the swept bend volume must split the external-corner connection");
        helper.assertTrue(!RedAlloyWireBlock.isConnected(helper.getBlockState(floorWire), Direction.EAST)
                        && !RedAlloyWireBlock.isConnected(helper.getBlockState(wallWire), Direction.UP),
                "blocked external corners must retract both rendered arms");

        helper.destroyBlock(bendVolume);
        RedAlloyWireBlock.recomputeAt(helper.getLevel(), helper.absolutePos(floorWire));
        helper.assertTrue(power(helper, wallWire) == 14,
                "removing the obstruction must restore live corner connectivity");

        helper.destroyBlock(source);
        BlockPos reverseSource = wallWire.east();
        helper.setBlock(reverseSource, Blocks.REDSTONE_BLOCK);
        RedAlloyWireBlock.recomputeAt(helper.getLevel(), helper.absolutePos(wallWire));
        helper.assertTrue(power(helper, wallWire) == 15 && power(helper, floorWire) == 14,
                "the same external corner must propagate symmetrically from wall to floor");
        helper.destroyBlock(wallWire);
        helper.assertTrue(!RedAlloyWireBlock.isConnected(helper.getBlockState(floorWire), Direction.EAST),
                "removing one corner piece must immediately retract its peer's arm");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void multipartFacesFormInternalCornerAndLoseOnlyUnsupportedPart(GameTestHelper helper) {
        BlockPos space = new BlockPos(3, 2, 2);
        BlockPos floorSupport = space.below();
        BlockPos wallSupport = space.west();
        helper.setBlock(floorSupport, Blocks.STONE);
        helper.setBlock(wallSupport, Blocks.STONE);
        helper.setBlock(space, wire(Direction.UP));

        helper.assertTrue(RedAlloyWireBlock.addFace(helper.getLevel(), helper.absolutePos(space), Direction.EAST),
                "a second supported face must enter the existing wire block space");
        helper.assertTrue(RedAlloyWireBlock.hasFace(helper.getLevel(), helper.absolutePos(space), Direction.UP)
                        && RedAlloyWireBlock.hasFace(helper.getLevel(), helper.absolutePos(space), Direction.EAST),
                "both independently mounted face parts must remain occupied");
        helper.setBlock(space.above(), Blocks.STONE);
        helper.assertTrue(!RedAlloyWireBlock.addFace(helper.getLevel(), helper.absolutePos(space), Direction.DOWN),
                "opposite face pairs must be rejected because they cannot form a valid surface junction");
        helper.assertTrue(RedAlloyWireBlock.isConnected(
                        RedAlloyWireBlock.renderState(helper.getLevel(), helper.absolutePos(space), Direction.UP),
                        Direction.WEST)
                        && RedAlloyWireBlock.isConnected(
                        RedAlloyWireBlock.renderState(helper.getLevel(), helper.absolutePos(space), Direction.EAST),
                        Direction.DOWN),
                "internal-corner face parts must expose reciprocal arms toward their shared edge");
        helper.setBlock(wallSupport, Blocks.REDSTONE_BLOCK);
        RedAlloyWireBlock.recomputeAt(helper.getLevel(), helper.absolutePos(space));
        helper.assertTrue(RedAlloyWireBlock.power(helper.getLevel(), helper.absolutePos(space), Direction.UP) == 15
                        && RedAlloyWireBlock.power(helper.getLevel(), helper.absolutePos(space), Direction.EAST) == 15,
                "all face parts touching the same vanilla source must accept its full direct strength");
        helper.setBlock(wallSupport, Blocks.STONE);

        helper.destroyBlock(wallSupport);
        helper.runAfterDelay(3, () -> {
            helper.assertTrue(RedAlloyWireBlock.hasFace(helper.getLevel(), helper.absolutePos(space), Direction.UP),
                    "removing one support must preserve other supported parts in the multipart space");
            helper.assertTrue(!RedAlloyWireBlock.hasFace(helper.getLevel(), helper.absolutePos(space), Direction.EAST),
                    "only the face whose support disappeared may be removed");
            helper.assertTrue(helper.getBlockState(space).getBlock() instanceof RedAlloyWireBlock,
                    "the container block must survive while at least one face part remains");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void targetedMultipartRemovalSelectsOneFaceAndDropsOneItemPerPart(GameTestHelper helper) {
        BlockPos space = new BlockPos(3, 2, 2);
        helper.setBlock(space.below(), Blocks.STONE);
        helper.setBlock(space.west(), Blocks.STONE);
        helper.setBlock(space, wire(Direction.UP));
        helper.assertTrue(RedAlloyWireBlock.addFace(helper.getLevel(), helper.absolutePos(space), Direction.EAST),
                "test fixture must contain independently targetable floor and wall parts");

        BlockPos worldPos = helper.absolutePos(space);
        Vec3 center = Vec3.atCenterOf(worldPos);
        helper.assertTrue(RedAlloyWireBlock.targetedFace(helper.getLevel(), worldPos,
                        center.add(0.0D, 2.0D, 0.0D), center.add(0.0D, -2.0D, 0.0D)) == Direction.UP,
                "a ray from above must select the floor-mounted part");
        helper.assertTrue(RedAlloyWireBlock.targetedFace(helper.getLevel(), worldPos,
                        center.add(2.0D, 0.0D, 0.0D), center.add(-2.0D, 0.0D, 0.0D)) == Direction.EAST,
                "a horizontal ray must select the wall-mounted part without hitting the floor part");

        helper.assertTrue(RedAlloyWireBlock.removeFace(helper.getLevel(), worldPos, Direction.EAST, true),
                "targeted removal must consume the selected occupied face");
        helper.assertTrue(RedAlloyWireBlock.hasFace(helper.getLevel(), worldPos, Direction.UP)
                        && !RedAlloyWireBlock.hasFace(helper.getLevel(), worldPos, Direction.EAST),
                "removing the wall part must preserve the floor part and multipart container");
        AABB drops = new AABB(worldPos).inflate(1.0D);
        helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, drops).stream()
                        .mapToInt(entity -> entity.getItem().getCount()).sum() == 1,
                "removing one multipart face must drop exactly one wire item");

        helper.assertTrue(RedAlloyWireBlock.removeFace(helper.getLevel(), worldPos, Direction.UP, true),
                "the last occupied face must also be removable");
        helper.assertTrue(helper.getBlockState(space).isAir(),
                "removing the last part must remove the now-empty container block");
        helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, drops).stream()
                        .mapToInt(entity -> entity.getItem().getCount()).sum() == 2,
                "sequentially removing two parts must conserve two wire items");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void directionalVanillaSourceUsesItsOutputFace(GameTestHelper helper) {
        BlockPos wire = new BlockPos(3, 2, 2);
        BlockPos repeater = wire.west();
        helper.setBlock(wire.below(), Blocks.STONE);
        helper.setBlock(repeater.below(), Blocks.STONE);
        helper.setBlock(repeater, Blocks.REPEATER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.RepeaterBlock.FACING, Direction.EAST)
                .setValue(net.minecraft.world.level.block.RepeaterBlock.POWERED, true));
        helper.setBlock(wire, wire(Direction.UP));

        RedAlloyWireBlock.recomputeAt(helper.getLevel(), helper.absolutePos(wire));
        helper.assertTrue(power(helper, wire) == 15,
                "wire must query a directional source from the source's face toward the receiver");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void differentlyMountedAdjacentWiresDoNotFakeInternalMultipartCorner(GameTestHelper helper) {
        BlockPos floorWire = new BlockPos(2, 2, 2);
        BlockPos wallWire = floorWire.east();
        helper.setBlock(floorWire.below(), Blocks.STONE);
        helper.setBlock(wallWire.east(), Blocks.STONE);
        helper.setBlock(floorWire, wire(Direction.UP));
        helper.setBlock(wallWire, wire(Direction.WEST));

        helper.assertTrue(!RedAlloyWireBlock.isConnected(helper.getBlockState(floorWire), Direction.EAST),
                "different adjacent faces must not fake an internal corner before multipart occupancy exists");
        helper.succeed();
    }

    private static net.minecraft.world.level.block.state.BlockState wire(Direction face) {
        return ModRegistries.RED_ALLOY_WIRE_BLOCK.get().defaultBlockState()
                .setValue(RedAlloyWireBlock.FACE, face);
    }

    private static int power(GameTestHelper helper, BlockPos pos) {
        return helper.getBlockState(pos).getValue(RedAlloyWireBlock.POWER);
    }
}
