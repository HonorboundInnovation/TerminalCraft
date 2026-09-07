# Move drawer stock into a Sophisticated chest

Place a TerminalCraft terminal between a Storage Drawer and a Sophisticated
Storage chest. Put 24 diamonds in the drawer and leave room in the chest.
Use unprotected containers, or containers for which the integration supports
your access. Normal write and protection checks still apply.

Find each container's UUID:

```text
device list
storage count <drawer-uuid> minecraft:diamond
```

Then transfer the items and inspect the destination:

```text
device transfer <new-operation-uuid> <drawer-uuid> <chest-uuid> minecraft:diamond 24
storage count <chest-uuid> minecraft:diamond
```

Replace every angle-bracket placeholder. The operation UUID identifies one
transfer: generate a fresh UUID for each new transfer, but reuse the original
UUID when retrying that same request. Inspect the transfer result; a full
destination can cause a partial result rather than moving the requested count.

Both containers remain native mod inventories. TerminalCraft moves their actual
items through their exposed inventory interfaces; it does not maintain a second
copy of the stock. Placed Sophisticated Backpacks also expose inventory access;
a backpack carried by a player is not addressed as an adjacent block.
