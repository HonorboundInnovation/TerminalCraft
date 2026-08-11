# TerminalCraft 1.0.49

Build 49 introduces dense RedPower-style surface routing for both TerminalCraft cable families.

## Four lanes and sixteen colors

Shielded Red Alloy Wire and Network Cable now come in every Minecraft dye color. Aim at one of the
four quadrants of a supported floor, wall, or ceiling face to choose a lane. Up to four independent
runs can occupy that face. Craft an existing cable with any vanilla dye to recolor it.

The shared mapping is white `0`, orange `1`, magenta `2`, light blue `3`, yellow `4`, lime `5`, pink
`6`, gray `7`, light gray `8`, cyan `9`, purple `10`, blue `11`, brown `12`, green `13`, red `14`, and
black `15`.

For Shielded Red Alloy Wire, the number identifies its Bundled Red Alloy breakout channel while the
carried value remains an ordinary redstone strength from 0 through 15. For Network Cable, the number
is the default RedNet data/control channel. These are deliberately separate systems.

## Dedicated trunks

- **Bundled Red Alloy Cable** is the existing sixteen-channel redstone cable under its
  compatibility-safe `terminalcraft:bundled_cable` ID. Colored Shielded Red Alloy Wire enters or
  leaves its matching channel.
- **Bundled Network Cable** is a new packet-only trunk carrying data/control channels 0 through 15.
  Colored Network Cable acts as the corresponding channel breakout.

A wired modem directly attached to colored Network Cable automatically opens that cable's channel
and uses it when `modem send` or `modem sendto` omits the channel. A modem on Bundled Network Cable
exposes all sixteen color channels.

## World compatibility

Legacy Red Alloy Wire loads as red Shielded Red Alloy Wire, and legacy Network Cable loads as cyan.
Existing block/item IDs are retained. Normal targeted removal and support loss return correctly
colored items.

## Installation

Install the same JAR on the client and server. TerminalCraft 1.0.49 targets Minecraft 1.20.1,
Forge 47.4.10, and Java 21.

Artifact: `terminalcraft-1.20.1-47.4.10-1.0.49.jar`  
Size: `1,654,203` bytes  
SHA-256: `7c3538e1fff61f81eb99209bc4980416d57a7097aae93398443a1bdc6e393106`
