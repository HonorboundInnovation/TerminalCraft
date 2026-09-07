# TerminalCraft 1.0.77 — Player turtle inventory screen

- Crouch-right-click with an empty hand opens sixteen turtle slots alongside player inventory and hotbar; ordinary right-click retains the command screen.
- Normal slot transactions and shift-click transfers, selected-slot highlighting, selection arrows, fuel display and a one-item refuel button.
- Inventory sessions reject distant/removed/replaced turtles and close on movement. The separate command screen still follows movement.
- Fuel synchronization preserves balances above 65,535 through split container data fields.

All 85 headless checks and 111 required world tests passed. Live single-player rendering verified screen layout, selection wrapping, coal consumption/fuel updates and reopening. Pointer automation failed at the desktop layer; live buttons were exercised with keyboard navigation, while transfers were tested through actual menu objects.

This is a development checkpoint, not full multiplayer, survival or release qualification. Approved textures and the user's CurseForge installation/worlds are unchanged.
