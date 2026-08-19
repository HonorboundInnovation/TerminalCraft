package com.malice.terminalcraft.device;

import com.malice.terminalcraft.blockentity.MonitorBlockEntity;
import com.malice.terminalcraft.blockentity.ProgrammableLogicControllerBlockEntity;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraft.nbt.CompoundTag;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Live proof that adjacent monitor tiles expose one addressable canvas, not mirrored screens. */
@GameTestHolder("terminalcraft")
public final class MonitorWallGameTests {
    private static final BlockPos LEFT = new BlockPos(2, 2, 2);
    private static final BlockPos RIGHT = new BlockPos(3, 2, 2);
    private static final BlockPos PLC = new BlockPos(2, 2, 3);

    private MonitorWallGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void adjacentMonitorsFormOneIndependentlyRenderedCanvas(GameTestHelper helper) {
        helper.setBlock(LEFT, ModRegistries.MONITOR_BLOCK.get());
        helper.setBlock(RIGHT, ModRegistries.MONITOR_BLOCK.get());
        helper.runAfterDelay(5, () -> {
            MonitorBlockEntity left = (MonitorBlockEntity) helper.getBlockEntity(LEFT);
            MonitorBlockEntity right = (MonitorBlockEntity) helper.getBlockEntity(RIGHT);

            // Wall ownership follows visual top-left ordering for the shared facing, not packed
            // BlockPos ordering. The renderer and device registry must agree on that same owner.
            MonitorBlockEntity.WallRenderState leftState = left.wallRenderState();
            MonitorBlockEntity.WallRenderState rightState = right.wallRenderState();
            helper.assertTrue(leftState.anchor() != rightState.anchor(),
                    "exactly one tile must own rendering and device registration for the wall");
            MonitorBlockEntity anchor = leftState.anchor() ? left : right;
            MonitorBlockEntity second = leftState.anchor() ? right : left;

            ServerLevel level = helper.getLevel();
            DeviceAccess access = ServerDeviceManager.access(level.getServer(), new DeviceCallContext(
                    UUID.randomUUID(), "monitor-wall-test", Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE)));
            DeviceDescriptor descriptor = access.descriptor(anchor.getDeviceId()).orElseThrow(() ->
                    new AssertionError("visual wall anchor must own the registered monitor endpoint"));
            helper.assertTrue(number(descriptor, "columns") == 80 && number(descriptor, "rows") == 20,
                    "two horizontal tiles must expose one 80x20 canvas");
            String text = "A".repeat(40) + "B".repeat(10);
            DeviceResult result = access.call(anchor.getDeviceId(), "line.set",
                    List.of(DeviceValue.of(0), DeviceValue.of(text)));
            helper.assertTrue(result.isSuccess(), "wall line.set must succeed: " + result.error());
            helper.assertTrue(anchor.getLines().get(0).equals("A".repeat(40)),
                    "visual anchor tile must render only the first canvas segment");
            helper.assertTrue(second.getLines().get(0).equals("B".repeat(10)),
                    "second tile must render only the second canvas segment rather than mirroring");
            helper.succeed();
        });
    }

    /** Live topology proof that resize is emitted once, after the replacement endpoint is current. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void wallTopologyChangePublishesOneCurrentResizeEvent(GameTestHelper helper) {
        helper.setBlock(LEFT, ModRegistries.MONITOR_BLOCK.get());
        helper.runAfterDelay(5, () -> {
            ServerLevel level = helper.getLevel();
            DeviceRegistry registry = ServerDeviceManager.registry(level.getServer());
            DeviceCallContext reader = DeviceCallContext.readOnly("monitor-resize-test");
            DeviceResult subscribed = registry.subscribeEvents(reader,
                    new DeviceEventSubscription(null, Set.of("monitor_resize"), 0, false));
            helper.assertTrue(subscribed.isSuccess(), "resize subscription must succeed: " + subscribed.error());
            UUID subscriptionId = UUID.fromString(((DeviceValue.StringValue)
                    subscribed.value().orElseThrow()).value());

            // Subscribe only after the one-tile endpoint has stabilized, so the observed event must
            // describe this topology transition rather than initial registration.
            helper.setBlock(RIGHT, ModRegistries.MONITOR_BLOCK.get());
            helper.runAfterDelay(5, () -> {
                DeviceEventBatch batch = registry.pollSubscription(reader, subscriptionId, 10);
                helper.assertTrue(batch.dropped() == 0, "resize proof must not drop events");
                MonitorBlockEntity left = (MonitorBlockEntity) helper.getBlockEntity(LEFT);
                MonitorBlockEntity right = (MonitorBlockEntity) helper.getBlockEntity(RIGHT);
                UUID currentAnchorId = left.wallRenderState().anchor()
                        ? left.getDeviceId() : right.getDeviceId();
                List<DeviceEvent> localResizes = batch.events().stream()
                        .filter(event -> event.sourceDeviceId().equals(currentAnchorId))
                        .toList();
                helper.assertTrue(localResizes.size() == 1,
                        "one local wall topology transition must publish exactly one resize event, got "
                                + localResizes.size());
                DeviceEvent resize = localResizes.get(0);
                helper.assertTrue(mapNumber(resize.payload(), "width") == 2
                                && mapNumber(resize.payload(), "height") == 1,
                        "resize payload must report the current 2x1 tile geometry");

                DeviceDescriptor current = registry.descriptor(resize.sourceDeviceId()).orElseThrow(() ->
                        new AssertionError("resize source must resolve to the rebuilt current endpoint"));
                helper.assertTrue(number(current, "columns") == 80 && number(current, "rows") == 20,
                        "rebuilt endpoint geometry must already match the resize event");
                helper.assertTrue(registry.pollSubscription(reader, subscriptionId, 10).events().stream()
                                .noneMatch(event -> event.sourceDeviceId().equals(currentAnchorId)),
                        "stable topology must not publish duplicate local resize events");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void wallCharacterSurfaceSpansTilesAndPagesBoundedDeltas(GameTestHelper helper) {
        helper.setBlock(LEFT, ModRegistries.MONITOR_BLOCK.get());
        helper.setBlock(RIGHT, ModRegistries.MONITOR_BLOCK.get());
        helper.runAfterDelay(5, () -> {
            MonitorBlockEntity left = (MonitorBlockEntity) helper.getBlockEntity(LEFT);
            MonitorBlockEntity right = (MonitorBlockEntity) helper.getBlockEntity(RIGHT);
            MonitorBlockEntity.WallRenderState leftState = left.wallRenderState();
            MonitorBlockEntity anchor = leftState.anchor() ? left : right;
            MonitorBlockEntity second = leftState.anchor() ? right : left;

            ServerLevel level = helper.getLevel();
            DeviceAccess access = ServerDeviceManager.access(level.getServer(), new DeviceCallContext(
                    UUID.randomUUID(), "monitor-wall-surface-test", Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE)));
            DeviceDescriptor descriptor = access.descriptor(anchor.getDeviceId()).orElseThrow();
            long before = (long) number(descriptor, "surface_revision");
            helper.assertTrue(access.call(anchor.getDeviceId(), "term.set_cursor_pos",
                    List.of(DeviceValue.of(39), DeviceValue.of(1))).isSuccess(),
                    "wall cursor placement must succeed");
            helper.assertTrue(access.call(anchor.getDeviceId(), "term.blit",
                    List.of(DeviceValue.of("ABCD"), DeviceValue.of("0123"), DeviceValue.of("4567"))).isSuccess(),
                    "wall blit must succeed across the tile boundary");

            char anchor38 = anchor.terminalSurface().characterAt(38, 0);
            char anchor39 = anchor.terminalSurface().characterAt(39, 0);
            char second0 = second.terminalSurface().characterAt(0, 0);
            char second1 = second.terminalSurface().characterAt(1, 0);
            helper.assertTrue(anchor38 == 'A' && anchor39 == 'B' && second0 == 'C' && second1 == 'D',
                    "wall blit must write global cells into the two persisted tile surfaces; actual="
                            + printable(anchor38) + printable(anchor39) + "/"
                            + printable(second0) + printable(second1));
            DeviceValue.MapValue delta = (DeviceValue.MapValue) access.call(anchor.getDeviceId(), "term.delta",
                    List.of(DeviceValue.of(before), DeviceValue.of(128))).value().orElseThrow();
            helper.assertTrue(!((DeviceValue.BooleanValue) delta.values().get("complete")).value()
                            && ((DeviceValue.NumberValue) delta.values().get("total_cells")).value() == 1600,
                    "wall deltas must remain bounded and expose pagination for a full 80x20 surface");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void wallSurfaceAndIdentitySurviveTileSaveLoad(GameTestHelper helper) {
        helper.setBlock(LEFT, ModRegistries.MONITOR_BLOCK.get());
        helper.setBlock(RIGHT, ModRegistries.MONITOR_BLOCK.get());
        helper.runAfterDelay(5, () -> {
            MonitorBlockEntity left = (MonitorBlockEntity) helper.getBlockEntity(LEFT);
            MonitorBlockEntity right = (MonitorBlockEntity) helper.getBlockEntity(RIGHT);
            MonitorBlockEntity anchor = left.wallRenderState().anchor() ? left : right;
            UUID leftId = left.getDeviceId();
            UUID rightId = right.getDeviceId();
            anchor.setWallLine(0, "persisted wall surface" + " ".repeat(40));
            anchor.terminalSurface().setCell(39, 0, 'Z', 2, 4);
            CompoundTag leftImage = left.getUpdateTag();
            CompoundTag rightImage = right.getUpdateTag();

            left.load(leftImage);
            right.load(rightImage);
            helper.assertTrue(left.getDeviceId().equals(leftId) && right.getDeviceId().equals(rightId),
                    "tile reload must preserve stable monitor identities");
            helper.assertTrue(anchor.terminalSurface().characterAt(39, 0) == 'Z'
                            && anchor.getLines().get(0).contains("persisted wall"),
                    "tile reload must preserve the persisted character surface and line mirror");
            helper.assertTrue(anchor.wallColumns() == 80 && anchor.wallRows() == 20,
                    "reloaded tiles must continue resolving as one wall");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void wallFontScaleAndColorPersistAcrossEveryTile(GameTestHelper helper) {
        helper.setBlock(LEFT, ModRegistries.MONITOR_BLOCK.get());
        helper.setBlock(RIGHT, ModRegistries.MONITOR_BLOCK.get());
        helper.runAfterDelay(5, () -> {
            MonitorBlockEntity left = (MonitorBlockEntity) helper.getBlockEntity(LEFT);
            MonitorBlockEntity right = (MonitorBlockEntity) helper.getBlockEntity(RIGHT);
            MonitorBlockEntity anchor = left.wallRenderState().anchor() ? left : right;
            DeviceAccess access = ServerDeviceManager.access(helper.getLevel().getServer(), new DeviceCallContext(
                    UUID.randomUUID(), "monitor-appearance-test",
                    Set.of(DeviceCallContext.READ, DeviceCallContext.WRITE)));

            DeviceResult scaled = access.call(anchor.getDeviceId(), "monitor.set_text_scale",
                    List.of(DeviceValue.of(2.5)));
            helper.assertTrue(scaled.isSuccess(), "device API text-scale edit must succeed: " + scaled.error());
            anchor.configureWallAppearance(2.5, 0xFFB347);
            helper.assertTrue(left.wallTextScale() == 2.5 && right.wallTextScale() == 2.5,
                    "font scale must propagate to every connected monitor tile");
            helper.assertTrue(left.foregroundColor() == 0xFFB347 && right.foregroundColor() == 0xFFB347,
                    "default font color must propagate to every connected monitor tile");
            helper.assertTrue(left.terminalSurface().paletteColor(left.terminalSurface().textColor()) == 0xFFB347
                            && right.terminalSurface().paletteColor(right.terminalSurface().textColor()) == 0xFFB347,
                    "default text palette entries must follow the configured font color");

            CompoundTag leftImage = left.getUpdateTag();
            CompoundTag rightImage = right.getUpdateTag();
            anchor.configureWallAppearance(1.0, 0x66FF99);
            left.load(leftImage);
            right.load(rightImage);
            helper.assertTrue(left.wallTextScale() == 2.5 && right.wallTextScale() == 2.5
                            && left.wallForegroundColor() == 0xFFB347,
                    "wall appearance must survive tile save and reload");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void removingAndReformingWallRebuildsCurrentEndpoint(GameTestHelper helper) {
        helper.setBlock(LEFT, ModRegistries.MONITOR_BLOCK.get());
        helper.setBlock(RIGHT, ModRegistries.MONITOR_BLOCK.get());
        helper.runAfterDelay(5, () -> {
            ServerLevel level = helper.getLevel();
            DeviceAccess access = ServerDeviceManager.access(level.getServer(), DeviceCallContext.readOnly("wall-lifecycle"));
            MonitorBlockEntity left = (MonitorBlockEntity) helper.getBlockEntity(LEFT);
            MonitorBlockEntity right = (MonitorBlockEntity) helper.getBlockEntity(RIGHT);
            MonitorBlockEntity anchor = left.wallRenderState().anchor() ? left : right;
            BlockPos removable = anchor == left ? RIGHT : LEFT;
            UUID anchorId = anchor.getDeviceId();
            helper.assertTrue(access.descriptor(anchorId).orElseThrow().properties().containsKey("surface_revision"),
                    "initial wall endpoint must expose its surface metadata");
            helper.setBlock(removable, Blocks.AIR);
            helper.runAfterDelay(6, () -> {
                helper.assertTrue(access.descriptor(anchorId).orElseThrow().properties().get("columns")
                                .equals(DeviceValue.of(40)),
                        "removing a tile must rebuild the surviving endpoint to one tile");
                helper.setBlock(removable, ModRegistries.MONITOR_BLOCK.get());
                helper.runAfterDelay(6, () -> {
                    helper.assertTrue(access.descriptor(anchorId).orElseThrow().properties().get("columns")
                                    .equals(DeviceValue.of(80)),
                            "reforming a tile must rebuild the endpoint to the current wall width");
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void wallTouchUsesCurrentAnchorAndGlobalCoordinates(GameTestHelper helper) {
        helper.setBlock(LEFT, ModRegistries.MONITOR_BLOCK.get());
        helper.setBlock(RIGHT, ModRegistries.MONITOR_BLOCK.get());
        helper.runAfterDelay(5, () -> {
            ServerLevel level = helper.getLevel();
            DeviceRegistry registry = ServerDeviceManager.registry(level.getServer());
            DeviceCallContext reader = DeviceCallContext.readOnly("wall-touch-reader");
            UUID subscriptionId = UUID.fromString(((DeviceValue.StringValue) registry.subscribeEvents(reader,
                    new DeviceEventSubscription(null, Set.of("touch"), 0, false))
                    .value().orElseThrow()).value());
            MonitorBlockEntity right = (MonitorBlockEntity) helper.getBlockEntity(RIGHT);
            MonitorBlockEntity left = (MonitorBlockEntity) helper.getBlockEntity(LEFT);
            MonitorBlockEntity anchor = left.wallRenderState().anchor() ? left : right;
            MonitorBlockEntity second = anchor == left ? right : left;
            BlockPos secondPos = second == left ? LEFT : RIGHT;
            second.publishTouch(new Vec3(helper.absolutePos(secondPos).getX() + 0.5,
                    helper.absolutePos(secondPos).getY() + 0.5,
                    helper.absolutePos(secondPos).getZ() + 0.5), helper.makeMockPlayer());
            DeviceEvent touch = registry.pollSubscription(reader, subscriptionId, 1).events().stream()
                    .findFirst().orElseThrow();
            helper.assertTrue(touch.sourceDeviceId().equals(anchor.getDeviceId()),
                    "touch events must be sourced by the current wall anchor");
            helper.assertTrue(mapNumber(touch.payload(), "x") >= 40
                            && mapNumber(touch.payload(), "x") < 80
                            && mapNumber(touch.payload(), "y") >= 0
                            && mapNumber(touch.payload(), "y") < 20,
                    "touch events must expose global wall coordinates");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void plcDashboardTouchStartsController(GameTestHelper helper) {
        helper.setBlock(LEFT, ModRegistries.MONITOR_BLOCK.get());
        helper.setBlock(PLC, ModRegistries.PROGRAMMABLE_LOGIC_CONTROLLER_BLOCK.get());
        helper.runAfterDelay(5, () -> {
            MonitorBlockEntity monitor = (MonitorBlockEntity) helper.getBlockEntity(LEFT);
            ProgrammableLogicControllerBlockEntity plc =
                    (ProgrammableLogicControllerBlockEntity) helper.getBlockEntity(PLC);
            helper.assertTrue(plc.loadProgram("IN START REDSTONE NORTH\nOUT MOTOR REDSTONE SOUTH\nRUNG MOTOR = START"),
                    "dashboard test program must compile");
            helper.assertTrue(!plc.isRunning(), "PLC starts stopped");
            // Monitor NORTH faces the local X axis. Row 2 and column 4 are inside the RUN button.
            monitor.publishTouch(new Vec3(helper.absolutePos(LEFT).getX() + 0.10,
                    helper.absolutePos(LEFT).getY() + 0.875,
                    helper.absolutePos(LEFT).getZ() + 0.50), helper.makeMockPlayer());
            helper.assertTrue(plc.isRunning(), "touching the monitor RUN button must start the PLC");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void directlyAttachedPlcDashboardSpansCompleteMonitorWall(GameTestHelper helper) {
        BlockPos bottomLeft = new BlockPos(2, 2, 2);
        BlockPos bottomRight = new BlockPos(3, 2, 2);
        BlockPos topLeft = new BlockPos(2, 3, 2);
        BlockPos topRight = new BlockPos(3, 3, 2);
        BlockPos plcPos = bottomLeft.south();
        helper.setBlock(bottomLeft, ModRegistries.MONITOR_BLOCK.get());
        helper.setBlock(bottomRight, ModRegistries.MONITOR_BLOCK.get());
        helper.setBlock(topLeft, ModRegistries.MONITOR_BLOCK.get());
        helper.setBlock(topRight, ModRegistries.MONITOR_BLOCK.get());
        helper.setBlock(plcPos, ModRegistries.PROGRAMMABLE_LOGIC_CONTROLLER_BLOCK.get());

        helper.runAfterDelay(7, () -> {
            MonitorBlockEntity topLeftMonitor = (MonitorBlockEntity) helper.getBlockEntity(topLeft);
            MonitorBlockEntity topRightMonitor = (MonitorBlockEntity) helper.getBlockEntity(topRight);
            MonitorBlockEntity bottomLeftMonitor = (MonitorBlockEntity) helper.getBlockEntity(bottomLeft);
            MonitorBlockEntity bottomRightMonitor = (MonitorBlockEntity) helper.getBlockEntity(bottomRight);
            MonitorBlockEntity wallAnchor = topLeftMonitor.wallAnchor();
            helper.assertTrue(topLeftMonitor.wallColumns() == 80 && topLeftMonitor.wallRows() == 40,
                    "four connected monitors must expose one 80x40 PLC canvas");
            helper.assertTrue(topLeftMonitor.terminalSurface().characterAt(0, 3) == '─'
                            && topRightMonitor.terminalSurface().characterAt(0, 3) == '─',
                    "PLC dashboard upper framing must span both horizontal wall tiles");
            int titleForeground = wallAnchor.terminalSurface().foregroundAt(0, 0);
            int titleBackground = wallAnchor.terminalSurface().backgroundAt(0, 0);
            int ruleBackground = wallAnchor.terminalSurface().backgroundAt(0, 3);
            helper.assertTrue(titleForeground == 1 && titleBackground == 8 && ruleBackground == 15,
                    "PLC dashboard must preserve hexadecimal foreground/background palette indexes; actual="
                            + titleForeground + "/" + titleBackground + "/" + ruleBackground);
            helper.assertTrue(bottomLeftMonitor.terminalSurface().characterAt(0, 19) == '─'
                            && bottomRightMonitor.terminalSurface().characterAt(0, 19) == '─',
                    "PLC dashboard lower framing must span every tile in a vertical wall");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void wallRevisionTracksMutationsOnEveryTile(GameTestHelper helper) {
        helper.setBlock(LEFT, ModRegistries.MONITOR_BLOCK.get());
        helper.setBlock(RIGHT, ModRegistries.MONITOR_BLOCK.get());
        helper.runAfterDelay(5, () -> {
            ServerLevel level = helper.getLevel();
            DeviceAccess access = ServerDeviceManager.access(level.getServer(),
                    DeviceCallContext.readOnly("wall-revision-test"));
            MonitorBlockEntity left = (MonitorBlockEntity) helper.getBlockEntity(LEFT);
            MonitorBlockEntity right = (MonitorBlockEntity) helper.getBlockEntity(RIGHT);
            MonitorBlockEntity anchor = left.wallRenderState().anchor() ? left : right;
            MonitorBlockEntity lowerRevisionTile = anchor == left ? right : left;
            for (int i = 0; i < 8; i++) {
                anchor.terminalSurface().setCell(i, 0, (char) ('A' + i), 0, 15);
            }
            long before = (long) number(access.descriptor(anchor.getDeviceId()).orElseThrow(),
                    "surface_revision");
            lowerRevisionTile.terminalSurface().setCell(0, 0, 'Z', 0, 15);
            long after = (long) number(access.descriptor(anchor.getDeviceId()).orElseThrow(),
                    "surface_revision");
            helper.assertTrue(after > before,
                    "a mutation on a lower-revision wall tile must advance the global surface revision");
            helper.succeed();
        });
    }

    private static int number(DeviceDescriptor descriptor, String key) {
        return (int) ((DeviceValue.NumberValue) descriptor.properties().get(key)).value();
    }

    private static int mapNumber(DeviceValue.MapValue value, String key) {
        return (int) ((DeviceValue.NumberValue) value.values().get(key)).value();
    }

    private static String printable(char value) {
        return value == ' ' ? "<space>" : Character.toString(value);
    }
}
