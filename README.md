# TerminalCraft

TerminalCraft is a Forge mod for **Minecraft 1.20.1** that adds programmable, Bash-style computers and automation tools. It combines persistent terminals, turtles, displays, storage access, redstone control, and an in-world wired/wireless network while keeping gameplay logic server-authoritative and bounded.

> **Project status:** TerminalCraft is under active development. Back up important worlds before testing development builds.

## Features

- **Terminal computers** with a persistent Bash-inspired shell and virtual filesystem.
- **In-game TerminalCraft Guide** with searchable chapters covering the Bash shell, scripting,
  PLC language, networking, devices, automation, troubleshooting, and complete example programs.
- **Pocket terminals** that retain their state on the item.
- **Turtles** with programmable movement, inspection, digging, placing, and device access.
- **Monitor walls** for local scripts, animated full-wall geometric screensavers, typed RedNet publication from pocket terminals, wireless display links, routed video cables, and optional Create Display Links.
- **Floppy disks and disk drives** with mountable, persistent storage.
- **RedNet networking** with wireless modems, physical network cable, routers, named hosts and services, typed routes, bounded queues, and reliable-delivery diagnostics.
- **RedPower-style surface automation** with craftable unshielded Red Alloy, sixteen color-isolated
  Shielded Red Alloy variants, sixteen colored Network Cables, dedicated 16-channel trunks, and a
  compact six-sided Red Alloy Capacitor for restoring attenuated signals straight through. All cable
  families use face-oriented half-slab/half-wall selection geometry while retaining thin models.
- **Programmable Logic Controllers** with editor-backed scan programs, timers, counters, latches,
  vanilla redstone I/O, bundled 16-channel I/O, and monitor dashboards reachable by direct,
  wireless, or video-cable display paths; live watch tables, force controls, alarms, ownership, and
  program slots. PLCs also support 0–15 analog signals, linear scaling, bounded PID loops, a
  draggable ladder projection, persisted trend history, and owner-authorized remote programming.
- **Display transport** with paired wireless display links and six-direction routed video cable
  components, diagnostics/configuration GUIs, fault-contained rendering, and remote mirroring.
- **Monitor widgets and terminal ergonomics** including bars, LEDs, sparklines, and Ctrl+R history
  search/Ctrl+L input clearing.
- **Graphical device Control Center** with live discovery, DNS naming, schema-driven device actions,
  mouse/keyboard navigation, and remote loading of general, Create, Mekanism, and SecurityCraft PLC program templates.
- **SCADA supervisory control** with persistent hierarchical process tags, quality-aware live values,
  bounded history, alarm acknowledgment/shelving, multi-page full-color advanced HMI dashboards,
  touch controls, a graphical terminal layout designer, operator roles, audit records, Device API
  events, and a read-only typed RedNet gateway hosted by a server rack.
- **Server racks and scheduled jobs** for bounded, persistent automation workloads.
- **Generic Forge device access** for adjacent item, fluid, and energy capabilities, plus a dynamic
  provider-neutral chemical tank contract with exact quantities and no resource allowlist.
- **Universal Sensor Arrays** with 16 configurable channels for redstone, block state, inventory,
  fluid, energy, entity, machine, environment, network, kinetic, and dynamic chemical telemetry.
  Channels expose quality state, calibration, terminal commands, PLC `SENSOR` inputs, device events,
  and optional typed RedNet telemetry services. Each family is also available as its own one-channel
  sensor block for compact builds and dedicated PLC inputs.
- **Network Access Storage** with eight portable, tiered solid-state drive bays for bounded electronic
  item and fluid storage, plus a read-only **Materializer** output block for players and automation.
- **Optional integrations** for Create, Mekanism, SecurityCraft, Sophisticated Storage, Sophisticated Backpacks,
  Storage Drawers, Refined Storage, and Applied Energistics 2. Create adds native kinetic/stress/link/
  threshold/sequencer devices; Mekanism adds machine/progress/Joule/heat/redstone/security telemetry
  and dynamically discovered add-on chemicals. SecurityCraft adds ownership-safe native device,
  option, module, audit, sensor, and specialized security-system control. None is required for
  TerminalCraft to start.

## Shell overview

The shell includes common filesystem and scripting commands such as:

```text
help  echo  pwd  cd  ls  cat  write  touch  mkdir  rm  clear
env  history  whoami  uname  date  test  source  bash
```

It also exposes gameplay-oriented command families for:

```text
redstone  wire  plc  peripheral  device  control  hmi  storage  turtle  monitor  modem  sensor  scada
mount  umount  disk  server/jobs  auth/authorization
```

Scripts support variables, exit status, pipes, redirection, command chaining, and bounded `if`, `for`, and `while` control flow.

## Complete documentation

Read **[The TerminalCraft Guide](docs/TERMINALCRAFT_GUIDE.md)** for the complete player and administrator manual. For production-style examples, failure-aware automation, exact transfers, RedNet operations, events, and jobs, see the **[Advanced Script Cookbook](docs/ADVANCED_SCRIPT_COOKBOOK.md)**. Both are included in the searchable in-game TerminalCraft Guide item. Version history is maintained in the **[changelog](docs/CHANGELOG.md)**, with additional context in the **[1.0.63 release notes](docs/RELEASE_NOTES_1.0.63.md)**.

The full guide includes:

- every major block, item, and crafting recipe;
- the virtual filesystem and complete shell language subset;
- scripting limits and differences from GNU Bash;
- turtles, monitors, disks, redstone, PLCs, bundled cable, and RedNet;
- devices, storage, exact transfers, events, jobs, and authorization;
- optional integrations, server configuration, and troubleshooting;
- a library of practical, source-controlled [sample scripts](examples/scripts).

## Requirements

| Component | Version |
|---|---|
| Minecraft | 1.20.1 |
| Minecraft Forge | 47.4.10 |
| Java | 17 |

TerminalCraft is required on both the client and server.

## Installation

1. Install Minecraft Forge for Minecraft 1.20.1.
2. Download or build the TerminalCraft JAR.
3. Copy the JAR into the instance's `mods` directory.
4. Launch the game with the matching Forge profile.

Optional integration mods can be installed separately. They are never bundled with TerminalCraft and remain subject to their own licenses and compatibility requirements.

## Building from source

Clone the repository and use the included Gradle wrapper:

```bash
git clone https://github.com/HonorboundInnovation/TerminalCraft.git
cd TerminalCraft
./gradlew clean build
```

On Windows:

```powershell
.\gradlew.bat clean build
```

The built mod JAR is written to:

```text
build/libs/terminalcraft-1.20.1-47.4.10-1.0.63.jar
```

ForgeGradle will download the required development dependencies during the first build.

## Testing

Run the complete headless verification and production build:

```bash
./gradlew clean check build
```

Run the Forge GameTest server for world-behavior tests:

```bash
./gradlew runGameTestServer
```

Run a development client:

```bash
./gradlew runClient
```

The default build compiles against optional integration APIs but does not require those mods at runtime. Focused optional-mod GameTest profiles use local mod directories configured through Gradle properties.

## Basic in-game workflow

1. Craft or obtain a Terminal and place it in the world.
2. Open it and run `help` to inspect the available commands.
3. Place a Disk Drive beside the Terminal, insert a Floppy Disk, and run `mount`.
4. Place a modem beside a computer and use `modem help` to configure RedNet communication.
5. Connect wired modems with colored Network Cable, Bundled Network Cable, and Network Routers; switch each modem to wired mode before routing traffic. Cable colors default to channels 0–15.

## Security and data model

TerminalCraft treats the server as authoritative. Public operations use bounded inputs, queues, persistence records, and per-owner work budgets. Device and storage mutations are permission checked, and optional integrations are designed to fail closed when their native authority cannot be verified.

Even with these safeguards, development builds should be tested on backed-up worlds.

## Contributing

Issues and focused pull requests are welcome. Before submitting a change:

```bash
./gradlew clean check build
```

For changes involving blocks, networking, persistence, capabilities, or world lifecycle, also run:

```bash
./gradlew runGameTestServer
```

Keep optional integrations absence-safe and avoid introducing mandatory dependencies without prior discussion.

## License

TerminalCraft is licensed under the **GNU General Public License, version 3 only** (`GPL-3.0-only`). See [`LICENSE`](LICENSE) for the complete license text.

Minecraft, Minecraft Forge, and optional integration projects are owned by their respective authors and are distributed under their own terms. TerminalCraft is not affiliated with or endorsed by Mojang Studios.
