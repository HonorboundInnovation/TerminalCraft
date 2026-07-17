# The TerminalCraft Guide

**The comprehensive player, administrator, shell, scripting, networking, and automation manual for TerminalCraft 1.0.0**

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

---

## 1. Requirements and installation

| Component | Required version |
|---|---|
| Minecraft | 1.20.1 |
| Minecraft Forge | 47.3.0 or compatible Forge 47.x |
| Java | 17 |
| TerminalCraft | Both client and server |

Install Forge, place the TerminalCraft JAR in the instance's `mods` directory, and launch the matching Forge profile. On multiplayer servers, install the same TerminalCraft build on the server and every connecting client.

Build from source with:

```bash
git clone https://github.com/HonorboundInnovation/TerminalCraft.git
cd TerminalCraft
./gradlew clean build
```

The output is `build/libs/terminalcraft-1.0.0.jar`.

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

## 3. Blocks and items

### Terminal

A placed, persistent Bash-style computer. Use it as the normal workstation for scripts, adjacent devices, storage, networking, redstone, disks, and monitors.

### Pocket Terminal

A stack-size-one handheld computer opened by using the item. Its shell state, label, stable modem identity, and open channels are stored on the item. It includes a built-in wireless modem with a default 64-block range and up to 128 open channels, and rebinding follows the carrying player. It is not a placed world host and therefore cannot provide every adjacency-based operation available to a placed terminal.

### Turtle

A mobile computer with its own shell and VFS. It can move, rotate, inspect, dig, and place. Turtle commands fail rather than silently claiming success when movement or world interaction cannot be completed.

### Monitor

A server-owned text display with 20 rows of 40 characters per tile. Adjacent computers can write text, set individual zero-based rows, set a title and color palette, clear it, or read its current lines. Up to 8 by 6 equally facing adjacent tiles form a wall (maximum 48 tiles). Shift-right-click displays content in chat; normal right-click publishes a touch event. Monitor resizing/rebuild behavior is exposed through the event system.

### Disk Drive and Floppy Disk

Place a Disk Drive adjacent to a computer, insert a Floppy Disk, and mount it at `/disk`. Floppies carry a portable bounded filesystem and label.

### Modem

Provides RedNet communication with a default 64-block wireless range and at most 128 open channels. A modem can operate wirelessly or as an endpoint on physical Network Cable. Shift-right-click toggles its wireless/wired mode; normal right-click reports status. A placed computer must have an adjacent modem for the `modem` command family.

### Network Cable

Carries wired RedNet traffic through physical topology. Breaking a required segment partitions the route immediately. It does not carry bundled-control signals.

### Network Router

Connects and diagnoses wired network segments. Each face has persistent enabled state and logical-network assignment. Shift-right-click a face with a redstone torch to toggle it, with a renamed item to assign that item's custom name as the network, or empty-handed to restore automatic selection. Normal right-click shows bounded topology diagnostics. A router does not create a cross-dimensional gateway.

### Red Alloy Wire

A compact vanilla-redstone surface wire. It is separate from data networking and bundled control.

### Bundled Control Cable

Carries 16 independent channels numbered `0` through `15`. Values are strengths from `0` through `15`. Channel 0 bridges vanilla redstone behavior; data-network traffic remains a separate system.

### Server Rack, Server Blade, and Router Blade

Server racks host bounded scheduled work. Up to three equally facing rack blocks form a six-bay cabinet (two bays per block). Right-click a bay with a blade to install it; shift-right-click an occupied bay empty-handed to remove its module. A Server Blade supplies shell/job service and a Router Blade supplies internal routing. Shell access uses `server` (alias `jobs`) from a computer with an available local rack service.

### Refined Storage Bridge

A dedicated attachment point for optional Refined Storage telemetry and crafting integration. It deliberately fails closed when native network identity or permissions are unavailable.

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

### Network Router

```text
CRC
RTR
CRC
```

`C` Network Cable, `T` comparator.

### Red Alloy Wire (12)

```text
RRR
ICI
RRR
```

`I` iron nugget, `C` copper ingot.

### Bundled Control Cable (6)

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

### Refined Storage Bridge

```text
IRI
RCR
IRI
```

`C` comparator.

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
| `redstone`, `rs` | Read/set vanilla redstone sides |
| `wire`, `bundled` | Read/set bundled channels |
| `turtle`, `tu` | Turtle movement and interaction |
| `monitor` | Adjacent monitor control |
| `modem`, `rednet` | RedNet network operations |
| `mount`, `umount`, `unmount`, `disk` | Floppy operations |
| `device`, `devices` | Unified device API |
| `storage`, `inventory` | Generic storage API |
| `sophisticated` | Sophisticated Storage/Backpacks adapter |
| `drawers` | Storage Drawers adapter |
| `server`, `jobs` | Server-rack jobs |
| `auth`, `authorization` | Current caller authorization diagnostics |

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

Variable expansion is intentionally simple and occurs before tokenization. Quote text containing spaces. There is no command substitution (`$(...)` or backticks), arrays, arithmetic expansion, or `${name:-default}` syntax.

### Quoting

Single and double quotes group whitespace. The current shell performs variable expansion before lexical tokenization, including text written inside single quotes. Do not rely on GNU Bash's rule that single quotes suppress expansion.

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
test "$VALUE"          # nonempty string
[ -f script.sh ]
```

Numeric comparisons (`-eq`, `-lt`, and friends), compound tests (`-a`, `-o`), regexes, and glob tests are not implemented. The current `exit` command also does not abort a script; use `&&`, `||`, and `if` to guard later work.

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

Because there is no arithmetic expansion, use `for` for most fixed-count automation. Every loop is bounded to prevent runaway scripts.

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
```

Colors accept `#RRGGBB`, `0xRRGGBB`, or decimal `0..16777215`. Quote `#RRGGBB` values in scripts so `#` is not parsed as a comment.

Examples:

```sh
monitor color right '#ffaa00' '#101010'
monitor title right 'Smelter'
monitor set right 0 'State: ready'
monitor set right 1 'Input: check chest'
monitor read right
```

`monitor write` appends/writes according to the monitor host behavior; `monitor set` is preferable for stable dashboards.

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

A successful read only says that the side was valid and readable; script conditions cannot directly compare command output because command substitution is unavailable.

### Bundled cable

```text
wire get <side|any> <channel 0-15>
wire output <side|any> <channel 0-15>
wire set <side|any> <channel 0-15> <strength 0-15>
```

Examples:

```sh
wire get back 3
wire set back 3 15
wire output back 3
```

Bundled components are bounded and resolve per-channel strengths. Bundled control, red-alloy signals, and RedNet data are distinct systems.

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
- Hostnames and service names are canonical aliases, not authority credentials.
- Logical network names can isolate or connect configured topology.
- Wireless reachability and wired physical topology are different transports.
- Every logical server and dimension has an isolated runtime scope.
- There is no implicit cross-dimensional gateway.

### 14.2 Core commands

```text
modem open|listen <channel>
modem close|unlisten <channel>
modem channels
modem hostname [name|clear]
modem network [name|clear]
modem interfaces
modem neighbors [max]
modem route <host>
modem ping <host>
modem hosts
modem service [list|add <name> <channel>|remove <name>]
modem services
modem call <service> [replyChannel] <message>
modem send <channel> [replyChannel] <message>
modem sendto <host> <channel> [replyChannel] <message>
modem probe <host> <port> <replyChannel> <message>
modem delivery <messageId>
modem recv [max]
```

List limits are bounded. `neighbors [max]` accepts `1..128`.

### 14.3 Wireless setup

On receiver:

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
modem ping receiver
modem sendto receiver 42 41 'hello receiver'
```

On receiver:

```sh
modem recv 8
```

### 14.4 Wired setup

1. Place a modem beside each computer.
2. Shift-right-click each modem into wired mode.
3. Connect modems with Network Cable and, where desired, routers.
4. Ensure required router faces/interfaces are enabled.
5. Assign compatible logical network names or explicit router transitions.
6. Use `modem interfaces`, `neighbors`, and `route <host>` to diagnose.

A wireless modem touching cable is not a wired endpoint. Both route endpoints must be live wired modems.

### 14.5 Named services

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

### 14.6 Reliable probes

```sh
modem probe receiver 42 41 'health-check'
```

The command prints a stable message/correlation UUID and initial state. Query it with:

```sh
modem delivery <message-uuid>
```

Reliable delivery is asynchronous, bounded, and opt-in. It uses acknowledgement, timeout, retry, correlation, and duplicate-suppression logic. Delivery records are transient; restart does not preserve them. A transport acknowledgement does not mean an application performed a requested business action.

### 14.7 Network limits and diagnostics

Queues, queue bytes, sender message/byte rates, duplicate state, acknowledgement controls, and retained delivery records are bounded. Admission failure is reported instead of silently pretending to send. RedNet does not use the machine's real network stack.

## 15. Devices, storage, fluids, and energy

### 15.1 Device discovery

```sh
device list
device info <device-uuid>
```

`device list` prints UUID, type, status, address, and display name for discoverable devices. Only server-authorized adjacent/current endpoints are exposed.

### 15.2 Calling methods

```sh
device call <device-uuid> <method> [arguments...]
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
| Command substitution `$(...)` / backticks | Not available |
| Arithmetic `$((...))`, `let`, `((...))` | Not available |
| Functions | Not available |
| Arrays | Not available |
| Globbing/filename expansion | Not a documented feature |
| Numeric `test -eq/-lt/...` | Not available |
| `case`, `select`, `until` | Not available |
| Background jobs `&` | Not available |
| Signals/process control | Not available |
| `exit` terminates a script | No; current `exit` prints `logout` and succeeds |
| Real sleeping through `sleep` | Not available; current command is a no-op |
| Single quotes suppress expansion | **No**; expansion occurs before tokenization |
| Full escape/`printf` formatting | Not available |
| Unlimited loops/scripts | No; deliberately bounded |
| Pipes/redirection | Supported as bounded sequential text flow |
| Variables and `$1..$9` | Supported |
| `if`, `for`, `while` | Supported bounded subset |
| `;`, `&&`, `||` | Supported |
| VFS scripts and shebang | Supported |

Design scripts around explicit commands, exit codes, literal bounded loops, files, RedNet messages, event subscriptions, and server-rack jobs. That style is both reliable in the current release and aligned with TerminalCraft's server-safe automation model.

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
device events 16
```

For source, releases, and issue reporting, visit <https://github.com/HonorboundInnovation/TerminalCraft>.
