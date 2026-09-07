# TerminalCraft 1.0.94 — rack controls and Refined Storage crafting fixes

Development build, not a release candidate. Back up the whole world before testing.

- Added administrator settings for rack job/service admission, execution cap, minimum service interval, global remote access and remote response size. `server policy` displays the active limits. Defaults preserve previous behavior; lower admission limits retain saved work.
- Offline SCADA monitor bindings no longer consume live display-update slots.
- Refined Storage crafting now calculates each explicit order's full quantity and starts it once. The previous path used a convenience request that already started work and subtracted matching outstanding orders.
- Explicit native RS completion/cancellation is recorded server-side, allowing later status queries after tasks leave RS's active list. Recipe calculation and execution remain native work.
- The guide includes rack administration and native crafting usage, and clearly identifies AE2 transfer/crafting as unimplemented rather than implying support.

RS remains optional. Its live checks use version 1.12.4. The real two-order recipe produces 16 planks from four logs and retains both completion receipts after the native tasks disappear. This does not prove an authenticated client journey or full in-flight restart recovery: tasks reconstructed by RS after restart do not yet regain the observer. Unobserved disappearance stays unknown; do not blindly resubmit it.

AE2 writes remain disabled pending the owner's access-policy decision. Protocol stays 8; textures and registry IDs are unchanged. New server-private saved data stores bounded crafting receipts. Preserve the whole world and use this version or newer for these features; older versions do not provide the new completion behavior.

Verification and artifact details are in `completion-evidence/release/integrations/INTEGRATION_FIXES_1.0.94.md`. No automatic CurseForge installation is performed. Broader multiplayer, restart, integration, visual and performance qualification remains open.

All 85 baseline headless checks and assembly passed. The preceding RS-profile run passed all 167 world tests. Archive version, guide and feature classes verified; all 73 textures match 1.0.93. SHA-256: `e2704a52c777a28e0ada26ab9afd513c53569ca721b1ae63056deb0f0e83f2b1`.
