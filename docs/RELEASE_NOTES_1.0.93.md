# TerminalCraft 1.0.93 — rack automation and remote sessions

Development feature build, not a release candidate. Back up the whole world before testing.

- Schedule delayed rack commands with `server after <ticks> '<command>'`.
- Enable owner-controlled remote rack access with `server remote on`; allow/deny teammates and inspect a saved metadata-only audit.
- Run individual remote commands with `rsh <rack-uuid> '<command>'`, or connect with `rsh connect <rack-uuid>` and return locally using `exit`.
- Create recurring automation with `service define <name> <interval-ticks> '<command>'`, then start, stop, restart, inspect or remove it. Services share scheduler capacity, retain ownership and configuration, and stop with a visible error on command failure.
- Standard SCADA monitor dashboards show quality before value/tag, keeping offline status visible on a 40-column screen.
- The guide now documents the complete rack workflows. Wired SCADA telemetry and authorized HMI-to-PLC/rack workflows have added world-test coverage.

Remote sessions operate on the shared rack shell through the server device registry, not SSH or physical RedNet transport. Full-screen interfaces and remote completion are not forwarded. Sessions are transient; service configuration and remote access settings persist with the rack. Remote permissions do not secure physical rack access. Audit records omit command text and output.

Protocol remains 8; no registry IDs or textures changed. Rack saves gain remote-access and service-manager fields. Do not downgrade a world using these features: older versions do not preserve those fields. Existing world-data backup and downgrade restrictions still apply. No claim of full multiplayer, optional-integration, performance or release qualification.

Verification and artifact details are recorded in `completion-evidence/release/core/RACK_AUTOMATION_1.0.93.md`. This build is not automatically installed into the owner's CurseForge profile.

All 85 baseline headless checks passed with assembly. The preceding source workflow run passed all 165 world tests. Archive metadata, feature classes and bundled guide verified; all 73 textures unchanged from 1.0.92. SHA-256: `e2dafaf5c6148fba354d7d2f9313d8fa1ed2ed00b9c24b28166191aedcd135c3`. No launch of the packaged JAR is claimed.
