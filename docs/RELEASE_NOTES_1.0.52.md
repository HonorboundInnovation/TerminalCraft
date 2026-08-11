# TerminalCraft 1.0.52

Build 52 turns the SCADA foundation into a practical advanced HMI system for control rooms,
production lines, and distributed Minecraft factories.

## Multi-page plant displays

An advanced HMI is a persistent named dashboard bound to a monitor wall. Engineers lay out text,
live values, gauges, historian trends, active-alarm panels, operator buttons, and page links on a
12 by 12 grid. The same definition scales across connected monitor walls, with full per-cell color
and atomic frame updates to prevent partial redraws.

## Touch and authenticated control

Monitor walls now act as control surfaces. Touching a page link navigates the dashboard; touching a
button issues the configured value or no-value command as that player. SCADA roles, Device API
permissions, and PLC ownership remain authoritative, and control results remain audited.

## Graphical terminal viewer and designer

Run `hmi` to open a live automatically refreshed HMI in any terminal. Operators navigate dashboards
and pages with arrows, select controls with Tab, and activate them with Enter or the mouse. Engineers
press F2 for a graphical designer with mouse selection, arrow-key movement, Shift+Arrow resizing,
and add/edit/delete actions. `help hmi` contains the complete key reference.

## Shell, Device API, and guide

`scada hmi` manages dashboards, pages, and all widget types from scripts. The virtual SCADA device
adds HMI discovery, page-selection, and widget-activation methods. The searchable in-game
TerminalCraft Guide includes a full worked multi-page plant example, widget argument table, role
behavior, designer controls, and runtime limits.

Install the same JAR on the client and server. TerminalCraft 1.0.52 targets Minecraft 1.20.1 and
Forge 47.4.10.

Artifact: `terminalcraft-1.20.1-47.4.10-1.0.52.jar`

Size: `2,614,100` bytes  
SHA-256: `3f56dda044e8a70e0594d8f691a697fbe5e722dfe6ef71432fbf49f3d0f4b691`
