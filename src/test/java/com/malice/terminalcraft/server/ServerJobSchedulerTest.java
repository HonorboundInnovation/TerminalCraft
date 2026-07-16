package com.malice.terminalcraft.server;

import com.malice.terminalcraft.device.DeviceCallContext;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Headless regression coverage for bounded execution, caller identity, cancellation, and persistence. */
public final class ServerJobSchedulerTest {
    private ServerJobSchedulerTest() {}

    public static void main(String[] args) {
        DeviceCallContext alice = new DeviceCallContext(UUID.randomUUID(), "alice",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        DeviceCallContext bob = DeviceCallContext.readOnly("bob");
        DeviceCallContext aliceService = DeviceCallContext.service(alice.principalId(), "alice-service",
                Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE));
        ServerJobScheduler scheduler = new ServerJobScheduler();
        ServerJobScheduler.Job first = scheduler.submit(alice, "echo first", 10);
        ServerJobScheduler.Job second = scheduler.submit(alice, "false", 11);
        require(first.process().kind() == com.malice.terminalcraft.device.PrincipalIdentity.Kind.PROCESS,
                "every scheduled job has an explicit process identity");
        require(first.process().id().equals(first.id()),
                "new job process identity is stably derived from the job ID");
        require(!first.process().equals(alice.principal()),
                "process provenance remains distinct from its submitting owner");
        ServerJobScheduler.Job cancelled = scheduler.submit(bob, "echo cancelled", 12);
        require(scheduler.cancel(cancelled.id(), bob, 13), "queued job should cancel");

        AtomicInteger calls = new AtomicInteger();
        int executed = scheduler.tick(20, 1, job -> {
            calls.incrementAndGet();
            require(job.context().equals(alice), "executor receives authenticated submitter context");
            return job.command().equals("false") ? 1 : 0;
        });
        require(executed == 1 && calls.get() == 1, "tick budget must cap execution");
        require(scheduler.get(first.id()).state() == ServerJobScheduler.State.COMPLETED, "first completed");
        require(scheduler.get(second.id()).state() == ServerJobScheduler.State.QUEUED, "second remains queued");
        require(scheduler.get(cancelled.id()).state() == ServerJobScheduler.State.CANCELLED,
                "cancelled remains cancelled");

        CompoundTag saved = scheduler.save();
        ServerJobScheduler restored = new ServerJobScheduler();
        restored.load(saved);
        require(restored.list().size() == 3, "persistence retains jobs");
        require(restored.get(second.id()).context().equals(alice), "persistence retains exact caller grants");
        require(restored.get(second.id()).process().equals(second.process()),
                "persistence retains exact process identity");
        ServerJobScheduler.Job serviceJob = restored.submit(aliceService, "echo service", 21);
        CompoundTag typedImage = restored.save();
        ServerJobScheduler typedRestored = new ServerJobScheduler();
        typedRestored.load(typedImage);
        require(typedRestored.get(serviceJob.id()).context().principalKind()
                        == com.malice.terminalcraft.device.PrincipalIdentity.Kind.SERVICE,
                "persistence retains typed service identity");
        require(!typedRestored.cancel(serviceJob.id(), alice, 22),
                "player cannot cancel same-UUID service work");
        require(typedRestored.cancel(serviceJob.id(), aliceService, 22),
                "exact typed principal can cancel its work");
        restored.tick(30, 4, job -> 1);
        require(restored.get(second.id()).state() == ServerJobScheduler.State.FAILED, "nonzero exit fails job");
        require(restored.get(second.id()).exitCode() == 1, "exit code retained");

        ServerJobScheduler running = new ServerJobScheduler();
        ServerJobScheduler.Job retry = running.submit(alice, "echo retry", 40);
        CompoundTag crashImage = running.save();
        crashImage.getList("Jobs", Tag.TAG_COMPOUND).getCompound(0).putString("State", "RUNNING");
        ServerJobScheduler recovered = new ServerJobScheduler();
        recovered.load(crashImage);
        require(recovered.get(retry.id()).state() == ServerJobScheduler.State.QUEUED,
                "in-flight job recovers at a safe retry point");

        CompoundTag malformedKindImage = running.save();
        malformedKindImage.getList("Jobs", Tag.TAG_COMPOUND).getCompound(0)
                .putString("PrincipalKind", "administrator");
        ServerJobScheduler malformedKind = new ServerJobScheduler();
        malformedKind.load(malformedKindImage);
        require(malformedKind.get(retry.id()) == null,
                "unknown persisted principal kinds fail closed");

        // Legacy jobs had an owner but no principal/grant envelope. They must migrate read-only.
        CompoundTag legacyImage = running.save();
        ListTag jobs = legacyImage.getList("Jobs", Tag.TAG_COMPOUND);
        jobs.getCompound(0).remove("PrincipalId");
        jobs.getCompound(0).remove("Permissions");
        ServerJobScheduler legacy = new ServerJobScheduler();
        legacy.load(legacyImage);
        DeviceCallContext migrated = legacy.get(retry.id()).context();
        require(legacy.get(retry.id()).process().id().equals(retry.id()),
                "legacy jobs derive a stable process identity from their job ID");
        require(migrated.permissions().equals(Set.of(DeviceCallContext.READ)),
                "legacy jobs migrate with least privilege");

        reject(() -> scheduler.submit(alice, " ", 0), "blank command");
        reject(() -> scheduler.submit(alice, "x".repeat(ServerJobScheduler.MAX_COMMAND_LENGTH + 1), 0),
                "oversized command");
        reject(() -> scheduler.submit(null, "echo no", 0), "missing context");
        require(!scheduler.cancel(second.id(), bob, 0), "another principal cannot cancel a job");
        require(!scheduler.cancel(UUID.randomUUID(), alice, 0), "unknown job cannot cancel");

        timersWaitForLogicalGameTimeAndSurviveReload(alice);
        ownersReceiveFairTickService(alice, bob);
        ownerQuotaAndTickBudgetAreBounded(alice);
        cooperativeSlicesPersistAndCancellationWins(alice);
        executorDoesNotHoldSchedulerMonitor(alice);

        System.out.println("Server job scheduler tests: OK");
    }

    private static void timersWaitForLogicalGameTimeAndSurviveReload(DeviceCallContext owner) {
        ServerJobScheduler scheduler = new ServerJobScheduler();
        ServerJobScheduler.Job timer = scheduler.schedule(owner, "echo later", 100, 20);
        require(timer.eligibleAt() == 120, "timer uses logical game-time eligibility");
        require(scheduler.tick(119, 8, job -> 0) == 0, "timer cannot run before its deadline");
        require(scheduler.diagnostics().deferred() == 1, "deferred timer is observable");

        ServerJobScheduler restored = new ServerJobScheduler();
        restored.load(scheduler.save());
        require(restored.get(timer.id()).eligibleAt() == 120, "timer deadline survives persistence");
        require(restored.tick(120, 8, job -> 0) == 1, "timer runs at its logical deadline");
        require(restored.get(timer.id()).state() == ServerJobScheduler.State.COMPLETED,
                "due timer completes normally");
        reject(() -> scheduler.schedule(owner, "bad", 0, -1), "negative timer delay");
        reject(() -> scheduler.schedule(owner, "bad", 0,
                ServerJobScheduler.MAX_DELAY_TICKS + 1), "oversized timer delay");
        reject(() -> scheduler.schedule(owner, "bad", Long.MAX_VALUE, 1),
                "overflowing timer deadline");
    }

    private static void ownersReceiveFairTickService(DeviceCallContext alice, DeviceCallContext bob) {
        ServerJobScheduler scheduler = new ServerJobScheduler();
        scheduler.submit(alice, "alice-1", 0);
        scheduler.submit(alice, "alice-2", 0);
        scheduler.submit(bob, "bob-1", 0);
        java.util.List<String> executed = new java.util.ArrayList<>();
        require(scheduler.tick(1, 2, job -> { executed.add(job.command()); return 0; }) == 2,
                "fair scheduler consumes available budget");
        require(executed.equals(java.util.List.of("alice-1", "bob-1")),
                "one owner cannot monopolize a constrained tick");
        require(scheduler.diagnostics().executed() == 2
                        && scheduler.diagnostics().queued() == 1,
                "tick cost and remaining queue are observable");
    }

    private static void ownerQuotaAndTickBudgetAreBounded(DeviceCallContext owner) {
        ServerJobScheduler scheduler = new ServerJobScheduler();
        for (int i = 0; i < ServerJobScheduler.MAX_ACTIVE_JOBS_PER_OWNER; i++) {
            scheduler.submit(owner, "echo " + i, 0);
        }
        rejectState(() -> scheduler.submit(owner, "overflow", 0), "per-owner active-job quota");
        int executed = scheduler.tick(1, Integer.MAX_VALUE, job -> 0);
        require(executed == ServerJobScheduler.MAX_ACTIVE_JOBS_PER_OWNER,
                "available work below the hard tick cap executes normally");
        require(scheduler.diagnostics().budget() == ServerJobScheduler.MAX_TICK_BUDGET,
                "requested tick budget is clamped to the hard cap");
    }

    private static void cooperativeSlicesPersistAndCancellationWins(DeviceCallContext owner) {
        ServerJobScheduler scheduler = new ServerJobScheduler();
        ServerJobScheduler.Job sliced = scheduler.submit(owner, "cooperative", 0);
        require(scheduler.tickSteps(1, 1, job ->
                        ServerJobScheduler.StepResult.yield(1, "phase-two")) == 1,
                "one cooperative slice consumes one budget unit");
        ServerJobScheduler.Job yielded = scheduler.get(sliced.id());
        require(yielded.state() == ServerJobScheduler.State.QUEUED
                        && yielded.eligibleAt() == 2
                        && yielded.continuationVersion() == 1
                        && yielded.continuation().equals("phase-two"),
                "yield persists a bounded versioned continuation for the next tick");

        ServerJobScheduler restored = new ServerJobScheduler();
        restored.load(scheduler.save());
        require(restored.get(sliced.id()).continuation().equals("phase-two"),
                "cooperative continuation survives reload");
        require(restored.tickSteps(2, 1, job -> {
            require(job.continuationVersion() == 1 && job.continuation().equals("phase-two"),
                    "executor receives the persisted continuation");
            return ServerJobScheduler.StepResult.completed(0);
        }) == 1, "restored continuation receives service");
        require(restored.get(sliced.id()).state() == ServerJobScheduler.State.COMPLETED,
                "restored cooperative job completes");

        ServerJobScheduler cancelling = new ServerJobScheduler();
        ServerJobScheduler.Job victim = cancelling.submit(owner, "cancel-between-slices", 0);
        cancelling.tickSteps(1, 1, running -> {
            require(cancelling.cancel(running.id(), owner, 1),
                    "executor callback runs outside the scheduler monitor and can be cancelled");
            return ServerJobScheduler.StepResult.completed(0);
        });
        require(cancelling.get(victim.id()).state() == ServerJobScheduler.State.CANCELLED,
                "authoritative cancellation wins over a stale slice result");

        ServerJobScheduler waiting = new ServerJobScheduler();
        ServerJobScheduler.Job waiter = waiting.submit(owner, "wait", 10);
        waiting.tickSteps(10, 1, job -> ServerJobScheduler.StepResult.waitUntil(20, 2, "wake"));
        require(waiting.tickSteps(19, 1, job -> ServerJobScheduler.StepResult.completed(0)) == 0,
                "waiting continuation cannot run before its logical deadline");
        require(waiting.tickSteps(20, 1, job -> ServerJobScheduler.StepResult.completed(0)) == 1
                        && waiting.get(waiter.id()).state() == ServerJobScheduler.State.COMPLETED,
                "waiting continuation runs at its deadline");
    }

    private static void executorDoesNotHoldSchedulerMonitor(DeviceCallContext owner) {
        ServerJobScheduler scheduler = new ServerJobScheduler();
        ServerJobScheduler.Job job = scheduler.submit(owner, "blocked-slice", 0);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                scheduler.tickSteps(1, 1, running -> {
                    entered.countDown();
                    try {
                        if (!release.await(2, TimeUnit.SECONDS)) {
                            throw new AssertionError("timed out waiting to release cooperative slice");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("cooperative slice was interrupted", interrupted);
                    }
                    return ServerJobScheduler.StepResult.completed(0);
                });
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "scheduler-monitor-proof");
        worker.start();
        await(entered, "executor did not start");

        long started = System.nanoTime();
        require(scheduler.cancel(job.id(), owner, 1),
                "cancellation must acquire the scheduler while a slice callback is blocked");
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        require(elapsedMillis < 500,
                "scheduler monitor was held by the executor callback for " + elapsedMillis + " ms");
        release.countDown();
        try {
            worker.join(2_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while joining scheduler proof thread", interrupted);
        }
        require(!worker.isAlive(), "scheduler proof thread must terminate");
        if (failure.get() != null) throw new AssertionError("scheduler proof thread failed", failure.get());
        require(scheduler.get(job.id()).state() == ServerJobScheduler.State.CANCELLED,
                "blocked slice completion cannot overwrite cancellation");
    }

    private static void await(CountDownLatch latch, String message) {
        try {
            require(latch.await(2, TimeUnit.SECONDS), message);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, interrupted);
        }
    }

    private static void rejectState(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message + ": expected rejection");
        } catch (IllegalStateException expected) {
            // Expected.
        }
    }

    private static void reject(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message + ": expected rejection");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
