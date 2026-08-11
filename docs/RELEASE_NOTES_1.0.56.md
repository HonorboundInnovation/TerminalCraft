# TerminalCraft 1.0.56 — Directional Four-Lane Routing

TerminalCraft 1.0.56 gives every individual Shielded Red Alloy Wire and Network Cable lane an
explicit directional route. Four lanes are no longer a fixed stripe overlay: the lane bank rotates
with the player or the cable being extended, and each lane can change direction on one block.

## Placement

- With no matching neighbor, the player's view projected into the mounted face selects the initial
  front/back, left/right, or wall up/down straight axis.
- With a reciprocal matching neighbor, placement follows that incoming lane automatically.
- Looking toward the straight-ahead exit continues the run. Looking toward a perpendicular exit
  creates an elbow inside the current block.
- The live preview uses the same geometry as the placed cable and reports its world-direction ports.

## Routing and editing

- Every lane persists an independent six-direction port mask, restricted to its mounting plane.
- Reciprocal ports govern electrical/data continuity, bundled breakouts, device attachments, direct
  neighbors, in-position face transitions, and supported external corners.
- Straight runs, elbows, endpoints, and multi-port junctions have exact textured geometry and
  matching selection shapes.
- Sneak-right-clicking a selected lane with a compatible wrench toggles the port in the projected
  player-view direction. Normal wrench use reports the route without changing it.
- Different colors and lane indices stay isolated even where their visible routes cross.

## World compatibility

Older cable block entities do not contain route masks. They migrate to all four valid in-plane ports,
which preserves their previous automatic connectivity. Lane occupancy, dye colors, RedNet channels,
redstone strength, bundled-channel mapping, and saved block positions are retained.
