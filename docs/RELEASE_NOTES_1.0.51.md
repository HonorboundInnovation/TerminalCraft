# TerminalCraft 1.0.51

Build 51 turns TerminalCraft's PLC, sensor, device, monitor, networking, and server-rack foundations
into a complete first SCADA system.

## Process database and historian

Engineers can bind human hierarchical tags such as `factory.line1.boiler.temperature` to typed
Device API methods. Every live tag carries a scalar value, unit, server tick, last-good tick, detail,
and explicit quality. A bounded persistent historian retains value and quality together.

## Alarm management

Numeric threshold, equality, and bad-quality rules support severity and deadband. Operators can
acknowledge or temporarily shelve alarms; activation, acknowledgment, clearing, shelving, and shelf
expiry have distinct states and audit records.

## HMI and supervisory control

Monitor walls can be assigned auto-refreshing dashboards filtered by tag hierarchy. Writable tags
route authenticated operator intent through the normal Device API, so device permissions and PLC
ownership remain authoritative. No-value PLC actions use explicit `@method` command tags and
`scada command`, while setpoint tags use `scada write`; accepted and rejected actions are audited.

## Roles, events, and networking

Viewer, operator, engineer, and administrator roles control plant-wide access. SCADA also appears as
a virtual discoverable device, publishes tag/alarm events, and can be exposed as a bounded read-only
typed RedNet service by placing a modem beside one Server Rack.

Run `scada init`, then `help scada` for the complete in-game command reference. The searchable
TerminalCraft Guide contains a full SCADA chapter with sensor, alarm, dashboard, security, Device API,
and RedNet examples.

Install the same JAR on the client and server. TerminalCraft 1.0.51 targets Minecraft 1.20.1 and
Forge 47.4.10.

Artifact: `terminalcraft-1.20.1-47.4.10-1.0.51.jar`

Size: `2,558,409` bytes  
SHA-256: `9e11e3adc0970784138073e31cb96d99ed4e524acc25d19ad38894f7a4365dd4`
