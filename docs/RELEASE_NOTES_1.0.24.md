# TerminalCraft 1.0.24

TerminalCraft 1.0.24 adds a display transport layer for remote monitor walls and turns monitors
attached to PLCs into interactive control dashboards.

## Wireless Display Links

- Place a source link beside a terminal, turtle, server rack, or PLC.
- Place a receiver link beside a monitor or monitor wall.
- Sneak-right-click the source, then the receiver, to pair them with a bounded channel.
- Named channels can be configured with `displaylink source <channel>` and
  `displaylink sink <channel>` from an adjacent shell.

## Video Cable

- Video Cable blocks route in all six directions and form a bounded connected component.
- A component can mirror a terminal-capable host to one or more monitors.
- Cable transport carries display cells only; RedNet, redstone, and bundled signals remain separate.

## PLC dashboards

- A monitor directly beside a PLC displays a colored dashboard instead of the PLC terminal.
- The same dashboard is used through Wireless Display Link and Video Cable paths.
- Dashboard views show state, scan count, I/O, source, and faults.
- Touch `RUN`, `STOP`, or `RESET` on the monitor to control the PLC server-side.

The release artifact is:

`terminalcraft-1.20.1-47.4.10-1.0.24.jar`

Size: `1,223,223` bytes

SHA-256: `0b4b3f75fee7bf5d9847b2c8f003417a6ef9946462692f1742f6c90995987ec0`
