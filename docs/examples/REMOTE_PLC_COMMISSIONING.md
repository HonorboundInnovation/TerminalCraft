# Commission a PLC from a terminal

Use development build 1.0.92 or newer. This example uses TerminalCraft's authenticated device registry, not the separate physical RedNet cable transport.

1. Place a terminal and a PLC in the same loaded test area. They need not be adjacent. Use the player who owns the PLC.
2. Place a redstone lamp immediately west of the PLC. Leave the north side free for an input. These are compass directions, not the player's facing.
3. In the terminal, run `device list programmable_logic_controller` and copy the PLC UUID. Use `device info <uuid>` to confirm its address.
4. Open `edit /home/player/interlock.plc`, enter the following program, and save it:

```text
IN ENABLE REDSTONE NORTH
OUT MOTOR REDSTONE WEST
RUNG MOTOR = ENABLE
```

5. Replace `<uuid>` below with the discovered UUID (or an existing DNS alias):

```text
plc remote load <uuid> /home/player/interlock.plc
device call <uuid> control.run
device call <uuid> io.get
```

6. Place a redstone block immediately north of the PLC: the west lamp should light. Remove it: the lamp should switch off. Query `io.get` again to inspect the change.
7. Run `device call <uuid> control.stop` to stop the controller.

The remote file-loading command preserves multiline source and uses the normal device permissions and PLC owner check. It does not start the program automatically or grant authority. Remote source is limited to 4,096 characters by the device API; larger programs require the PLC programmer. This example is a commissioning demonstration, not a certified safety interlock.
