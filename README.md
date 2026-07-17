# TerminalCraft

TerminalCraft is a Forge mod for **Minecraft 1.20.1** that adds programmable, Bash-style computers and automation tools. It combines persistent terminals, turtles, displays, storage access, redstone control, and an in-world wired/wireless network while keeping gameplay logic server-authoritative and bounded.

> **Project status:** TerminalCraft is under active development. Back up important worlds before testing development builds.

## Features

- **Terminal computers** with a persistent Bash-inspired shell and virtual filesystem.
- **Pocket terminals** that retain their state on the item.
- **Turtles** with programmable movement, inspection, digging, placing, and device access.
- **Monitor walls** and text displays for scripts and automation feedback.
- **Floppy disks and disk drives** with mountable, persistent storage.
- **RedNet networking** with wireless modems, physical network cable, routers, named hosts and services, typed routes, bounded queues, and reliable-delivery diagnostics.
- **Redstone automation** through red alloy wire and 16-channel bundled control cable.
- **Server racks and scheduled jobs** for bounded, persistent automation workloads.
- **Generic Forge device access** for adjacent item, fluid, and energy capabilities.
- **Optional storage integrations** for Sophisticated Storage, Sophisticated Backpacks, Storage Drawers, and Refined Storage. These mods are not required for TerminalCraft to start.

## Shell overview

The shell includes common filesystem and scripting commands such as:

```text
help  echo  pwd  cd  ls  cat  write  touch  mkdir  rm  clear
env  history  whoami  uname  date  test  source  bash
```

It also exposes gameplay-oriented command families for:

```text
redstone  wire  peripheral  device  storage  turtle  monitor  modem
mount  umount  disk  server/jobs  auth/authorization
```

Scripts support variables, exit status, pipes, redirection, command chaining, and bounded `if`, `for`, and `while` control flow.

## Complete documentation

Read **[The TerminalCraft Guide](docs/TERMINALCRAFT_GUIDE.md)** for the complete player and administrator manual, including:

- every major block, item, and crafting recipe;
- the virtual filesystem and complete shell language subset;
- scripting limits and differences from GNU Bash;
- turtles, monitors, disks, redstone, bundled cable, and RedNet;
- devices, storage, exact transfers, events, jobs, and authorization;
- optional integrations, server configuration, and troubleshooting;
- a library of practical, source-controlled [sample scripts](examples/scripts).

## Requirements

| Component | Version |
|---|---|
| Minecraft | 1.20.1 |
| Minecraft Forge | 47.3.0 or a compatible Forge 47.x release |
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
build/libs/terminalcraft-1.0.0.jar
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
5. Connect wired modems with Network Cable and Network Routers; switch each modem to wired mode before routing traffic.

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
