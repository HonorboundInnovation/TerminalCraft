# TerminalCraft 1.0.72 — Bound floppy mounts and stale-write protection

Development checkpoint. Approved textures remain unchanged.

- Floppies gain persisted media identity and content-revision UUIDs; existing media receives them lazily without replacing its filesystem. Filesystem writes advance the revision; labels do not.
- Terminal and turtle mounts retain a drive/media/revision token across shell saves. `disk sync`, editor auto-sync and normal unmount reject replacement media, another drive and a revision changed by another writer.
- Rejected writes retain the mounted files. Recover pending files locally before `umount --discard`, which explicitly releases the mounted copy without touching physical media, then remount to read the current disk.
- Old saved mounts with no token remain readable but cannot write back implicitly. The guide documents recovery and remounting.
- Added live tests with registered terminals/turtles/drives for swapping floppies, changing drives, shell reload, retry and competing writers; expanded the headless legacy-mount regression.

These IDs are persistence and concurrency controls, not a security boundary against manually cloned/edited item NBT. Full core-workflow and release qualification remain open. No CurseForge install or user world was modified.

Verification: 85/85 headless tasks and 93/93 required world tests pass. Diagnostic packaging succeeded after both suites passed.
