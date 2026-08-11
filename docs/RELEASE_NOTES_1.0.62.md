# TerminalCraft 1.0.62 — Independent 16-Channel Computer I/O

TerminalCraft 1.0.62 makes all 16 Bundled Red Alloy channels explicit and independently usable
from every computer-class host.

- `redstone`/`rs` now accepts channel-aware input, output, set, and full status-table forms while
  retaining all existing vanilla six-side forms.
- `wire`/`bundled` now documents external input, accepts `all`, and provides a 16-row status view.
- External input, local computer output, and the combined effective bus signal are separate values;
  a host no longer reads its own local output back as external input.
- Terminal, Server Rack, PLC, and Turtle implement the same adjacent Bundled Red Alloy contract.
- PLC `BUNDLED` input bindings read external input, preventing accidental self-latching through a
  matching output on the attached segment.
- Bundled Red Alloy now accepts and emits redstone only through a color-selected Shielded Red Alloy
  breakout; direct uncolored vanilla redstone no longer creates an ambiguous channel-zero bridge.
- Bundled Network routing now retains a fixed channel lane through the trunk and allows packets to
  leave only by the matching colored Network Cable breakout.

Artifact: `terminalcraft-1.20.1-47.4.10-1.0.62.jar`

Verification:

- 89 automated checks passed.
- Size: `2,681,325` bytes
- SHA-256: `85debd58cc72fd11ed14eaeac8074434868e7f01d1f20ad2494af760b470c06c`
