# TerminalCraft 1.0.58 — Four-by-Four Cable Lattice

TerminalCraft 1.0.58 implements the confirmed surface-routing model for both Shielded Red Alloy Wire
and Network Cable: every occupied block face is a four-by-four point lattice.

## Lanes and turns

- Each straight lane contains four points spanning the complete block face.
- Four parallel lanes form four rows or columns and occupy all 16 lattice points.
- A reciprocal neighboring cable fixes the incoming edge and lane.
- Choosing a perpendicular exit rasterizes the route between its two edge points. The outer path is
  a four-point corner-to-corner diagonal; the inner paths shorten to three, two, and one point.
- Electrical/data continuity still uses the saved reciprocal port mask, so the visual lattice does
  not weaken lane, color, channel, or crossing isolation.

## Placement corrections

- Additional lanes inherit the existing route bank on that mounted face instead of rotating from a
  fresh player aim.
- Clicking the side of thin floor/ceiling/wall geometry resolves the run's stored mounting face,
  preventing false `west/east face unsupported` placement errors.
- Preview, textured rendering, ray targeting, and selection geometry use the same lattice shape.
- Existing 1.0.56 and 1.0.57 route masks load directly; no world-data migration is required.
