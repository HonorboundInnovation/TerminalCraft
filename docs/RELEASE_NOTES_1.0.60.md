# TerminalCraft 1.0.60 — Continuous Lattice Lanes

TerminalCraft 1.0.60 completes the independently placed 4×4 routing model introduced in 1.0.59.
Cable points now assemble into visibly continuous, functional lanes instead of appearing as isolated
squares.

- Adjacent same-color points on one block form short horizontal, vertical, or diagonal links.
- Across neighboring blocks, horizontal travel preserves the row and vertical travel preserves the
  column. Each point extends to the shared block boundary, so the two runs meet without a gap.
- Shielded Red Alloy Wire uses these links for real redstone propagation and bundled-channel
  breakouts.
- Network Cable uses them for real RedNet topology, bundled-trunk breakouts, modem attachment, and
  wired-device discovery.
- One placement still occupies exactly one lattice point; this update does not restore row autofill.

Artifact: `terminalcraft-1.20.1-47.4.10-1.0.60.jar`

Size: `2,676,620` bytes  
SHA-256: `f9a528762933dc6d81a6edf4261ba2f1848de8a328e92cfdcde39996f29b7325`
