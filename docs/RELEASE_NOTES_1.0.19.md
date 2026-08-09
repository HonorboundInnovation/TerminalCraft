# TerminalCraft 1.0.19

Build 19 turns TerminalCraft monitor walls into shared display infrastructure.

TerminalCraft targets Java 21 for the current Minecraft Forge 1.20.1 development and runtime
baseline.

Pocket terminals and networked computers can now publish text to a named monitor service using
`monitor remote`. The receiving modem may touch the wall directly or use an adjacent terminal or
turtle as a gateway. The receiver must have an open port, an explicitly registered service, and
exactly one resolvable wall.

Create 6.0.8 Display Links can also target any TerminalCraft monitor tile. The target exposes the
full connected wall geometry, so multi-row Display Link output can use the entire wall rather than
one physical tile. Create is optional and is not included in the TerminalCraft JAR.

An optional Applied Energistics 2 bridge is now available. It discovers only directly adjacent
exposed AE2 nodes and provides bounded cached item queries plus grid power, boot, channel, node,
inventory, and crafting-CPU telemetry. It is read-only: insertion, extraction, crafting, cancellation, and security
principal impersonation are denied until caller-aware native security mapping is implemented.

Red-alloy surface wiring now samples directional vanilla sources, attenuates one strength per
connected edge, and emits only within each mounted face plane. Bundled cables preserve sixteen
independent channels, bridge channel zero to vanilla redstone without support-face leakage, sanitize
unsupported persisted faces, and expose bounded `wire`/`bundled` APIs to terminals and turtles.

Monitor-capable devices now expose a bounded revisioned character-cell surface with per-cell colors,
cursor state, scroll/blit updates, and `term.delta` synchronization. Single monitor tiles persist
that surface with a safe fallback for legacy line-only data, while connected walls expose one global
surface coordinate space and paginate large deltas with a bounded offset.

Caller-owned event subscriptions can also be consumed through bounded cooperative wait tokens.
Matching events wake parked server-rack jobs before their logical deadline; timeouts, cancellation,
ownership, and quotas remain scheduler-controlled, and no server thread blocks.

Terminal screens now include a passive F6 character-cell mode backed by the same bounded shell
surface persisted for terminals, turtles, racks, and pocket computers. Clipped viewports and capped
window/widget layout primitives are available for reusable GUI screens without executing client-side
scripts.

Monitor-wall lifecycle coverage now includes tile save/load, stable identities, endpoint rebuilds
after removal and reformation, and current-anchor global-coordinate touch events.

The final display hardening pass makes the shared wall revision advance for mutations on every tile
and renders persisted per-cell palette backgrounds and foregrounds through the global wall surface.

The RedNet monitor protocol carries only bounded display operations; it cannot execute commands.
Service names are discoverable routing aliases, not passwords, so isolate sensitive displays with
physical/logical RedNet topology.

See the Monitor chapter in the [TerminalCraft Guide](TERMINALCRAFT_GUIDE.md) and the
[`network-monitor-publish.sh`](../examples/scripts/advanced/network-monitor-publish.sh) example.

Artifact: `terminalcraft-1.20.1-47.4.10-1.0.19.jar`  
Size: `1,125,826` bytes
SHA-256: `bc8a4f0cd5e7775257192c66ed5e5d791946d64752bd24902f3959c6616db862`

The headless `check` and production `build` pass with 74 actionable tasks. Live Forge/GameTest
execution is owner-managed because this workspace's hosted runner stalls during spawn preparation
before emitting test results.
