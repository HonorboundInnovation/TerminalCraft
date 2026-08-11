# TerminalCraft 1.0.54 — Optional SecurityCraft Integration

TerminalCraft 1.0.54 adds native SecurityCraft 1.10.2.1 integration without making SecurityCraft a
runtime dependency. The adapter follows the official SecurityCraft 1.20.1 GitHub APIs and is loaded
only when Forge reports mod ID `securitycraft` as installed.

## Coverage

- Every adjacent SecurityCraft `IOwnable` block entity is discoverable through the Device API,
  including reinforced blocks and future block families using the same official interfaces.
- Generic custom-option support exposes native values and ranges and performs type/range checked
  owner-authorized changes.
- Generic module support reports accepted, installed, and enabled module types and can toggle only
  modules already physically installed.
- Specialized telemetry and configuration cover alarms, change detectors, username loggers,
  inventory scanners, lasers, keypad/keycard systems, retinal/scanner devices, portable radar,
  projectors, Protecto, rift/trophy systems, cameras, Sonic Security, Secure Redstone Interfaces,
  track mines, IMS, frames/display cases, and sentries.
- Nearby Security Sea Boats are discoverable as mobile entity devices with bounded, owner-only
  position, chest inventory, option, and module telemetry.
- Machine Sensors expose bounded SecurityCraft operational metrics for PLC and SCADA use.
- Ten compile-checked SecurityCraft PLC templates provide practical redstone-facing security control
  patterns without requiring SecurityCraft to compile or run those programs.

## Security boundary

`securitycraft.status` is non-sensitive operational telemetry. Detailed configuration, native audit
entries, module/option details, and all native writes require TerminalCraft permission plus the
online authenticated player whom SecurityCraft recognizes as owner. Machine, device, service, and
process principals cannot impersonate an owner, so unattended PLC control uses the devices' normal
redstone interfaces.

TerminalCraft does not return or accept passcodes, hashes, salts, allowlist/denylist contents,
keycard item data, or owner UUIDs. It does not change SecurityCraft ownership and cannot create or
remove module items.

## Compatibility

- Target: Minecraft 1.20.1, Forge 47.4.10, SecurityCraft 1.10.2.1 when installed.
- SecurityCraft declares Forge 47.3.30 or newer; TerminalCraft's Forge 47.4.10 target satisfies it.
- The adapter is compile-only, is not bundled in TerminalCraft, and is never resolved when
  SecurityCraft is absent.
- Verified artifact details are recorded in the changelog after the production build.
