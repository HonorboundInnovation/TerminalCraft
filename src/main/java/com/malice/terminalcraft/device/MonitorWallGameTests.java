package com.malice.terminalcraft.device;

import com.malice.terminalcraft.blockentity.MonitorBlockEntity;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.gametest.GameTestHolder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Live proof that adjacent monitor tiles expose one addressable canvas, not mirrored screens. */
@GameTestHolder("terminalcraft")
public final class MonitorWallGameTests {
    private static final BlockPos LEFT = new BlockPos(2, 2, 2);
    private static final BlockPos RIGHT = new BlockPos(3, 2, 2);

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

    private static int number(DeviceDescriptor descriptor, String key) {
        return (int) ((DeviceValue.NumberValue) descriptor.properties().get(key)).value();
    }

    private static int mapNumber(DeviceValue.MapValue value, String key) {
        return (int) ((DeviceValue.NumberValue) value.values().get(key)).value();
    }
}
