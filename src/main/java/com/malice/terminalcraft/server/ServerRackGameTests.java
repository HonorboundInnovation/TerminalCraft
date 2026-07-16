package com.malice.terminalcraft.server;

import com.malice.terminalcraft.block.ServerRackBlock;
import com.malice.terminalcraft.blockentity.ServerRackBlockEntity;
import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.network.WiredNetworkTopology;
import com.malice.terminalcraft.registry.ModRegistries;
import com.malice.terminalcraft.shell.ShellCommandResult;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;

import java.util.Set;
import java.util.UUID;

/** Live rack-cabinet coverage for bays, stack limits, routing, drops, and legacy migration. */
@GameTestHolder("terminalcraft")
public final class ServerRackGameTests {
    private static final BlockPos BOTTOM = new BlockPos(2, 2, 2);

    private ServerRackGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void threeRackCabinetHoldsSixBlades(GameTestHelper helper) {
        for (int y = 0; y < 3; y++) helper.setBlock(BOTTOM.above(y), ModRegistries.SERVER_RACK_BLOCK.get());
        helper.runAfterDelay(3, () -> {
            for (int y = 0; y < 3; y++) {
                ServerRackBlockEntity rack = (ServerRackBlockEntity) helper.getBlockEntity(BOTTOM.above(y));
                helper.assertTrue(rack.installModule(0, new ItemStack(ModRegistries.SERVER_BLADE.get()), false),
                        "lower server blade must install");
                ItemStack upper = new ItemStack(y == 1
                        ? ModRegistries.ROUTER_BLADE.get() : ModRegistries.SERVER_BLADE.get());
                helper.assertTrue(rack.installModule(1, upper, false),
                        "upper blade must install");
            }
            ServerRackBlockEntity bottom = (ServerRackBlockEntity) helper.getBlockEntity(BOTTOM);
            helper.assertTrue(bottom.cabinetStatus().contains("modules=6/6")
                            && bottom.cabinetStatus().contains("servers=5")
                            && bottom.cabinetStatus().contains("routers=1"),
                    "three connected racks must expose exactly six shared bays: " + bottom.cabinetStatus());
            helper.assertTrue(ServerRackBlock.connectedHeight(helper.getLevel(), helper.absolutePos(BOTTOM.above(3))) == 3,
                    "a fourth rack position must see the complete three-rack stack and be rejected");
            WiredNetworkTopology.Component network = WiredNetworkTopology.inspect(helper.getLevel(), helper.absolutePos(BOTTOM));
            helper.assertTrue(network.nodeCount() == 3,
                    "one router blade must activate all three sections of the shared cabinet backplane");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void oneBladeBudgetsOneJobPerTickAndCancellationWins(GameTestHelper helper) {
        helper.setBlock(BOTTOM, ModRegistries.SERVER_RACK_BLOCK.get());
        helper.runAfterDelay(3, () -> {
            ServerRackBlockEntity rack = (ServerRackBlockEntity) helper.getBlockEntity(BOTTOM);
            helper.assertTrue(rack.installModule(0,
                    new ItemStack(ModRegistries.SERVER_BLADE.get()), false),
                    "server blade must install for scheduler execution");
            DeviceCallContext owner = new DeviceCallContext(UUID.randomUUID(), "rack-budget-test",
                    Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
            String first = rack.serverSubmit(owner, "echo first");
            String second = rack.serverSubmit(owner, "echo second");
            String cancelled = rack.serverSubmit(owner, "echo cancelled");
            helper.assertTrue(!first.isBlank() && !second.isBlank() && !cancelled.isBlank(),
                    "all bounded jobs must be admitted");
            helper.assertTrue(rack.serverCancel(owner, cancelled),
                    "owner must cancel queued work before its execution slice");

            helper.runAfterDelay(1, () -> {
                String firstState = rack.serverJob(first);
                String secondState = rack.serverJob(second);
                helper.assertTrue(firstState.contains(" completed ") && secondState.contains(" queued "),
                        "one blade must execute exactly one queued command per tick: "
                                + firstState + " / " + secondState);
                helper.assertTrue(rack.serverJob(cancelled).contains(" cancelled "),
                        "cancelled work must remain terminal and must not execute");
                helper.runAfterDelay(1, () -> {
                    helper.assertTrue(rack.serverJob(second).contains(" completed "),
                            "remaining work must receive service on the next logical tick");
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void liveShellCommandsEnforceOwnershipAndExposeBoundedDiagnostics(GameTestHelper helper) {
        helper.setBlock(BOTTOM, ModRegistries.SERVER_RACK_BLOCK.get());
        helper.runAfterDelay(3, () -> {
            ServerRackBlockEntity rack = (ServerRackBlockEntity) helper.getBlockEntity(BOTTOM);
            helper.assertTrue(rack.installModule(0,
                    new ItemStack(ModRegistries.SERVER_BLADE.get()), false),
                    "server blade must install for live command verification");
            UUID sharedId = UUID.randomUUID();
            DeviceCallContext player = new DeviceCallContext(sharedId, "live-player",
                    Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
            DeviceCallContext sameIdService = DeviceCallContext.service(sharedId, "live-service",
                    Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));

            ShellCommandResult submitted = rack.getShell().executeForResult("server submit echo live", player);
            helper.assertTrue(submitted.exitCode() == 0 && submitted.outputLines().size() == 1,
                    "caller-bound live shell submission must return one job ID: " + submitted.outputLines());
            String jobId = submitted.outputLines().get(0);
            ShellCommandResult foreignStatus = rack.getShell().executeForResult(
                    "server status " + jobId, sameIdService);
            helper.assertTrue(foreignStatus.exitCode() == 1
                            && foreignStatus.outputLines().equals(java.util.List.of("server: job not found")),
                    "same-UUID service must not inspect a player's live job: "
                            + foreignStatus.outputLines());

            ShellCommandResult auth = rack.getShell().executeForResult("auth status", player);
            helper.assertTrue(auth.exitCode() == 0
                            && auth.outputLines().contains("read=allowed")
                            && auth.outputLines().contains("mutate=allowed")
                            && auth.outputLines().contains("escrow_admin=denied"),
                    "live auth command must report central decisions without elevation: "
                            + auth.outputLines());

            helper.runAfterDelay(1, () -> {
                ShellCommandResult ownerStatus = rack.getShell().executeForResult(
                        "server status " + jobId, player);
                helper.assertTrue(ownerStatus.exitCode() == 0
                                && ownerStatus.outputLines().get(0).contains(" completed "),
                        "owner must inspect the completed live job: " + ownerStatus.outputLines());
                ShellCommandResult scheduler = rack.getShell().executeForResult("server scheduler", player);
                helper.assertTrue(scheduler.exitCode() == 0 && scheduler.outputLines().size() == 1
                                && scheduler.outputLines().get(0).contains("budget=1")
                                && scheduler.outputLines().get(0).contains("executed=1")
                                && !scheduler.outputLines().get(0).contains("live-player")
                                && !scheduler.outputLines().get(0).contains("echo live"),
                        "scheduler diagnostics must be bounded and aggregate-only: "
                                + scheduler.outputLines());
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void persistedLogicalTimerWaitsForDeadlineAfterRackLoad(GameTestHelper helper) {
        helper.setBlock(BOTTOM, ModRegistries.SERVER_RACK_BLOCK.get());
        helper.runAfterDelay(3, () -> {
            ServerRackBlockEntity rack = (ServerRackBlockEntity) helper.getBlockEntity(BOTTOM);
            DeviceCallContext owner = DeviceCallContext.readOnly("rack-timer-test");
            long now = helper.getLevel().getGameTime();
            ServerJobScheduler image = new ServerJobScheduler();
            ServerJobScheduler.Job timer = image.schedule(owner, "echo delayed", now, 6);
            CompoundTag persisted = new CompoundTag();
            persisted.put("Shell", rack.getShell().save());
            persisted.put("Scheduler", image.save());
            rack.load(persisted);
            rack.onLoad();
            helper.assertTrue(rack.hasServerModule(),
                    "legacy scheduler image must restore an executable server blade");

            helper.runAfterDelay(3, () -> {
                helper.assertTrue(rack.serverJob(timer.id().toString()).contains(" queued "),
                        "persisted timer must remain queued before its logical deadline");
                helper.runAfterDelay(4, () -> {
                    helper.assertTrue(rack.serverJob(timer.id().toString()).contains(" completed "),
                            "persisted timer must execute after its logical deadline");
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void emptyRackDoesNotRouteAndLegacyRackGainsServerBlade(GameTestHelper helper) {
        helper.setBlock(BOTTOM, ModRegistries.SERVER_RACK_BLOCK.get());
        helper.runAfterDelay(3, () -> {
            ServerRackBlockEntity rack = (ServerRackBlockEntity) helper.getBlockEntity(BOTTOM);
            helper.assertTrue(WiredNetworkTopology.inspect(helper.getLevel(), helper.absolutePos(BOTTOM)).nodeCount() == 0,
                    "an empty chassis must not forward RedNet traffic");

            CompoundTag legacy = new CompoundTag();
            legacy.put("Shell", rack.getShell().save());
            legacy.put("Scheduler", new ServerJobScheduler().save());
            rack.load(legacy);
            rack.onLoad();
            helper.assertTrue(rack.hasServerModule(),
                    "legacy monolithic rack data must migrate to one server blade");
            helper.assertTrue(rack.serverQueuedJobs() == 0,
                    "migrated server blade must retain an operational scheduler");
            helper.succeed();
        });
    }
}
