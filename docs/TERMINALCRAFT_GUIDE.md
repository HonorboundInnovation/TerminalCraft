# The TerminalCraft Guide

**The comprehensive player, administrator, shell, PLC, networking, SCADA, and automation manual for TerminalCraft 1.0.52**

TerminalCraft is a Minecraft Forge 1.20.1 mod built around persistent Bash-style computers. This guide documents the behavior of the current source release—not ordinary GNU Bash, ComputerCraft Lua, or features merely planned for a later release.

> TerminalCraft is under active development. Back up important worlds before using development builds.

## Contents

1. [Requirements and installation](#1-requirements-and-installation)
2. [Your first terminal](#2-your-first-terminal)
3. [Blocks and items](#3-blocks-and-items)
4. [Crafting reference](#4-crafting-reference)
5. [The virtual filesystem](#5-the-virtual-filesystem)
6. [Shell command reference](#6-shell-command-reference)
7. [Scripting language](#7-scripting-language)
8. [Writing and running scripts](#8-writing-and-running-scripts)
9. [Useful script library](#9-useful-script-library)
10. [Turtles](#10-turtles)
11. [Monitors](#11-monitors)
12. [Redstone and bundled control](#12-redstone-and-bundled-control)
13. [Floppy disks](#13-floppy-disks)
14. [RedNet networking](#14-rednet-networking)
15. [Devices, storage, fluids, and energy](#15-devices-storage-fluids-and-energy)
16. [Events, jobs, and authorization](#16-events-jobs-and-authorization)
17. [Optional integrations](#17-optional-integrations)
18. [Server administration and configuration](#18-server-administration-and-configuration)
19. [Persistence, limits, and security](#19-persistence-limits-and-security)
20. [Troubleshooting](#20-troubleshooting)
21. [Current shell differences from GNU Bash](#21-current-shell-differences-from-gnu-bash)
22. [SCADA supervisory control](#22-scada-supervisory-control)

---

## 1. Requirements and installation

| Component | Required version |
|---|---|
| Minecraft | 1.20.1 |
| Minecraft Forge | 47.4.10 |
| Java | 17 or newer |
| TerminalCraft | Both client and server |

Install Forge, place the TerminalCraft JAR in the instance's `mods` directory, and launch the matching Forge profile. On multiplayer servers, install the same TerminalCraft build on the server and every connecting client.

Build from source with:

```bash
git clone https://github.com/HonorboundInnovation/TerminalCraft.git
cd TerminalCraft
./gradlew clean build
```

The output is `build/libs/terminalcraft-1.20.1-47.4.10-1.0.63.jar`.

## 2. Your first terminal

1. Craft and place a **Terminal**.
2. Right-click it to open its terminal screen.
3. Run:

```sh
help
pwd
ls /
ls /home/player
uname
```

4. Create and run a script:

```sh
mkdir -p /home/player/bin
write /home/player/bin/hello.sh 'echo Hello from TerminalCraft'
bash /home/player/bin/hello.sh
```

5. Give the computer a recognizable label:

```sh
label workshop
label
```

Terminal state, label, shell files, history-relevant state, and supported host state are persisted by the server. Clients display and submit input; they do not authoritatively mutate the world.

Press `F6` in an open terminal to switch between the scrollback log and the server-synchronized
40×16 character-cell surface. The surface mode is a passive clipped view of the same bounded state
used by terminal-capable clients; input still goes through the authenticated server shell.

### Device Control Center

Run `control` (aliases: `devmgr`, `setup`) to open the full-screen device setup program. It uses the
same authenticated Device API as the `device` command, so discovery and mutations remain
server-authoritative and PLC ownership rules still apply.

The Control Center provides three tabs:

- **Overview** shows live/offline state, type, address, stable identity, source mod, and advertised
  properties for every discoverable device.
- **Methods** lists the selected device's advertised API methods. Select a method and press `Enter`;
  methods with parameters open a typed argument prompt. Put string arguments containing spaces in
  quotes.
- **PLC Templates** loads a general, Create, Mekanism, or SecurityCraft-oriented starter program into a selected network PLC
  through its owner-authorized `program.set` method. This does not require the terminal itself to be
  the PLC host.

Use the arrow keys or mouse wheel to navigate, `Left`/`Right` to move between the device and detail
panes, `Tab`/`Shift+Tab` to change tabs, and `Enter` to select or activate. On Overview, `Enter` opens
the selected device's Methods pane; there it runs the selected method. `F2` or `N` assigns a durable
DNS alias to the selected device, `F5` or `R` refreshes discovery and live status, and `Escape`
cancels an input prompt or exits the Control Center back to the shell. The letter shortcuts are
provided for modpacks that reserve function keys. The mouse can select tabs, devices, methods, and
templates; keyboard activation is required for mutating actions to avoid accidental clicks. Run
`help control` in the terminal for the complete in-game key reference.

## 3. Blocks and items

### Terminal

A placed, persistent Bash-style computer. Use it as the normal workstation for scripts, adjacent devices, storage, networking, redstone, disks, and monitors.

### Pocket Terminal

A stack-size-one handheld computer opened by using the item. Its shell state, label, stable modem identity, and open channels are stored on the item. It includes a built-in wireless modem with a default 64-block range and up to 128 open channels, and rebinding follows the carrying player. Shift-right-click any Sensor Array or individual sensor with the Pocket Terminal to open a session in which that clicked sensor is treated as adjacent; `sensor configure`, `sensor calibrate`, and the other local sensor commands then target it directly.

### TerminalCraft Guide

A craftable, stack-size-one field manual containing this complete guide, the Advanced Script
Cookbook, and the exact source of every built-in general, Create, Mekanism, and SecurityCraft PLC template. Right-click the
book to open its searchable chapter reader. Select chapters with the mouse
or `Left`/`Right`, scroll with the wheel or `Page Up`/`Page Down`, and press `Ctrl+F` to search chapter
titles and full text. The manual is local to the installed JAR, works offline, does not pause the
game, and requires no optional documentation mod.

### Turtle

A mobile computer with its own shell and VFS. It can move, rotate, inspect, dig, and place. Turtle commands fail rather than silently claiming success when movement or world interaction cannot be completed.

### Monitor

A server-owned text display with 20 rows of 40 characters per tile. Adjacent computers can write text, set individual zero-based rows, set a title and color palette, clear it, or read its current lines. Up to 8 by 6 equally facing adjacent tiles form a wall (maximum 48 tiles). Shift-right-click displays content in chat; normal right-click publishes a touch event. Monitor resizing/rebuild behavior is exposed through the event system.

Monitor walls can also be display targets. A monitor directly beside a PLC shows its colored PLC
dashboard instead of the PLC shell. A wireless display receiver or a routed Video Cable can mirror
an adjacent terminal, turtle, server rack, or PLC onto the complete connected wall. The wall is one
global canvas; a source is never duplicated independently per tile.

### Disk Drive and Floppy Disk

Place a Disk Drive adjacent to a computer, insert a Floppy Disk, and mount it at `/disk`. Floppies carry a portable bounded filesystem and label.

### Modem

Provides RedNet communication with a default 64-block wireless range and at most 128 open channels. A modem can operate wirelessly or as an endpoint on physical Network Cable. Shift-right-click toggles its wireless/wired mode; normal right-click reports status. A placed computer must have an adjacent modem for the `modem` command family. New wireless or unconnected modems automatically open channel 42. A wired modem attached to colored Network Cable also opens that cable's color channel and uses it when a shell command omits the channel. Every modem receives a stable `node-...` hostname and a bounded private RedNet address.

### Network Cable

Carries wired RedNet data/control traffic through physical topology. Network Cable is available in all 16 Minecraft dye colors; the dye ID is its default channel from 0 through 15. Ordinary Network Cable uses one centered cable per face and automatically connects RedPower-style to compatible same-color cables on neighboring blocks, around supported corners, to bundled network trunks, and to wired devices. A second ordinary Network Cable cannot occupy the same face. Network Cable does not carry redstone power.

All ordinary and bundled Red Alloy and Network Cable blocks use a face-oriented half-slab or
half-wall selection volume. Floor and ceiling cables occupy the corresponding horizontal half;
wall cables occupy the corresponding vertical half. Multipart cables combine the halves of every
occupied face, while the visible cable model remains thin. This makes recessed cable installations
easy to select and provides consistent geometry for decorative covers or facades; normal Minecraft
blocks still cannot share one block position without a dedicated cover system.

RedNet packet subscriptions are exact: a packet sent on channel 3 is delivered only to modems
listening on channel 3, never to channels 2 or 4. Cable color provisions the matching default channel;
it does not merge the 16 RedNet channel queues. Physical routing also retains the selected channel
through a Bundled Network Cable: a channel-3 packet can leave only through channel-3 colored
breakouts, even if a modem behind a differently colored breakout manually listens on channel 3.

### Bundled Network Cable

Carries all 16 independently routed Network Cable data/control channels in one high-density surface
trunk. Any colored Network Cable can break out only into the matching numbered channel, and a wired
modem beside the trunk automatically exposes channels 0 through 15. It remains packet transport only
and never carries Red Alloy/redstone power.

### Cable color/channel map

| Channel | Color | Channel | Color |
|---:|---|---:|---|
| 0 | White | 8 | Light Gray |
| 1 | Orange | 9 | Cyan |
| 2 | Magenta | 10 | Purple |
| 3 | Light Blue | 11 | Blue |
| 4 | Yellow | 12 | Brown |
| 5 | Lime | 13 | Green |
| 6 | Pink | 14 | Red |
| 7 | Gray | 15 | Black |

### Universal Sensor Array

```text
IRI
RCR
IRI
```

`I` iron ingot, `R` redstone, `C` comparator.

### Wireless Display Link

A paired source/receiver display transport. Place the source link beside a terminal, turtle, server
rack, or PLC and the receiver beside a monitor or monitor wall. Sneak-right-click the source and then
the receiver to pair them, or use `displaylink source`, `sink`, and `pair` from an adjacent shell.
Links use a bounded, case-normalized channel and only connect source and receiver links in the same
dimension. A PLC source renders its dashboard; every other host is copied as a passive terminal
surface. Normal right-click opens a diagnostics/configuration GUI where the channel, role, attachment
state, and pair/clear actions are visible. Named channels can be edited directly in that GUI.

### Video Cable

A physical six-direction display cable. Connected cable blocks form a bounded routed component and
may turn horizontally or vertically. A component can have a terminal-capable source at one end and
one or more monitors at the other; the lowest-positioned source is selected if multiple sources are
present. Video Cable carries display cells only, not RedNet packets, redstone, or bundled signals.
Normal right-click opens a cable diagnostics GUI showing local directions, routing limits, and endpoint
guidance. Branching a cable naturally acts as a splitter; the component graph remains bounded to 2,048
nodes and chooses the lowest-positioned source deterministically.

### Network Router

Connects and diagnoses wired network segments and acts as the DHCP-equivalent authority for its attached RedNet subnets. Each face has persistent enabled state and logical-network assignment. Shift-right-click a face with a redstone torch to toggle it, with a renamed item to assign that item's custom name as the network, or empty-handed to restore automatic selection. Normal right-click shows bounded topology diagnostics. A router does not create a cross-dimensional gateway.

### Individual sensor recipes

Each dedicated sensor is crafted shapelessly from one **Universal Sensor Array** plus its family
ingredient:

| Sensor | Added ingredient |
|---|---|
| Redstone | Redstone dust |
| Block State | Comparator |
| Inventory | Chest |
| Fluid | Bucket |
| Energy | Redstone block |
| Entity | Spider Eye |
| Machine | Observer |
| Environment | Compass |
| Network | Network Cable |
| Kinetic | Iron Ingot |
| Chemical | Glass Bottle |

### Universal Sensor Array

A **Universal Sensor Array** is a server-authoritative telemetry block with up to 16 named channels.
Place it beside a terminal, PLC, or modem. Right-click shows a bounded status summary; terminals use
the `sensor` command family for configuration. Each channel targets `self` or one adjacent face and
selects a metric from a standard, provider-neutral family:

- `redstone`: signal level or powered state;
- `block_state`: block ID or an exposed block-state property;
- `inventory`: item count, capacity, slots, fill percentage, or an exact item selector;
- `fluid`: millibuckets, capacity, tanks, fill percentage, or an exact fluid selector;
- `energy`: FE stored/capacity/fill percentage and receive/extract capability;
- `entity`: bounded nearby counts, presence, `players`, `items`, or an entity type selector;
- `machine`: common active/lit/powered/progress state properties, TerminalCraft PLC state, and
  native SecurityCraft active/powered/disabled/locked/module/audit/range/output telemetry;
- `environment`: light, sky/block light, rain, thunder, day, time, temperature, and dimension;
- `network`: modem online/channels/pending/hosts/routers or router status;
- `kinetic`: generic state plus native Create speed, direction, stress, capacity, network, and
  overstress telemetry when Create is installed;
- `chemical`: runtime-discovered provider tanks, amount, capacity, fill percentage, tank count, and
  exact resource selectors when an optional chemical provider is installed.

Samples carry a quality value (`ok`, `unavailable`, `chunk_unloaded`, `partial`, or `unsupported`).
An unavailable or unsupported sample never becomes a PLC signal. Native Create, Mekanism, and SecurityCraft adapters
are registered only when their mods are installed; otherwise the same sensor engine continues through
vanilla state and standard Forge capabilities without trying to load those APIs.

Example terminal setup:

```sh
sensor configure tank_level fluid north fill_percent
sensor configure iron inventory north count minecraft:iron_ingot 2
sensor configure machine machine east active
sensor list
sensor read tank_level
sensor calibrate tank_level 0 100
```

PLC programs can consume an adjacent sensor or a named sensor reachable by network cable or wireless:

```text
SCAN 2
AIN TANK SENSOR tank_level
AOUT PUMP REDSTONE EAST
RUNG PUMP = TANK
```

When exactly one sensor is reachable, ordinary terminal commands such as `sensor status`,
`sensor configure ...`, and `sensor calibrate ...` discover it automatically. Likewise,
`AIN TANK SENSOR tank_level` in a PLC uses an adjacent sensor first and otherwise discovers the
single reachable network sensor. DNS selectors remain an optional advanced feature for networks
with several sensors. A wired link is made by
connecting Network Cable to both endpoints. A terminal or PLC uses an adjacent wireless modem for
wireless access; the Pocket Terminal uses its built-in modem.

For a network with several sensors, run `sensor discover`. While adjacent to a sensor or after
shift-right-clicking it with a Pocket Terminal, assign a short name with `sensor name tank`.
Then use `sensor tank status`, `sensor tank calibrate value 0 100`, or the PLC binding
`AIN LEVEL SENSOR tank value`. Duplicate names are reported and are never selected by guessing.

The array also registers as a normal `device` endpoint with `sensor.list`, `sensor.read`,
`sensor.snapshot`, configuration, calibration, enable, remove, and interval methods. A modem touching
the array can publish it as a typed RedNet service with `modem sensor add telemetry 42`; remote hosts
can request `list`, `snapshot`, or `read <channel>` with `modem sensor request ...`. RedNet replies are
bounded text telemetry and do not grant world mutation.

Every array also advertises itself automatically as a wireless RedNet telemetry service. Its stable
DNS-style name is `sensor-<uuid prefix>` (for example, `sensor-a1b2c3d4e5f6`), it listens on channel
`42`, and its default wireless range is 64 blocks. Open channel 42 on a modem, use `modem services`
to discover nearby sensors, then use `modem sensor request <name> list|snapshot|read [channel]`.
This automatic endpoint is additive: adjacent wired/device and PLC integration continue to work.

### Individual Sensors

Every Sensor Array family is also available as a dedicated one-channel block: **Redstone**,
**Block State**, **Inventory**, **Fluid**, **Energy**, **Entity**, **Machine**, **Environment**,
**Network**, **Kinetic**, and **Chemical Sensor**. Place one against the block or space it should
sample; the sensor face points at its target. Each block exposes the fixed channel `value`, has the
same quality-aware reading engine as the array, emits a redstone signal from its calibrated numeric
reading, and can be attached directly to a terminal, PLC, or modem.

Dedicated sensors use the same terminal commands with a shorter configuration form:

```sh
sensor configure fill_percent
sensor configure count minecraft:iron_ingot 2
sensor read value
sensor calibrate value 0 100
sensor disable value
```

The sensor family is fixed by the block, while the metric and optional selector remain configurable.
An individual sensor can be used by a PLC with `AIN LEVEL SENSOR value`. If several individual
sensors are adjacent, the PLC uses the first one found in its fixed direction order; use a Sensor
Array when a deterministic multi-channel panel is needed. A modem can publish an individual sensor
with the same `modem sensor add` and `modem sensor request` workflow used by an array. Its device
endpoint exposes one channel and does not offer channel removal; break the block to remove it.

Individual sensors are wireless RedNet telemetry endpoints as well. They use the same automatic
`sensor-<uuid prefix>` naming, channel 42, 64-block range, discovery, and request commands as the
array, without requiring a modem to be placed beside the block.

### Network Access Storage and Materializer

A **Network Access Storage** block accepts up to eight portable Solid-State Drives. Basic, Advanced,
and Quantum drives provide progressively larger item/fluid capacities. Drive contents are stored on
the SSD item, so removing a drive is safe and the drive can be moved to another NAS.

Shift-right-click the NAS with an item stack to store it. A filled fluid container can be emptied into
the NAS. An adjacent terminal can use the generic storage surface:

```sh
storage list
storage query 0 --limit 16
storage count 0 minecraft:iron_ingot
storage extract 0 minecraft:iron_ingot 32
```

The NAS also exposes item and fluid Forge capabilities for compatible automation. Its device endpoint
supports bounded item queries and item/fluid insertion and extraction.

Place a **Materializer** beside the NAS to create an output point. Empty-hand right-click extracts the
first available item; holding an item requests that item; holding a compatible fluid container fills
it from stored fluid. Machines can pull through the Materializer's read-only item/fluid capabilities,
which prevents accidental insertion back into the storage network.

### Unshielded and Shielded Red Alloy Wire

The craftable **Unshielded Red Alloy Wire** is the original compact, attenuating vanilla-redstone
surface conductor. It can join any Red Alloy insulation color but has no bundled-channel identity,
so it never enters a Bundled Red Alloy Cable. Combine it with a vanilla dye to create the matching
**Shielded Red Alloy Wire**. The sixteen shielded colors remain electrically isolated from one
another and each selects the same-numbered bundled channel.

Both forms use one centered cable per face. They automatically connect RedPower-style on neighboring
blocks, around supported outside corners, to compatible occupied faces in the same multipart space,
and to vanilla-redstone endpoints. Shielded wire also connects to a matching Bundled Red Alloy
channel. A second ordinary Red Alloy wire cannot occupy the same face. Network Cable follows the
same single-cable placement and automatic-routing model for data/control traffic. Placed Shielded
Red Alloy and colored Network Cables use clean, opaque dye-color rendering without patterned overlays.
Every ordinary and bundled cable uses the half-slab or half-wall selection volume described under
Network Cable, oriented to the face carrying that cable.

While holding either ordinary cable, the world preview shows one centered marker on the selected
face. Red means the face is unsupported or already occupied. Right-click with a Forge/Common-tagged
wrench (including the Create wrench) to inspect its color, face, current power or data channel, and
live links. Empty-hand interaction is deliberately left unclaimed for compatibility with Carry On
and similar interaction mods. A vanilla source is sampled using its
directional output toward the wire, including the solid support behind a mounted face. Power loses
one strength per connected wire edge. Output is limited to the plane of the mounted face, so a
floor wire emits horizontally, a wall wire emits vertically and horizontally within that wall,
and neither leaks through its support nor through its outward normal.
Each shielded color can enter or leave the matching channel of a Bundled Red Alloy Cable. It is separate from
data networking: redstone strength `0..15` is not the same thing as data/control channel `0..15`.

### Red Alloy Capacitor

The Red Alloy Capacitor is a compact six-port signal regenerator for long Red Alloy runs. Any
nonzero signal entering one face becomes strength `15` on the directly opposing face. The other
four faces remain isolated from that path: west input produces east output, north produces south,
and down produces up, with the reverse paths working independently at the same time. This lets one
capacitor extend several straight wire axes without merging them or feeding an output back into its
own input. Right-click it with a Forge/Common-tagged wrench to list its currently active paths.

### Bundled Red Alloy Cable

![Bundled Red Alloy and Bundled Network cable families](terminalcraft:textures/gui/guide/bundled_cable_families.png)

> Cable-family plate: the warm red/multiconductor jacket is Bundled Red Alloy; the navy/cyan packet-marked jacket is Bundled Network. They never exchange signals.

Carries 16 independent Red Alloy/redstone channels numbered `0` through `15`. Values are strengths
from `0` through `15`. Colored Shielded Red Alloy Wire breaks out to the channel with the same dye
ID. Direct uncolored vanilla redstone does not connect to a bundled trunk because it supplies no
channel identity; use the matching colored Shielded Red Alloy Wire as the input or output breakout.
White selects channel 0 just as red selects 14 and black selects 15. Data-network traffic remains a
separate system.
Right-click with a Forge/Common-tagged wrench to inspect the selected face, links, and all active
channels. Terminals, server racks, PLCs, and turtles adjacent to a cable expose the same channels
through both `redstone`/`rs` and `wire`/`bundled`. Each channel has a separate external input, local
computer-owned output, and combined bus value; a host's own output is not echoed as its input.
Every channel remains isolated through straight runs, corners, multipart faces, computer I/O, and
colored breakouts.

### Programmable Logic Controller

A **Programmable Logic Controller** is a dedicated server-authoritative automation block. Right-click
it to open the PLC Programmer: a line-numbered source editor with compile/load, run, stop, reset,
alarm acknowledgment, and four save/load program slots. The live commissioning panel shows scan
state, I/O, forces, alarms, and compile/runtime faults. The attached shell still exposes the `plc`
command family for scripted administration. The controller can read adjacent vanilla redstone or
bundled cable channels, evaluate a bounded program, and drive redstone/bundled outputs. Programs
stop and clear outputs when compilation or I/O fails.

When a PLC is directly beside a monitor, that monitor becomes a control dashboard. The same dashboard
is used through a wireless display receiver or Video Cable. It shows run state, scan count, inputs,
outputs, program text, faults, and a four-page trend view. Touch the `RUN`, `STOP`, or `RESET` buttons on the monitor to
control the PLC; the interaction is server-authoritative and uses the wall's global coordinates. The
`<` and `>` buttons switch between overview, live watch, program/fault-history, and oscilloscope/trend pages. PLCs record
their placer as owner; the owner or a server operator can control the dashboard, while everyone can
read it.

### Server Rack, Server Blade, and Router Blade

Server racks host bounded scheduled work. Up to three equally facing rack blocks form a six-bay cabinet (two bays per block). Right-click a bay with a blade to install it; shift-right-click an occupied bay empty-handed to remove its module. A Server Blade supplies shell/job service and a Router Blade supplies internal routing. Shell access uses `server` (alias `jobs`) from a computer with an available local rack service.

### Refined Storage Bridge

A dedicated attachment point for optional Refined Storage telemetry and crafting integration. It deliberately fails closed when native network identity or permissions are unavailable.

### Applied Energistics 2 Bridge

A dedicated attachment point for optional Applied Energistics 2 grid telemetry and read-only cached
item queries. The bridge examines only exposed AE2 nodes on its six directly adjacent blocks and
fails closed for detached, ambiguous, inactive, or API-error topology. Item queries are bounded,
paginated, tag-aware, and aggregate item-key variants by item ID.
If the cached source contains more than the adapter's 4,096-key scan bound, the query returns an
empty page instead of presenting an incomplete result as complete.

The bridge never inserts, extracts, crafts, cancels, or impersonates an AE2 security principal.
Until caller-aware native security mapping is available, all mutations are denied before AE2 is
invoked. Power, boot, channel, node, and bounded cached-inventory telemetry remain visible through
the generic device descriptor.
When AE2 crafting CPUs are present, the descriptor also reports bounded CPU count, busy CPU count,
available storage, and whether the CPU scan was complete. This is telemetry only; it never starts or
cancels an AE2 job.

## 4. Crafting reference

Use normal crafting-table placement. Symbols are defined beneath each recipe.

### Terminal

```text
III
IGI
IRI
```

`I` iron ingot, `G` glass pane, `R` redstone.

### Pocket Terminal

```text
IGI
IRI
" I "
```

### TerminalCraft Guide

```text
 R
PBP
 R
```

`B` book, `P` paper, `R` redstone.

### Turtle

```text
III
ITI
IRI
```

`T` Terminal.

### Monitor

```text
GGG
GRG
GGG
```

### Disk Drive

```text
III
IRI
ISI
```

`S` stone.

### Floppy Disk

```text
" P "
PRP
" P "
```

`P` paper. Quoted rows preserve leading/trailing empty crafting slots.

### Modem

```text
" R "
RER
" R "
```

`E` ender pearl. Quoted rows preserve leading/trailing empty crafting slots.

### Network Cable (8)

```text
IRI
RGR
IRI
```

Here `I` is an iron nugget.

Craft any Network Cable together with one vanilla dye to recolor it. Recoloring preserves one cable
and assigns the dye's default data/control channel.

### Bundled Network Cable (6)

```text
CCC
GGG
CCC
```

`C` Network Cable, `G` glass pane.

### Network Router

```text
CRC
RTR
CRC
```

`C` Network Cable, `T` comparator.

### Solid-State Drives

The Basic SSD is shapeless: iron ingot + redstone + glass pane. Upgrade shapelessly with:

- Basic SSD + diamond + redstone block → Advanced SSD;
- Advanced SSD + Nether Star + Ender Pearl → Quantum SSD.

### Network Access Storage

```text
IRI
DCD
IRI
```

`I` iron ingot, `R` redstone, `D` Basic SSD, `C` comparator.

### Materializer

```text
IPI
RNR
IGI
```

`I` iron ingot, `P` piston, `R` redstone, `N` Network Access Storage, `G` glass pane.

### Unshielded Red Alloy Wire (12)

```text
RRR
ICI
RRR
```

`R` redstone, `I` iron nugget, `C` copper ingot. This recipe produces the unshielded base wire.

Craft an Unshielded or Shielded Red Alloy Wire together with one vanilla dye to produce the matching
Shielded Red Alloy color. Network Cable is likewise craftable from its listed recipe and may be
combined with any dye to select that color and default channel.

### Red Alloy Capacitor

Craft shapelessly with one Red Alloy Wire, one Redstone Torch, one Copper Ingot, and one Nether Quartz.

### Bundled Red Alloy Cable (6)

```text
RRR
GGG
RRR
```

### Server Blade

```text
IRI
RCR
III
```

`C` Terminal.

### Router Blade

```text
IRI
RMR
III
```

`M` Modem.

### Server Rack

```text
ITI
CRC
INI
```

`T` Terminal, `C` comparator, `R` redstone block, `N` Network Router.

### Programmable Logic Controller

```text
IRI
RCR
IRI
```

`I` iron ingot, `R` redstone, `C` comparator.

### Wireless Display Link

```text
IRI
RER
ICI
```

`I` iron ingot, `R` redstone, `E` ender pearl, `C` comparator.

### Video Cable (8)

```text
IRI
CCC
IRI
```

`I` iron nugget, `R` redstone, `C` copper ingot.

### Refined Storage Bridge

```text
IRI
RCR
IRI
```

`C` comparator.

### Applied Energistics 2 Bridge

```text
IRI
RCR
IRI
```

`C` comparator. AE2 must be installed for the bridge's network adapter to activate; the block itself
remains safe to load when AE2 is absent.

## 5. The virtual filesystem

Each computer has a bounded, server-persisted virtual filesystem (VFS). Paths may be absolute (`/home/player/file.txt`) or relative to the current directory (`file.txt`).

Important locations:

| Path | Purpose |
|---|---|
| `/` | Filesystem root |
| `/home/player` | Default home directory |
| `/tmp` | Temporary working convention inside the persisted VFS |
| `/disk` | Mount point for an adjacent floppy |
| `/usr/share/doc/terminalcraft.txt` | In-game overview when present |

Common operations:

```sh
pwd
ls
ls -la /
cd /home/player
mkdir -p projects/demo
touch projects/demo/notes.txt
write projects/demo/notes.txt 'first line'
cat projects/demo/notes.txt
rm projects/demo/notes.txt
rm -rf projects/demo
```

Redirection is often the easiest way to create files:

```sh
echo first line > notes.txt
echo second line >> notes.txt
cat notes.txt
```

Input redirection and pipelines are supported:

```sh
cat < notes.txt
cat notes.txt | head -n 1
cat notes.txt | write copy.txt
```

The pipeline passes one command's textual output to the next command's standard input. Only commands that consume standard input use it meaningfully; notably `cat`, `head`, `wc`, and `write <file>` can do so.

## 6. Shell command reference

### Filesystem and text

| Command | Purpose |
|---|---|
| `pwd` | Print current directory |
| `cd [path]` | Change directory; no argument goes home |
| `ls [-l|-a|-la] [path...]` | List files/directories |
| `cat [file...]` | Print files, or pipeline/stdin with no files |
| `head [-n count|-count] [file...]` | Print leading lines |
| `wc [file...]` | Count content |
| `basename path` | Print final path component |
| `dirname path` | Print parent path |
| `write file text...` | Replace a file with text and a newline |
| `write file` | Write pipeline/stdin to a file |
| `touch file...` | Create files if absent |
| `mkdir [-p] dir...` | Create directories |
| `rm [-r|-R|-f|-rf] path...` | Remove files or trees |
| `edit file`, `nano file` | Open the built-in editor |

### Shell and diagnostics

| Command | Purpose |
|---|---|
| `help` | Built-in command summary |
| `echo args...` | Print arguments |
| `printf args...` | Print arguments; expands `\n` and `\t` |
| `env [NAME]`, `printenv [NAME]` | List environment or query one variable |
| `history` | Show command history |
| `clear` | Clear terminal output |
| `whoami` | Print shell user |
| `uname` | Print TerminalCraft system identity |
| `date` | Print server-provided date/time text |
| `true`, `false` | Return success or failure |
| `test ...`, `[ ... ]` | Evaluate supported conditions |
| `source file [args...]`, `. file [args...]` | Run a script in the current shell |
| `bash file [args...]`, `sh file [args...]` | Run a VFS script |
| `selftest` | Run built-in shell checks |
| `exit` | Print `logout` and return success; it does not terminate script execution or close Minecraft |
| `sleep` | Compatibility no-op in the current release; it does **not** wait |

### Host and world commands

| Command | Purpose |
|---|---|
| `label [name]` | Read or set computer label |
| `peripheral` | List adjacent peripheral/device information |
| `redstone`, `rs` | Read/set vanilla sides and all 16 Bundled Red Alloy channels |
| `wire`, `bundled` | Read/set Bundled Red Alloy input, output, and combined bus channels |
| `plc` | Program, inspect, start, stop, and reset the attached PLC |
| `displaylink`, `display-link`, `dlink` | Pair and configure adjacent wireless display links |
| `turtle`, `tu` | Turtle movement and interaction |
| `monitor` | Adjacent monitor control |
| `modem`, `rednet` | RedNet network operations |
| `sensor`, `sensors` | Configure and read an adjacent Sensor Array or individual sensor |
| `scada` | Plant-wide process tags, historian, alarms, dashboards, roles, audit, and control |
| `mount`, `umount`, `unmount`, `disk` | Floppy operations |
| `device`, `devices` | Unified device API and persistent DNS aliases |
| `control`, `devmgr`, `setup` | Open the full-screen device setup and PLC template Control Center |
| `storage`, `inventory` | Generic storage API |
| `sophisticated` | Sophisticated Storage/Backpacks adapter |
| `drawers` | Storage Drawers adapter |
| `server`, `jobs` | Server-rack jobs |
| `auth`, `authorization` | Current caller authorization diagnostics |

The terminal screen also supports `Tab` command/path completion, click-drag selection of visible
scrollback, `Ctrl+C` to copy a selection, and `Ctrl+V` to paste into the active prompt or editor.
`Ctrl+R` performs reverse history search, `Ctrl+L` clears the input, `F6` switches between the
surface and log views, and Page Up/Page Down scrolls the log. With no scrollback selected,
`Ctrl+C` continues to use the prompt's normal text-field copy behavior.

Inside the Control Center, `Tab`, arrows, `Enter`, `F2`/`N`, `F5`/`R`, and `Escape` operate the
graphical device workflow. DNS and method prompts use the same command-line text box, including
mouse text selection and normal `Ctrl+C`/`Ctrl+V` clipboard behavior. Run `help control` for a
screen-readable list of every key and alias.

## 7. Scripting language

TerminalCraft implements a deliberately bounded Bash-inspired language.

### Comments and shebang

Unquoted `#` starts a comment. A leading shebang is accepted and ignored when the script runs:

```sh
#!/bin/bash
# TerminalCraft script
echo ready
```

A `#` inside quotes is text, which matters for monitor colors:

```sh
monitor color any '#00ff66' '#001108'
```

### Variables

Names begin with a letter or underscore and continue with letters, digits, or underscores.

```sh
NAME=workshop
export MODE=production
echo $NAME
echo ${MODE}
echo $?
echo $$
```

Special variables:

| Variable | Meaning |
|---|---|
| `$?` | Exit code of the previous command |
| `$$` | Small shell-instance identifier |
| `$0` | Script path/name |
| `$1` … `$9` | First nine script arguments |

TerminalCraft supports bounded `$(command)` capture and signed-integer `$((expression))`
expansion. Backticks, arrays, and `${name:-default}` remain unavailable.

```sh
JOB=$(server submit monitor set any 0 ready)
NEXT=$((COUNT + 1))
let COUNT=COUNT+1
arith COUNT*2
```

Command substitution is limited to four nested substitutions, the configured command-length
limit, and 4096 captured characters. Captured lines are normalized to a single space-separated
value. The command still executes server-authoritatively and retains its normal side effects.

### Quoting

Single and double quotes group whitespace. As in Bash, single quotes suppress variable, command,
and arithmetic expansion; double quotes permit expansion while preserving the resulting text as
one argument.

```sh
GREETING='Hello operator'
echo "$GREETING"
```

Backslash escape processing is limited. `printf` recognizes textual `\n` and `\t`; this is not full Bash `printf` formatting.

### Exit codes and chaining

`0` means success; nonzero means failure.

```sh
mkdir -p logs && echo ready > logs/status.txt
cat missing.txt || echo fallback
true; echo always-runs
```

- `;` always proceeds.
- `&&` runs the next command only after success.
- `||` runs the next command only after failure.

### Tests

Supported forms are:

```sh
test -n "$VALUE"       # nonempty string
test -z "$VALUE"       # empty string
test -f path            # regular VFS file exists
test -d path            # VFS directory exists
test -e path            # file or directory exists
test "$A" = "$B"      # string equality
test "$A" == "$B"     # string equality
test "$A" != "$B"     # string inequality
test "$A" -eq "$B"    # integer equality
test "$A" -ne "$B"    # integer inequality
test "$A" -lt "$B"    # integer less-than
test "$A" -le "$B"    # integer less-than-or-equal
test "$A" -gt "$B"    # integer greater-than
test "$A" -ge "$B"    # integer greater-than-or-equal
test "$VALUE"          # nonempty string
[ -f script.sh ]
```

Compound tests (`-a`, `-o`), regexes, and glob tests are not implemented. `exit [status]`
terminates the current script, while `break` and `continue` affect the nearest loop.

### Conditionals

Single-line and multiline blocks are supported:

```sh
if [ -f settings.txt ]; then
  echo settings-found
else
  echo defaults-used
fi
```

Conditions are commands. Their exit code determines the branch.

### For loops

A `for` loop iterates a literal/expanded word list:

```sh
for side in front left right; do
  redstone get $side
done
```

A practical fixed-count loop repeats labels:

```sh
for step in 1 2 3 4 5 6 7 8; do
  echo step-$step
done
```

### While loops

`while` reruns a command condition until it fails:

```sh
FLAG=yes
while [ "$FLAG" = yes ]; do
  echo one-pass
  FLAG=no
done
```

Arithmetic makes bounded counters possible:

```sh
COUNT=0
while [ "$COUNT" -lt 8 ]; do
  let COUNT=COUNT+1
  if [ "$COUNT" -eq 3 ]; then continue; fi
  echo step=$COUNT
  if [ "$COUNT" -ge 6 ]; then break; fi
done
```

Every loop remains bounded to 256 iterations to prevent runaway scripts.

### Pipes and redirection

```sh
echo status | write status.txt
cat report.txt | head -n 5
command > file
command >> file
command < file
```

Pipes are sequential text transfer, not concurrent operating-system processes. Pipeline output cannot be captured directly into a variable.

### Script bounds

The current shell enforces safeguards including:

- maximum script nesting depth: 8;
- maximum iterations per loop: 256;
- maximum top-level chain steps: 128;
- maximum command-substitution nesting: 4;
- maximum captured command-substitution output: 4096 characters;
- command length controlled by server config (default 512 characters);
- command packet admission controlled by server config (default 20 per second per player);
- editor: 512 lines, 512 characters per line, and 64 KiB maximum save size.

## 8. Writing and running scripts

### Fast creation

For a static one-line script:

```sh
write hello.sh 'echo Hello from TerminalCraft'
bash hello.sh
```

Because variable expansion happens before tokenization—even inside quotes—use the editor when the file itself must contain literal `$1`, `$NAME`, or `$?` text. There is no general backslash escape that suppresses this early expansion.

### Editor

```sh
edit /home/player/bin/demo.sh
```

Type normal lines to append. Editor commands:

| Editor command | Action |
|---|---|
| `:w [path]` | Save |
| `:q` | Quit if clean |
| `:q!` | Quit without saving |
| `:wq`, `:x` | Save and quit |
| `:r` | Redisplay bounded buffer |
| `:help` | Editor help |

Saving a file under a mounted `/disk` auto-syncs when media is available.

### PATH execution

Scripts in directories listed by `PATH` can be invoked by name when the shell finds their VFS content. Explicit execution is clearest:

```sh
bash /home/player/bin/demo.sh argument
source /home/player/bin/demo.sh argument
```

Scripts execute in the same shell environment in the current implementation. Treat variable changes as persistent shell-state changes unless you explicitly overwrite them later.

## 9. Useful script library

The repository includes ready-to-copy files under [`examples/scripts`](../examples/scripts). These host files are examples, not automatically installed into a computer's VFS: enter them through `edit`, or place equivalent text on a mounted floppy using in-game tools. They only use syntax supported by TerminalCraft. Advanced operational examples are indexed and explained in the [Advanced Script Cookbook](ADVANCED_SCRIPT_COOKBOOK.md), with source files under [`examples/scripts/advanced`](../examples/scripts/advanced).

### 9.1 Workstation bootstrap

Creates a predictable workspace and status file:

```sh
#!/bin/bash
mkdir -p /home/player/bin
mkdir -p /home/player/logs
label "$1"
echo terminal=$1 > /home/player/logs/boot.txt
echo status=ready >> /home/player/logs/boot.txt
cat /home/player/logs/boot.txt
```

Run with:

```sh
bash workstation-bootstrap.sh workshop-main
```

### 9.2 Monitor dashboard

```sh
#!/bin/bash
monitor clear any
monitor title any 'Workshop Control'
monitor color any '#00ff66' '#001108'
monitor set any 0 'TerminalCraft online'
monitor set any 1 'Network: check modem hosts'
monitor set any 2 'Storage: check storage list'
```

### 9.3 Fixed-length turtle tunnel

This deliberately uses a bounded literal loop. Failed actions report errors; the loop continues to its next literal step because the current shell has no `break`:

```sh
#!/bin/bash
for step in 1 2 3 4 5 6 7 8; do
  turtle dig front && turtle forward && turtle dig top
done
```

The turtle's selected inventory behavior determines placement operations; test destructive scripts in a safe area.

### 9.4 Turtle survey

```sh
#!/bin/bash
echo survey-start > /home/player/survey.txt
for side in front top bottom left right back; do
  echo side=$side >> /home/player/survey.txt
  turtle inspect $side >> /home/player/survey.txt
done
cat /home/player/survey.txt
```

### 9.5 Redstone shutdown scene

```sh
#!/bin/bash
redstone set front 0
redstone set back 0
redstone set left 0
redstone set right 0
redstone set top 0
redstone set bottom 0
monitor write any 'Outputs disabled'
```

### 9.6 Bundled machine preset

```sh
#!/bin/bash
wire set back 0 15
wire set back 1 8
wire set back 2 0
wire set back 3 15
```

### 9.7 RedNet service node

```sh
#!/bin/bash
modem hostname "$1" && modem network "$2" && modem open 80 && modem open 81 && modem service add status 80
modem channels
modem service list
```

Run with `bash rednet-service-node.sh workshop-node factory-lan`.

### 9.8 RedNet status client

```sh
#!/bin/bash
if modem open 81 && modem ping "$1"; then
  modem call status 81 'status-request'
  modem recv 8
fi
```

`modem recv` is a nonblocking snapshot; it may show no messages if the peer has not replied yet. Current scripts cannot sleep or block awaiting a packet.

### 9.9 Floppy backup

```sh
#!/bin/bash
if mount; then
  mkdir -p /disk/backup
  cat /home/player/config.txt | write /disk/backup/config.txt
  cat /home/player/logs/boot.txt | write /disk/backup/boot.txt
  disk sync
  umount
fi
```

### 9.10 Diagnostic report

```sh
#!/bin/bash
echo 'TerminalCraft diagnostic report' > /home/player/diagnostics.txt
uname >> /home/player/diagnostics.txt
date >> /home/player/diagnostics.txt
label >> /home/player/diagnostics.txt
auth status >> /home/player/diagnostics.txt
device list >> /home/player/diagnostics.txt
storage list >> /home/player/diagnostics.txt
modem interfaces >> /home/player/diagnostics.txt
modem hosts >> /home/player/diagnostics.txt
cat /home/player/diagnostics.txt
```

## 10. Turtles

Command reference:

```text
turtle forward|fd|f
turtle back|bk|b
turtle up|u
turtle down|d
turtle left|l
turtle right|r
turtle turn left|right
turtle dig [side]
turtle place [side]
turtle inspect [side]
turtle facing
```

Default interaction side is `front`. Recognized side vocabulary across host operations includes relative (`front`, `back`, `left`, `right`, `top`, `bottom`) and absolute (`north`, `south`, `east`, `west`, `up`, `down`) forms where the host supports them.

Safe automation pattern:

```sh
turtle inspect front
turtle dig front && turtle forward
```

Do not write an unqualified `while true` movement loop. The runtime will stop it at its iteration bound, but it can still alter many blocks.

## 11. Monitors

```text
monitor write [side] <text>
monitor clear [side]
monitor set [side] <zero-based-row> <text>
monitor title [side] <title>
monitor color [side] <foreground> <background>
monitor read [side]
monitor bar [side] <row> <label> <0-100> [width]
monitor led [side] <row> <label> <on|off>
monitor spark [side] <row> <label> <values...>
monitor screensaver [start|color|stop|status] [side]
monitor service [list|add <name> <port>|remove <name>]
monitor remote <service> clear|write|set|title|color ...
displaylink status|list
displaylink source <channel>
displaylink sink <channel>
displaylink pair [channel]
```

Colors accept `#RRGGBB`, `0xRRGGBB`, or decimal `0..16777215`. Quote `#RRGGBB` values in scripts so `#` is not parsed as a comment.

Examples:

```sh
monitor color right '#ffaa00' '#101010'
monitor title right 'Smelter'
monitor set right 0 'State: ready'
monitor set right 1 'Input: check chest'
monitor read right
monitor bar right 2 'Steam Load' 72 16
monitor led right 3 'Pump' on
monitor spark right 4 'Temperature' 10 12 15 18 14 11
```

`monitor write` appends/writes according to the monitor host behavior; `monitor set` is preferable for stable dashboards.

The geometric screensaver uses the complete connected wall as one global canvas. It renders a
bounded animated pattern at the server tick rate and only splits rows when persisting them into
the physical monitor tiles, so each tile displays its portion of one larger animation:

```sh
monitor screensaver start any
monitor screensaver color any
monitor screensaver status any
monitor screensaver stop any
bash ~/programs/geometric_screensaver.sh
bash ~/programs/geometric_screensaver_color.sh
```

The bundled programs start the runtime and return immediately; use the `stop` action to end the
animation. `color` enables the full 16-color animated palette while `start` retains the original
monochrome green presentation. Re-running either start action restarts the phase on the current
wall geometry. The source versions are available as
[`geometric-screensaver.sh`](../examples/scripts/geometric-screensaver.sh) and
[`geometric-screensaver-color.sh`](../examples/scripts/geometric-screensaver-color.sh).

The monitor device also advertises the bounded ComputerCraft-style character-cell surface used by
terminal-capable clients. Its `term.blit` and `term.scroll` operations update per-cell colors and
cursor state, while the read-only `term.delta <since_revision> <max_cells> [offset]` method returns
a revisioned, bounded page. A response with `complete=false` supplies `next_offset`; request that
offset with the same revision before advancing the client's acknowledgement. The descriptor's
`surface_revision` property is the current server-authoritative revision. Connected walls use one
global surface coordinate space while persisting each tile's bounded cell state.

The wall surface revision is global across all connected tiles, so a cell mutation on any tile is
visible to the next delta request. The in-world renderer uses those persisted cell colors and
backgrounds while keeping the server authoritative.

Terminal-screen windows and widgets use bounded renderer-neutral layout primitives. Windows are
clipped to the active viewport, widgets are capped at 128 per layout, hit testing is topmost-first,
and no client-side script is executed. This keeps the client passive while allowing later screens to
reuse the same surface and layout model.

### Remote monitor walls

A monitor service accepts a small, typed text-control protocol. It does not execute shell text.
On the receiving computer, place an open modem so it either touches one monitor wall directly, or
touches a terminal/turtle that in turn touches the wall. Exactly one wall must resolve from that
modem.

Receiver setup:

```sh
modem open 42
monitor service add factory-wall 42
monitor service list
```

From a pocket terminal within wireless range, open any reply channel once, then publish:

```sh
modem open 41
monitor remote factory-wall clear
monitor remote factory-wall title 'Factory Status'
monitor remote factory-wall set 0 'Production online'
monitor remote factory-wall color '#66ff99' '#050a05'
```

The direct layout still needs an adjacent terminal or turtle as its setup console. Registration is
persisted on the modem. Service names are routing aliases, not passwords: any reachable peer on the
same logical RedNet scope can publish to an advertised monitor service. Use network topology and
logical network names as the trust boundary.

### Create Display Links

With Create `6.0.8` installed, a TerminalCraft monitor tile is a Display Link target. Point the
Display Link at any tile; Create sees the row and column capacity of the complete connected wall.
Choose the TerminalCraft monitor in the Display Link target UI and configure the source as usual.
TerminalCraft sanitizes incoming components and bounds writes to the wall. Create remains optional.

### Wireless display links and video cable

Wireless pairing is intentionally a two-step physical interaction:

1. Place a source link directly beside the terminal, server rack, turtle, or PLC that owns the display.
2. Place a receiver link directly beside the target monitor or monitor wall.
3. Sneak-right-click the source link, then sneak-right-click the receiver link.

The source link receives a generated per-player channel. For named channels, configure either link
from its adjacent shell:

```sh
displaylink source factory-floor
displaylink sink factory-floor
displaylink status
```

For a wired path, place Video Cable blocks between the source host and monitor. The cable can route
through adjacent blocks in all six directions and updates when endpoints or cable segments change.
The transport is bounded to 2,048 cable nodes per component. A PLC dashboard is selected automatically
for PLC sources; ordinary hosts mirror their current terminal surface.

## 12. Redstone and bundled control

### Vanilla redstone

```text
redstone sides
redstone get|input|in <side|all>
redstone output|out <side|all>
redstone set <side> <0-15>
redstone <side>
redstone <side> <0-15>
```

Examples:

```sh
rs get back
rs set front 15
rs output front
```

A successful read only says that the side was valid and readable. Capture bounded output when a
script needs to record or compare a returned value:

```sh
POWER=$(rs get back)
echo power=$POWER
```

### Six-side vanilla and 16-channel Bundled Red Alloy forms

The same `redstone`/`rs` command also directly addresses Bundled Red Alloy channels when a channel
argument is present:

```text
redstone input <side|any> <channel 0-15|all>
redstone output <side|any> <channel 0-15|all>
redstone set <side|any> <channel 0-15> <strength 0-15>
redstone channels <side|any>
```

Examples:

```sh
rs input back 3
rs set back 3 15
rs output back 3
rs channels back
```

`rs channels` prints one color-labeled row for every channel from 0 through 15. `input` is the
external signal entering that host's attached cable segment, `output` is that host segment's local
computer-driven source, and `bus` is their combined effective component signal. Input and output
remain separate on terminals, server racks, PLCs, and turtles. In particular, a PLC cannot satisfy
its own `BUNDLED` input binding merely by driving the corresponding output on the same segment.

### `wire`/`bundled` aliases

```text
wire get <side|any> <channel 0-15|all>
wire input <side|any> <channel 0-15|all>
wire output <side|any> <channel 0-15|all>
wire set <side|any> <channel 0-15> <strength 0-15>
wire status <side|any>
```

Examples:

```sh
wire input back 3
wire set back 3 15
wire output back 3
wire status back
```

`wire get` is retained for scripts that need the combined effective bus value. `wire input` reads
external input without local-output echo. Adding `all` lists all 16 values independently.
Bundled components are bounded and preserve channel identity. Network Cable and Bundled Network
Cable carry RedNet packets instead; they never become redstone conductors.

### Programmable Logic Controller

A **Programmable Logic Controller** is a dedicated server-authoritative automation block. Right-click
it to open the PLC Programmer. A terminal's normal shell editor can also author a program file, and
the `plc` command compiles or runs it:

```sh
edit /home/player/programs/factory.plc
plc load /home/player/programs/factory.plc
plc status
plc start
```

Programs are deliberately bounded to 128 lines, 16 KiB, 32 total bindings, and 64 rungs. The
controller scans every 1 to 20 game ticks, reads its declared inputs, updates timers/counters,
evaluates rungs in order, and writes declared outputs. A missing input/output or compile error stops
the controller and drives its declared outputs to zero.

Example program:

```text
SCAN 2
IN START REDSTONE NORTH
IN STOP REDSTONE SOUTH
IN SPEED BUNDLED WEST 3
OUT MOTOR REDSTONE EAST
OUT RUNNING BUNDLED WEST 4
TIMER DELAY 20 = START
LATCH ENABLE SET START RESET STOP
RUNG MOTOR = ENABLE AND DELAY.DONE AND NOT STOP
RUNG RUNNING = MOTOR OR SPEED
```

Use `REDSTONE side` for vanilla power and `BUNDLED side channel` for a 0–15 cable channel. Boolean
expressions use `AND`, `OR`, `NOT`, parentheses, `ON`, and `OFF`. Analog bindings use `AIN` and
`AOUT`; redstone analog inputs are 0–15, while sensor inputs preserve the sensor's native numeric
value (for example, a fluid `fill_percent` reading is 0–100). `MOVE` copies an analog value and `SCALE` linearly maps an
input range to an output range. `PID` defines a bounded discrete controller, for example:
`PID LOOP SETPOINT 12 PROCESS SENSOR OUTPUT HEATER KP 2 KI 0.1 KD 0.2`. Timer and counter flags are named
`NAME.ACTIVE` and `NAME.DONE`; counters increment on rising edges. Latches are reset-dominant.
`AIN name SENSOR channel` reads the numeric value from an adjacent Sensor Array or individual
sensor (`value`). `AIN name SENSOR hostname channel` reads a named sensor reachable through
Network Cable or an adjacent wireless modem. Sensor bindings
are input-only, and a non-`ok` sample stops the PLC and clears its outputs.

#### PLC scan model

The PLC is a deterministic scan controller, not a general-purpose shell. On each scan it performs
the following order:

1. Read declared inputs. Redstone inputs are `0..15`; sensor analog inputs retain their native numeric value.
2. Convert digital inputs to false (`0`) or true (`1..15`).
3. Update timers, rising-edge counters, and reset-dominant latches.
4. Evaluate Boolean rungs in source order.
5. Evaluate analog `MOVE`, `SCALE`, and `PID` operations.
6. Write declared outputs. Digital outputs write `0` or `15`; analog outputs write `0..15`.

If a declared endpoint disappears, a sensor reports non-`ok` quality, compilation fails, or an
output cannot be written, the controller stops and attempts to drive all declared outputs to zero.
Design real machines so zero output is the safest state. Invert active-low Create components such as
Clutches and Funnels where necessary.

#### Source format and names

- One instruction is written per line. `#` begins a comment.
- Instructions and signal references are case-insensitive and canonicalized to upper case.
- Signal names start with a letter or underscore and may contain letters, digits, underscores, and
  dots. Names must be unique where the compiler requires them.
- A program is bounded to 16 KiB, 128 lines, 192 characters per line, 32 I/O bindings, 64 rungs,
  and 16 each of timers, counters, and latches.
- `SCAN <ticks>` selects a scan period from 1 through 20 game ticks. The default is 2.

#### Digital and analog I/O declarations

```text
IN <name> REDSTONE <side>
IN <name> BUNDLED <side> <channel 0-15>
IN <name> SENSOR <sensor-channel>
IN <name> SENSOR <sensor-hostname> <sensor-channel>
OUT <name> REDSTONE <side>
OUT <name> BUNDLED <side> <channel 0-15>

AIN <name> REDSTONE <side>
AIN <name> BUNDLED <side> <channel 0-15>
AIN <name> SENSOR <sensor-channel>
AIN <name> SENSOR <sensor-hostname> <sensor-channel>
AOUT <name> REDSTONE <side>
AOUT <name> BUNDLED <side> <channel 0-15>
```

Use `IN`/`OUT` when only off/on matters. Use `AIN`/`AOUT` when the numeric value or full redstone
strength matters.
Sides are relative to the PLC block (`NORTH`, `SOUTH`, `EAST`, `WEST`, `UP`, `DOWN`). A bundled
binding selects one of the 16 channels on that face. Sensor bindings are input-only and name the
configured channel exposed by an adjacent Sensor Array or individual sensor; `value` is the standard
numeric channel on an individual sensor. The two-argument sensor form selects a named sensor over
a physically reachable wired or wireless network.

#### Boolean expressions and rungs

```text
RUNG <output-or-signal> = <expression>
```

Expressions support named signals, numeric literals, comparisons (`==`, `!=`, `<`, `<=`, `>`, `>=`),
`ON`/`TRUE`, `OFF`/`FALSE`, parentheses, `NOT`/`!`, `AND`/`&&`, and `OR`/`||`. Precedence is
comparisons, `NOT`, then `AND`, then `OR`. Parenthesize safety logic
when the intended order is not immediately obvious.

```text
RUNG MOTOR = RUN AND GUARD AND NOT ESTOP
RUNG ALARM = ESTOP OR OVERLOAD
RUNG PERMISSIVE = (PRESSURE_OK OR BYPASS) AND GUARD
RUNG EMPTY = LEVEL == 0
RUNG FULL = LEVEL == 100
```

A rung with the same name as a digital `OUT` controls that output. A rung may also create an
intermediate Boolean signal for later rungs.

#### Timers, counters, and latches

```text
TIMER <name> <ticks> = <expression>
COUNTER <name> <preset> = <expression>
LATCH <name> SET <expression> RESET <expression>
```

A timer accumulates while its expression is true and resets to zero when false. It exposes
`NAME.ACTIVE` and `NAME.DONE`. The preset is 1 through 72,000 ticks. A counter increments only on a
false-to-true edge, saturates at its preset, and exposes the same `.ACTIVE` and `.DONE` flags. A
held-high input is one count, not one count per scan. Timers and counters reset when the program is
loaded or `plc reset` is used. Latches retain their state across scans; when SET and RESET are both
true, RESET wins.

```text
TIMER START_DELAY 40 = RUN
COUNTER PARTS 16 = ITEM_PULSE AND RUN
LATCH RUN SET START RESET STOP OR ESTOP
RUNG MOTOR = RUN AND START_DELAY.DONE
RUNG BATCH_DONE = PARTS.DONE
```

#### Analog MOVE, SCALE, and PID

```text
MOVE <AOUT> = <source>
SCALE <AOUT> = <source> <in-min> <in-max> <out-min> <out-max>
PID <name> SETPOINT <0-15|signal> PROCESS <signal> OUTPUT <AOUT>
    KP <number> KI <number> KD <number>
```

`MOVE` copies a `0..15` value. `SCALE` performs bounded linear mapping and can reverse a signal by
reversing an output range. Its input minimum and maximum must differ. `PID` is a discrete bounded
controller: the setpoint is a literal or known signal, the process references a known input, and the
output must be an `AOUT`. Gains are finite numbers from `-64` through `64`; the integral term is
internally bounded and the final output is clamped to `0..15`.

```text
SCAN 2
AIN LEVEL SENSOR value
AIN DEMAND BUNDLED WEST 0
AOUT VALVE REDSTONE EAST
AOUT MIRROR BUNDLED EAST 3
PID LEVEL_LOOP SETPOINT DEMAND PROCESS LEVEL OUTPUT VALVE KP 2 KI 0.02 KD 0.1
MOVE MIRROR = LEVEL
```

Tune a PID with `KI` and `KD` at zero first, raise `KP` until the response is useful without severe
oscillation, then add small integral and derivative terms only as the machine requires. Always test
with the machine disconnected or otherwise made safe.

#### Complete motor starter example

```text
# Start/stop seal-in with guard, emergency stop, delayed start, and alarm.
SCAN 1
IN START REDSTONE NORTH
IN STOP REDSTONE SOUTH
IN GUARD BUNDLED WEST 0
IN ESTOP BUNDLED WEST 1
OUT MOTOR REDSTONE EAST
OUT ALARM BUNDLED EAST 1
LATCH RUN SET START AND GUARD RESET STOP OR ESTOP OR NOT GUARD
TIMER START_DELAY 20 = RUN
RUNG MOTOR = RUN AND START_DELAY.DONE AND GUARD AND NOT ESTOP
RUNG ALARM = ESTOP OR NOT GUARD
```

Commission it in this order:

```sh
plc template show motor-start-stop
edit /home/player/programs/motor.plc
plc load /home/player/programs/motor.plc
plc status
plc watch
plc start
```

Use `plc stop` before rewiring, `plc reset` to clear retained controller state, and `plc alarm` plus
`plc acknowledge` to inspect and acknowledge faults. The four program slots provide local rollback;
the template catalog provides compile-tested starting points rather than machine-specific final
control logic.

The complete command surface is:

```text
plc status | show | watch | start | stop | reset | clear
plc load <file>
plc set <program>       # use \n for embedded line breaks
plc append <instruction>
plc force input|analog|output <name> <on|off|0-15|clear>
plc alarm|acknowledge
plc page <0|1|2|3|next|previous>
plc slot save|load|clear <0-3>
plc remote open <x> <y> <z>
plc template categories
plc template list [category]
plc template show <name>
plc template load <name>
```

The built-in template catalog provides compile-checked starters for motor start/stop, delayed
starts, conveyor interlocks, pump level switches, acknowledged alarms, batch counters, bundled
channel gates, sensor scaling, PID temperature control, security zones, airlocks, and other common patterns. Use
`plc template list` to browse it from any terminal, `plc template show <name>` to inspect a source
program from any terminal, or `plc template load <name>` to compile and load one directly into an
attached PLC. From an ordinary terminal, use `plc remote open <x> <y> <z>` first to open the PLC
programmer at a remote controller.

### Create PLC template library

The `create` category contains fourteen programs designed around the redstone behavior of Create
6.0.8 on Minecraft 1.20.1. They use only TerminalCraft PLC redstone, bundled, timer, counter, latch,
analog, and PID instructions, so Create remains optional. Browse the category with:

```text
plc template categories
plc template list create
plc template show create-clutch-safety
```

| Template | Create components and purpose |
|---|---|
| `create-clutch-safety` | Fail-safe Clutch starter with stop, E-stop, and permissive inputs |
| `create-reversing-drive` | Clutch plus Gearshift forward/reverse drive |
| `create-chain-speed-control` | Open-loop analog control of an Adjustable Chain Gearshift |
| `create-speed-regulator` | PID feedback from a Speedometer comparator into an input-side Adjustable Chain Gearshift |
| `create-belt-jam-stop` | Content Observer dwell detection, clutch stop, and latched jam alarm |
| `create-threshold-refill` | Low/high Threshold Switch refill control for a Funnel or feeder Clutch |
| `create-sequenced-gearshift` | Bounded trigger pulse for a configured Sequenced Gearshift |
| `create-deployer-workcell` | Tunable operating window for a clutch-fed Deployer |
| `create-mixer-batch` | Timed Basin/Mixer run followed by interlocked Funnel discharge |
| `create-crushing-line` | Downstream-first startup for belts, crushing wheels, or other processing lines |
| `create-contraption-indexer` | Four-position one-shot index using Redstone Contact pulses |
| `create-portable-interface-dock` | Dock, cargo-ready delay, PSI disconnect, and contraption departure |
| `create-elevator-two-stop` | Two-stop Rope Pulley, Gantry, or piston lift using a Clutch and Gearshift |
| `create-item-batch-counter` | Sixteen-item one-shot batch from Content Observer transfer pulses |

Create Clutches transmit rotation when unpowered and stop when powered. Funnels and Portable
Storage Interfaces also use powered states as stops or disconnects. Templates whose output is named
`RUN_ENABLE`, `DRIVE_ENABLE`, or `TRANSFER_ENABLE` therefore document a redstone-torch inverter
between the PLC output and the Create component. With that wiring, TerminalCraft's fault behavior
(all PLC outputs go to zero) restores power to the Clutch or Funnel and leaves the machine stopped
or closed. Outputs such as `GEAR_REVERSE`, `SEQUENCE_TRIGGER`, and `PSI_RELEASE` are direct unless
the program comments say otherwise.

Template faces and bundled channels are starter assignments. Change the `REDSTONE <side>` and
`BUNDLED <side> <channel>` declarations to match the physical machine before loading. Timer values
also need tuning for the kinetic speed and travel distance. The two counter templates are explicitly
one-shot because PLC counters retain their value until `plc reset` or the program is reloaded. Any
ordinary-redstone input or output in these programs can be carried wirelessly through a paired
Create Redstone Link; the PLC logic does not need to change.

### Mekanism PLC template library

The `mekanism` category contains eight compile-checked starters for machines configured with
Mekanism's native redstone control, normally `HIGH`. Mekanism is still optional because the programs
use TerminalCraft redstone, bundled I/O, timers, latches, and calibrated sensor contacts:

```text
plc template list mekanism
plc template show mekanism-machine-starter
plc template load mekanism-chemical-refill
```

| Template | Mekanism components and purpose |
|---|---|
| `mekanism-machine-starter` | Start/stop, E-stop, and permissive control for one HIGH-mode machine |
| `mekanism-ore-line` | Downstream-first ore-processing line startup and fault trip |
| `mekanism-chemical-refill` | Low/high chemical buffer refill using runtime resource selectors |
| `mekanism-energy-load-shed` | Essential/nonessential load shedding from calibrated energy contacts |
| `mekanism-factory-jam` | Blocked-output dwell, latched jam, and feeder stop |
| `mekanism-fission-scram` | Reset-dominant coolant, waste, temperature, and containment scram |
| `mekanism-turbine-trip` | Condenser, energy-space, overspeed, and E-stop interlock |
| `mekanism-digital-miner-window` | Authorized schedule, storage-space, and power window |

These are commissioning starters, not a substitute for reactor protection design. Configure every
machine's native redstone mode and every sensor threshold first, confirm that loss of PLC power moves
the plant to its safest state, then test each trip before supplying valuable material or fuel.

### SecurityCraft PLC template library

The `securitycraft` category contains ten compile-checked starters for SecurityCraft devices that
emit or accept redstone. SecurityCraft remains optional because the programs use only TerminalCraft
redstone, bundled I/O, timers, and latches:

```text
plc template list securitycraft
plc template show securitycraft-perimeter-alarm
plc template load securitycraft-panic-lockdown
```

| Template | Security system purpose |
|---|---|
| `securitycraft-perimeter-alarm` | Latched alarm from a Laser, scanner, logger, or other zone detector |
| `securitycraft-laser-zone` | Valid-access window and unauthorized Laser crossing lockdown |
| `securitycraft-inventory-checkpoint` | Inventory Scanner verification, release, and contraband alarm |
| `securitycraft-panic-lockdown` | Reset-dominant Panic Button lockdown and fail-closed access release |
| `securitycraft-camera-zone` | Confirmed motion event associated with a camera zone |
| `securitycraft-two-door-airlock` | Mutually exclusive door releases and chamber dwell |
| `securitycraft-rift-lockdown` | Unauthorized Rift Stabilizer activity containment |
| `securitycraft-trophy-defense` | Sustained projectile/degraded-defense response |
| `securitycraft-secure-redstone-backhaul` | Four-channel alarm backhaul through Secure Redstone Interfaces |
| `securitycraft-redundant-perimeter` | Two-out-of-three Laser/retinal/logger trip voting |

These programs never bypass a passcode, keycard, allowlist, or owner check. Wire a reader's successful
redstone output into a PLC input and let the PLC coordinate releases, alarms, and interlocks. For
fail-safe doors, confirm whether the chosen SecurityCraft door opens on power or on loss of power and
add a torch inverter where necessary. Test every detector, broken-wire state, PLC stop, and server
restart before treating the installation as secure.

The dashboard is available without opening the PLC terminal. Attach a monitor directly to the PLC,
or connect it through a display-link receiver or Video Cable. Touching its buttons changes the PLC
run state; the dashboard refreshes every few ticks and keeps the PLC's normal compile and fail-safe
rules. `plc watch` reports live signals, analog values, PID outputs, timers, counters, latches, trend
series, and active overrides. Forced
inputs and outputs are persisted until cleared; compile/I/O faults are retained in an eight-entry
alarm history. `plc slot` provides four persisted program versions for quick rollback.

The programmer screen's `F7` key toggles between source mode and the ladder workspace. In ladder
mode, drag an available signal from the palette onto a rung to append an `AND` contact, or drag a
rung to another position to reorder it. Declarations, timers, counters, latches, `SCALE`, and `PID`
instructions remain source-controlled and are preserved when switching modes. Compile & Load always
uses the same bounded server compiler.

PLCs register on the authenticated TerminalCraft device network. From any authorized terminal,
`device list`/`device info <name-or-uuid>` discovers the controller; `device call <name-or-uuid> status` reads
status, `program.get` reads chunks, `program.set <source>` loads a program, `control.run|stop|reset`
controls it, and `trend.get` returns the last 64 samples. Program writes and control calls require
the PLC owner, and `plc remote open <x> <y> <z>` opens the same programmer screen from an
authorized terminal in the same dimension.

Red-alloy and bundled wiring is intentionally visible in 1.0.26. This release does not provide a
concealment/facade representation; use the wrench interaction to inspect a mounted face or remove
that face without disturbing the other faces in the same compact wire block.

## 13. Floppy disks

1. Place a Disk Drive adjacent to the computer.
2. Insert a Floppy Disk using the drive interaction.
3. Run `mount`; media appears at `/disk`.
4. Read and write normally.
5. Run `disk sync` for an explicit flush.
6. Run `umount` before removing media.

Commands:

```text
mount
umount
disk
disk status
disk label
disk label <new name>
disk sync
```

Example:

```sh
mount
ls -la /disk
disk label automation-library
mkdir -p /disk/programs
write /disk/programs/hello.sh 'echo portable-script'
disk sync
umount
```

If the current directory is inside `/disk`, unmounting returns the shell to its home directory. Always unmount or sync before ejecting media when preserving recent writes matters.

## 14. RedNet networking

### 14.1 Mental model

- A modem is a network interface with a stable UUID identity.
- Ports/channels are integers from `0` to `65535`.
- A receiver must open the destination port.
- Hostnames are a bounded DNS-like directory: each name points to one device UUID, while the UUID
  remains authoritative if a name is renamed. Modems keep a primary hostname; other devices can
  have durable secondary aliases.
- `rednet:<uuid>/<name>` is an explicit identity-preserving address; scripts may also route to a
  raw modem UUID when a hostname is unavailable.
- Hostnames and service names are canonical aliases, not authority credentials.
- Logical network names can isolate or connect configured topology.
- Wireless reachability and wired physical topology are different transports.
- Every logical server and dimension has an isolated runtime scope.
- There is no implicit cross-dimensional gateway.

### 14.2 Beginner setup: place, connect, communicate

The default profile is designed so a small network works without a configuration ceremony:

1. Place a modem beside each Terminal, Turtle, or other shell host.
2. For a wired network, shift-right-click each modem into wired mode and connect them with Network
   Cable. Insert a Network Router anywhere a separate cable segment should be joined.
3. Leave modem channels, hostnames, and router faces at their automatic defaults.
4. Run `modem status` on each host. The modem should report `state=ready`, channel `42`, a
   `node-...` identity, and a `10.x.x.x` RedNet lease.
5. Send a message with `modem send 'hello network'`; receive it with `modem recv`.

Automatic wired leases use the physical cable subnet as their pool. If a router is attached, the
lease reports `source=router`; an isolated cable or wireless network uses the same bounded allocator
as a link-local fallback. The lease is an in-world RedNet address and never opens a real LAN socket.

### 14.3 Core commands

```text
modem open|listen <channel>
modem close|unlisten <channel>
modem channels
modem auto [on|off]
modem status
modem hostname [name|clear]
modem network [name|clear]
modem interfaces
modem neighbors [max]
modem route <host>
modem ping <host>
modem hosts
modem dns [list|resolve <name|uuid>|self]
modem service [list|add <name> <channel>|remove <name>]
modem sensor [list|add <name> <channel>|remove <name>|request <service> <list|snapshot|read> [channel] [replyChannel]]
modem scada [list|add <name> <channel>|remove <name>|request <service> <status|tags|read|history|alarms> <selector|-> <limit> <replyChannel>]
modem services
modem call <service> [replyChannel] <message>
modem send [channel] [replyChannel] <message>
modem sendto <host> [channel] [replyChannel] <message>
modem probe <host> <port> <replyChannel> <message>
modem delivery <messageId>
modem recv [max]
```

The unified device shell also manages durable DNS aliases:

```text
device dns list
device dns add <name|uuid> <alias>
device dns resolve <name|uuid>
device dns remove <alias>
device dns clear <name|uuid>
```

List limits are bounded. `neighbors [max]` accepts `1..128`.

### 14.4 Wireless setup

For a manual, named setup, configure the optional fields explicitly. The automatic equivalent is
simply to place two wireless modems within range and use channel 42:

```sh
modem status
modem send 'hello wireless'
modem recv
```

On receiver (advanced named setup):

```sh
modem hostname receiver
modem network workshop
modem open 42
```

On sender:

```sh
modem hostname sender
modem network workshop
modem open 41
modem dns self
modem ping receiver
modem sendto receiver 42 41 'hello receiver'
```

On receiver:

```sh
modem recv 8
```

### 14.5 Wired setup

1. Place a modem beside each computer.
2. Shift-right-click each modem into wired mode.
3. Connect modems with Network Cable and, where desired, routers.
4. Leave router faces enabled and automatic unless an advanced partition is required.
5. Use `modem status`, `modem neighbors`, and `modem route <host>` to diagnose.
6. Assign compatible logical network names only when intentionally partitioning or joining named
   networks.

A wireless modem touching cable is not a wired endpoint. Both route endpoints must be live wired modems.

`modem hostname name` registers the attached modem UUID under a human-friendly name. The mapping
is unique within the current server/dimension scope and is restored from the modem's saved state.
Use `modem dns list` to inspect name-to-UUID records, `modem dns resolve receiver` (or the
shorthand `modem resolve receiver`) to inspect the canonical `rednet:<uuid>/receiver` address, and
`modem route`, `modem ping`, `modem sendto`, and `modem probe` with either a hostname, raw UUID, or
encoded RedNet address. Renaming a modem changes only its alias; UUID-targeted scripts continue to
resolve the same device.

For any live TerminalCraft device shown by `device list`, use its UUID (or an existing DNS name) to
create a persistent alias:

```sh
device list
device dns add 123e4567-e89b-12d3-a456-426614174000 production-plc
device dns resolve production-plc
device info production-plc
device call production-plc status
```

Aliases are unique within the current server/dimension scope, survive world reloads, and resolve
back to the authoritative UUID. `device dns remove <alias>` removes one alias; `device dns clear
<name|uuid>` removes all aliases owned by that device. A DNS alias identifies a device for discovery
and device calls; RedNet packet transport still requires the target to expose a modem or other
network interface.

### 14.6 Advanced network controls and protocol

`modem auto off` disables the default channel, generated hostname, and automatic lease renewal for
that modem. `modem auto on` restores them. Explicit `modem hostname`, `modem network`, `modem open`,
router face names, and disabled router faces remain the advanced controls for segmented or managed
installations.

`modem status` also reports the RedNet envelope version, maximum hop count, payload bound, and the
built-in channel/control protocol identifiers. RedNet packets are versioned, bounded, dimension
local, and server-authoritative; a router forwards only valid reachable wired paths and decrements
the envelope hop limit on each router transition.

### 14.7 Named services

Server:

```sh
modem open 80
modem hostname inventory-node
modem service add inventory 80
modem service list
```

Client:

```sh
modem open 81
modem services
modem call inventory 81 'count-request'
```

Service registration requires an open port and can fail for invalid/duplicate names or bounded directory capacity.

### 14.8 Reliable probes

```sh
modem probe receiver 42 41 'health-check'
```

The command prints a stable message/correlation UUID and initial state. Query it with:

```sh
modem delivery <message-uuid>
```

Reliable delivery is asynchronous, bounded, and opt-in. It uses acknowledgement, timeout, retry, correlation, and duplicate-suppression logic. Delivery records are transient; restart does not preserve them. A transport acknowledgement does not mean an application performed a requested business action.

### 14.9 Network limits and diagnostics

Queues, queue bytes, sender message/byte rates, duplicate state, acknowledgement controls, and retained delivery records are bounded. Admission failure is reported instead of silently pretending to send. RedNet does not use the machine's real network stack.

## 15. Devices, storage, fluids, and energy

### 15.1 Device discovery

```sh
device list
device info <device-name-or-uuid>
```

`device list` prints UUID, type, status, address, and display name for discoverable devices. Only server-authorized adjacent/current endpoints are exposed. After creating a DNS alias with `device dns add`, the alias can be used by the unified `device info`, `device call`, transfer, and escrow commands.

### 15.2 Calling methods

```sh
device call <device-name-or-uuid> <method> [arguments...]
```

Run `device info` first to see methods and typed parameters. Arguments are parsed according to the published method descriptor rather than treated as arbitrary Java values.

### 15.3 Generic storage

```text
storage list [--json]
storage info <device> [--json]
storage query <device> [--resource id] [--namespace ns] [--tag #id]
              [--text text] [--cursor token] [--limit n] [--json]
storage count <device> <item-id> [--json]
storage insert|extract|simulate-insert|simulate-extract
        <device> <item-id> <count> [--json]
```

A `<device>` selector may be the displayed list index, UUID, or unambiguous address.

Start safely with simulation:

```sh
storage list
storage info 0
storage query 0 --namespace minecraft --limit 16
storage simulate-extract 0 minecraft:cobblestone 64
storage extract 0 minecraft:cobblestone 64
```

Mutation is capability-, lifecycle-, side-, permission-, and budget-aware. Partial execution is a valid result. Never assume the requested amount equals the executed amount; inspect output or use `--json` for machine-readable diagnostics.

### 15.4 Exact item transfer

```text
device transfer <operation-uuid> <source-uuid> <destination-uuid> <item-id> <count>
```

The operation UUID is an idempotency key. Reusing it with the same request returns/reconciles the same operation; reusing it for a conflicting request is rejected. Use a newly generated UUID for each logically new transfer.

### 15.5 Exact fluid transfer

```text
device fluid-transfer <operation-uuid> <source-uuid> <destination-uuid> <fluid-id> <amount-mB>
```

Fluid units are millibuckets (`mB`). Exact variants, rollback, replay, and escrow are designed around conservation. Partial or indeterminate failures must be treated according to the returned structured result, not blindly retried with a new operation ID.

### 15.6 Escrow recovery

```text
device escrow list
device escrow recover <escrow-uuid> <destination-uuid>
device fluid-escrow list
device fluid-escrow recover <escrow-uuid> <destination-uuid>
```

Escrow administration requires explicit authority. Escrow exists to preserve exact remainders after a transfer cannot safely complete or roll back; it is not a normal inventory.

### 15.7 Forge Energy

Adjacent energy endpoints expose typed methods through `device info`/`device call`. Energy mutation is a bounded, single-attempt, non-reversible FE operation. A post-execution failure may be reported as indeterminate and non-retryable because repeating it could duplicate the effect.

## 16. Events, jobs, and authorization

### 16.1 Events

Legacy bounded poll:

```sh
device events [limit]
```

Owned subscriptions:

```text
device events subscribe <source-uuid|*> <types-csv|*> [debounce-ticks] [coalesce]
device events poll <subscription-uuid> [limit]
device events diagnostics <subscription-uuid>
device events unsubscribe <subscription-uuid>
```

Example:

```sh
device events subscribe '*' 'disk_inserted,disk_removed,monitor_resize' 5 true
```

The returned subscription UUID belongs to the authenticated caller. Queues are bounded and best-effort. Diagnostics report queued, delivered, dropped, debounced, and coalesced counts. Subscriptions are in-memory logical-server-lifetime state and do not survive restart.

Scheduler-backed callers can use the `DeviceEventWaitAccess` capability for a cooperative wait:

```text
beginEventWait(subscription, current_game_time, timeout_ticks)
pollEventWait(wait_id, current_game_time)
cancelEventWait(wait_id)
```

`beginEventWait` returns immediately with `waiting`, `event`, or `timeout` plus a bounded `wake_at` tick. A matching publication completes the token and can wake a parked `ServerJobScheduler` job through `DeviceEventSchedulerBridge`; timeout resolution occurs when the caller polls at or after `wake_at`. One subscription may have one active wait, waits are caller-owned, and the maximum timeout is 1,200 game ticks. No server thread sleeps or blocks.

### 16.2 Server-rack jobs

```text
server list
server queued
server scheduler
server submit <command...>
server status <job-uuid>
server cancel <job-uuid>
```

Example:

```sh
server submit monitor write any 'scheduled job ran'
server list
server scheduler
```

Jobs are caller-owned and foreign jobs are concealed. Scheduler work is bounded, owner-fair, and cooperatively sliced. Whether a specific command can run depends on rack availability, persisted continuation support, current authority, and host services.

### 16.3 Authorization diagnostics

```sh
auth status
```

This reports the authenticated typed principal and central decisions for discovery, read, mutation, and escrow administration. It is diagnostic only: it cannot grant permission.

Typed principals distinguish player, device, service, and process identity even when UUID text happens to match. Names and labels are never substitutes for authority.

## 17. Optional integrations

All integrations are optional and centrally isolated. TerminalCraft must remain startup-safe when these mods are absent.

### Create 6.0.8

Every adjacent Create kinetic block entity can be discovered through the normal Device API even when
it has no Forge inventory, fluid, or energy capability. Native methods expose live speed, theoretical
speed, direction, stress impact, added capacity, source, kinetic network ID, and overstress state.
Stressometers add network load/capacity; Threshold Switches add live thresholds and authenticated
inversion; Redstone Links add signal telemetry and authenticated 0–15 transmission; configured
Sequenced Gearshifts add state and an authenticated idle-only trigger. Existing monitor-wall Display
Link support remains available.

Useful calls after `device list` and `device methods <device>` include:

```text
device call <device> create.kinetic.status
device call <device> create.stress.status
device call <device> create.threshold.status
device call <device> create.redstone_link.transmit 15
device call <device> create.sequencer.trigger
```

Write methods require `device.write`. Create's own redstone-facing components remain compatible with
ordinary, shielded, bundled, and wireless TerminalCraft wiring, so direct methods are additive rather
than a required control path. Create is compile-only for development and neither bundled nor required
to start TerminalCraft.

### Mekanism 10.4.16 and dynamic chemicals

Every adjacent Mekanism base machine can expose activity, powered state, current redstone level and
mode, process progress/ticks, native Joule containers, heat capacitors, supported substance families,
and non-sensitive security status. `mekanism.redstone_control.set` selects `disabled`, `high`, `low`,
or supported `pulse` mode through the authenticated Device API. TerminalCraft does not impersonate a
Mekanism owner or trusted player: mutation is allowed only for Mekanism-public machines and otherwise
fails closed in addition to requiring `device.write`.

Chemical storage is provider-neutral and runtime-discovered. Mekanism contributes every live gas,
infusion, pigment, and slurry handler visible on the accessed side; resource IDs are read from the
actual tank stacks. There is no built-in chemical allowlist, so Mekanism add-ons and other mods that
register resources in those handlers work automatically. Other optional integrations can register
the same generic contract, while chemicals represented as Forge fluids continue through `fluid.*`.

```text
device call <device> mekanism.status
device call <device> mekanism.energy.containers
device call <device> mekanism.heat.capacitors
device call <device> chemical.tanks
device call <device> chemical.count addonchem:tritium_mix gas
device call <device> mekanism.redstone_control.set high
sensor configure hydrogen chemical north fill_percent mekanism:hydrogen 2
sensor configure mixture chemical north amount gas:addonchem:tritium_mix 2
```

`chemical.tanks` returns family, tank, live resource ID, exact amount/capacity decimal strings, fill
percentage, and unit. `chemical.count` also returns a decimal string so quantities above the exact
IEEE-754 integer range are not rounded. The `CHEMICAL` sensor produces bounded numeric PLC/SCADA
samples from the same provider snapshot.

### SecurityCraft 1.10.2.1

TerminalCraft's SecurityCraft adapter is based on the official
[SecurityCraft 1.20.1 source and public APIs](https://github.com/Geforce132/SecurityCraft/tree/1.20.1).
It is loaded only when mod ID `securitycraft` is present. Every adjacent SecurityCraft block entity
implementing the official `IOwnable` API is discoverable, including reinforced and technical blocks.
`ICustomizable` options and `IModuleInventory` modules are handled generically, so additional
SecurityCraft block families using those APIs inherit support without a TerminalCraft block-ID list.

The native endpoint covers:

- owner/validation, powered, active, disabled, lock, passcode-configured, module, option, and audit counts;
- native option values/ranges and enabling or disabling modules that are already physically installed;
- nearby Security Sea Boat discovery with owner-only position, option, module, and bounded chest telemetry;
- Alarm, Block Change Detector, Username Logger, Inventory Scanner, Laser, keypad/keycard,
  retinal/scanner, Portable Radar, Projector, Protecto, Rift Stabilizer, Trophy System, Security
  Camera, Sonic Security System, Secure Redstone Interface, Track Mine, IMS, display/frame, and
  sentry status;
- owner-authorized configuration for alarms, keycard levels/signatures, laser sides, rift/trophy
  filters, scanner power, projectors, Sonic Security, Secure Redstone Interfaces, track mines,
  cameras/EMP shutdown, and sentry mode/shutdown;
- native Machine Sensor metrics including `active`, `powered`, `disabled`, `enabled`, `locked`,
  `passcode_configured`, `module_count`, `enabled_modules`, `audit_entries`, `range`,
  `signal_length`, `redstone_output`, `shutdown`, `target_present`, and `mode` where supported.

Useful Device API calls are:

```text
device methods <device>
device call <device> securitycraft.status
device call <device> securitycraft.details
device call <device> securitycraft.options
device call <device> securitycraft.option.set disabled false
device call <device> securitycraft.modules
device call <device> securitycraft.module.set_enabled redstone true
device call <device> securitycraft.config.set frequency 42
device call <device> securitycraft.audit
```

`securitycraft.status` is operational and secret-safe. Detailed configuration, audit entries, and
all writes require both TerminalCraft's normal permission and the online authenticated player whom
SecurityCraft itself recognizes as owner. Device/service/process identities cannot impersonate that
player; use redstone for unattended PLC control. TerminalCraft never returns or accepts passcodes,
hashes, salts, allowlist/denylist contents, keycard item data, or owner UUIDs, and it never changes
ownership or creates/removes module items. Sentry support is reached safely through SecurityCraft's
ownable disguise block entity at the sentry position.

SecurityCraft is compile-only during development and is neither bundled nor mandatory. The verified
optional target is `[1.20.1] SecurityCraft v1.10.2.1.jar`; TerminalCraft continues to start and run
when it is absent.

### Sophisticated Storage and Sophisticated Backpacks

Placed blocks/backpacks can expose generic item storage and namespaced metadata through public APIs. Supported metadata includes bounded accessible-slot, effective-capacity, filter, sorting, and upgrade information where available. Player-carried backpacks are intentionally not enumerated or addressable.

Commands:

```text
sophisticated list
sophisticated info <device>
sophisticated items <device> ...
sophisticated count <device> <item-id>
sophisticated insert|extract|simulate-insert|simulate-extract ...
```

Use `sophisticated` with no/invalid arguments to display exact current usage.

### Storage Drawers

Individual drawers and controllers expose bounded logical storage queries. Compacting drawers use canonical resource accounting. Protected/native policy can deny or narrow access, and controller mutation fails closed when safe authority cannot be established.

Use the analogous `drawers` command family.

### Refined Storage

The dedicated bridge exposes bounded item/fluid/network/energy/capacity telemetry. Native crafting submit/status/cancel requires a currently authenticated online player and Refined Storage `AUTOCRAFTING` permission. Generic mutation is denied when authenticated native principal mapping is unavailable.

### Applied Energistics 2

The dedicated bridge exposes bounded cached item queries plus grid power, boot, channel, node,
inventory-key, and read-only crafting-CPU telemetry. It requires a directly adjacent exposed AE2 node. The first slice is
intentionally read-only: all insert/extract/crafting operations fail closed because TerminalCraft
does not yet have a caller-aware AE2 security principal bridge. Cached inventory is refreshed by
AE2 at most once per tick, and item/NBT variants are aggregated by item ID for the generic query
contract.

Craft operations use durable operation/job/native-task correlation to prevent duplicate submission across retries and restart ambiguity. Missing or ambiguous native state is not invented as success.

## 18. Server administration and configuration

TerminalCraft's common Forge config includes:

| Key | Default | Range / meaning |
|---|---:|---|
| `maxHistoryLines` | 200 | 20..2000 terminal scrollback lines |
| `showWelcomeBanner` | true | Show shell banner |
| `maxCommandLength` | 512 | 64..4096 characters |
| `maxCommandsPerSecond` | 20 | 1..200 player command packets/sec |
| `crtScanlines` | true | Client CRT overlay |
| `terminalSounds` | true | Terminal UI sounds |
| `crtTextColor` | `0x00FF66` | Default RGB text color |
| `maxDiskFiles` | 256 | 16..4096 soft documentation/library limit |
| `terminalChunkLoading` | false | Explicitly enable terminal chunk tickets |
| `maxTerminalChunkTicketsPerDimension` | 16 | 0..4096 |
| `maxTerminalChunkTicketsPerServer` | 64 | 0..16384 |

Chunk loading is disabled by default. Enabling it is an administrator policy decision, not a requirement for ordinary interactive use. Quotas are deterministic, and zero denies all TerminalCraft-owned terminal tickets at that scope.

Recommended production practice:

1. Keep chunk loading disabled until persistent automation needs it.
2. Start with conservative ticket limits.
3. Keep command and network quotas at defaults unless measurements justify changes.
4. Back up worlds before changing mod versions.
5. Test optional integrations in a staging instance using the exact intended versions.

## 19. Persistence, limits, and security

TerminalCraft is designed around these rules:

- server-authoritative command, GUI, device, storage, network, and world mutation;
- bounded command input, VFS data, queues, subscriptions, delivery state, diagnostics, and scheduler work;
- typed principal identity and central fail-closed authorization;
- current endpoint/capability reacquisition rather than trusting stale references;
- exact item/fluid conservation with replay and escrow handling;
- optional native security may narrow access but cannot create a TerminalCraft grant;
- unauthorized discovery and foreign owned state may be concealed;
- malformed/oversized persistence is isolated or bounded instead of trusted;
- no real Internet or LAN socket access through RedNet;
- no cross-dimensional RedNet transport unless a future explicit gateway is designed and approved.

No safeguard replaces backups. Modded Minecraft blocks and integrations can still change across versions, and development software can contain defects.

## 20. Troubleshooting

### `command not found`

Run `help`, verify spelling, and use `bash /absolute/path/script.sh`. TerminalCraft is not a full Linux environment and does not include arbitrary external programs.

### Script syntax error

Check block terminators: `if ... then ... fi`, `for ... do ... done`, and `while ... do ... done`. Keep early scripts multiline and simple. Remember numeric tests and arithmetic expansion are unavailable.

### A script loop stops

Loops are capped at 256 iterations. Chain execution and nesting are also bounded. Redesign continuous automation as scheduled, event-driven, or repeated bounded jobs rather than one endless shell command.

### `sleep` returns immediately

That is current behavior. `sleep` is a compatibility no-op, not a timer. Use server-rack scheduler/timer facilities where exposed rather than busy loops.

### Disk changes disappeared

Mount before access; run `disk sync`; then `umount` before ejecting. Confirm the Disk Drive is adjacent and still contains the same media.

### Modem says no adjacent modem

Place a modem directly adjacent to the host. Check whether the intended transport is wireless or wired. For wired routing, both endpoints must be wired and connected through valid enabled cable/router topology.

### No route or no service

Check, in order:

```sh
modem interfaces
modem channels
modem hostname
modem network
modem neighbors 32
modem hosts
modem services
modem route target-host
```

Confirm destination port is open, names are unique within scope, network assignments are compatible, router faces are enabled, chunks are loaded, and both endpoints are in the same dimension.

### Storage mutation is denied

Run `auth status`, `device list`, `storage info <device>`, then simulation. Verify the capability side, current block incarnation, native mod permissions, and whether the endpoint supports the requested method. Read-only telemetry does not imply mutation authority.

### A transfer returned partial/indeterminate

Do not create a new operation ID and blindly retry. Query relevant diagnostics/replay state, inspect escrow if authorized, and preserve the original operation ID for idempotent reconciliation.

### Monitor color line is truncated

Quote hexadecimal colors beginning with `#`:

```sh
monitor color any '#ffffff' '#000000'
```

## 21. Current shell differences from GNU Bash

This section prevents the most common incorrect assumptions.

| GNU Bash feature | TerminalCraft status |
|---|---|
| External OS commands/processes | Not available |
| Command substitution `$(...)` | Supported with depth/output bounds; backticks unavailable |
| Arithmetic `$((...))`, `let`, `arith` | Supported signed-integer subset; `((...))` command unavailable |
| Functions | Not available |
| Arrays | Not available |
| Globbing/filename expansion | Not a documented feature |
| Numeric `test -eq/-ne/-lt/-le/-gt/-ge` | Supported |
| `case`, `select`, `until` | Not available |
| Background jobs `&` | Not available |
| Signals/process control | Not available |
| `exit` terminates a script | Supported with optional status |
| `break` / `continue` | Supported for the nearest bounded loop |
| Real sleeping through `sleep` | Not available; current command is a no-op |
| Single quotes suppress expansion | Supported |
| Full escape/`printf` formatting | Not available |
| Unlimited loops/scripts | No; deliberately bounded |
| Pipes/redirection | Supported as bounded sequential text flow |
| Variables and `$1..$9` | Supported |
| `if`, `for`, `while` | Supported bounded subset |
| `;`, `&&`, `||` | Supported |
| VFS scripts and shebang | Supported |

Design scripts around explicit commands, exit codes, literal bounded loops, files, RedNet messages, event subscriptions, and server-rack jobs. That style is both reliable in the current release and aligned with TerminalCraft's server-safe automation model.

## 22. SCADA supervisory control

TerminalCraft's SCADA system combines existing devices, sensors, PLCs, RedNet, server racks, and
monitors into one server-authoritative process database. It provides live named tags, measurement
quality, persistent history, alarm lifecycle management, automatically refreshed HMI dashboards,
role-based operator control, and an audit trail.

SCADA does not replace PLC control logic. A PLC should continue to run time-sensitive interlocks and
safe local control. SCADA supervises many controllers and machines: it presents their state,
records trends, alerts operators, and issues authorized high-level commands.

### 22.1 Initialize the plant

The first authenticated player to initialize a new world's SCADA database becomes its administrator:

```sh
scada init
scada status
```

Initialization can happen only once. Later access is assigned explicitly with SCADA roles. Device
permissions and PLC ownership remain additional mandatory checks; a SCADA role never bypasses the
underlying device's security policy.

### 22.2 Process tags

A tag is a durable human name bound to a typed Device API method:

```text
scada tag add <name> <device> <readMethod> <path|-> <unit|-> <interval> <stale> <writeMethod|@commandMethod|-> [typedArgs...]
```

- Names are hierarchical, such as `factory.line1.boiler.temperature`.
- The device may be a stable UUID or RedNet DNS name.
- The value path uses slash-separated map keys or list indexes. Use `-` when the method returns a
  scalar directly. Dots inside a Device API map key remain ordinary characters.
- Interval and stale time are server ticks. Stale time must be at least the sample interval.
- Use `-` for the write method to make a read-only tag.
- Prefix a write method with `@` when it is a no-value command such as `@control.run`. Invoke that
  tag with `scada command`; static arguments are still passed, but no operator value is appended.
- Static method arguments and write values are typed as `n:12.5`, `b:true`, or `s:text`.

Bind a Sensor Array channel named `tank_level`:

```sh
device dns add <sensor-uuid> line1-sensors
scada tag add factory.line1.tank.level line1-sensors sensor.read value % 20 100 - s:tank_level
scada tags factory.line1
scada read factory.line1.tank.level
scada history factory.line1.tank.level 32
```

The sensor's `sensor.read` result is a map, so `value` selects its scalar measurement. SCADA also
imports the result's `quality` and `detail` fields automatically.

A writable tag appends the requested operator value after its configured static arguments. This is
useful for paired methods such as a terminal's `redstone.input` and `redstone.set`:

```sh
scada tag add factory.line1.pump.command pump-io redstone.input - level 5 20 redstone.set s:north
scada write factory.line1.pump.command n:15
```

The caller needs the SCADA operator role and the underlying device must independently authorize
`redstone.set`. Every accepted or rejected write is recorded in the audit log.

PLC command methods do not take a setpoint value. Bind those with `@` and invoke them explicitly:

```sh
scada tag add factory.line1.plc.start line1-plc status state - 20 100 @control.run
scada command factory.line1.plc.start
```

Here the tag continues to acquire the PLC's `status`/`state` value, while the command invokes
`control.run`. `scada write` is rejected for command tags, and `scada command` is rejected for
ordinary writable tags, preventing an ambiguous operator action.

### 22.3 Quality and historian

Each live value carries its server tick timestamp, last-good tick, unit, detail, and quality:

| Quality | Meaning |
|---|---|
| `good` | The method returned a usable scalar |
| `stale` | The last good update exceeded the configured stale time |
| `offline` | The device is absent, unloaded, or offline |
| `access_denied` | The acquisition identity could not read the method |
| `bad_response` | The call failed or the selected value was not scalar |
| `config_error` | The method, arguments, or path are invalid |

When quality degrades, SCADA retains the last known scalar but marks it unusable. It never silently
presents an old value as good. The historian stores both value and quality, retains up to 2,048
points per tag, and enforces a global 65,536-point cap. Shell and device queries return no more than
256 points at once.

### 22.4 Alarm lifecycle

Alarm rules support `above`, `below`, `equal`, `not_equal`, and `bad_quality`, with `info`, `warning`,
`high`, or `critical` severity. Numeric above/below rules use deadband to prevent threshold chatter:

```sh
scada alarm add tank-high factory.line1.tank.level above n:85 critical 3 'Tank above safe level'
scada alarm add tank-signal-bad factory.line1.tank.level bad_quality - high 0 'Tank telemetry unavailable'
scada alarm list
scada alarm ack tank-high
scada alarm shelve tank-high 1200
scada alarm shelve tank-high 0
```

An alarm moves through `normal`, `active`, `acknowledged`, and `shelved` states. Acknowledgment does
not clear the process condition. The alarm clears only when its rule becomes false; shelving hides
the presentation until its bounded timeout but evaluation continues. Activation, acknowledgment,
shelving, clearing, and removal are audited.

### 22.5 Monitor HMI dashboards

TerminalCraft provides two HMI levels. A legacy dashboard is a fast automatic tag list; an advanced
HMI is a persistent, multi-page, full-color layout with live value, gauge, trend, alarm, text,
button, and page-link widgets.

#### Legacy automatic dashboard

Assign a DNS name to a monitor or use its UUID, then create an automatically refreshed dashboard:

```sh
device dns add <monitor-uuid> line1-overview
scada dashboard add line1-overview factory.line1 20 'LINE 1 OVERVIEW'
scada dashboard list
```

The dashboard uses the full connected monitor wall exposed by the monitor device. It displays SCADA
health, active-alarm count, matching tag values, units, and quality. `!` marks a tag with a non-normal
alarm. The refresh period is bounded from 10 to 1,200 ticks, dashboard writes are server-side, and
only a bounded number of dashboards refresh per tick.

Remove a binding without deleting the monitor or tags:

```sh
scada dashboard remove line1-overview
```

#### Advanced multi-page HMI

Create an advanced HMI on a monitor wall, add its pages, and place widgets on a normalized 12 by 12
grid. The same layout scales from one monitor to a connected wall without changing coordinates:

```sh
device dns add <monitor-uuid> line1-hmi
scada hmi create line1 line1-hmi 10 'LINE 1 CONTROL'
scada hmi page add line1 controls 'Controls'

scada hmi widget add line1 overview heading text 0 0 12 1 - - 'LINE 1 PROCESS'
scada hmi widget add line1 overview tank_value value 0 1 4 3 factory.line1.tank.level - 'Tank Level'
scada hmi widget add line1 overview tank_gauge gauge 4 1 8 3 factory.line1.tank.level 0:100 'Tank Level'
scada hmi widget add line1 overview tank_trend trend 0 4 8 4 factory.line1.tank.level - 'Recent Trend'
scada hmi widget add line1 overview alarms alarms 8 4 4 6 factory.line1 - 'Active Alarms'
scada hmi widget add line1 overview controls page_link 4 9 4 2 controls - 'Controls'

scada hmi widget add line1 controls pump_on button 0 2 5 3 factory.line1.pump.command n:15 'PUMP ON'
scada hmi widget add line1 controls pump_off button 7 2 5 3 factory.line1.pump.command n:0 'PUMP OFF'
scada hmi widget add line1 controls overview page_link 4 9 4 2 overview - 'Overview'
```

Widget arguments depend on type:

| Type | Source | Argument |
|---|---|---|
| `text` | `-` | `-` |
| `value` | readable tag | `-` |
| `gauge` | numeric tag | `minimum:maximum` |
| `trend` | numeric tag with history | `-` |
| `alarms` | tag hierarchy prefix or `-` for all | `-` |
| `button` | writable or command tag | typed value, or `command` |
| `page_link` | existing page name | `-` |

The `x y width height` fields must fit within the 12 by 12 grid. Pages retain widget ordering and
can hold up to 32 widgets; each dashboard holds up to eight pages. Use these commands to inspect or
change a definition without opening the graphical editor:

```sh
scada hmi list
scada hmi page list line1
scada hmi widget list line1 overview
scada hmi page select line1 controls
scada hmi widget remove line1 overview tank_trend
scada hmi remove line1
```

Run `hmi` in a terminal to open the live graphical viewer. It repaints automatically while open:

| Key or input | Operator view |
|---|---|
| Up / Down | Change dashboard |
| Left / Right | Change page |
| Tab / Shift+Tab | Select a button or page link |
| Enter / mouse click | Activate the selected control |
| F5 or R | Refresh immediately |
| Escape | Close the HMI |

An engineer or administrator can press F2 to enter the graphical designer. Click or Tab selects a
widget, arrow keys move it, Shift+Arrow resizes it, A adds a widget, E edits the selected widget,
and Delete twice removes it. Add/edit prompts use the same compact widget specification shown after
`scada hmi widget add`: `id type x y width height source|- argument|- label`.

Physical monitor walls are interactive too. Clicking a page link changes the displayed page;
clicking a button submits control as the touching player. A viewer can navigate, an operator can use
authorized controls, and only engineers or administrators can change layouts. Underlying device
permissions and PLC ownership still apply. Every page selection, layout change, and accepted or
rejected control is server-authoritative and audited.

Advanced frames carry sixteen-color foreground/background cells and are applied atomically to a
monitor wall, avoiding partially redrawn screens. TerminalCraft renders up to 320 by 32 cells and
falls back to a plain-text frame for a compatible monitor endpoint that lacks atomic color frames.

### 22.6 Roles and audit

Roles are cumulative:

| Role | Capabilities |
|---|---|
| `viewer` | Read tags, history, alarms, dashboards, roles, and audit |
| `operator` | Viewer access plus control writes and alarm acknowledge/shelve |
| `engineer` | Operator access plus tag, alarm, and dashboard configuration |
| `admin` | Engineer access plus role administration |

Administrators grant access by authenticated player UUID. Display names are diagnostic only:

```sh
scada role grant <player-uuid> operator Alex
scada role list
scada audit 64
scada role revoke <player-uuid> Alex
```

The final administrator cannot be removed, preventing an accidental permanent lockout. The audit
log is bounded to the newest 1,024 entries.

### 22.7 Device API and Control Center

SCADA registers as the virtual device **TerminalCraft SCADA**. It appears in `device list` and the
graphical Control Center with these methods:

- `status`, `tags.list`, `tag.read`, `history.get`, and `alarms.list`;
- `hmi.list`, `hmi.page.select`, and `hmi.widget.activate` for scripted HMI navigation and controls;
- `alarm.ack` for operators;
- `tag.write` for writable tags, using the same typed value text;
- `tag.command` for no-value command tags.

It emits `tag_changed` and `alarm_changed` Device API events. Event subscriptions can therefore wake
server-rack jobs or scripts without polling the entire plant.

### 22.8 RedNet SCADA gateway

Place exactly one Server Rack beside a modem, open a channel, and publish a read-only typed service:

```sh
modem open 42
modem scada add plant-scada 42
```

Remote terminals discover it with `modem services` and request bounded status, tags, one live value,
history, or alarms. The explicit request form avoids ambiguous optional arguments:

```sh
modem open 41
modem scada request plant-scada status - 32 41
modem scada request plant-scada tags factory.line1 32 41
modem scada request plant-scada read factory.line1.tank.level 1 41
modem scada request plant-scada history factory.line1.tank.level 32 41
modem scada request plant-scada alarms - 32 41
modem recv 8
```

The gateway intentionally exposes telemetry only. RedNet packets cannot remotely initialize SCADA,
change configuration, acknowledge alarms, assign roles, or control equipment. Use an authenticated
terminal/Device API caller for those operations and use logical RedNet networks or physical routing
to isolate telemetry scopes. Scalar text and engineering units percent-escape protocol separators
(`%`, `|`, `,`, `:`, `=`, `[` and `]`) in responses; receivers should percent-decode displayed
fields after splitting the response structure.

### 22.9 Runtime limits and safe design

The SCADA runtime scans at most 16 due tags per server tick, refreshes at most four due legacy or
advanced dashboards per tick, and bounds tags, alarms, dashboards, pages, widgets, roles, history,
responses, and audit records. All
acquisition and control run on the logical server. A client never supplies an authoritative role,
principal, value timestamp, alarm state, or historian record.

Prefer event-capable devices and reasonable scan intervals. Use PLCs for fast interlocks, local
fail-safe behavior, and outputs that must remain deterministic during an HMI or network outage. Use
SCADA for plant-wide visibility, history, alarm coordination, operator intent, and supervisory
setpoints.

---

## Quick-reference starter sheet

```sh
# Files
pwd
ls -la
mkdir -p /home/player/bin
write /home/player/bin/hello.sh 'echo Hello from TerminalCraft'
bash /home/player/bin/hello.sh

# Disk
mount
ls /disk
disk sync
umount

# Monitor
monitor title any 'Control'
monitor set any 0 'Ready'
monitor color any '#00ff66' '#001108'

# Redstone
redstone get back
redstone set front 15
wire set back 3 15

# Network
modem hostname node-a
modem network workshop
modem open 42
modem hosts
modem sendto node-b 42 41 'hello'
modem recv 8

# Devices/storage
auth status
device list
storage list
storage query 0 --limit 16

# Jobs/events
server list

# SCADA
scada init
scada status
scada tags
scada alarm list
scada hmi list
hmi
scada audit 16
device events 16
```

For source, releases, and issue reporting, visit <https://github.com/HonorboundInnovation/TerminalCraft>.
