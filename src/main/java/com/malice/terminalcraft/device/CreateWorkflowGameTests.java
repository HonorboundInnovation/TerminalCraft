package com.malice.terminalcraft.device;

import com.malice.terminalcraft.blockentity.TerminalBlockEntity;
import com.malice.terminalcraft.registry.ModRegistries;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;
import com.simibubi.create.content.redstone.link.LinkBehaviour;
import com.simibubi.create.content.redstone.link.RedstoneLinkBlock;
import com.simibubi.create.content.redstone.link.RedstoneLinkBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.Set;
import java.util.UUID;

/** Normal terminal-to-Create workflow, compiled only for the installed Create profile. */
@GameTestHolder("terminalcraft")
@PrefixGameTestTemplate(false)
public final class CreateWorkflowGameTests {
    @GameTest(template = "serverrackgametests.empty", timeoutTicks = 100)
    public static void terminalReadsMotorAndControlsNativeLink(GameTestHelper helper) {
        BlockPos terminalPos = new BlockPos(2, 2, 2);
        BlockPos shaftPos = terminalPos.north();
        BlockPos motorPos = shaftPos.north();
        BlockPos transmitterPos = terminalPos.east();
        BlockPos receiverPos = new BlockPos(5, 2, 2);
        BlockPos lampPos = receiverPos.south();
        var motorBlock = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("create", "creative_motor"));
        var shaftBlock = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("create", "shaft"));
        var linkBlock = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("create", "redstone_link"));
        helper.assertTrue(motorBlock != null && shaftBlock != null && linkBlock != null, "Create fixture blocks available");
        helper.setBlock(terminalPos, ModRegistries.TERMINAL_BLOCK.get());
        helper.setBlock(motorPos, motorBlock.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.SOUTH));
        helper.setBlock(shaftPos, shaftBlock.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
        helper.setBlock(transmitterPos.below(), Blocks.STONE);
        helper.setBlock(receiverPos.below(), Blocks.STONE);
        helper.setBlock(transmitterPos, linkBlock.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
        helper.setBlock(receiverPos, linkBlock.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP)
                .setValue(RedstoneLinkBlock.RECEIVER, true));
        // Create's deferred link behaviour is constructed during the initial NBT read.
        // Bare GameTest setBlock bypasses the structure/item data load used by normal placement.
        for (BlockPos pos : java.util.List.of(transmitterPos, receiverPos)) {
            var initial = new net.minecraft.nbt.CompoundTag();
            initial.putBoolean("Transmitter", pos.equals(transmitterPos));
            helper.getBlockEntity(pos).load(initial);
        }
        helper.setBlock(lampPos, Blocks.REDSTONE_LAMP);
        var owner = DeviceCallContext.player(UUID.randomUUID(), "create-operator", Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        helper.runAfterDelay(4, () -> {
            var motor = (CreativeMotorBlockEntity) helper.getBlockEntity(motorPos);
            motor.generatedSpeed.setValue(64);
            var transmitter = (RedstoneLinkBlockEntity) helper.getBlockEntity(transmitterPos);
            var receiver = (RedstoneLinkBlockEntity) helper.getBlockEntity(receiverPos);
            for (var link : java.util.List.of(transmitter, receiver)) {
                var behaviour = link.getBehaviour(LinkBehaviour.TYPE);
                behaviour.setFrequency(true, new ItemStack(Items.DIAMOND));
                behaviour.setFrequency(false, new ItemStack(Items.REDSTONE));
            }
            helper.runAfterDelay(8, () -> {
                var terminal = (TerminalBlockEntity) helper.getBlockEntity(terminalPos);
                String dimension = helper.getLevel().dimension().location().toString();
                var shaftId = AdjacentForgeEndpointResolver.adjacent(dimension, helper.absolutePos(terminalPos), Direction.NORTH).id();
                var linkId = AdjacentForgeEndpointResolver.adjacent(dimension, helper.absolutePos(terminalPos), Direction.EAST).id();
                helper.assertTrue(Math.abs(((KineticBlockEntity) helper.getBlockEntity(shaftPos)).getSpeed()) == 64, "native motor powers shaft at 64 RPM");
                var speed = terminal.getShell().executeForResult("device call " + shaftId + " create.kinetic.status", owner);
                helper.assertTrue(speed.succeeded() && speed.outputLines().stream().anyMatch(line -> line.contains("speed_rpm") && line.contains("64")), "terminal reads native kinetic telemetry: " + speed);
                var transmit = terminal.getShell().executeForResult("device call " + linkId + " create.redstone_link.transmit 15", owner);
                helper.assertTrue(transmit.succeeded(), "terminal transmits using native link: " + transmit);
                helper.runAfterDelay(8, () -> {
                    helper.assertTrue(receiver.getReceivedSignal() == 15 && helper.getBlockState(lampPos).getValue(RedstoneLampBlock.LIT), "Create receiver powers real lamp");
                    helper.assertTrue(terminal.getShell().executeForResult("device call " + linkId + " create.redstone_link.transmit 0", owner).succeeded(), "terminal turns link off");
                    helper.runAfterDelay(8, () -> {
                        helper.assertTrue(receiver.getReceivedSignal() == 0 && !helper.getBlockState(lampPos).getValue(RedstoneLampBlock.LIT), "native receiver and lamp turn off");
                        helper.succeed();
                    });
                });
            });
        });
    }
}
