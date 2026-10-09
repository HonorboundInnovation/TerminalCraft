# TerminalCraft — 1.0.64 through 1.0.102

This release brings a broad round of computer, automation, storage, networking, and interface
improvements.

## 1.0.64–1.0.70 — cable, signal, and network foundations

- Stabilized startup, chunk-load network discovery, block-entity signal notifications, and legacy
  wire save migration.
- Corrected PLC output direction, terminal and wire redstone boundary queries, repeater fixtures,
  cable support fixtures, modem queue cleanup, and monitor descriptor synchronization.
- Restored solid neutral cable tint assets and aligned cable selection, rendering, routing, bundled
  channels, RedNet topology, and device attachments with the documented surface geometry.
- Added and expanded coverage for Red Alloy wires, Network Cables, bundled channels, capacitors,
  monitors, PLCs, sensors, and optional bridge profiles.

## 1.0.71–1.0.83 — computers, turtles, and filesystem safety

- Made floppy unmounts retain mounted files after absent-media or failed-write errors, then added
  media identity, content revisions, stale-writer protection, retry behavior, and explicit discard
  recovery.
- Added safe turtle movement, fuel accounting, digging and placement validation, live shell transfer,
  movement-aware command and inventory screens, sixteen inventory slots, refueling, item collection,
  orientation-aware placement, hopper interaction, and saved-computer pickup recovery.
- Added bounded display snapshots and filename completion for large filesystems, editor limits and
  final-newline preservation, capacity checks, rollback on rejected writes, and chunked NBT storage
  for large ASCII and Unicode files.
- Added missing survival drops and pickaxe tags for registered blocks, fixed NAS quantity
  duplication, corrected SSD integer stack persistence, and enforced storage capacity limits.
- Network protocol advanced from 7 to 8 during the pocket-storage work; matching client and server
  versions are required.

## 1.0.84–1.0.94 — storage lifecycle, recovery, automation, and integrations

- Completed survival drops for sensors, NAS, and Materializer, then hardened NAS, Materializer, and
  SSD loading, extraction, fluid identity, capacity, and malformed-data handling.
- Moved pocket-computer files into world-backed records with revision checks, verified writes,
  export/import, nested inventory migration, damaged-pocket refusal, original-snapshot recovery,
  readable-file recovery, and bounded diagnostics.
- Added recovery handling across player inventories, ender inventories, containers, shulker boxes,
  loose item entities, death/restore paths, and placed computers including racks.
- Added Operations Projects with discovery, validation, saved state, deployment, and command support.
- Added rack scheduling, owner-controlled remote sessions, recurring services, bounded audit metadata,
  administration policy, SCADA quality-first display data, and native Refined Storage crafting
  completion/cancellation tracking. AE2 writes remain intentionally disabled.
- Added connected device discovery, remote PLC program loading, cable endpoints for terminals, PLCs,
  sensor arrays, and standalone sensors, plus clearer PLC fault and recovery commands.

## 1.0.95–1.0.98 — model and targeting pass

- Reworked the visual models and selection/collision shapes for server racks, router and server
  blades, PLCs, NAS, storage bridges, Refined Storage and AE2 bridge blocks, Materializer, sensors,
  sensor arrays, router, wireless display link, SSDs, turtles, modems, and storage cabinets.
- Added facing, rotation, and mirroring support where applicable while preserving existing textures,
  registry IDs, network behavior, storage capacities, and saved-data contracts.

## 1.0.99–1.0.102 — interface and interaction polish

- Made terminal and editor panels responsive to ordinary scaled windows, with viewport paging and a
  compact layout that hides the optional status rail when space is limited.
- Made guide illustrations, chapter navigation, search, display-link channel drafts, cable lists,
  and display configuration fit narrow windows and survive focus changes or resizing.
- Refined disk-drive and loose rack-blade models while preserving insertion, ejection, mounting, and
  saved contents.
- Corrected monitor selection and collision geometry for every horizontal facing and improved PLC
  editor layout, live-watch spacing, status text, empty-ladder guidance, and footer wrapping.

## Compatibility

Back up your world before upgrading. Client and server should use the same TerminalCraft version.
