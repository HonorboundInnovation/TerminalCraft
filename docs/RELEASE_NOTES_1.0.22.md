# TerminalCraft 1.0.22

Build 22 fixes color output on monitor blocks. The screensaver was generating and persisting
colored character cells correctly, but the monitor device endpoint saw the changing legacy line
mirror and imported it back into the shared surface using the default green palette. That feedback
loop erased the colors immediately after every frame.

The endpoint now checks whether its shared surface already contains the current line mirror before
performing a legacy import. Colored monitor-wall frames therefore retain their per-cell foreground
and background palette indexes through server synchronization and client update packets.

The focused monitor endpoint regression test and geometric screensaver test cover the fix. Live
client presentation remains owner-managed.

Artifact: `terminalcraft-1.20.1-47.4.10-1.0.22.jar`
Size: `1,139,580` bytes
SHA-256: `e450f8a7a6b8d93a9910ae4a381a3fb20ab9d2ba50ccba8d0bf03baa6678a456`
