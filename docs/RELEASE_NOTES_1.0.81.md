# TerminalCraft 1.0.81 — Editor file safety

- Files exceeding editor limits now refuse to open instead of silently truncating their contents in the editor.
- GUI saves preserve empty files and final-newline endings. CRLF and CR still normalize to LF.
- Files with exactly 512 lines and a final newline are accepted; overlong typed lines are rejected without truncation.
- Editor snapshots preserve final-newline state, with compatibility for older saved editor sessions.

All 85 headless checks and 120 required world tests passed, including expanded editor boundary, save/retry and persistence scenarios. No fresh live-client interaction was performed for this checkpoint.

Development checkpoint only; broader persistence, dedicated multiplayer and release qualification remain open. Original CurseForge installation/worlds and approved textures are unchanged. Network protocol remains 7.
