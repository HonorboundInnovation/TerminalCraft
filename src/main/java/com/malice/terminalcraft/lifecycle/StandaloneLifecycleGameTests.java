package com.malice.terminalcraft.lifecycle;

import com.malice.terminalcraft.blockentity.DiskDriveBlockEntity;
import com.malice.terminalcraft.blockentity.ModemBlockEntity;
import com.malice.terminalcraft.blockentity.MonitorBlockEntity;
import com.malice.terminalcraft.blockentity.TerminalBlockEntity;
import com.malice.terminalcraft.blockentity.TurtleBlockEntity;
import com.malice.terminalcraft.item.FloppyDiskItem;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;

import java.util.List;
import java.util.UUID;

/** Live characterization of normal standalone save, reload, movement, and media lifecycles. */
@GameTestHolder("terminalcraft")
public final class StandaloneLifecycleGameTests {
    private static final BlockPos PRIMARY = new BlockPos(2, 2, 2);
    private static final BlockPos SECONDARY = new BlockPos(3, 2, 2);
    private static final BlockPos TURTLE_FORWARD = new BlockPos(2, 2, 1);

    private StandaloneLifecycleGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void terminalSaveReloadPreservesIdentityShellLabelAndRedstone(GameTestHelper helper) {
        helper.setBlock(PRIMARY, ModRegistries.TERMINAL_BLOCK.get());
        TerminalBlockEntity terminal = (TerminalBlockEntity) helper.getBlockEntity(PRIMARY);
        terminal.setLabel("control-room");
        terminal.setRedstoneOutput("north", 12);
        terminal.getShell().runScriptText("cd /tmp; TERMINAL_STATE=ready; echo persisted > lifecycle.txt; false");
        UUID identity = terminal.getDeviceId();
        CompoundTag saved = terminal.saveWithFullMetadata();

        helper.setBlock(PRIMARY, Blocks.AIR);
        helper.setBlock(PRIMARY, ModRegistries.TERMINAL_BLOCK.get());
        TerminalBlockEntity restored = (TerminalBlockEntity) helper.getBlockEntity(PRIMARY);
        restored.load(saved);

        helper.assertTrue(identity.equals(restored.getDeviceId()), "terminal identity must survive reload");
        helper.assertTrue("control-room".equals(restored.getLabel()), "terminal label must survive reload");
        helper.assertTrue(restored.getRedstoneOutput("north") == 12,
                "terminal redstone output must survive reload");
        helper.assertTrue("/tmp".equals(restored.getShell().getCwd()), "terminal cwd must survive reload");
        helper.assertTrue(restored.getShell().getLastExitCode() == 1,
                "terminal exit status must survive reload");
        helper.assertTrue("persisted\n".equals(restored.getShell().getVfs().readFile("/tmp/lifecycle.txt")),
                "terminal VFS must survive reload");
        helper.assertTrue("ready".equals(restored.getShell().executeForResult("echo $TERMINAL_STATE")
                .outputLines().get(0)), "terminal environment must survive reload");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void turtleMovementPreservesIdentityAndComputerStateAndRejectsObstruction(GameTestHelper helper) {
        helper.setBlock(PRIMARY, ModRegistries.TURTLE_BLOCK.get());
        TurtleBlockEntity turtle = (TurtleBlockEntity) helper.getBlockEntity(PRIMARY);
        turtle.setLabel("miner-one");
        turtle.setRedstoneOutput("north", 9);
        turtle.getShell().runScriptText("cd /tmp; echo moving > movement.txt");
        UUID identity = turtle.getDeviceId();

        helper.setBlock(TURTLE_FORWARD, Blocks.STONE);
        helper.assertTrue(!turtle.turtleForward(), "turtle must reject an obstructed destination");
        helper.assertTrue(identity.equals(((TurtleBlockEntity) helper.getBlockEntity(PRIMARY)).getDeviceId()),
                "failed movement must retain the original turtle");

        helper.setBlock(TURTLE_FORWARD, Blocks.AIR);
        helper.assertTrue(turtle.turtleForward(), "turtle must move into empty space");
        helper.assertTrue(helper.getBlockState(PRIMARY).isAir(), "movement must clear the old position");
        TurtleBlockEntity moved = (TurtleBlockEntity) helper.getBlockEntity(TURTLE_FORWARD);
        helper.assertTrue(identity.equals(moved.getDeviceId()), "turtle identity must survive movement");
        helper.assertTrue("miner-one".equals(moved.getLabel()), "turtle label must survive movement");
        helper.assertTrue(moved.getRedstoneOutput("north") == 9,
                "turtle redstone state must survive movement");
        helper.assertTrue("moving\n".equals(moved.getShell().getVfs().readFile("/tmp/movement.txt")),
                "turtle VFS must survive movement");
        helper.assertTrue(moved.turtleTurnRight(), "moved turtle must remain operable");
        helper.assertTrue("east".equals(moved.turtleFacing()), "right turn must update facing");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void monitorSaveReloadAndWallReformPreserveVisibleState(GameTestHelper helper) {
        helper.setBlock(PRIMARY, ModRegistries.MONITOR_BLOCK.get());
        helper.setBlock(SECONDARY, ModRegistries.MONITOR_BLOCK.get());
        helper.runAfterDelay(5, () -> {
            MonitorBlockEntity first = (MonitorBlockEntity) helper.getBlockEntity(PRIMARY);
            MonitorBlockEntity second = (MonitorBlockEntity) helper.getBlockEntity(SECONDARY);
            MonitorBlockEntity anchor = first.wallRenderState().anchor() ? first : second;
            MonitorBlockEntity other = anchor == first ? second : first;
            anchor.setTitle("factory-wall");
            anchor.setPalette(0x123456, 0x654321);
            anchor.setLine(0, "A".repeat(40));
            other.setLine(0, "B".repeat(12));
            CompoundTag firstSaved = first.saveWithFullMetadata();
            CompoundTag secondSaved = second.saveWithFullMetadata();
            UUID firstIdentity = first.getDeviceId();
            UUID secondIdentity = second.getDeviceId();

            helper.setBlock(PRIMARY, Blocks.AIR);
            helper.setBlock(SECONDARY, Blocks.AIR);
            helper.setBlock(PRIMARY, ModRegistries.MONITOR_BLOCK.get());
            helper.setBlock(SECONDARY, ModRegistries.MONITOR_BLOCK.get());
            MonitorBlockEntity restoredFirst = (MonitorBlockEntity) helper.getBlockEntity(PRIMARY);
            MonitorBlockEntity restoredSecond = (MonitorBlockEntity) helper.getBlockEntity(SECONDARY);
            restoredFirst.load(firstSaved);
            restoredSecond.load(secondSaved);

            helper.assertTrue(firstIdentity.equals(restoredFirst.getDeviceId())
                            && secondIdentity.equals(restoredSecond.getDeviceId()),
                    "monitor tile identities must survive reload");
            MonitorBlockEntity restoredAnchor = restoredFirst.wallRenderState().anchor()
                    ? restoredFirst : restoredSecond;
            MonitorBlockEntity restoredOther = restoredAnchor == restoredFirst ? restoredSecond : restoredFirst;
            helper.assertTrue("factory-wall".equals(restoredAnchor.getTitle()),
                    "monitor wall title must survive reload");
            helper.assertTrue(restoredAnchor.foregroundColor() == 0x123456
                            && restoredAnchor.backgroundColor() == 0x654321,
                    "monitor palette must survive reload");
            helper.assertTrue(restoredAnchor.getLines().get(0).equals("A".repeat(40))
                            && restoredOther.getLines().get(0).equals("B".repeat(12)),
                    "monitor wall segments must survive reload without mirroring");

            helper.setBlock(SECONDARY, Blocks.AIR);
            MonitorBlockEntity survivor = (MonitorBlockEntity) helper.getBlockEntity(PRIMARY);
            MonitorBlockEntity.WallRenderState reformed = survivor.wallRenderState();
            helper.assertTrue(reformed.anchor() && reformed.width() == 1 && reformed.height() == 1,
                    "remaining monitor must reform as a one-tile wall");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void modemSaveReloadReregistersChannelsHostAndService(GameTestHelper helper) {
        helper.setBlock(PRIMARY, ModRegistries.MODEM_BLOCK.get());
        ModemBlockEntity modem = (ModemBlockEntity) helper.getBlockEntity(PRIMARY);
        helper.assertTrue(modem.openChannel(42), "modem channel must open");
        helper.assertTrue(modem.setNetworkName("factory-net"), "logical network name must be accepted");
        helper.assertTrue(modem.setHostname("controller"), "hostname must register");
        helper.assertTrue(modem.registerService("status", 42), "service must register on an open channel");
        UUID identity = modem.getModemId();
        CompoundTag saved = modem.saveWithFullMetadata();

        helper.setBlock(PRIMARY, Blocks.AIR);
        helper.setBlock(PRIMARY, ModRegistries.MODEM_BLOCK.get());
        ModemBlockEntity restored = (ModemBlockEntity) helper.getBlockEntity(PRIMARY);
        restored.load(saved);
        restored.reregister();

        helper.assertTrue(identity.equals(restored.getModemId()), "modem identity must survive reload");
        helper.assertTrue(restored.getOpenChannels().equals(List.of(42)),
                "open channels must survive reload");
        helper.assertTrue("factory-net".equals(restored.getNetworkName()),
                "logical network must survive reload");
        helper.assertTrue("controller".equals(restored.getHostname()), "hostname must survive reload");
        helper.assertTrue(restored.localServices().equals(List.of("status 42")),
                "service configuration must survive reload");
        helper.assertTrue("controller".equals(com.malice.terminalcraft.network.RednetNetwork.hostname(
                        helper.getLevel(), restored.getModemId())),
                "restored hostname must be registered in the runtime directory");
        helper.assertTrue(com.malice.terminalcraft.network.RednetNetwork.unregisterService(
                        helper.getLevel(), restored.getModemId(), "status"),
                "restored service must be registered in the runtime directory");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void diskInsertionWriteReloadEjectionAndReinsertionPreserveMedia(GameTestHelper helper) {
        helper.setBlock(PRIMARY, ModRegistries.DISK_DRIVE_BLOCK.get());
        DiskDriveBlockEntity drive = (DiskDriveBlockEntity) helper.getBlockEntity(PRIMARY);
        ItemStack disk = new ItemStack(ModRegistries.FLOPPY_DISK.get());
        FloppyDiskItem.setDiskLabel(disk, "jobs");
        drive.setDisk(disk);
        CompoundTag media = new CompoundTag();
        media.putString("LifecycleProof", "saved");
        helper.assertTrue(drive.writeMedia(media), "loaded media must be writable");
        UUID identity = drive.getDeviceId();
        CompoundTag saved = drive.saveWithFullMetadata();

        helper.setBlock(PRIMARY, Blocks.AIR);
        helper.setBlock(PRIMARY, ModRegistries.DISK_DRIVE_BLOCK.get());
        DiskDriveBlockEntity restored = (DiskDriveBlockEntity) helper.getBlockEntity(PRIMARY);
        restored.load(saved);
        helper.assertTrue(identity.equals(restored.getDeviceId()), "drive identity must survive reload");
        helper.assertTrue(restored.hasDisk(), "inserted disk must survive drive reload");
        helper.assertTrue("jobs".equals(restored.getDiskLabel()), "disk label must survive drive reload");
        helper.assertTrue("saved".equals(restored.readMedia().getString("LifecycleProof")),
                "written media must survive drive reload");

        ItemStack ejected = restored.ejectDisk();
        helper.assertTrue(!ejected.isEmpty() && !restored.hasDisk(), "ejection must return and remove media");
        restored.setDisk(ejected);
        helper.assertTrue(restored.hasDisk()
                        && "saved".equals(restored.readMedia().getString("LifecycleProof")),
                "reinserted media must retain its data");
        helper.succeed();
    }
}
