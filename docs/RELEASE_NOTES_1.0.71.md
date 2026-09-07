# TerminalCraft 1.0.71 — Preserve files when floppy unmount fails

Development checkpoint for core-computer implementation. Approved textures are unchanged.

`umount` previously discarded the shell's mounted filesystem even if the disk was absent or the physical write failed. It now reports a nonzero exit status and retains `/disk` and the current directory until the media write succeeds. The pending mounted copy remains part of normal shell persistence.

A regression test reproduces the prior failure and checks retained contents/cwd, absent-media handling, shell save/reload, and successful retry. Restore the original floppy before retrying: this does not yet add physical-media identity checks or concurrent-writer conflict detection.

Turtle recovery, media swapping, live drive interactions and broader player lifecycle checks remain open. No installed CurseForge files or player saves were modified.

Verification: 85/85 headless tasks and 90/90 required world tests pass, including the new headless failure/retry/persistence regression. Physical drive ejection/reinsertion acceptance remains separate.
