package com.malice.terminalcraft.block;

import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Ordinary targeting follows the smaller turtle and modem models. */
@GameTestHolder("terminalcraft")
@PrefixGameTestTemplate(false)
public final class MachineShapeGameTests {
    @GameTest(template = "serverrackgametests.empty")
    public static void monitorBoundsFollowThinPanel(GameTestHelper helper) {
        BlockPos pos = new BlockPos(2, 2, 2);
        for (var facing : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            var state = ModRegistries.MONITOR_BLOCK.get().defaultBlockState().setValue(MonitorBlock.FACING, facing);
            helper.setBlock(pos, state);
            double x = facing == net.minecraft.core.Direction.EAST ? 0.9375
                    : facing == net.minecraft.core.Direction.WEST ? 0.0625 : 0.5;
            double z = facing == net.minecraft.core.Direction.SOUTH ? 0.9375
                    : facing == net.minecraft.core.Direction.NORTH ? 0.0625 : 0.5;
            for (var shape : java.util.List.of(state.getShape(helper.getLevel(), helper.absolutePos(pos)),
                    state.getCollisionShape(helper.getLevel(), helper.absolutePos(pos)))) {
                helper.assertTrue(shape.toAabbs().stream().anyMatch(box -> box.contains(x, 0.5, z)),
                        "visible monitor panel remains selectable and solid");
                helper.assertTrue(shape.toAabbs().stream().noneMatch(box -> box.contains(0.5, 0.5, 0.5)),
                        "empty space behind monitor is not a full-block obstacle");
            }
            helper.assertTrue(state.rotate(net.minecraft.world.level.block.Rotation.CLOCKWISE_90)
                            .getValue(MonitorBlock.FACING) == facing.getClockWise(),
                    "structure rotation follows monitor facing");
        }
        helper.succeed();
    }

    @GameTest(template = "serverrackgametests.empty")
    public static void storageCabinetShapesFollowFacing(GameTestHelper helper) {
        BlockPos pos = new BlockPos(2, 2, 2);
        for (var block : java.util.List.of(ModRegistries.NETWORK_ACCESS_STORAGE_BLOCK.get(),
                ModRegistries.REFINED_STORAGE_BRIDGE_BLOCK.get(), ModRegistries.APPLIED_ENERGISTICS_BRIDGE_BLOCK.get(),
                ModRegistries.DISK_DRIVE_BLOCK.get())) {
            for (var facing : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                var state = block.defaultBlockState().setValue(NetworkAccessStorageBlock.FACING, facing);
                helper.setBlock(pos, state);
                var shape = state.getShape(helper.getLevel(), helper.absolutePos(pos));
                helper.assertTrue(shape.toAabbs().stream().anyMatch(box -> box.contains(0.5, 0.5, 0.5)),
                        "cabinet body remains selectable in every facing");
                // Sample inside a bay, not exactly on the seam between the NAS columns.
                double x = switch (facing) {
                    case NORTH -> 0.5625;
                    case EAST -> 0.96875;
                    case SOUTH -> 0.4375;
                    default -> 0.03125;
                };
                double z = switch (facing) {
                    case NORTH -> 0.03125;
                    case EAST -> 0.5625;
                    case SOUTH -> 0.96875;
                    default -> 0.4375;
                };
                double y = block instanceof NetworkAccessStorageBlock ? 0.625 : 0.5;
                helper.assertTrue(shape.toAabbs().stream().anyMatch(box -> box.contains(x, y, z)),
                        "raised front panel follows cabinet facing");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "serverrackgametests.empty")
    public static void turtleAndModemUseVisibleBounds(GameTestHelper helper) {
        BlockPos pos = new BlockPos(2, 2, 2);
        for (var block : java.util.List.of(ModRegistries.TURTLE_BLOCK.get(), ModRegistries.MODEM_BLOCK.get())) {
            helper.setBlock(pos, block);
            var state = helper.getBlockState(pos);
            var selection = state.getShape(helper.getLevel(), helper.absolutePos(pos));
            var collision = state.getCollisionShape(helper.getLevel(), helper.absolutePos(pos));
            helper.assertTrue(selection.toAabbs().stream().anyMatch(box -> box.contains(0.5, 0.25, 0.5)),
                    "visible machine body can be selected");
            helper.assertTrue(selection.toAabbs().stream().noneMatch(box -> box.contains(0.02, 0.5, 0.02)),
                    "empty space around machine is not selectable");
            helper.assertTrue(collision.toAabbs().stream().noneMatch(box -> box.contains(0.02, 0.5, 0.02)),
                    "empty space around machine does not collide");
        }
        helper.succeed();
    }
}
