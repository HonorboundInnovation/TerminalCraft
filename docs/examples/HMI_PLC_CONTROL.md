# HMI buttons and scheduled PLC control

Use an authorized SCADA engineer/administrator who also owns the PLC. First load a valid PLC program, for example the remote commissioning walkthrough. Find the PLC and monitor UUIDs with filtered `device list` commands.

Create command tags and a dashboard (replace placeholders):

```text
scada tag add hmi.start <plc-uuid> status state - 2 20 @control.run
scada tag add hmi.stop <plc-uuid> status state - 2 20 @control.stop
scada hmi create control-test <monitor-uuid> 2 CONTROLS
scada hmi widget add control-test overview start button 0 2 5 3 hmi.start command START
scada hmi widget add control-test overview stop button 7 2 5 3 hmi.stop command STOP
```

The monitor displays START and STOP controls. From an authenticated terminal, the same activation path is available through the virtual SCADA device; find its UUID with `device list scada`:

```text
device call <scada-uuid> hmi.widget.activate control-test start
device call <scada-uuid> hmi.widget.activate control-test stop
```

SCADA viewers cannot activate these controls. Operator status alone does not bypass the PLC's ownership check. Use a real operator identity with both permissions rather than granting broad roles merely to make a command work.

On a server rack with a compute blade, the authorized PLC owner can queue the same action:

```text
server submit "device call <scada-uuid> hmi.widget.activate control-test start"
server list
```

The job retains the submitting caller's context. Inspect its completion/exit status instead of assuming submission means execution succeeded. This example creates tags/dashboard configuration and actively controls the selected PLC; use a test machine first. It does not automatically reset a latched PLC fault.
