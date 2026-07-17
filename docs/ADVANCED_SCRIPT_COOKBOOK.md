# Advanced TerminalCraft Script Cookbook

This cookbook accompanies [The TerminalCraft Guide](TERMINALCRAFT_GUIDE.md) and the ready-to-copy files in [`examples/scripts/advanced`](../examples/scripts/advanced).

These are operational examples, not generic GNU Bash scripts. They deliberately use TerminalCraft's bounded shell subset and current server-authoritative commands.

## Installing the examples in-game

Files in the GitHub repository are not automatically inserted into an existing computer's virtual filesystem. To use one:

1. Open the desired file on GitHub.
2. Create `/home/player/bin` in TerminalCraft:

   ```sh
   mkdir -p /home/player/bin
   ```

3. Open a destination file with the built-in editor:

   ```sh
   edit /home/player/bin/safe-turtle-tunnel.sh
   ```

4. Enter the script, save with `:wq`, and run it explicitly:

   ```sh
   bash /home/player/bin/safe-turtle-tunnel.sh
   ```

A prepared floppy can also carry scripts between computers. The deployment example assumes selected advanced scripts already exist under `/home/player/bin` and copies them to `/disk/toolkit`.

## Safety rules used by these examples

- No command substitution, arithmetic expansion, functions, arrays, globbing, or external operating-system programs.
- No reliance on `exit` to abort a script; current `exit` is not a control-flow terminator.
- Required-argument checks wrap the operation in `if`/`else`.
- Destructive or mutating storage work simulates first where the API supports simulation.
- Exact transfers and crafting submissions require caller-generated operation UUIDs. Preserve an operation UUID for retries of the same logical request; never reuse it for a different request.
- A successful storage command can still report a partial result. Read the result, especially JSON output.
- RedNet probes are asynchronous. The script records the returned message UUID, but TerminalCraft has no command substitution to automatically feed it into `modem delivery`.
- Event subscriptions and jobs return UUIDs that must be recorded and used in later commands.
- Event queues, network queues, scheduler work, scripts, loops, and diagnostics are bounded.

## Script index

| Script | Purpose | Arguments / important output |
|---|---|---|
| `safe-turtle-tunnel.sh` | Excavates up to 16 tunnel steps, records every stage, and disables later iterations after the first failure | Writes `/home/player/tunnel-run.log` |
| `storage-audit-json.sh` | Captures bounded storage metadata and one aggregate query page in JSON | `<device-selector> [namespace]` |
| `safe-storage-extract.sh` | Simulates and then performs one extraction, preserving both structured results | `<device> <item-id> <count>` |
| `exact-item-transfer.sh` | Runs/reconciles an idempotent exact item transfer | `<operation-uuid> <source-uuid> <destination-uuid> <item-id> <count>` |
| `exact-fluid-transfer.sh` | Runs/reconciles an idempotent exact fluid transfer in millibuckets | `<operation-uuid> <source-uuid> <destination-uuid> <fluid-id> <amount-mB>` |
| `crafting-submit.sh` | Submits/replays a generic crafting job and records its returned job UUID | `<device-uuid> <operation-uuid> <resource-id> <amount>` |
| `rednet-health-probe.sh` | Collects interfaces, neighbors, hosts, services, route state, and a reliable probe | `<host> <port> <reply-port>` |
| `commission-network-node.sh` | Configures hostname, logical network, ports, status service, label, and monitor | `<hostname> <network-name>` |
| `event-watch-setup.sh` | Creates bounded subscriptions for disk, monitor, and modem events | Records subscription UUIDs in `/home/player/events/subscriptions.txt` |
| `rack-dashboard-jobs.sh` | Queues a small caller-owned monitor job batch and records scheduler state | Records returned job UUIDs in `/home/player/jobs/last-batch.txt` |
| `deploy-toolkit-to-floppy.sh` | Copies an explicit curated toolkit to mounted removable media with a manifest | Requires an adjacent drive/floppy and source files under `/home/player/bin` |
| `incident-snapshot.sh` | Captures authority, scheduler, event, device, storage, and network diagnostics | Writes `/home/player/audits/incident.txt` |

## 1. Failure-aware turtle tunneling

Run:

```sh
bash /home/player/bin/safe-turtle-tunnel.sh
```

The script uses an `ACTIVE` flag because TerminalCraft does not currently provide Bash `break`, arithmetic counters, or a terminating `exit`. Every loop iteration checks the flag. A failed dig, movement, or ceiling operation writes a specific failure stage and prevents later iterations from changing the world.

Review the log:

```sh
cat /home/player/tunnel-run.log
```

This is intentionally bounded to 16 literal steps. Inspect the area first and test destructive automation away from valuable builds.

## 2. Storage audit and guarded extraction

Discover a selector:

```sh
storage list
```

Audit the first storage device, limited to the `minecraft` namespace:

```sh
bash /home/player/bin/storage-audit-json.sh 0 minecraft
```

Safely request 64 cobblestone:

```sh
bash /home/player/bin/safe-storage-extract.sh 0 minecraft:cobblestone 64
```

The extraction script only executes after simulation succeeds. Simulation is not a reservation: another actor can change inventory state between simulation and execution. Always inspect `requested`, `simulated`, `executed`, and completion/partial fields in:

```sh
cat /home/player/operations/last-extract.txt
```

## 3. Exact item and fluid transfers

First discover endpoint UUIDs:

```sh
device list
```

Generate a fresh UUID outside TerminalCraft or use an administrator-provided operation UUID. Submit an item transfer:

```sh
bash /home/player/bin/exact-item-transfer.sh 11111111-1111-4111-8111-111111111111 aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb minecraft:iron_ingot 64
```

Submit 1000 mB of water:

```sh
bash /home/player/bin/exact-fluid-transfer.sh 22222222-2222-4222-8222-222222222222 aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb minecraft:water 1000
```

If a result is uncertain, repeat the exact same request with the exact same operation UUID. Do not invent a new operation UUID merely because a timeout or adapter error occurred. If conservation recovery is required and the caller is authorized:

```sh
device escrow list
device fluid-escrow list
```

## 4. Generic crafting submission

Find a device whose `device info` output exposes `crafting_service` and `crafting.submit`:

```sh
device list
device info <device-uuid>
```

Submit a request:

```sh
bash /home/player/bin/crafting-submit.sh <device-uuid> <fresh-operation-uuid> minecraft:iron_ingot 32
```

The output contains a stable TerminalCraft job UUID. Because command substitution is unavailable, copy that UUID into later commands:

```sh
device call <device-uuid> crafting.status <job-uuid>
device call <device-uuid> crafting.cancel <job-uuid>
```

Reusing the operation UUID with the same request performs idempotent replay/reconciliation. Reusing it for different output or amount is a conflict.

## 5. RedNet commissioning and health probes

Commission a node with an adjacent modem and monitor:

```sh
bash /home/player/bin/commission-network-node.sh workshop-a factory-lan
```

The script configures:

- hostname `workshop-a`;
- logical network `factory-lan`;
- service port 80;
- reply port 81;
- named `status` service;
- computer label and monitor status.

Probe it from another reachable node:

```sh
bash /home/player/bin/rednet-health-probe.sh workshop-a 80 81
```

The probe output includes a message UUID. Later, query retained delivery state with:

```sh
modem delivery <message-uuid>
```

A transport acknowledgement proves correlated transport receipt, not completion of an application-specific task. There is no implicit cross-dimensional gateway, and both endpoints of a wired route must be wired modems on valid loaded topology.

## 6. Event watches

Create subscriptions:

```sh
bash /home/player/bin/event-watch-setup.sh
```

The sample subscribes to currently produced event types:

- `media_changed` from disk drives;
- `monitor_resize`, `output_changed`, and `touch` from monitors;
- `message_received` from modems.

The wildcard source still respects caller discovery authority. Copy each returned subscription UUID and use:

```sh
device events poll <subscription-uuid> 32
device events diagnostics <subscription-uuid>
device events unsubscribe <subscription-uuid>
```

Subscriptions are caller-owned, bounded, best-effort, and do not survive logical-server restart.

## 7. Server-rack job batch

With a local server-rack service and adjacent monitor:

```sh
bash /home/player/bin/rack-dashboard-jobs.sh
```

Review submitted IDs and aggregate scheduler state:

```sh
cat /home/player/jobs/last-batch.txt
server list
server scheduler
```

Then inspect or cancel one caller-owned job:

```sh
server status <job-uuid>
server cancel <job-uuid>
```

Foreign jobs are concealed. Submission success does not bypass current device, world, or authorization checks when a job runs.

## 8. Portable toolkit deployment

After installing selected advanced examples under `/home/player/bin`, insert a floppy into an adjacent drive and run:

```sh
bash /home/player/bin/deploy-toolkit-to-floppy.sh
```

The script:

1. mounts media;
2. creates `/disk/toolkit`;
3. copies three explicit source files when present;
4. records installed/missing entries in `MANIFEST.txt`;
5. labels and synchronizes the floppy;
6. prints the manifest;
7. unmounts.

It uses explicit filenames because globbing and recursive `cp` are not part of the documented shell.

## 9. Incident snapshot

Run:

```sh
bash /home/player/bin/incident-snapshot.sh
```

The snapshot continues through unavailable optional subsystems so one missing modem, rack, or storage endpoint does not prevent later diagnostics. The output is stored at:

```sh
cat /home/player/audits/incident.txt
```

The report intentionally uses bounded listings. It contains operational metadata and aggregate diagnostics, not plaintext credentials.

## Designing additional advanced scripts

Use these patterns:

```sh
# Required arguments: wrap all real work in else because exit does not abort.
if [ -z "$1" ]; then
  echo 'usage: script.sh <argument>'
else
  echo "using $1"
fi

# Failure-aware state without break/arithmetic.
ACTIVE=yes
for step in 1 2 3 4; do
  if [ "$ACTIVE" = yes ]; then
    if some-command; then
      echo step=$step,status=ok
    else
      ACTIVE=no
    fi
  fi
done

# Capture command output in a file instead of $(command).
device list > /home/player/audits/devices.txt

# Copy VFS content without cp.
cat source.txt | write destination.txt
```

Avoid scripts that assume GNU Bash-only behavior such as `$(...)`, `$((...))`, functions, arrays, `case`, `break`, `continue`, process substitution, background jobs, heredocs, arbitrary Linux commands, or real waiting through `sleep`.
