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

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void namedServiceRunsStopsAndResumes(GameTestHelper helper) {
        helper.setBlock(BOTTOM, ModRegistries.SERVER_RACK_BLOCK.get());
        var rack = (ServerRackBlockEntity) helper.getBlockEntity(BOTTOM);
        rack.installModule(0, new ItemStack(ModRegistries.SERVER_BLADE.get()), false);
        var owner = DeviceCallContext.player(UUID.randomUUID(), "service-owner", Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        var guest = DeviceCallContext.player(UUID.randomUUID(), "service-guest", Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        var defined = rack.getShell().executeForResult("service define heartbeat 4 'echo beat >> /home/player/beats.txt'", owner);
        helper.assertTrue(defined.succeeded(), "define recurring service: " + defined);
        helper.assertTrue(rack.getShell().executeForResult("service start heartbeat", owner).succeeded(), "start service");
        helper.runAfterDelay(18, () -> {
            String beats = rack.getShell().getVfs().readFile("/home/player/beats.txt");
            helper.assertTrue(beats != null && beats.lines().count() >= 2, "service repeatedly runs its real shell command");
            helper.assertTrue(!rack.getShell().executeForResult("service stop heartbeat", guest).succeeded(), "other player cannot stop owner's service");
            helper.assertTrue(rack.getShell().executeForResult("service stop heartbeat", owner).succeeded(), "owner stops service");
            helper.runAfterDelay(8, () -> {
                helper.assertTrue(beats.equals(rack.getShell().getVfs().readFile("/home/player/beats.txt")), "stopped service no longer executes");
                helper.assertTrue(rack.getShell().executeForResult("service restart heartbeat", owner).succeeded(), "owner restarts service");
                rack.load(rack.saveWithoutMetadata());
                helper.runAfterDelay(12, () -> {
                    helper.assertTrue(rack.getShell().getVfs().readFile("/home/player/beats.txt").length() > beats.length(), "enabled service resumes after saved-state reload");
                    var status = rack.getShell().executeForResult("service status heartbeat", owner);
                    helper.assertTrue(status.succeeded() && status.outputLines().get(0).contains("enabled=true"), "service status exposes desired state");
                    rack.getShell().executeForResult("service stop heartbeat", owner);
                    helper.assertTrue(rack.getShell().executeForResult("service remove heartbeat", owner).succeeded(), "stopped service can be removed");
                    rack.getShell().executeForResult("service define broken 4 definitely-not-a-command", owner);
                    rack.getShell().executeForResult("service start broken", owner);
                    helper.runAfterDelay(6, () -> {
                        var failed = rack.getShell().executeForResult("service status broken", owner);
                        helper.assertTrue(failed.succeeded() && failed.outputLines().get(0).contains("failed enabled=false"), "failed command stops service with visible result: " + failed);
                        helper.succeed();
                    });
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void terminalRunsAuthorizedRemoteRackCommands(GameTestHelper helper) {
        helper.setBlock(BOTTOM, ModRegistries.SERVER_RACK_BLOCK.get());
        var rack = (ServerRackBlockEntity) helper.getBlockEntity(BOTTOM);
        rack.installModule(0, new ItemStack(ModRegistries.SERVER_BLADE.get()), false);
        BlockPos terminalPos = BOTTOM.east(3);
        helper.setBlock(terminalPos, ModRegistries.TERMINAL_BLOCK.get());
        var terminal = (com.malice.terminalcraft.blockentity.TerminalBlockEntity) helper.getBlockEntity(terminalPos);
        var owner = DeviceCallContext.player(UUID.randomUUID(), "rack-owner", Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        var teammate = DeviceCallContext.player(UUID.randomUUID(), "rack-teammate", Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        helper.runAfterDelay(4, () -> {
            String remote = "rsh " + rack.getDeviceId() + " ";
            helper.assertTrue(!terminal.getShell().executeForResult(remote + "pwd", owner).succeeded(), "remote access starts disabled");
            helper.assertTrue(rack.getShell().executeForResult("server remote on", owner).succeeded(), "owner enables access locally");
            var discovery = terminal.getShell().executeForResult("device list remote_shell", owner);
            helper.assertTrue(discovery.outputLines().stream().anyMatch(line -> line.contains(rack.getDeviceId().toString())), "rack is discoverable");
            helper.assertTrue(terminal.getShell().executeForResult(remote + "'echo remote-file > /home/player/remote.txt'", owner).succeeded(), "owner writes on remote rack");
            var read = terminal.getShell().executeForResult(remote + "'cat /home/player/remote.txt'", owner);
            helper.assertTrue(read.succeeded() && read.outputLines().contains("remote-file"), "remote output returns to originating terminal: " + read);
            helper.assertTrue(terminal.getShell().getVfs().readFile("/home/player/remote.txt") == null, "remote file is not created on client terminal");
            helper.assertTrue(!terminal.getShell().executeForResult(remote + "pwd", teammate).succeeded(), "uninvited teammate cannot execute");
            helper.assertTrue(rack.getShell().executeForResult("server remote allow " + teammate.principalId(), owner).succeeded(), "owner invites teammate");
            helper.assertTrue(terminal.getShell().executeForResult(remote + "pwd", teammate).succeeded(), "invited teammate executes");
            var saved = rack.saveWithoutMetadata();
            rack.load(saved);
            helper.assertTrue(terminal.getShell().executeForResult(remote + "'cat /home/player/remote.txt'", owner).succeeded(), "saved access and files reload");
            var audit = rack.getShell().executeForResult("server remote audit", owner);
            helper.assertTrue(audit.succeeded() && audit.outputLines().stream().anyMatch(line -> line.contains("exec exit=0")), "owner can inspect audit");
            terminal.getShell().execute("export WHERE=local", owner);
            rack.getShell().executeForResult("export WHERE=remote", owner);
            terminal.getShell().execute("rsh connect " + rack.getDeviceId(), owner);
            helper.assertTrue(terminal.getShell().getLastExitCode() == 0, "interactive session connects");
            terminal.getShell().execute("mkdir /home/player/session", owner);
            terminal.getShell().execute("cd /home/player/session", owner);
            terminal.getShell().execute("echo $WHERE > answer.txt", owner);
            helper.assertTrue("remote\n".equals(rack.getShell().getVfs().readFile("/home/player/session/answer.txt")), "session expands variables and redirects on rack");
            helper.assertTrue("/home/player".equals(terminal.getShell().getCwd()), "session leaves local directory unchanged");
            terminal.getShell().execute("cat answer.txt", owner);
            helper.assertTrue(terminal.getShell().getLastExitCode() == 0, "session keeps remote working directory between commands");
            terminal.getShell().execute("exit", owner);
            terminal.getShell().execute("echo $WHERE > local.txt", owner);
            helper.assertTrue("local\n".equals(terminal.getShell().getVfs().readFile("/home/player/local.txt")), "disconnect restores local shell execution");
            terminal.getShell().execute("rsh connect " + rack.getDeviceId(), owner);
            helper.assertTrue(rack.getShell().executeForResult("server remote off", owner).succeeded(), "owner disables access");
            terminal.getShell().execute("echo denied > must-not-exist.txt", owner);
            helper.assertTrue(terminal.getShell().getLastExitCode() != 0 && terminal.getShell().getVfs().readFile("/home/player/must-not-exist.txt") == null,
                    "revoked session does not retry command locally");
            helper.assertTrue(!terminal.getShell().executeForResult(remote + "pwd", owner).succeeded(), "disabled service refuses new commands");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void shellSchedulesDelayedRackWork(GameTestHelper helper) {
        helper.setBlock(BOTTOM, ModRegistries.SERVER_RACK_BLOCK.get());
        var rack = (ServerRackBlockEntity) helper.getBlockEntity(BOTTOM);
        helper.assertTrue(rack.installModule(0, new ItemStack(ModRegistries.SERVER_BLADE.get()), false), "compute blade installed");
        var owner = DeviceCallContext.player(UUID.randomUUID(), "delayed-owner", Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        var result = rack.getShell().executeForResult("server after 20 \"echo delayed-output > /home/player/delayed.txt\"", owner);
        helper.assertTrue(result.succeeded() && rack.serverQueuedJobs() == 1, "shell schedules one delayed job: " + result);
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(rack.getShell().getVfs().readFile("/home/player/delayed.txt") == null && rack.serverQueuedJobs() == 1,
                    "delayed command does not run early");
            var saved = rack.saveWithoutMetadata();
            rack.load(saved);
            helper.assertTrue(rack.serverQueuedJobs() == 1, "delayed job survives saved-state reload");
            helper.runAfterDelay(25, () -> {
                helper.assertTrue("delayed-output\n".equals(rack.getShell().getVfs().readFile("/home/player/delayed.txt")),
                        "eligible delayed job executes through the real shell");
                var jobs = rack.getShell().executeForResult("server list", owner);
                helper.assertTrue(jobs.succeeded() && jobs.outputLines().stream().anyMatch(line -> line.contains("completed") && line.contains("exit=0")),
                        "owner sees completed delayed job: " + jobs);
                helper.succeed();
            });
        });
    }

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
