# Live sensor dashboard

Use current development source after 1.0.92 for the corrected standard-monitor layout. This workflow uses the authorized device registry, not a RedNet cable transport test.

Place a terminal, a north-facing Redstone Sensor, and a monitor in a loaded test area. Put a redstone block north of the sensor. Find their UUIDs with `device list redstone` and `device list monitor`; confirm the intended devices with `device info <uuid>`.

On a new SCADA installation, `scada init` makes the initiating player its administrator. On an existing installation, use an authorized engineer/administrator instead; do not reinitialize it.

Replace the two UUID placeholders and run:

```text
scada tag add plant.signal <sensor-uuid> sensor.read value level 2 20 - s:value
scada alarm add signal-lost plant.signal bad_quality - high 0 Signal unavailable
scada dashboard add <monitor-uuid> plant 10 PLANT
```

The monitor should show `good`, the value `15`, and `plant.signal`. Removing the sensor makes that row show `offline` with an alarm marker. The last known value stays visible but is no longer a good sample. `scada history plant.signal 8` shows recent acquisition quality.

The tag is read-only. This demonstration creates a tag, alarm and monitor binding in the world's SCADA configuration; use a test setup rather than replacing an operating plant's configuration. A newly placed sensor has a new identity and must be deliberately rebound to the tag. No automatic substitution of a different sensor is implied.
