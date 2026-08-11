# TerminalCraft 1.0.50

Build 50 makes the high-density cable system readable before placement and diagnosable after it.

## Placement preview

Holding Shielded Red Alloy Wire or Network Cable now outlines all four lanes on the targeted block
face. The selected free lane uses the held cable's color, other free lanes are gray, and occupied
lanes are red. If the aimed quadrant is occupied, the preview shows the actual next free lane that
placement will use. A compact crosshair readout identifies lane, mounting face, color, and channel.

Bundled Red Alloy and Bundled Network items receive a centered face preview and explicitly identify
whether they carry redstone channels or data/control channels 0 through 15.

## Visual identity

Bundled Red Alloy Cable now uses a warm red frame around its visible multicolor conductors. Bundled
Network Cable has its own complete model family using a navy/cyan jacket and white packet markings.
The two trunks remain separate electrically and are now recognizable without reading a tooltip.

## Wrench diagnostics

Using a compatible wrench produces a structured multi-line report rather than one dense sentence.
Surface cable reports include the exact face and one-based lane, color/channel, signal where
applicable, physical links, bundle/trunk status, topology neighbors, and every occupied lane on that
face. Bundled cable reports include mounted faces, active channels, breakouts, and signal sources.

## Illustrated field manual

The in-game TerminalCraft Guide can now render allowlisted manual illustrations. Its wiring chapter
contains searchable plates for the four face lanes and the separate Red Alloy/Network trunks, with
technical captions retained as text for accuracy and future localization.

Install the same JAR on the client and server. TerminalCraft 1.0.50 targets Minecraft 1.20.1 and
Forge 47.4.10.

Artifact: `terminalcraft-1.20.1-47.4.10-1.0.50.jar`  
Size: `2,463,268` bytes  
SHA-256: `0f6aff974cab31ed29a5e9e734205fb8f356d2423511e84a70a45c1bae9160f4`
