# TerminalCraft 1.0.57 — RedPower-Style Cable Points

TerminalCraft 1.0.57 corrects the directional four-lane cable display for both Shielded Red Alloy
Wire and Network Cable. Configured routing directions no longer paint phantom edge-to-edge runs.

## Placement and rendering

- Placement previews expose four compact lane points rather than four full-length strips.
- An isolated configured run renders as a small point at its selected lane position.
- A visible arm grows only toward a real reciprocal cable, bundled trunk, wired device, modem, or
  adjacent redstone/block connection.
- Removing a neighbor retracts the corresponding arm while retaining the editable route intent.
- Selection geometry and textured block-entity rendering use the same connected-port shape.

## Routing behavior retained

- Player-relative lane-bank rotation, automatic incoming-lane selection, single-block elbows, wall
  up/down routing, face transitions, independent colors/channels, and crossings remain supported.
- Normal wrench use reports configured ports; sneak-wrench toggles the projected port on the selected
  compact lane point.
- Saved 1.0.56 route data remains compatible and requires no world migration.
