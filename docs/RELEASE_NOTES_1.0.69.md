# TerminalCraft 1.0.69 — Wire source input and monitor surface repair

Diagnostic development build; not a fully qualified release. Includes earlier startup, terminal and PLC signal repairs.

- Red Alloy wire now reads adjacent vanilla sources on support and outward faces, as well as its surface plane. Wire-to-wire topology, color isolation, and signal output remain surface-scoped.
- Prevented a reentrant monitor descriptor refresh from importing a partially synchronized legacy line mirror over authoritative character cells. Tile output events can occur during synchronization; the endpoint now defers that refresh until mirroring finishes.
- Added a headless callback regression that reproduced color loss before the monitor repair.
- Updated wire fixtures for the documented half-block selection volumes and distinguished visible arms touching solid blocks from reciprocal electrical corner connections.
- Updated monitor lifecycle and touch fixtures to follow the visual anchor for the wall's facing, instead of assuming the lower-X tile owns the endpoint.

No registry IDs or save formats were changed by this checkpoint. The owner's CurseForge installation and saves were not modified. Client visual inspection, full-modpack integration and release acceptance remain pending.

Verification: all 90 required in-world GameTests pass, up from 82/90 at the previous checkpoint. Headless checks remain 84/85 passing; `surfaceCableSupportTest` still fails its existing cable texture contract. The overall qualification gate therefore remains failed even though the world suite passes.
