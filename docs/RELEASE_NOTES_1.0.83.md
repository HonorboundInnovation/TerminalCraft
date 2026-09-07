# TerminalCraft 1.0.83 — Editor paste safety and hopper qualification

- Oversized editor pastes and typing now reject the complete edit with an on-screen notice. Existing text, selection, saved status and undo/redo history are retained; clipboard content is unchanged by paste.
- Added real ticking-hopper tests for transfer through a turtle, full-inventory rejection/retry, and hopper interactions after turtle movement. Exact item totals and fuel costs are checked. No production hopper change was needed.

All 85 headless checks and 123 required world tests passed. GUI notice rendering has not received fresh live-client acceptance. Broader dedicated multiplayer, recovery, integration and visual qualification remain open.

Packaged development checkpoint only. Original CurseForge install/worlds and approved textures are unchanged. Network protocol remains 7. The 1.0.82 chunked-file no-downgrade requirement still applies.
