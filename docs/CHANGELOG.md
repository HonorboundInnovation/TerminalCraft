# TerminalCraft Changelog

All notable player-facing changes are recorded here. TerminalCraft build numbers increase with the
final component of the version: `1.0.17` is build 17, `1.0.18` is build 18, and so on.

## 1.0.63 — 2026-08-10

### Solid cable colors and restored unshielded Red Alloy

- Replaced the patterned Shielded Red Alloy and colored Network Cable world textures with opaque
  neutral tint bases, making every placed cable render as its assigned dye color without the old
  dot/ring artifacts.
- Replaced both item textures with clean solid-color cable silhouettes that use the same dye tint.
- Restored the tag-free Unshielded Red Alloy Wire to creative tabs alongside all sixteen shielded
  colors and gave it a distinct name, tooltip, placement preview, wrench diagnostic, and drop.
- Made the existing Red Alloy recipe produce twelve unshielded wires; dyeing one produces the
  selected shielded color. The existing Network Cable recipe produces eight cables and dyeing one
  selects its color/default channel.
- Persisted insulation independently from color. Existing 1.0.49–1.0.62 colored wires remain
  shielded, while original pre-insulation saves migrate as unshielded conductors.
- Unshielded Red Alloy joins ordinary Red Alloy circuits but cannot select or leak into a Bundled
  Red Alloy channel; only a colored shielded breakout has channel identity.
- Added the craftable compact Red Alloy Capacitor. Any nonzero input on any face is restored to
  strength 15 only on the directly opposing face; all six directions operate as independent
  straight-through paths, with wrench diagnostics showing the currently active paths.
- Changed ordinary Red Alloy Wire, Network Cable, Bundled Red Alloy Cable, and Bundled Network Cable
  selection shapes to face-oriented half blocks: slab halves on floors/ceilings and wall halves on
  vertical faces. Multipart installations union the occupied face halves while rendering stays thin.

## 1.0.62 — 2026-08-10

### Independent 16-channel computer I/O

- Extended `redstone`/`rs` with explicit Bundled Red Alloy channel input, output, set, and 16-row
  status forms while retaining the existing vanilla six-side forms.
- Extended `wire`/`bundled` with documented external-input reads, `all` channel views, and a full
  color-labeled input/output/effective-bus table.
- Separated external bundled input from local computer-owned output so a host does not read its own
  output back as input.
- Applied the same adjacent bundled-channel contract to terminals, server racks, PLCs, and turtles;
  PLC `BUNDLED` input bindings now consume the external-input value.
- Enforced strict Bundled Red Alloy separation through color-selected breakouts and removed the
  ambiguous direct-vanilla channel-zero bridge.
- Made Bundled Network routes retain a fixed 0–15 physical lane so a packet cannot leave through a
  differently colored breakout even when the remote modem manually listens on that packet channel.

### Verification

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.62.jar`
- Size: `2,681,325` bytes
- SHA-256: `85debd58cc72fd11ed14eaeac8074434868e7f01d1f20ad2494af760b470c06c`

## 1.0.61 — 2026-08-10

### Restored single-cable RedPower routing

- Removed the active 4×4 matrix/grid model from ordinary Shielded Red Alloy Wire and Network Cable.
- Restored one centered cable per occupied face for both families, with automatic straight, corner,
  junction, multipart-face, bundled-trunk, and endpoint connections.
- Replaced the 16-point placement overlay with one centered face marker and made a second ordinary
  cable on the same face fail cleanly.
- Made old 1.0.58-1.0.60 row/matrix saves collapse deterministically to one retained cable per face;
  the first stored color is retained and Red Alloy keeps the strongest saved signal.
- Updated wrench diagnostics, tooltips, previews, and the complete in-game Guide Book.

### Verification

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.61.jar`
- Size: `2,672,297` bytes
- SHA-256: `455bb2d4abb614479749e5b8d877003525dc8d29cb58ba962fa17fc339b15a21`

## 1.0.60 — 2026-08-10

### Continuous lattice lanes

- Made individually placed Shielded Red Alloy and Network Cable points render continuous links to
  adjacent same-color points instead of remaining isolated node squares.
- Made neighboring blocks connect dynamically by lane: horizontal travel preserves the lattice row,
  while vertical travel preserves the lattice column.
- Added boundary-reaching visual arms for cross-block links, bundled breakouts, redstone endpoints,
  modems, and other wired devices, with exact floor, wall, and ceiling geometry.
- Applied the same connection rules to electrical propagation, vanilla-redstone input/output,
  bundled Red Alloy channels, RedNet topology, bundled Network Cable, and device channel discovery.
- Updated wrench diagnostics and the complete in-game Guide Book to describe dynamic lane assembly.

### Verification

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.60.jar`
- Size: `2,676,620` bytes
- SHA-256: `f9a528762933dc6d81a6edf4261ba2f1848de8a328e92cfdcde39996f29b7325`

## 1.0.59 — 2026-08-10

### Individually placed 4×4 cable points

- Changed both Shielded Red Alloy Wire and Network Cable so one item places exactly the one
  lattice point under the crosshair instead of automatically filling a four-point row.
- Expanded persistent face occupancy from four routed runs to 16 independent points, with exact
  point targeting, selection, removal, drops, rendering, preview colors, and wrench diagnostics.
- Made matching-color horizontal, vertical, and diagonal neighbor points connect, and mapped edge
  points to their exact opposite-edge point in the adjacent block.
- Preserved 1.0.58 worlds by expanding every saved four-point row or turn into its equivalent set of
  individually stored points when the block entity loads.
- Updated bundled-network breakouts so only a colored edge point physically touching the trunk is
  exposed, and revised the complete in-game Guide Book instructions.

### Verification

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.59.jar`
- Size: `2,675,122` bytes
- SHA-256: `342ae3efdfc97f1fb2571d3025746af3f726fa41e04d2762e594f3a482e9861e`

## 1.0.58 — 2026-08-10

### Four-by-four surface-routing lattice

- Replaced the temporary centered-node interpretation with the confirmed four-by-four face lattice
  for both Shielded Red Alloy Wire and Network Cable.
- Made each straight lane four points long; four parallel lanes now form four rows or columns totaling
  all 16 points on the block face.
- Rasterized neighbor-initiated perpendicular turns between lane edge points, producing a four-point
  outer corner-to-corner diagonal and progressively shorter inner turns.
- Made added multipart runs inherit the existing face bank and resolve the stored mounting face when
  a player clicks the incidental side of thin geometry, preventing false unsupported-face errors and
  unintended bank rotation.
- Kept configured port masks, electrical/data topology, colors, channels, and existing save data intact.

### Verification

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.58.jar`
- Size: `2,674,734` bytes
- SHA-256: `25a287079a5f7d1d890415613ee86060c9353b75d8ead4fdfa68d9f4a72f9944`

## 1.0.57 — 2026-08-10

### RedPower-style connected cable geometry

- Replaced full-length lane placement outlines with four compact, direction-aware lane points for
  both Shielded Red Alloy Wire and Network Cable.
- Separated configured route intent from visible geometry: isolated runs remain compact points and
  arms reach block edges only for reciprocal cables, bundled trunks, devices, modems, or adjacent
  redstone/block connections.
- Made removal of a physical neighbor retract its arm without destroying the editable route mask,
  preserving automatic continuation, single-block elbows, and saved 1.0.56 routing data.
- Kept world rendering, targeting/selection geometry, wrench editing, and placement previews aligned
  to the same compact-node model.

### Verification

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.57.jar`
- Size: `2,733,504` bytes
- SHA-256: `41cb19a55199e2297a5de1fccc26c42acc69223b7a68bb40fd0e3a80777e0702`

## 1.0.56 — 2026-08-10

### Player-relative directional cable routing

- Added a persistent six-port route mask to every individual Shielded Red Alloy Wire and Network
  Cable lane. Each lane now controls its own front/back/left/right or wall up/down connections.
- Made initial straight runs rotate with the player's view projected into the mounted face. Extending
  a matching cable prefers its reciprocal incoming lane, while looking toward a perpendicular exit
  creates a 90-degree turn entirely within the newly placed block.
- Added exact route geometry shared by placement previews, textured world rendering, ray targeting,
  and selection shapes. Straight banks rotate, elbows bend, junctions branch, and endpoints remain on
  their actual block edges.
- Restricted redstone propagation, RedNet topology, bundled-cable breakouts, device attachments, and
  face transitions to reciprocal configured ports; crossings on different lanes remain isolated.
- Added sneak-wrench port toggling and expanded wrench diagnostics with route directions and shape.
- Added legacy migration: 1.0.55 and older runs load with all four valid in-plane ports, retaining
  existing colors, lanes, channels, power, and permissive historical connectivity.
- Updated tooltips and the complete in-game Guide Book construction instructions for both families.

### Verification

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.56.jar`
- Size: `2,732,184` bytes
- SHA-256: `7bb80dac8e360b01ee3183682305965e45118c89bdc384d0156547bab82a090d`

## 1.0.55 — 2026-08-10

### True four-lane surface cabling

- Replaced the misleading two-by-two face-quadrant layout with four long, side-by-side lane bands
  for both Shielded Red Alloy Wire and Network Cable.
- Updated placement hit selection, world previews, rendered run offsets, selection/collision shapes,
  tooltips, tests, and the in-game Guide Book to use the same four-band geometry.
- Preserved saved lane IDs `0..3`; existing cable occupancy, colors, channels, signals, and network
  topology remain intact while their visible positions move into the corrected parallel layout.

### Verification

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.55.jar`
- Size: `2,716,071` bytes
- SHA-256: `c8fc72d4c5222d77315f297f1bc61c7ca461c2d3e4b5178c2eb7d0c4de42af7d`

## 1.0.54 — 2026-08-10

### Optional SecurityCraft integration

- Added an absence-safe native adapter verified against the official SecurityCraft 1.20.1 branch
  and `[1.20.1] SecurityCraft v1.10.2.1.jar`.
- Added automatic Device API discovery for every adjacent official `IOwnable` block entity, generic
  `ICustomizable` option support, generic `IModuleInventory` inspection/toggling, and secret-safe
  status for reinforced and technical block families.
- Added specialized telemetry and controls for alarms, detectors/loggers, inventory scanners,
  lasers, keypad/keycard readers, cameras, Secure Redstone Interfaces, Sonic Security Systems,
  projectors, rift/trophy systems, track mines, sentries, and nearby Security Sea Boats.
- Added native Machine Sensor metrics and ten compile-checked SecurityCraft PLC templates for
  perimeter alarms, access windows, checkpoints, panic lockdown, airlocks, rift/trophy defense,
  secure-redstone backhaul, and redundant voting.
- Preserved SecurityCraft authority: detailed reads and every native write require the online
  authenticated owning player. Passcodes, hashes, salts, list contents, keycard item data, owner
  UUIDs, ownership changes, and module item creation/removal are never exposed.
- Kept SecurityCraft compile-only and optional. Minimal TerminalCraft installations do not load any
  SecurityCraft API class.

### Verification

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.54.jar`
- Size: `2,716,401` bytes
- SHA-256: `d03de382b358fb9e28cb8fbcb56e2c079225f38e25d23de08216a91e9dca70d2`

## 1.0.53 — 2026-08-10

### Optional Create and Mekanism automation

- Added absence-safe native device projections for Create 6.0.8 kinetic blocks, Stressometers,
  Threshold Switches, Redstone Links, and configured Sequenced Gearshifts.
- Added native Kinetic/Machine sensor telemetry for Create speed, direction, stress, capacity,
  network, thresholds, signals, sequencer state, and overstress.
- Added absence-safe Mekanism 10.4.16 machine status, progress, exact Joule-container, heat,
  redstone-mode, supported-substance, and security telemetry.
- Added authenticated Create controls and Mekanism redstone-mode control. Mekanism non-public targets
  fail closed; TerminalCraft does not impersonate owners or trusted users.
- Added a provider-neutral dynamic chemical storage contract. Live tank resource IDs—including
  add-on namespaces—flow into `chemical.tanks`, exact `chemical.count`, and Chemical Sensors without
  a static resource allowlist.
- Added eight compile-checked Mekanism PLC templates and exposed them in the shell, Control Center,
  and generated in-game Guide Book library.
- Kept Create, embedded Ponder API, and Mekanism compile-only; none is bundled or mandatory at runtime.

### Verification

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.53.jar`
- Size: `2,670,936` bytes
- SHA-256: `f2c785a1819c379380cbc2b833fc28b2041e3d74cfae4828ad380368de7b0d78`

## 1.0.52 — 2026-08-10

### Advanced HMI

- Added persistent named advanced-HMI dashboards with up to eight pages and 32 ordered widgets per
  page on a resolution-independent 12 by 12 layout grid.
- Added full-color text, live value, gauge, historian trend, active-alarm panel, operator button, and
  page-link widgets. Layouts scale across connected monitor walls up to 320 by 32 character cells.
- Added physical monitor touch operation. Page links navigate immediately and control buttons run as
  the touching player through SCADA roles, Device API permission checks, PLC ownership, and audit.
- Added the top-level `hmi` graphical terminal program with live automatic repaint, dashboard/page
  navigation, mouse selection, keyboard activation, and an engineer-only F2 layout designer.
- Added designer move, resize, add, edit, and double-confirmed delete controls, plus persistent
  full-screen program state and focused `help hmi` instructions.
- Added the complete `scada hmi` shell family for dashboards, pages, and widgets; Device API
  `hmi.list`, `hmi.page.select`, and `hmi.widget.activate`; tab completion through the existing shell
  registry; and comprehensive searchable Guide Book examples.
- Added an atomic `term.frame` monitor method that transfers text, per-cell foreground/background
  colors, and a 16-color palette in one bounded operation, batching wall synchronization per tile.
- Added headless acceptance coverage for layout authorization/persistence, live values, gauges,
  trends, alarms, touch hit-testing, full-color frames, and invalid bindings.

### Compatibility

- Existing 1.0.51 tags, history, alarms, roles, audits, and legacy monitor dashboards remain valid.
  Advanced HMI data is additive in the same bounded SCADA world record.
- Minecraft: 1.20.1; Forge: 47.4.10; required on client and server.

### Verification

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.52.jar`
- Size: `2,614,100` bytes
- SHA-256: `3f56dda044e8a70e0594d8f691a697fbe5e722dfe6ef71432fbf49f3d0f4b691`

## 1.0.51 — 2026-08-10

### SCADA supervisory control

- Added a persistent server-global SCADA process database with up to 256 hierarchical tags bound to
  typed Device API methods. Bindings support scalar arguments, nested value paths, engineering
  units, per-tag scan/stale intervals, and optional authorized write methods.
- Added quality-aware live values with good, stale, offline, access-denied, bad-response, and
  configuration-error states. The last known value is retained but never presented as usable after
  quality degrades.
- Added a bounded persistent historian with per-tag and global retention, timestamped value/quality
  points, shell queries, Device API queries, and monitor trend-ready data.
- Added above, below, equal, not-equal, and bad-quality alarms with severity, deadband, active,
  acknowledged, shelved, and normal lifecycle states. Shelf timeouts use logical server time.
- Added viewer, operator, engineer, and administrator plant roles. First-use initialization safely
  establishes one administrator, the final administrator cannot be removed, and SCADA never
  bypasses underlying device permissions or PLC ownership.
- Added a persistent bounded audit trail for configuration, role changes, control acceptance or
  rejection, value/quality transitions, and alarm lifecycle events.
- Added automatically refreshed monitor-wall HMI dashboards filtered by tag prefix, with live values,
  engineering units, quality, active-alarm count, and alarm markers.
- Registered a virtual TerminalCraft SCADA device in discovery and the graphical Control Center. It
  exposes status, tags, live reads, history, alarms, acknowledgment, authorized control, and
  `tag_changed`/`alarm_changed` events.
- Added an adjacent Server Rack + modem typed RedNet gateway for bounded read-only SCADA status,
  tag, history, and alarm queries. Network packets cannot initialize, configure, acknowledge, grant
  roles, or control equipment.
- Added the complete `scada` terminal command family, `help scada`, `modem scada`, tab completion,
  field-manual chapter, examples, runtime limits, and headless acceptance/protocol coverage.
- Added explicit no-value command tags (`@method`, `scada command`, and Device API `tag.command`) so
  PLC run/stop/reset operations remain distinct from writable setpoint tags.

### Compatibility

- SCADA data uses new server-global `terminalcraft_scada` SavedData and does not alter existing PLC,
  sensor, RedNet, terminal, cable, or storage save formats.
- Minecraft: 1.20.1; Forge: 47.4.10; required on client and server.

### Verification

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.51.jar`
- Size: `2,558,409` bytes
- SHA-256: `9e11e3adc0970784138073e31cb96d99ed4e524acc25d19ad38894f7a4365dd4`

## 1.0.50 — 2026-08-10

### Cable usability and field guidance

- Added a live world-space placement preview for both surface cable families. Four face-quadrant
  outlines show the chosen lane, free lanes, occupied lanes, and automatic next-free-lane fallback.
- Added a crosshair readout before placement with the actual lane, mounting face, insulation color,
  Red Alloy bundle channel, or Network default channel. Bundled items identify their 0–15 transport.
- Replaced single dense wrench messages with structured multi-line diagnostics covering target face,
  lane, color/channel, signal level, links, trunk connection, topology neighbors, face occupancy,
  active bundled channels, and signal sources.
- Gave Bundled Red Alloy Cable a warm red multiconductor jacket and Bundled Network Cable a separate
  navy/cyan packet-marked model and texture family, including the inventory model.
- Extended the in-game Markdown reader with allowlisted, bounded guide-image rows and added two
  searchable field-manual plates illustrating four-lane placement and the separate bundle families.
- Added headless contracts for preview quadrants, one-based diagnostic lane labels, localization,
  distinct model/texture routing, guide image dimensions, resources, and searchable image alt text.

### Compatibility

- Cable registry IDs, saved run data, color/channel mapping, recipes, and electrical/network behavior
  are unchanged from 1.0.49. The usability pass is safe for existing 1.0.49 development worlds.
- Minecraft: 1.20.1; Forge: 47.4.10; required on client and server.

### Verification

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.50.jar`
- Size: `2,463,268` bytes
- SHA-256: `0f6aff974cab31ed29a5e9e734205fb8f356d2423511e84a70a45c1bae9160f4`

## 1.0.49 — 2026-08-10

### Colored surface wiring and channel trunks

- Added all 16 dye colors for Shielded Red Alloy Wire and Network Cable. Minecraft dye IDs map
  deterministically to channels 0 through 15: white is 0, cyan is 9, red is 14, and black is 15.
- Added four independently persisted and targetable lanes to every supported block face. Placement
  uses the clicked face quadrant and falls forward to the next free lane when necessary.
- Shielded Red Alloy runs now preserve color/lane electrical isolation, attenuate independently,
  retain colored drops, migrate legacy faces safely, and expose color/lane/power wrench diagnostics.
- Colored Network Cable now advertises its default data/control channel, automatically provisions
  that channel on an attached wired modem, and supplies it as the omitted-channel shell default.
- Reclassified the existing `bundled_cable` world ID as the **Bundled Red Alloy Cable**, preserving
  existing worlds and its sixteen independent redstone channels.
- Added a separate **Bundled Network Cable** for sixteen-channel RedNet data/control trunks. Colored
  Network Cable can enter and leave the trunk without crossing into the redstone system.
- Added bidirectional color-matched Shielded Red Alloy breakouts on Bundled Red Alloy Cable without
  feedback latching; the bundle derives breakout input from native redstone sources.
- Added a special cable+dye survival recipe, creative inventory variants, color-aware item names and
  tooltips, bundled-network recipe/model/loot resources, and searchable guide coverage.
- Added headless color/channel, quadrant/lane, survival-resource, and dual-bundle contract checks.

### Compatibility

- Existing Red Alloy Wire faces migrate as red runs; existing Network Cable faces migrate as cyan
  runs. Registry IDs for those blocks and for the original bundled redstone cable are unchanged.
- Minecraft: 1.20.1; Forge: 47.4.10; Java: 21; required on client and server.

### Verification

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.49.jar`
- Size: `1,654,203` bytes
- SHA-256: `7c3538e1fff61f81eb99209bc4980416d57a7097aae93398443a1bdc6e393106`

## 1.0.48 — 2026-08-10

### In-game TerminalCraft Guide

- Added a craftable `TerminalCraft Guide` item that opens a dedicated offline field-manual screen.
- Added a searchable chapter index, mouse selection, wheel and Page Up/Page Down scrolling,
  Left/Right chapter navigation, Ctrl+F search focus, previous/next buttons, and a non-pausing reader.
- Bundled the complete canonical TerminalCraft Guide and Advanced Script Cookbook directly from the
  source documentation during the build, avoiding a shorter duplicate that could drift out of date.
- Generated two additional PLC example chapters directly from every compile-tested general and
  Create template, keeping the in-book sample source identical to `plc template show` and the
  graphical Control Center library.
- Expanded the PLC language manual with scan order, bounds, names, every digital and analog binding,
  Boolean precedence, rungs, timers, edge counters, reset-dominant latches, MOVE, SCALE, PID tuning,
  fail-safe behavior, commissioning commands, and complete controller examples.
- Added the guide to the TerminalCraft and standard utility creative tabs, a crafting recipe, item
  model, localized tooltips, and bounded headless parser/resource/search coverage.

### Verification

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.48.jar`
- Size: `1,620,791` bytes
- SHA-256: `4eb832d36575d7e30b679053b2a7aa2c9482de0839682218a7ec08fac19c9f06`

## 1.0.47 — 2026-08-10

### Control Center interaction fixes

- Fixed F2 DNS naming and every other Control Center action failing to update the open terminal
  promptly on placed computers. Each authenticated action now sends its authoritative shell state
  directly back to the player instead of relying on block-update timing.
- Made `Enter` on Overview open the selected device's Methods pane; pressing it again runs the
  selected method. Method argument and DNS prompts now become usable immediately after activation.
- Added `N` (DNS naming) and `R` (refresh) fallbacks for modpacks that reserve `F2` or `F5`.
- Added `help control`, `help setup`, and `control --help`, with a complete readable list of commands,
  navigation keys, aliases, and text-entry behavior. General `help` retains the Control Center entry
  and now points to the focused reference at the end of its output.
- Expanded headless interaction coverage across all advertised Control Center actions and help
  entry points.

### Verification

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.47.jar`
- Size: `1,560,309` bytes
- SHA-256: `ee2835037c0dbe03dabc0d3ffa5a82cbaa2d10ff487922112b77c3f4ad3eceac`

## 1.0.46 — 2026-08-10

### Visual fixes

- Fixed neighboring block faces disappearing around the PLC cabinet. The inset PLC model no longer
  claims full-cube occlusion, so placing it beside any block preserves that block's visible face.
- Fixed colored Control Center cells remaining on the shared terminal surface after exiting.
- The Control Center now temporarily owns surface mode and restores the terminal's previous log or
  surface view when it closes.
- Added focused regression coverage for the PLC occlusion contract and complete shell color reset.

### Verification

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.46.jar`
- Size: `1,559,383` bytes
- SHA-256: `bcbb45192b3f7e6597a6c6e898478d26023c1305b5c82566132b6c29657df6b5`

## 1.0.45 — 2026-08-10

### Device Control Center

- Added the full-screen `control` program (`devmgr` and `setup` aliases) for discovering and
  configuring devices without manually typing UUID-heavy Device API calls.
- Added mouse and keyboard navigation across live device overview, advertised methods, and PLC
  template tabs.
- Added an `F2` workflow for durable human-friendly device DNS aliases and `F5` live refresh.
- Added schema-driven method argument prompts that retain the existing server-authenticated read and
  write permission boundaries.
- Added remote loading of all general and Create PLC templates through the selected PLC's
  owner-authorized `program.set` endpoint.
- Added synchronized Control Center session state, a bounded action packet, and headless interaction
  coverage.

### Verification

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.45.jar`
- Size: `1,559,159` bytes
- SHA-256: `6ef7c9736aba73d449322ee98d97dcc7223b9e284c7992fe57017b7f722971fc`

## 1.0.38 — 2026-08-08

### World-load hardening

- Disabled Redstone Sensor-to-Redstone Sensor chaining before vanilla signal propagation can enter
  a feedback graph during world startup.
- Kept the re-entrant probe guard and fail-closed output from 1.0.37 as a second safety boundary.
- Corrected the Network Sensor recipe to use `terminalcraft:network_cable` instead of the invalid
  `minecraft:network_cable` identifier.

### Verification

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.38.jar`
- Size: `1,494,812` bytes
- SHA-256: `8c7b0e05537918868e56d4ecd14b45ce3dafdee008a790d0ef10356debeac288`

## 1.0.37 — 2026-08-08

### Stability and resource fixes

- Fixed wireless modem, Network Cable, and Bundled Control Cable item models that referenced
  nonexistent custom parent models and therefore rendered without a texture.
- Prevented mutually-facing Redstone Sensors from recursively querying one another and crashing the
  server with `StackOverflowError`.
- Clamped failed sensor outputs to a safe redstone level of zero.
- Replaced translucent router casing references on the Network Router, PLC, and Server Rack router
  faces with opaque themed textures to eliminate visual bleed-through and rendering artifacts.

### Verification

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.37.jar`
- Size: `1,494,763` bytes
- SHA-256: `78639cf5b97d5390ed4b79baa3d6e280cc809f25b271aa5d16667034502f5bc9`

## 1.0.36 — 2026-08-08

### Cohesive electronics asset pass

- Replaced the remaining placeholder and vanilla-facing visuals on the Terminal, Monitor, Modem,
  Network Router, PLC, Disk Drive, Turtle, and Server Rack families with a shared dark industrial
  pixel-art visual language.
- Added individually colored sensor textures for the Universal Sensor Array and every standalone
  sensor type: Redstone, Block State, Inventory, Fluid, Energy, Entity, Machine, Environment,
  Network, Kinetic, and Chemical.
- Added dedicated themed textures for the NAS, Materializer, Wireless Display Link, Refined Storage
  Bridge, and Applied Energistics Bridge.
- Applied separate Red Alloy Wire, Network Cable, Bundled Cable, and Video Cable textures across
  every routed connection variant without changing their connection topology or model selection.
- Added custom front-panel geometry to the Refined Storage Bridge so it reads as a distinct device
  instead of a generic cube.

### Verification

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.36.jar`
- Size: `1,494,292` bytes
- SHA-256: `a5dd03e24def89f6dfc4d68471726a48bccbece73d478d84557955abf22147f5`

## 1.0.35 — 2026-08-08

### Solid-State Drive visuals

- Replaced the vanilla iron ingot, diamond, and nether star item icons with custom SSD textures.
- Added distinct 3D item housings and connector layouts for Basic, Advanced, and Quantum drives.
- Added cyan/graphite, violet/magenta, and emerald/obsidian tier palettes.

### Verification

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.35.jar`
- Size: `1,452,455` bytes
- SHA-256: `85fff40e5e3c6e223d39b32db958a6aefb8e65fe67ee4f03376b4642eacb3c7d`

## 1.0.34 — 2026-08-08

### Block coverage

- Added a full opaque bottom plinth to every full-size electronics model so the block beneath is
  never visible through the placed block's lower layer.
- Kept cable core and arm models open by design for connected-network routing.

### Verification

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.34.jar`
- Size: `1,447,553` bytes
- SHA-256: `8d78263e56b2fda9cc86b4ad7ac2293ada81e6f8de8b2a2604612487f8f5485a`

## 1.0.33 — 2026-08-08

### Rendering stability

- Removed transparent glass and animated water sprites from the new opaque block models.
- Separated front panels, slot bars, and full-block bases that could occupy the same render plane
  and cause z-fighting or flickering.
- Preserved the individual silhouettes and material identity introduced in 1.0.32.

### Verification

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.33.jar`
- Size: `1,447,258` bytes
- SHA-256: `7277dbab7909b303e6548c8a3f8a4059c847d2c0d46770c42a51554b107d3e1c`

## 1.0.32 — 2026-08-08

### Visual overhaul

- Replaced the shared placeholder cube used by the sensor family, Universal Sensor Array, Network
  Access Storage, and Materializer with individually authored block geometry.
- Added visually distinct silhouettes and material palettes for Redstone, Block State, Inventory,
  Fluid, Energy, Entity, Machine, Environment, Network, Kinetic, and Chemical Sensors.
- Reworked the Network Router and Programmable Logic Controller models with dedicated front panels,
  ports, indicators, and control details.
- Reworked the Wireless Display Link and Applied Energistics Bridge with dedicated relay, crystal,
  and signal-port geometry.
- Gave Basic, Advanced, and Quantum Solid-State Drives distinct tier textures in inventory.
- Added directional model rotations so sensor faces follow their placed block orientation.

### Verification

- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.32.jar`
- Size: `1,447,223` bytes
- SHA-256: `63dd4c5e5bef7d0bb90b8d40607492f37fe9c2e9dea230f00e993f235ca3ffe8`

## 1.0.31 — 2026-08-08

### Added

- Added the eight-slot Network Access Storage block.
- Added Basic, Advanced, and Quantum Solid-State Drives with portable item/fluid storage data.
- Added the Materializer output block for player extraction and read-only item/fluid automation.
- Added NAS device APIs through the existing generic storage contract, including bounded item queries,
  item insertion/extraction, fluid filling/draining, and adjacent capability access.

### Safety and persistence

- SSD contents remain stored on the drive item, so removing a drive from a NAS is non-destructive.
- NAS and Materializer operations reuse the existing bounded capability and transfer surfaces.

## 1.0.30 — 2026-08-08

### Added

- Added dedicated Redstone, Block State, Inventory, Fluid, Energy, Entity, Machine, Environment,
  Network, Kinetic, and Chemical Sensor blocks.
- Individual sensors preserve the provider-neutral probe engine while exposing a fixed `value`
  channel, calibrated redstone output, terminal configuration, PLC `SENSOR` inputs, device APIs,
  change events, and typed RedNet telemetry.
- Added one-step shapeless conversion recipes from the Universal Sensor Array to each dedicated
  sensor family, plus creative-tab entries, models, blockstates, and localized names.

### Compatibility

- The Universal Sensor Array remains available for multi-channel installations. Individual sensors
  are additive and use the same bounded quality and fail-closed behavior.

## 1.0.29 — 2026-08-08

### Added

- Added the Universal Sensor Array with 16 persistent channels and provider-neutral probes for
  redstone, block state, inventory, fluid, energy, entities, machine state, environment, network,
  and kinetic telemetry.
- Added quality-aware sensor readings, PLC calibration, terminal `sensor` commands, device API
  methods/events, PLC `SENSOR` inputs, and typed RedNet sensor services.
- Added headless sensor model and PLC binding coverage.

### Compatibility

- Chemical telemetry is intentionally reported as unsupported until a stable generic capability or
  optional adapter exists. Existing devices and programs remain compatible.

## 1.0.28 — 2026-08-08

### Added

- Added beginner-first RedNet auto-provisioning: new modems open channel 42, receive a stable
  `node-...` hostname, and renew an in-world private address automatically.
- Added bounded DHCP-equivalent leases with deterministic `/24` pools, router authority tracking,
  lease renewal, link-local fallback, and network-status diagnostics.
- Added persistent router identities and server-side router registration so router blocks participate
  in lease authority discovery as well as existing physical forwarding.
- Added the built-in versioned RedNet channel/control protocol catalog and `modem status` plus
  `modem auto on|off` commands.
- Added shorthand `modem send <message>` and `modem sendto <host> <message>` forms for quick setup.

### Compatibility

- Existing explicit hostnames, logical network names, channels, services, router face assignments,
  and manual diagnostics remain available. Closing the automatic channel opts that modem out of
  auto-provisioning; `modem auto on` restores it.
- Existing modems with no saved automatic-setup flag migrate into the beginner-friendly profile.

## 1.0.27 — 2026-08-08

### Added

- Added analog `AIN`/`AOUT` bindings, `MOVE`, `SCALE`, and bounded discrete `PID` scan operations.
- Added persisted 64-sample PLC trend history, analog/PID watch values, and a fourth monitor-dashboard
  oscilloscope page.
- Added a source-preserving F7 ladder workspace with draggable signal contacts and rung reordering.
- Added owner-authorized PLC device endpoints for remote status, program chunks, controls, I/O, and trends.
- Added `plc remote open <x> <y> <z>` to open the PLC programmer from an authorized terminal session.

### Verification

- Added analog/PID runtime, ladder projection, and remote endpoint authorization coverage.
- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.27.jar`
- Size: `1,297,505` bytes
- SHA-256: `6d1bc6dca54ea7c2a92b2964ad94ca1349e15099a8a5968e1d54b5e63847e93f`

## 1.0.26 — 2026-08-08

### Added

- Added a dedicated PLC programming and commissioning GUI with source editing, compile/load, run,
  stop, reset, alarm acknowledgment, and four program slots.
- Added live PLC watch information to the programming screen, including I/O state, forces, alarms,
  scan count, cycle interval, and compile/runtime faults.
- Improved PLC monitor dashboards with operations/status sections, alarm indicators, and wider
  touch hitboxes.
- Bound monitor-wall touch routing to the display source that rendered the current frame, fixing
  direct, wireless, and Video Cable PLC dashboard interaction.
- Added a monitor GameTest covering the touch-to-RUN controller path.

### Verification

- Full headless check and production JAR build pass.
- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.26.jar`
- Size: `1,271,994` bytes
- SHA-256: `4339de25856ed52b2d6934e9bd97a7ea8564fc32496cacef0549d99b1eb4bf82`

## 1.0.25 — 2026-08-07

### Added

- Added diagnostics/configuration GUIs for Wireless Display Links and Video Cable segments.
- Added bounded display transport fault containment and operator snapshots.
- Added PLC live watch tables, persisted input/output force controls, alarm history,
  acknowledgement, owner/operator authorization, dashboard pages, and four program slots.
- Added monitor `bar`, `led`, and `spark` widgets for compact dashboards.
- Added terminal `Ctrl+R` reverse history search and `Ctrl+L` input clearing.
- Added active PLC registration to the existing bounded TerminalCraft chunk-ticket policy.

### Verification

- Full Java compilation, resource validation, headless verification, and production build pass.
- Added monitor-widget and display-control regression coverage.
- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.25.jar`
- Size: `1,250,470` bytes
- SHA-256: `4749cecefef071351e719d241f9e084513b32d6fc8029ae6c53a2623a84cccc2`

## 1.0.24 — 2026-08-07

### Added

- Added paired Wireless Display Link blocks for attaching terminal-capable hosts to remote monitors.
- Added `displaylink status|source|sink|pair` shell configuration with bounded normalized channels.
- Added routed Video Cable blocks with six-direction connections and a bounded 2,048-node display
  component graph.
- Added passive terminal-surface mirroring for terminals, turtles, server racks, and other
  `ShellComputer` hosts across either display transport.
- Added colored PLC dashboards for direct monitors, wireless receivers, and Video Cable endpoints.
- Added server-authoritative `RUN`, `STOP`, and `RESET` monitor controls for PLC dashboards.

### Verification

- Java compilation, JSON resource validation, headless tests, and the production JAR build pass.
- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.24.jar`
- Size: `1,223,223` bytes
- SHA-256: `0b4b3f75fee7bf5d9847b2c8f003417a6ef9946462692f1742f6c90995987ec0`

## 1.0.23 — 2026-08-07

### Added

- Added a placeable programmable logic controller with a terminal-style editor and server-authoritative scan cycles.
- Added bounded PLC programs with redstone/bundled I/O, boolean rungs, timers, rising-edge counters, and reset-dominant latches.
- Added `plc status|show|load|set|append|start|stop|reset|clear` commands and persisted program source.
- PLC compile and wiring faults stop the scan loop and drive declared outputs safe.

### Verification

- Added headless PLC compiler/runtime tests covering timer, latch, counter, malformed-program, and fail-safe behavior.
- Full `check build` verification passes with 77 actionable tasks and zero failures.
- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.23.jar`
- Size: `1,189,822` bytes
- SHA-256: `8bd0255a0de27e30969188cab25272d7f5859a04965196bec2abae8db2362dd3`

## 1.0.22 — 2026-08-07

### Fixed

- Fixed colored monitor-wall surfaces being immediately overwritten by the monitor device
  endpoint's legacy line synchronization after each `output_changed` event.
- Shared monitor surfaces now retain their per-cell foreground/background indexes and palette
  entries during endpoint refreshes.

### Verification

- Focused monitor endpoint and screensaver tests pass.
- Full Java 21 headless `check build` passes with 76 actionable tasks and zero failures.
- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.22.jar`
- Size: `1,139,580` bytes
- SHA-256: `e450f8a7a6b8d93a9910ae4a381a3fb20ab9d2ba50ccba8d0bf03baa6678a456`

## 1.0.21 — 2026-08-07

### Added

- Added `monitor screensaver start|stop|status [side]` with a bounded server-tick geometric
  animation.
- Added the bundled `/home/player/programs/geometric_screensaver.sh` program.
- Added `monitor screensaver color [side]` and the bundled
  `/home/player/programs/geometric_screensaver_color.sh` full-color variant.
- Full-color frames use the persisted 16-entry terminal palette and per-cell foreground/background
  indexes across the global wall canvas.
- The screensaver renders one global wall canvas and splits rows into physical monitor tiles only
  when persisting each frame; patterns are not duplicated per monitor.
- Batched wall-frame writes so each animation frame updates the connected wall coherently with
  bounded output-event publication.

### Fixed

- Retained the connected monitor-wall re-entrancy guard that prevents synchronous `output_changed`
  callbacks from recursively importing a shared surface and overflowing the server thread.

### Verification

- Full Java 21 headless `check build` passes with 76 actionable tasks and zero failures.
- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.21.jar`
- Size: `1,139,573` bytes
- SHA-256: `0844117e29654303a41c943633f39cb378852f37734c1e5acc27a46cf58c8e50`

## 1.0.20 — 2026-08-07

### Added

- Refined the terminal GUI into a larger CRT-style workstation with a beveled frame, mode header,
  dedicated shell output column, command footer, and live status rail.
- Added visible terminal status for shell mode, current directory, last exit code, command-history
  depth, surface/log mode, and keyboard shortcuts.
- Improved surface mode layout so the shared character-cell terminal remains readable beside the
  status rail while retaining F6 log/surface switching.
- Fixed a connected monitor-wall stack overflow caused by synchronous `output_changed` callbacks
  re-entering surface synchronization while a wall was being imported.

### Verification

- Full Java 21 headless `check build` passes with 75 actionable tasks and zero failures.
- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.20.jar`
- Size: `1,127,037` bytes
- SHA-256: `5756389d285aee2c205598b5daf78a766fb2853255286b5c4334b8a1933290a5`

## 1.0.19 — 2026-07-17

### Added

- Added named, typed RedNet monitor services for publishing bounded monitor operations from a
  pocket terminal or another networked computer.
- Added both requested receiver layouts: modem directly beside a monitor wall, and modem beside a
  terminal/turtle gateway that is beside the wall.
- Added `monitor service` registration/list/removal and `monitor remote` clear, write, set, title,
  and color operations.
- Added optional Create 6.0.8 Display Link targeting for complete TerminalCraft monitor walls.
- Added bounded red-alloy attenuation, directional vanilla source/output behavior, and mounted-plane
  output protection.
- Completed bundled channel-zero vanilla bridging, persisted-face sanitation, turtle bundled APIs,
  and vanilla receiver notification coverage for the compact wiring slice.
- Added bounded revisioned monitor character-cell deltas, per-cell color/cursor metadata, and
  persistence for single-tile monitor surfaces with legacy line-data fallback.
- Connected monitor walls now use one global character-cell coordinate space with bounded paginated
  deltas across persisted tile buffers.
- Added caller-owned cooperative event waits with logical deadlines and scheduler wake callbacks;
  waits never block a server thread and remain bounded by subscription, owner, and timeout quotas.
- Added a persisted bounded shell character-cell surface, passive F6 terminal-screen mode, clipped
  viewports, and capped renderer-neutral window/widget layout primitives.
- Added monitor-wall lifecycle coverage for tile persistence, topology reformation, current-anchor
  endpoint rebuilding, and global touch coordinates.
- Hardened connected-wall revision aggregation so a mutation on any tile advances the shared delta
  cursor, and the wall renderer now consumes the persisted global cell surface for per-cell palette
  backgrounds and foregrounds.
- Added an optional Applied Energistics 2 bridge for directly adjacent, bounded read-only cached item
  queries and grid power, boot, channel, node, inventory, and crafting-CPU telemetry.
- Added protocol characterization and live GameTests for both remote-monitor layouts.

### Safety and compatibility

- Monitor payloads are versioned, typed, bounded, non-executable, and consumed separately from
  ordinary modem receive traffic.
- A receiver resolves exactly one wall; ambiguous layouts fail closed.
- Monitor services are opt-in and persistent, but service names are routing aliases rather than
  authentication credentials. Reachable peers within the same RedNet trust boundary may publish.
- Create remains optional and is not bundled. The adapter is only loaded when Create is installed.

### Compatibility

- Minecraft: 1.20.1
- Minecraft Forge: 47.4.10
- Java: 21
- Create integration: 6.0.8 (optional)
- Applied Energistics 2 integration: 15.4.10 Forge (optional, read-only first slice)

### Verification

- The complete Java 21 headless check and production build pass: 74 actionable tasks with zero
  failures.
- Artifact: `terminalcraft-1.20.1-47.4.10-1.0.19.jar`
- Size: `1,125,826` bytes
- SHA-256: `bc8a4f0cd5e7775257192c66ed5e5d791946d64752bd24902f3959c6616db862`
- Direct, gateway, red-alloy, and bundled world-behavior GameTests are packaged for owner-managed
  in-game qualification; the hosted GameTest runner spawn-region stall is not claimed as a pass.

## 1.0.18 — 2026-07-17

### Added

- Added bounded shell command substitution with `$(command)`.
- Added signed 64-bit arithmetic through `$((expression))`, `let NAME=EXPRESSION`, and
  `arith EXPRESSION`.
- Added numeric test operators: `-eq`, `-ne`, `-lt`, `-le`, `-gt`, and `-ge`.
- Added functional `break`, `continue`, and script-local `exit [status]` control flow.
- Added wrench inspection for red alloy wire and bundled cable. Right-clicking with a
  Forge/Common-tagged wrench, including Create's wrench, reports the selected face, connections,
  power, multipart occupancy, and active bundled channels as applicable.
- Added the advanced `production-cell-check.sh` example combining storage, wire, monitor, and
  bundled-output diagnostics.
- Added characterization coverage for the new shell behavior and wrench-recognition contract.

### Changed

- Updated the build and development runtime to Java 21.
- Updated the supported Forge baseline and packaged dependency range to Forge 47.4.10 through the
  47.x line.
- Runtime shell identity now reports the version from the packaged JAR manifest instead of a
  hard-coded development version.
- Single-quoted shell text is now literal; double-quoted text continues to allow expansion.
- Command substitutions preserve command side effects while capturing displayed output, with a
  four-level nesting limit and a 4,096-character output limit.
- Arithmetic is limited to 256-character expressions and 16 levels of nesting, with explicit
  overflow and division-by-zero failures.
- Updated the advanced automation examples to capture returned identifiers and use explicit loop
  and arithmetic control.
- Empty-hand wire interactions are no longer consumed, avoiding conflicts with Carry On and
  similar interaction mods.

### Fixed

- Fixed red alloy wire failing to render an arm toward adjacent terminals and other
  Forge-compatible redstone devices.
- Fixed red alloy wire notifying neighboring receivers while feedback suppression was still
  active, which could make lamps, repeaters, terminals, or other devices read zero instead of the
  newly computed wire power.
- Fixed multipart red alloy wire loading corrupt opposing-face combinations.
- Fixed removed red alloy wire faces retaining stale saved power.
- Fixed bundled cable loading shorter or missing signal arrays without clearing stale values.
- Fixed bundled cable loading corrupt opposing-face combinations.
- Fixed several direct headless tests failing under Java 21 because vanilla registry bootstrap was
  running without ModLauncher's Forge transformation environment.

### Compatibility

- Minecraft: 1.20.1
- Minecraft Forge: 47.4.10
- Java: 21
- Required on both client and server.
- Optional storage integrations remain absence-safe and are not bundled.

### Verification

- The complete Java 21 headless check and production build pass: 69 actionable tasks with zero
  failures.
- Corrective artifact:
  `terminalcraft-1.20.1-47.4.10-1.0.18.jar`
- SHA-256:
  `19a5d66fb212af46a75f1151e4990e64eb66557c811d2efb8e02133fe7c334f0`
- The standalone Forge GameTest runner still has a recorded spawn-region preparation stall before
  tests execute. Full-pack Java 21 startup passes; final P7.1 in-game requalification of the
  corrective wire build remains pending.

## 1.0.17

- Previous development build and baseline for the 1.0.18 consolidation work.
