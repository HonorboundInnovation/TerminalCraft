# Control a Mekanism machine from a terminal

Place an Energized Smelter directly beside a TerminalCraft terminal. Use a public
machine: private/trusted Mekanism machines remain read-only through this adapter.
Normal TerminalCraft write permissions and protection checks still apply.

Find the machine and substitute its listed UUID for `<machine>`:

```text
device list mekanism
device call <machine> mekanism.status
device call <machine> mekanism.redstone_control.set high
device call <machine> mekanism.status
device call <machine> mekanism.redstone_control.set disabled
```

`high` requires a redstone signal for operation. `disabled` disables the redstone
requirement, not the machine itself. The smelter still needs energy, input, and
space for its output. `low` requires no redstone signal; `pulse` is available only
on machines that support it.

Status includes native activity, redstone mode, supported substances, security,
and progress where supported. Energy telemetry, when exposed on the adjacent
side, is available through `mekanism.energy.containers` and uses native Joules.
