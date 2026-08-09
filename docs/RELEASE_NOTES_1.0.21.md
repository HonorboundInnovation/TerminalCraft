# TerminalCraft 1.0.21

Build 21 adds a server-tick geometric monitor-wall screensaver.

Run `monitor screensaver start any` from a terminal or turtle beside a connected monitor wall, or
run the bundled `bash ~/programs/geometric_screensaver.sh` program. The animation uses the full
logical wall width and height as one canvas, then persists each frame into the appropriate tile
segments. It does not duplicate the same pattern on every physical monitor. `stop` and `status`
actions control the runtime.

The color variant is available with `monitor screensaver color any` or
`bash ~/programs/geometric_screensaver_color.sh`. It uses the persisted 16-entry terminal palette
and per-cell foreground/background indexes, so the palette and animated hue bands span the wall's
global canvas as well.

Frames are bounded and batched per wall update. The build also retains the monitor-wall
re-entrancy fix that prevents synchronous `output_changed` callbacks from overflowing the server
thread during surface synchronization.

Artifact: `terminalcraft-1.20.1-47.4.10-1.0.21.jar`
Size: `1,139,573` bytes
SHA-256: `0844117e29654303a41c943633f39cb378852f37734c1e5acc27a46cf58c8e50`

The complete headless `check build` and the geometric screensaver frame tests are included in the
verification pass. Live client presentation remains owner-managed.
