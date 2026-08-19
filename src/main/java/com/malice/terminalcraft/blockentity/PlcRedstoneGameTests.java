package com.malice.terminalcraft.blockentity;

import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraftforge.gametest.GameTestHolder;

/** Live contract for PLC program output reaching ordinary Minecraft redstone consumers. */
@GameTestHolder("terminalcraft")
public final class PlcRedstoneGameTests {
    private static final BlockPos PLC = new BlockPos(2, 2, 2);
    private static final BlockPos LAMP = PLC.west();

    private PlcRedstoneGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void constantProgramPowersSelectedVanillaRedstoneFace(GameTestHelper helper) {
        helper.setBlock(PLC, ModRegistries.PROGRAMMABLE_LOGIC_CONTROLLER_BLOCK.get());
        helper.setBlock(LAMP, Blocks.REDSTONE_LAMP);
        ProgrammableLogicControllerBlockEntity plc =
                (ProgrammableLogicControllerBlockEntity) helper.getBlockEntity(PLC);
        helper.assertTrue(plc.loadProgram("OUT TEST REDSTONE WEST\nRUNG TEST = ON"),
                "constant redstone test program must compile");
        plc.start();

        helper.runAfterDelay(5, () -> {
            BlockPos absolutePlc = helper.absolutePos(PLC);
            helper.assertTrue(plc.getRedstoneOutput("west") == 15,
                    "PLC runtime must retain the requested west output");
            helper.assertTrue(helper.getLevel().getSignal(absolutePlc, Direction.EAST) == 15,
                    "PLC block must expose the requested west signal to Minecraft");
            helper.assertTrue(helper.getBlockState(LAMP).getValue(RedstoneLampBlock.LIT),
                    "an adjacent vanilla redstone lamp must receive PLC power");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void terminalShellFindsAdjacentPlcOnEverySide(GameTestHelper helper) {
        BlockPos terminalPos = new BlockPos(3, 3, 3);
        helper.setBlock(terminalPos, ModRegistries.TERMINAL_BLOCK.get());
        TerminalBlockEntity terminal = (TerminalBlockEntity) helper.getBlockEntity(terminalPos);

        for (Direction direction : Direction.values()) {
            BlockPos plcPos = terminalPos.relative(direction);
            helper.setBlock(plcPos, ModRegistries.PROGRAMMABLE_LOGIC_CONTROLLER_BLOCK.get());
            com.malice.terminalcraft.shell.ShellCommandResult result =
                    terminal.getShell().executeForResult("plc status");
            helper.assertTrue(result.exitCode() == 0
                            && result.outputLines().size() == 1
                            && result.outputLines().get(0).startsWith("state=STOP"),
                    "terminal must resolve a directly adjacent PLC on its "
                            + direction.getName() + " side: " + result.outputLines());
            helper.destroyBlock(plcPos);
        }
        helper.succeed();
    }
}
