# Read a Create machine and switch a remote output

Place a terminal next to a powered Create shaft. Place a Create Redstone Link next to another side of the terminal. Configure a second link as a receiver with the same two frequency items, and place a lamp beside the receiver.

At the terminal, discover the adjacent Create devices:

```sh
device list create
device info <shaft-uuid>
device call <shaft-uuid> create.kinetic.status
device call <transmitter-uuid> create.redstone_link.transmit 15
device call <transmitter-uuid> create.redstone_link.transmit 0
```

The status reports real shaft speed, direction, stress and source information. The two transmit commands turn the linked output on and off. Create retains authority over its kinetic network, frequency matching and receiver behavior; TerminalCraft does not simulate a separate motor or wireless network.

The verified example uses Create 6.0.8, a creative motor set to 64 RPM, and a vanilla lamp. Normal command permissions apply. This is not a demonstration of sequenced gearshift control, moving contraptions or train control.
