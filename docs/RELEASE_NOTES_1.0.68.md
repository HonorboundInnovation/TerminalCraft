# TerminalCraft 1.0.68 — PLC output and routing test repairs

Diagnostic development build; full release qualification remains open. Includes the startup and signal repairs documented in the 1.0.67 notes.

- Corrected PLC redstone output direction at the Minecraft block boundary. Named output sides still mean the physical outward face; vanilla signal queries use the opposite, receiver-to-source direction.
- Strengthened the PLC world test: the selected west lamp must light, the east lamp must stay off, and the west lamp must turn off after the program clears its output.
- Updated ordinary network-cable fixtures to provide the solid floor support required by the current cable implementation. Dedicated corner and multipart fixtures retain their explicit layouts. Routing, partition, cache, interface, and message assertions are retained.
- Allowed the scheduled cable connection update to execute before checking persisted connection arms.
- Separated modem queue cleanup from hostname registration in the quota test. Closing all channels clears the queue and withdraws the live hostname; named-delivery verification explicitly registers the name again.

These fixture corrections are not claims of new routing functionality. Remaining world-test and texture failures must still be resolved before release. The owner's CurseForge profile and saves have not been modified.

Verification: 84/85 headless tasks and 82/90 live GameTests pass. All PLC and wired-network-topology tests pass. Five Red Alloy wire tests, three monitor-wall tests, and the cable texture contract remain failed. The diagnostic JAR packages successfully, but the full qualification gate remains failed.
