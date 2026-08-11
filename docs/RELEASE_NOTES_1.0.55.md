# TerminalCraft 1.0.55 — True Four-Lane Surface Cabling

TerminalCraft 1.0.55 corrects the physical and preview layout of Shielded Red Alloy Wire and Network
Cable. The previous implementation stored four lane IDs but displayed them as a two-by-two quadrant
grid. That made each face look like it carried only two parallel tracks in either direction.

## Corrected behavior

- Every supported floor, wall, or ceiling face now presents four long, side-by-side lane bands.
- Aiming across the face selects lane 1, 2, 3, or 4 directly.
- Placement previews show four parallel outlines; selected, free, and occupied states retain their
  cable-color, gray, and red indications.
- Actual rendered cable runs, ray targeting, collision/selection shapes, tooltips, and Guide Book
  instructions use the same corrected layout.
- The change applies equally to all sixteen colors of Network Cable and Shielded Red Alloy Wire.

## World compatibility

Saved lane indices remain `0..3`, so existing run occupancy, cable colors, default channels,
redstone strength, and network membership are not rewritten or discarded. Only the spatial mapping
of those lanes changes from a two-by-two grid to four genuinely parallel positions.
