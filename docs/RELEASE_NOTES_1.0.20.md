# TerminalCraft 1.0.20

Build 20 gives terminals a full workstation-style presentation while preserving the existing
server-authoritative shell and shared character-cell surface.

The terminal screen now uses a larger CRT-inspired panel with a beveled bezel, mode header, focused
shell output area, command footer, and live status rail. The rail reports shell/surface mode,
current directory, last exit code, command-history depth, and keyboard shortcuts. F6 continues to
switch between the shell log and the passive character-cell surface.

The existing editor, keyboard capture, command history, scroll controls, and server-authoritative
input path remain intact.

This build also guards connected monitor-wall surface synchronization against synchronous
`output_changed` callbacks re-entering the same import path and overflowing the server thread.

Artifact: `terminalcraft-1.20.1-47.4.10-1.0.20.jar`
Size: `1,127,037` bytes
SHA-256: `5756389d285aee2c205598b5daf78a766fb2853255286b5c4334b8a1933290a5`

The complete headless `check build` passes with 75 actionable tasks. Live client presentation and
input qualification remain owner-managed.
