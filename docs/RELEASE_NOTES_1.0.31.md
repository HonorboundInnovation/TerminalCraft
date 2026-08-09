# TerminalCraft 1.0.31

TerminalCraft 1.0.31 adds a portable electronic storage system for items and fluids.

## Network Access Storage

The **Network Access Storage** block accepts up to eight Solid-State Drives. Drives have three tiers:

| Tier | Item capacity | Fluid capacity | Resource entries |
|---|---:|---:|---:|
| Basic | 16,384 items | 64,000 mB | 16 items / 4 fluids |
| Advanced | 65,536 items | 256,000 mB | 64 items / 8 fluids |
| Quantum | 262,144 items | 1,024,000 mB | 128 items / 16 fluids |

Drive contents are stored on the SSD item itself. Removing a drive is safe, and the drive can be
installed in another NAS without losing its data.

Items can be inserted by shift-right-clicking the NAS with an item stack. Filled fluid containers can
be emptied into the NAS. Adjacent terminals can use the existing `storage` device commands, and the
NAS exposes Forge item/fluid capabilities for compatible automation.

## Materializer

Place a Materializer next to a NAS. Empty-hand interaction extracts the first available item; holding
an item requests that item; holding a compatible fluid container fills it from stored fluids. The
Materializer exposes read-only item and fluid output capabilities, so pipes and machines can pull
contents without inserting into the storage network.

## Verification

- `./gradlew clean check --no-daemon`
- `./gradlew build --no-daemon`
- JSON/resource validation

## Verification artifact

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.31.jar`
- Size: `1,441,089` bytes
- SHA-256: `e3b7ad6d04596cbef813c5c8d29b00cd31b6957488e7613b89f974475c69bd4e`
