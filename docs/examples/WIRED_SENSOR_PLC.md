# Wired sensor control

Use development build 1.0.92 or newer. No wireless modem is needed.

Place a PLC, then two same-color floor-mounted Network Cable segments extending east, then a Redstone Sensor at the far end. Put solid support below the cables. Face the sensor north. Put a terminal immediately south of the first cable and a redstone lamp immediately west of the PLC.

From the terminal, run `sensor discover`. With one reachable sensor, run `sensor name line-signal`, then `sensor @line-signal read value`. If there are several sensors, use the selector for the intended one before renaming it. Sensor names must be unambiguous on this connection.

Save this source as `/home/player/wired.plc` in the terminal editor:

```text
IN ENABLE SENSOR line-signal/value
OUT MOTOR REDSTONE WEST
RUNG MOTOR = ENABLE
```

Find the PLC with `device list programmable_logic_controller`, then, as its owner, replace `<uuid>` and run:

```text
plc remote load <uuid> /home/player/wired.plc
device call <uuid> control.run
```

Place a redstone block north of the sensor to supply its input. The PLC's west lamp should light. `sensor @line-signal read value` reports the sensor measurement over the cable. Breaking the second cable makes the sensor unreachable and latches a PLC fault with its automatic output cleared. Restore the cable, then run `device call <uuid> control.reset` and `device call <uuid> control.run`. Reset clears controller runtime state, including timers and counters; it is deliberate, not automatic recovery. A run request while the fault remains now reports failure instead of claiming the PLC started.

This is a simple commissioning example, not a certified safety interlock. Physical sensor reachability is distinct from registry-based remote PLC programming. A nearby wireless modem could provide an alternative sensor route, so omit one when checking cable loss.
