# TerminalCraft 1.0.19

Build 19 turns TerminalCraft monitor walls into shared display infrastructure.

TerminalCraft targets Java 17 for compatibility with the standard Minecraft Forge 1.20.1
runtime.

Pocket terminals and networked computers can now publish text to a named monitor service using
`monitor remote`. The receiving modem may touch the wall directly or use an adjacent terminal or
turtle as a gateway. The receiver must have an open port, an explicitly registered service, and
exactly one resolvable wall.

Create 6.0.8 Display Links can also target any TerminalCraft monitor tile. The target exposes the
full connected wall geometry, so multi-row Display Link output can use the entire wall rather than
one physical tile. Create is optional and is not included in the TerminalCraft JAR.

The RedNet monitor protocol carries only bounded display operations; it cannot execute commands.
Service names are discoverable routing aliases, not passwords, so isolate sensitive displays with
physical/logical RedNet topology.

See the Monitor chapter in the [TerminalCraft Guide](TERMINALCRAFT_GUIDE.md) and the
[`network-monitor-publish.sh`](../examples/scripts/advanced/network-monitor-publish.sh) example.

Artifact: `terminalcraft-1.20.1-47.4.10-1.0.19.jar`  
SHA-256: `0566fcbe3633ca5a52a2d879b7dfd3e448cdeffecfb3fab60db5a50b853e09f8`
