package com.malice.terminalcraft.network;

import com.malice.terminalcraft.block.NetworkCableBlock;
import com.malice.terminalcraft.blockentity.ModemBlockEntity;
import com.malice.terminalcraft.blockentity.TerminalBlockEntity;
import com.malice.terminalcraft.shell.ShellCommandResult;
import com.malice.terminalcraft.blockentity.NetworkRouterBlockEntity;
import net.minecraft.core.Direction;
import com.malice.terminalcraft.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;

/** Live topology coverage for routes, loops, diagnostics, and partitions. */
@GameTestHolder("terminalcraft")
public final class WiredNetworkTopologyGameTests {
    private static final BlockPos FIRST_MODEM = new BlockPos(1, 2, 1);
    private static final BlockPos CABLE_A = new BlockPos(2, 2, 1);
    private static final BlockPos ROUTER = new BlockPos(3, 2, 1);
    private static final BlockPos CABLE_B = new BlockPos(4, 2, 1);
    private static final BlockPos SECOND_MODEM = new BlockPos(5, 2, 1);

    private WiredNetworkTopologyGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void connectedRouteAndPartition(GameTestHelper helper) {
        helper.setBlock(FIRST_MODEM, ModRegistries.MODEM_BLOCK.get());
        helper.setBlock(CABLE_A, ModRegistries.NETWORK_CABLE_BLOCK.get());
        helper.setBlock(ROUTER, ModRegistries.NETWORK_ROUTER_BLOCK.get());
        helper.setBlock(CABLE_B, ModRegistries.NETWORK_CABLE_BLOCK.get());
        helper.setBlock(SECOND_MODEM, ModRegistries.MODEM_BLOCK.get());
        ((ModemBlockEntity) helper.getBlockEntity(FIRST_MODEM)).setWireless(false);
        ((ModemBlockEntity) helper.getBlockEntity(SECOND_MODEM)).setWireless(false);
        helper.assertTrue(NetworkCableBlock.isConnected(helper.getBlockState(CABLE_A), Direction.EAST)
                        && NetworkCableBlock.isConnected(helper.getBlockState(CABLE_B), Direction.EAST),
                "neighbor placement must extend network cable model arms toward routers and wired modems");

        WiredNetworkTopology.Route route = WiredNetworkTopology.route(helper.getLevel(),
                helper.absolutePos(FIRST_MODEM), helper.absolutePos(SECOND_MODEM));
        helper.assertTrue(route.reachable() && route.routerHops() == 1 && !route.truncated()
                        && route.routerPasses().size() == 1
                        && route.routerPasses().get(0).ingressRouter().equals(helper.absolutePos(ROUTER))
                        && route.routerPasses().get(0).ingressFace() == Direction.WEST
                        && route.routerPasses().get(0).egressRouter().equals(helper.absolutePos(ROUTER))
                        && route.routerPasses().get(0).egressFace() == Direction.EAST,
                "wired subnets must connect through one face-aware router pass: " + route);
        WiredNetworkTopology.Subnet leftSubnet = WiredNetworkTopology.subnet(
                helper.getLevel(), helper.absolutePos(CABLE_A)).orElseThrow();
        WiredNetworkTopology.Subnet rightSubnet = WiredNetworkTopology.subnet(
                helper.getLevel(), helper.absolutePos(CABLE_B)).orElseThrow();
        helper.assertTrue(!leftSubnet.id().equals(rightSubnet.id())
                        && leftSubnet.modemCount() == 1 && rightSubnet.modemCount() == 1
                        && !leftSubnet.truncated() && !rightSubnet.truncated(),
                "router boundaries must create two distinct physical subnets");
        WiredNetworkTopology.RouterView routerView = WiredNetworkTopology.routerInterfaces(
                helper.getLevel(), helper.absolutePos(ROUTER));
        helper.assertTrue(routerView.routerNodeCount() == 1 && routerView.interfaces().size() == 2
                        && routerView.interfaces().stream().allMatch(routerInterface ->
                        routerInterface.attachmentCount() == 1)
                        && routerView.interfaces().stream().map(WiredNetworkTopology.RouterInterface::subnet)
                        .collect(java.util.stream.Collectors.toSet())
                        .equals(java.util.Set.of(leftSubnet.id(), rightSubnet.id()))
                        && !routerView.truncated(),
                "one router must expose one interface for each adjacent physical subnet: " + routerView);
        helper.assertTrue(WiredNetworkTopology.modemSubnets(helper.getLevel(),
                        helper.absolutePos(FIRST_MODEM)).stream().map(WiredNetworkTopology.Subnet::id)
                        .toList().equals(java.util.List.of(leftSubnet.id())),
                "wired modem must resolve its directly attached physical subnet");
        WiredNetworkTopology.Component component = WiredNetworkTopology.inspect(
                helper.getLevel(), helper.absolutePos(ROUTER));
        helper.assertTrue(component.nodeCount() == 3 && component.modemCount() == 2 && !component.truncated(),
                "router diagnostics must report three nodes and two wired modems");
        ModemBlockEntity first = (ModemBlockEntity) helper.getBlockEntity(FIRST_MODEM);
        ModemBlockEntity second = (ModemBlockEntity) helper.getBlockEntity(SECOND_MODEM);
        first.openChannel(41);
        second.openChannel(41);
        helper.assertTrue(first.transmit(41, 42, "wired-test"),
                "online wired modem must accept transmission");
        helper.assertTrue(second.receiveMessages(1).stream().anyMatch(line -> line.contains("msg=wired-test")),
                "connected wired modem must receive traffic through the physical route");

        helper.destroyBlock(CABLE_B);
        helper.assertTrue(!WiredNetworkTopology.connected(helper.getLevel(),
                        helper.absolutePos(FIRST_MODEM), helper.absolutePos(SECOND_MODEM)),
                "breaking one cable must partition the route immediately");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void surfaceCableRoutesAcrossExternalCorner(GameTestHelper helper) {
        BlockPos support = new BlockPos(3, 1, 3);
        BlockPos floorCable = support.above();
        BlockPos wallCable = support.east();
        BlockPos firstModem = floorCable.west();
        BlockPos secondModem = wallCable.north();
        helper.setBlock(support, net.minecraft.world.level.block.Blocks.STONE);
        helper.setBlock(firstModem.below(), net.minecraft.world.level.block.Blocks.STONE);
        helper.setBlock(firstModem, ModRegistries.MODEM_BLOCK.get());
        helper.setBlock(secondModem, ModRegistries.MODEM_BLOCK.get());
        helper.setBlock(floorCable, ModRegistries.NETWORK_CABLE_BLOCK.get().defaultBlockState()
                .setValue(NetworkCableBlock.FACE, Direction.UP));
        helper.setBlock(wallCable, ModRegistries.NETWORK_CABLE_BLOCK.get().defaultBlockState()
                .setValue(NetworkCableBlock.FACE, Direction.EAST));
        ((ModemBlockEntity) helper.getBlockEntity(firstModem)).setWireless(false);
        ((ModemBlockEntity) helper.getBlockEntity(secondModem)).setWireless(false);

        helper.assertTrue(NetworkCableBlock.isConnected(
                        NetworkCableBlock.renderState(helper.getLevel(), helper.absolutePos(floorCable), Direction.UP),
                        Direction.EAST)
                        && NetworkCableBlock.isConnected(
                        NetworkCableBlock.renderState(helper.getLevel(), helper.absolutePos(wallCable), Direction.EAST),
                        Direction.UP),
                "surface network cable must render reciprocal arms around an external corner");
        helper.assertTrue(WiredNetworkTopology.connected(helper.getLevel(), helper.absolutePos(firstModem),
                        helper.absolutePos(secondModem)),
                "RedNet topology must traverse the same unobstructed external corner as Red Alloy Wire");

        helper.setBlock(floorCable.east(), net.minecraft.world.level.block.Blocks.STONE);
        helper.assertTrue(!WiredNetworkTopology.connected(helper.getLevel(), helper.absolutePos(firstModem),
                        helper.absolutePos(secondModem)),
                "a solid block in the bend volume must cut the routed cable corner");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void multipartSurfaceCableRoutesInternalCorner(GameTestHelper helper) {
        BlockPos space = new BlockPos(3, 2, 3);
        helper.setBlock(space.below(), net.minecraft.world.level.block.Blocks.STONE);
        helper.setBlock(space.west(), net.minecraft.world.level.block.Blocks.STONE);
        helper.setBlock(space, ModRegistries.NETWORK_CABLE_BLOCK.get().defaultBlockState()
                .setValue(NetworkCableBlock.FACE, Direction.UP));
        helper.assertTrue(NetworkCableBlock.addFace(helper.getLevel(), helper.absolutePos(space), Direction.EAST),
                "network cable must allow a second supported face in one block space");
        helper.assertTrue(NetworkCableBlock.hasFace(helper.getLevel(), helper.absolutePos(space), Direction.UP)
                        && NetworkCableBlock.hasFace(helper.getLevel(), helper.absolutePos(space), Direction.EAST),
                "both multipart network-cable faces must remain occupied");
        helper.assertTrue(NetworkCableBlock.isConnected(
                        NetworkCableBlock.renderState(helper.getLevel(), helper.absolutePos(space), Direction.UP),
                        Direction.WEST)
                        && NetworkCableBlock.isConnected(
                        NetworkCableBlock.renderState(helper.getLevel(), helper.absolutePos(space), Direction.EAST),
                        Direction.DOWN),
                "multipart network-cable faces must form the same internal corner as Red Alloy Wire");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void contiguousRouterGroupProducesOnePathTransition(GameTestHelper helper) {
        BlockPos first = new BlockPos(1, 2, 2);
        BlockPos westCable = new BlockPos(2, 2, 2);
        BlockPos westRouter = new BlockPos(3, 2, 2);
        BlockPos eastRouter = new BlockPos(4, 2, 2);
        BlockPos eastCable = new BlockPos(5, 2, 2);
        BlockPos second = new BlockPos(6, 2, 2);
        helper.setBlock(first, ModRegistries.MODEM_BLOCK.get());
        helper.setBlock(westCable, ModRegistries.NETWORK_CABLE_BLOCK.get());
        helper.setBlock(westRouter, ModRegistries.NETWORK_ROUTER_BLOCK.get());
        helper.setBlock(eastRouter, ModRegistries.NETWORK_ROUTER_BLOCK.get());
        helper.setBlock(eastCable, ModRegistries.NETWORK_CABLE_BLOCK.get());
        helper.setBlock(second, ModRegistries.MODEM_BLOCK.get());
        ((ModemBlockEntity) helper.getBlockEntity(first)).setWireless(false);
        ((ModemBlockEntity) helper.getBlockEntity(second)).setWireless(false);

        WiredNetworkTopology.Route route = WiredNetworkTopology.route(helper.getLevel(),
                helper.absolutePos(first), helper.absolutePos(second));
        helper.assertTrue(route.reachable() && route.routerHops() == 1
                        && route.routerPasses().size() == 1,
                "one contiguous router group must consume exactly one transition: " + route);
        WiredNetworkTopology.RouterPass pass = route.routerPasses().get(0);
        helper.assertTrue(pass.ingressRouter().equals(helper.absolutePos(westRouter))
                        && pass.ingressFace() == Direction.WEST
                        && pass.egressRouter().equals(helper.absolutePos(eastRouter))
                        && pass.egressFace() == Direction.EAST
                        && pass.traversedRouters().equals(java.util.List.of(
                        helper.absolutePos(westRouter), helper.absolutePos(eastRouter))),
                "path must retain the selected router-group ingress, egress, and internal nodes: " + pass);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void loopDoesNotPreventRouteResolution(GameTestHelper helper) {
        BlockPos a = new BlockPos(2, 2, 2);
        BlockPos b = new BlockPos(3, 2, 2);
        BlockPos c = new BlockPos(3, 2, 3);
        BlockPos d = new BlockPos(2, 2, 3);
        BlockPos left = new BlockPos(1, 2, 2);
        BlockPos right = new BlockPos(4, 2, 3);
        helper.setBlock(left, ModRegistries.MODEM_BLOCK.get());
        helper.setBlock(right, ModRegistries.MODEM_BLOCK.get());
        helper.setBlock(a, ModRegistries.NETWORK_CABLE_BLOCK.get());
        helper.setBlock(b, ModRegistries.NETWORK_CABLE_BLOCK.get());
        helper.setBlock(c, ModRegistries.NETWORK_CABLE_BLOCK.get());
        helper.setBlock(d, ModRegistries.NETWORK_CABLE_BLOCK.get());
        ((ModemBlockEntity) helper.getBlockEntity(left)).setWireless(false);
        ((ModemBlockEntity) helper.getBlockEntity(right)).setWireless(false);
        WiredNetworkTopology.Route route = WiredNetworkTopology.route(helper.getLevel(),
                helper.absolutePos(left), helper.absolutePos(right));
        helper.assertTrue(route.reachable() && route.routerHops() == 0 && !route.truncated(),
                "bounded traversal must resolve a zero-hop route through a cable loop: " + route);
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 80)
    public static void broadcastEnvelopesConsumePerRecipientRouterHops(GameTestHelper helper) {
        BlockPos senderPos = new BlockPos(1, 2, 2);
        BlockPos sharedCable = new BlockPos(2, 2, 2);
        BlockPos localReceiverPos = new BlockPos(2, 2, 3);
        BlockPos routerPos = new BlockPos(3, 2, 2);
        BlockPos remoteCable = new BlockPos(4, 2, 2);
        BlockPos remoteReceiverPos = new BlockPos(5, 2, 2);
        helper.setBlock(senderPos, ModRegistries.MODEM_BLOCK.get());
        helper.setBlock(sharedCable, ModRegistries.NETWORK_CABLE_BLOCK.get());
        helper.setBlock(localReceiverPos, ModRegistries.MODEM_BLOCK.get());
        helper.setBlock(routerPos, ModRegistries.NETWORK_ROUTER_BLOCK.get());
        helper.setBlock(remoteCable, ModRegistries.NETWORK_CABLE_BLOCK.get());
        helper.setBlock(remoteReceiverPos, ModRegistries.MODEM_BLOCK.get());
        ModemBlockEntity sender = (ModemBlockEntity) helper.getBlockEntity(senderPos);
        ModemBlockEntity local = (ModemBlockEntity) helper.getBlockEntity(localReceiverPos);
        ModemBlockEntity remote = (ModemBlockEntity) helper.getBlockEntity(remoteReceiverPos);
        sender.setWireless(false);
        local.setWireless(false);
        remote.setWireless(false);
        sender.openChannel(130);
        local.openChannel(130);
        remote.openChannel(130);

        helper.assertTrue(sender.transmit(130, 131, "hop-accounting"),
                "online sender must accept the broadcast");
        String localMessage = local.receiveMessages(1).stream().findFirst().orElse("");
        String remoteMessage = remote.receiveMessages(1).stream().findFirst().orElse("");
        helper.assertTrue(localMessage.contains("hops=0") && localMessage.contains("msg=hop-accounting"),
                "same-segment recipient must retain the full hop limit: " + localMessage);
        helper.assertTrue(remoteMessage.contains("hops=1") && remoteMessage.contains("msg=hop-accounting"),
                "recipient beyond one router must receive its own decremented envelope: " + remoteMessage);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void namedHostsDeliverOnlyAcrossReachableWiredRoute(GameTestHelper helper) {
        helper.setBlock(FIRST_MODEM, ModRegistries.MODEM_BLOCK.get());
        helper.setBlock(CABLE_A, ModRegistries.NETWORK_CABLE_BLOCK.get());
        helper.setBlock(ROUTER, ModRegistries.NETWORK_ROUTER_BLOCK.get());
        helper.setBlock(CABLE_B, ModRegistries.NETWORK_CABLE_BLOCK.get());
        helper.setBlock(SECOND_MODEM, ModRegistries.MODEM_BLOCK.get());
        ModemBlockEntity first = (ModemBlockEntity) helper.getBlockEntity(FIRST_MODEM);
        ModemBlockEntity second = (ModemBlockEntity) helper.getBlockEntity(SECOND_MODEM);
        first.setWireless(false);
        second.setWireless(false);
        first.openChannel(80);
        second.openChannel(80);
        helper.assertTrue(first.setHostname("factory"), "source hostname must register");
        helper.assertTrue(second.setHostname("warehouse"), "destination hostname must register");
        helper.assertTrue(!first.setHostname("warehouse"), "duplicate hostname must be rejected");
        helper.assertTrue(first.transmitTo("warehouse", 80, 81, "named-test"),
                "named message must cross the connected wired route");
        helper.assertTrue(second.receiveMessages(1).stream().anyMatch(line ->
                        line.contains("from=factory") && line.contains("hops=1") && line.contains("msg=named-test")),
                "destination must receive the named envelope with canonical source hostname");

        helper.destroyBlock(CABLE_B);
        helper.assertTrue(!first.transmitTo("warehouse", 80, 81, "partitioned"),
                "named delivery must fail after its physical route is partitioned");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void namedServicesResolveOnlyAcrossReachableOpenPort(GameTestHelper helper) {
        helper.setBlock(FIRST_MODEM, ModRegistries.MODEM_BLOCK.get());
        helper.setBlock(CABLE_A, ModRegistries.NETWORK_CABLE_BLOCK.get());
        helper.setBlock(ROUTER, ModRegistries.NETWORK_ROUTER_BLOCK.get());
        helper.setBlock(CABLE_B, ModRegistries.NETWORK_CABLE_BLOCK.get());
        helper.setBlock(SECOND_MODEM, ModRegistries.MODEM_BLOCK.get());
        ModemBlockEntity client = (ModemBlockEntity) helper.getBlockEntity(FIRST_MODEM);
        ModemBlockEntity server = (ModemBlockEntity) helper.getBlockEntity(SECOND_MODEM);
        client.setWireless(false);
        server.setWireless(false);
        client.openChannel(81);
        server.openChannel(90);

        helper.assertTrue(server.registerService("inventory", 90),
                "server must register a service on an open port");
        helper.assertTrue(!client.registerService("inventory", 81),
                "duplicate service names must be rejected");
        helper.assertTrue(client.visibleServices(8).stream().anyMatch(service ->
                        service.contains("name=inventory ") && service.contains(" port=90 ")),
                "reachable service discovery must include its canonical name and port");
        helper.assertTrue(client.transmitService("inventory", 81, "count iron"),
                "service call must resolve and cross the wired topology");
        helper.assertTrue(server.receiveMessages(1).stream().anyMatch(line ->
                        line.contains("ch=90") && line.contains("msg=count iron")),
                "service provider must receive the call on its registered port");

        RednetProtocol typedProtocol = new RednetProtocol(
                "terminalcraft:inventory", 2, "application/json");
        helper.assertTrue(RednetNetwork.registerService(helper.getLevel(), server.getModemId(),
                        "inventory", 90, typedProtocol),
                "service owner must be able to publish a typed protocol contract");
        RednetProtocol incompatible = new RednetProtocol(
                "terminalcraft:inventory", 1, "application/json");
        helper.assertTrue(!RednetNetwork.transmitService(helper.getLevel(), client.getModemId(),
                        helper.absolutePos(FIRST_MODEM), "inventory", 81, "mismatch",
                        false, 0, incompatible),
                "typed service calls must reject incompatible protocol versions");
        helper.assertTrue(RednetNetwork.transmitService(helper.getLevel(), client.getModemId(),
                        helper.absolutePos(FIRST_MODEM), "inventory", 81, "{\"count\":true}",
                        false, 0, typedProtocol),
                "typed service calls must accept an exact live protocol contract");
        RednetNetwork.PendingMessage typedMessage = RednetNetwork.receive(
                helper.getLevel(), server.getModemId(), 1).stream().findFirst().orElse(null);
        helper.assertTrue(typedMessage != null
                        && typedMessage.envelope.protocol().equals(typedProtocol.id())
                        && typedMessage.envelope.payloadType().equals(typedProtocol.payloadType()),
                "typed service delivery must retain protocol and payload metadata");

        net.minecraft.nbt.CompoundTag persisted = server.getUpdateTag();
        server.closeChannel(90);
        helper.assertTrue(client.visibleServices(8).isEmpty(),
                "closing the provider port must withdraw its service");
        helper.assertTrue(!client.transmitService("inventory", 81, "offline"),
                "calls to a withdrawn service must fail");

        server.load(persisted);
        server.reregister();
        helper.assertTrue(server.localServices().contains("inventory 90")
                        && client.visibleServices(8).stream().anyMatch(service ->
                        service.contains("name=inventory ") && service.contains(" port=90 ")),
                "persisted service state must rebuild the runtime directory after load");
        helper.assertTrue(client.transmitService("inventory", 81, "after-reload"),
                "restored service must accept calls on its restored open port");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void routerInterfacesPersistAndExposeFaceAssignments(GameTestHelper helper) {
        BlockPos routerPos = new BlockPos(3, 2, 2);
        BlockPos westCable = routerPos.west();
        BlockPos eastCable = routerPos.east();
        helper.setBlock(routerPos, ModRegistries.NETWORK_ROUTER_BLOCK.get());
        helper.setBlock(westCable, ModRegistries.NETWORK_CABLE_BLOCK.get());
        helper.setBlock(eastCable, ModRegistries.NETWORK_CABLE_BLOCK.get());
        NetworkRouterBlockEntity router = (NetworkRouterBlockEntity) helper.getBlockEntity(routerPos);

        helper.assertTrue(router.setInterfaceNetwork(Direction.WEST, " Factory-LAN ")
                        && router.setInterfaceNetwork(Direction.EAST, "Backbone")
                        && router.getInterfaceNetwork(Direction.WEST).equals("factory-lan")
                        && router.getInterfaceNetwork(Direction.EAST).equals("backbone"),
                "router face assignments must validate and canonicalize independently");
        helper.assertTrue(!router.setInterfaceNetwork(Direction.WEST, "invalid.network")
                        && router.getInterfaceNetwork(Direction.WEST).equals("factory-lan"),
                "invalid router updates must preserve the previous face assignment");
        helper.assertTrue(router.setInterfaceEnabled(Direction.EAST, false)
                        && !router.isInterfaceEnabled(Direction.EAST)
                        && router.isInterfaceEnabled(Direction.WEST),
                "router faces must default enabled and support independent administrative shutdown");

        java.util.List<WiredNetworkTopology.RouterAttachment> attachments =
                WiredNetworkTopology.routerAttachments(helper.getLevel(), helper.absolutePos(routerPos));
        helper.assertTrue(attachments.size() == 2
                        && attachments.stream().anyMatch(attachment -> attachment.face() == Direction.WEST
                        && attachment.networkName().equals("factory-lan"))
                        && attachments.stream().anyMatch(attachment -> attachment.face() == Direction.EAST
                        && attachment.networkName().equals("backbone") && !attachment.enabled())
                        && attachments.stream().anyMatch(attachment -> attachment.face() == Direction.WEST
                        && attachment.enabled()),
                "topology must retain face identity and logical assignment for each router attachment: "
                        + attachments);

        net.minecraft.nbt.CompoundTag persisted = router.getUpdateTag();
        router.setInterfaceNetwork(Direction.WEST, "");
        router.setInterfaceNetwork(Direction.EAST, "");
        router.setInterfaceEnabled(Direction.EAST, true);
        router.load(persisted);
        helper.assertTrue(router.getInterfaceNetwork(Direction.WEST).equals("factory-lan")
                        && router.getInterfaceNetwork(Direction.EAST).equals("backbone")
                        && !router.isInterfaceEnabled(Direction.EAST)
                        && router.isInterfaceEnabled(Direction.WEST),
                "router interface assignments and administrative state must survive NBT round-trip");

        persisted.getCompound("Interfaces").putString(Direction.EAST.getName(), "invalid.network");
        persisted.getCompound("DisabledInterfaces").putString(Direction.WEST.getName(), "invalid");
        router.load(persisted);
        helper.assertTrue(router.getInterfaceNetwork(Direction.WEST).equals("factory-lan")
                        && router.getInterfaceNetwork(Direction.EAST).isEmpty()
                        && router.isInterfaceEnabled(Direction.WEST)
                        && !router.isInterfaceEnabled(Direction.EAST),
                "malformed persisted fields must fail independently to safe legacy defaults");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void logicalNetworksEnforceAllWiredDeliveryPaths(GameTestHelper helper) {
        helper.setBlock(FIRST_MODEM, ModRegistries.MODEM_BLOCK.get());
        helper.setBlock(CABLE_A, ModRegistries.NETWORK_CABLE_BLOCK.get());
        helper.setBlock(ROUTER, ModRegistries.NETWORK_ROUTER_BLOCK.get());
        helper.setBlock(CABLE_B, ModRegistries.NETWORK_CABLE_BLOCK.get());
        helper.setBlock(SECOND_MODEM, ModRegistries.MODEM_BLOCK.get());
        ModemBlockEntity factory = (ModemBlockEntity) helper.getBlockEntity(FIRST_MODEM);
        ModemBlockEntity warehouse = (ModemBlockEntity) helper.getBlockEntity(SECOND_MODEM);
        NetworkRouterBlockEntity router = (NetworkRouterBlockEntity) helper.getBlockEntity(ROUTER);
        factory.setWireless(false);
        warehouse.setWireless(false);
        factory.openChannel(120);
        warehouse.openChannel(120);
        factory.setHostname("factory-host");
        warehouse.setHostname("warehouse-host");
        warehouse.registerService("logical-inventory", 120);

        factory.setNetworkName("factory-lan");
        warehouse.setNetworkName("warehouse-lan");
        router.setInterfaceNetwork(Direction.WEST, "factory-lan");
        router.setInterfaceNetwork(Direction.EAST, "warehouse-lan");
        helper.assertTrue(WiredNetworkTopology.connected(helper.getLevel(),
                        helper.absolutePos(FIRST_MODEM), helper.absolutePos(SECOND_MODEM)),
                "router must transition between two correctly assigned logical networks");
        helper.assertTrue(factory.visibleHosts(8).contains("warehouse-host")
                        && factory.visibleServices(8).stream().anyMatch(service ->
                        service.contains("name=logical-inventory ") && service.contains(" port=120 "))
                        && factory.transmitTo("warehouse-host", 120, 121, "allowed"),
                "host discovery, service discovery, and directed delivery must share logical routing policy");
        helper.assertTrue(warehouse.receiveMessages(1).stream().anyMatch(line -> line.contains("msg=allowed")),
                "correctly routed directed traffic must arrive");

        router.setInterfaceEnabled(Direction.EAST, false);
        helper.assertTrue(!WiredNetworkTopology.connected(helper.getLevel(),
                        helper.absolutePos(FIRST_MODEM), helper.absolutePos(SECOND_MODEM))
                        && factory.visibleHosts(8).isEmpty()
                        && factory.visibleServices(8).isEmpty()
                        && !factory.transmitTo("warehouse-host", 120, 121, "administratively-blocked"),
                "a disabled router face must immediately cut discovery and delivery without re-registration");
        factory.transmit(120, 121, "administratively-broadcast-blocked");
        helper.assertTrue(warehouse.receiveMessages(1).isEmpty(),
                "broadcast delivery must honor router administrative state");
        router.setInterfaceEnabled(Direction.EAST, true);

        router.setInterfaceNetwork(Direction.EAST, "factory-lan");
        helper.assertTrue(!WiredNetworkTopology.connected(helper.getLevel(),
                        helper.absolutePos(FIRST_MODEM), helper.absolutePos(SECOND_MODEM)),
                "a modem/router-face conflict on one physical segment must fail closed immediately");
        helper.assertTrue(factory.visibleHosts(8).isEmpty() && factory.visibleServices(8).isEmpty()
                        && !factory.transmitTo("warehouse-host", 120, 121, "blocked"),
                "logical isolation must cover host discovery, service discovery, and directed delivery");
        factory.transmit(120, 121, "broadcast-blocked");
        helper.assertTrue(warehouse.receiveMessages(1).isEmpty(),
                "broadcast delivery must use the same logical isolation rule");

        router.setInterfaceNetwork(Direction.EAST, "");
        warehouse.setNetworkName("");
        helper.assertTrue(WiredNetworkTopology.connected(helper.getLevel(),
                        helper.absolutePos(FIRST_MODEM), helper.absolutePos(SECOND_MODEM)),
                "automatic mode must preserve legacy reachability when no explicit conflict exists");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 40)
    public static void modemLogicalNetworkAssignmentPersistsAndValidates(GameTestHelper helper) {
        BlockPos modemPos = new BlockPos(2, 2, 2);
        helper.setBlock(modemPos, ModRegistries.MODEM_BLOCK.get());
        ModemBlockEntity modem = (ModemBlockEntity) helper.getBlockEntity(modemPos);

        helper.assertTrue(modem.getNetworkName().isEmpty(),
                "legacy modem state must default to automatic network mode");
        helper.assertTrue(modem.setNetworkName(" Factory-LAN ")
                        && modem.getNetworkName().equals("factory-lan"),
                "configured logical networks must canonicalize");
        helper.assertTrue(!modem.setNetworkName("invalid.network")
                        && modem.getNetworkName().equals("factory-lan"),
                "invalid updates must preserve the previous assignment");

        net.minecraft.nbt.CompoundTag persisted = modem.getUpdateTag();
        modem.setNetworkName("");
        modem.load(persisted);
        helper.assertTrue(modem.getNetworkName().equals("factory-lan"),
                "logical network assignment must survive NBT round-trip");

        persisted.putString("NetworkName", "invalid.network");
        modem.load(persisted);
        helper.assertTrue(modem.getNetworkName().isEmpty(),
                "invalid persisted assignments must fail closed to automatic mode");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void numericChannelCompatibilityUsesVersionedEnvelopeAndClampedPorts(GameTestHelper helper) {
        BlockPos senderPos = new BlockPos(2, 2, 2);
        BlockPos receiverPos = new BlockPos(3, 2, 2);
        helper.setBlock(senderPos, ModRegistries.MODEM_BLOCK.get());
        helper.setBlock(receiverPos, ModRegistries.MODEM_BLOCK.get());
        ModemBlockEntity sender = (ModemBlockEntity) helper.getBlockEntity(senderPos);
        ModemBlockEntity receiver = (ModemBlockEntity) helper.getBlockEntity(receiverPos);

        helper.assertTrue(sender.openChannel(7), "sender must be online");
        helper.assertTrue(receiver.openChannel(-1) && receiver.openChannel(70_000),
                "legacy modem API must accept and clamp out-of-range channels");
        helper.assertTrue(receiver.isOpen(0) && receiver.isOpen(65_535),
                "negative and oversized channels must map to compatibility endpoints 0 and 65535");

        helper.assertTrue(sender.transmit(-10, 70_000, "low-channel"),
                "legacy transmission must accept clamped channel arguments");
        helper.assertTrue(sender.transmit(70_000, -10, "high-channel"),
                "both compatibility boundary channels must coexist");
        java.util.List<RednetNetwork.PendingMessage> received = RednetNetwork.receive(
                helper.getLevel(), receiver.getModemId(), 2);
        helper.assertTrue(received.size() == 2, "receiver must get both compatibility messages");

        NetworkEnvelope low = received.get(0).envelope;
        NetworkEnvelope high = received.get(1).envelope;
        helper.assertTrue(low.version() == NetworkEnvelope.CURRENT_VERSION
                        && high.version() == NetworkEnvelope.CURRENT_VERSION
                        && NumericChannelCompatibilityAdapter.PROTOCOL.equals(low.protocol())
                        && NumericChannelCompatibilityAdapter.PROTOCOL.equals(high.protocol())
                        && NetworkEnvelope.TEXT_PAYLOAD.equals(low.payloadType())
                        && NetworkEnvelope.TEXT_PAYLOAD.equals(high.payloadType())
                        && NumericChannelCompatibilityAdapter.BROADCAST_DESTINATION.equals(low.destination())
                        && NumericChannelCompatibilityAdapter.BROADCAST_DESTINATION.equals(high.destination()),
                "legacy numeric traffic must use complete version-2 compatibility envelopes");
        NumericChannelCompatibilityAdapter.ChannelMessage lowProjection =
                NumericChannelCompatibilityAdapter.decode(low);
        NumericChannelCompatibilityAdapter.ChannelMessage highProjection =
                NumericChannelCompatibilityAdapter.decode(high);
        helper.assertTrue(lowProjection.channel() == 0 && lowProjection.replyChannel() == 65_535
                        && lowProjection.message().equals("low-channel")
                        && highProjection.channel() == 65_535 && highProjection.replyChannel() == 0
                        && highProjection.message().equals("high-channel"),
                "adapter projection must preserve clamped ports and text payloads exactly");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void reliableDirectedDeliveryAcknowledgesAndTimesOut(GameTestHelper helper) {
        BlockPos senderPos = new BlockPos(2, 2, 2);
        BlockPos receiverPos = new BlockPos(3, 2, 2);
        helper.setBlock(senderPos, ModRegistries.MODEM_BLOCK.get());
        helper.setBlock(receiverPos, ModRegistries.MODEM_BLOCK.get());
        ModemBlockEntity sender = (ModemBlockEntity) helper.getBlockEntity(senderPos);
        ModemBlockEntity receiver = (ModemBlockEntity) helper.getBlockEntity(receiverPos);
        sender.openChannel(70);
        receiver.openChannel(71);
        sender.setHostname("reliable-sender");
        receiver.setHostname("reliable-receiver");

        RednetDeliveryRuntime.Delivery delivery = sender.transmitReliableTo(
                "reliable-receiver", 71, 70, "apply-once", 5, 1);
        helper.assertTrue(delivery != null && delivery.state() == RednetDeliveryRuntime.State.ACCEPTED,
                "reliable directed send must be accepted and await application acknowledgement");
        RednetNetwork.PendingMessage received = RednetNetwork.receive(
                helper.getLevel(), receiver.getModemId(), 1).stream().findFirst().orElseThrow();
        helper.assertTrue(received.envelope.messageId().equals(delivery.messageId())
                        && !sender.acknowledge(received),
                "a modem other than the retained recipient must not forge an acknowledgement");
        sender.closeChannel(70);
        helper.assertTrue(!receiver.acknowledge(received),
                "acknowledgement must fail when the reverse reply-port transport is unavailable");
        sender.openChannel(70);
        helper.assertTrue(receiver.acknowledge(received),
                "authenticated application acknowledgement must traverse the reverse transport");
        helper.assertTrue(RednetNetwork.receive(helper.getLevel(), sender.getModemId(), 1).isEmpty(),
                "acknowledgement controls must never leak through application receive APIs");
        helper.runAfterDelay(1, () -> {
            RednetNetwork.tickDeliveries(helper.getLevel());
            helper.assertTrue(RednetNetwork.delivery(helper.getLevel(), delivery.messageId())
                            .map(value -> value.state() == RednetDeliveryRuntime.State.ACKNOWLEDGED).orElse(false),
                    "queued acknowledgement control must complete sender-side delivery");

            RednetDeliveryRuntime.Delivery unacknowledged = sender.transmitReliableTo(
                    "reliable-receiver", 71, 70, "timeout", 2, 0);
            helper.assertTrue(unacknowledged != null, "second reliable send must be admitted");
            RednetNetwork.receive(helper.getLevel(), receiver.getModemId(), 1);
            helper.runAfterDelay(3, () -> {
                RednetNetwork.tickDeliveries(helper.getLevel());
                helper.assertTrue(RednetNetwork.delivery(helper.getLevel(), unacknowledged.messageId())
                                .map(value -> value.state() == RednetDeliveryRuntime.State.TIMED_OUT).orElse(false),
                        "an accepted but unacknowledged zero-retry delivery must time out");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void reliableDeliveryRetriesAfterInitialTransportFailure(GameTestHelper helper) {
        BlockPos senderPos = new BlockPos(2, 2, 2);
        BlockPos receiverPos = new BlockPos(3, 2, 2);
        helper.setBlock(senderPos, ModRegistries.MODEM_BLOCK.get());
        helper.setBlock(receiverPos, ModRegistries.MODEM_BLOCK.get());
        ModemBlockEntity sender = (ModemBlockEntity) helper.getBlockEntity(senderPos);
        ModemBlockEntity receiver = (ModemBlockEntity) helper.getBlockEntity(receiverPos);
        sender.openChannel(72);
        sender.setHostname("retry-sender");
        receiver.setHostname("retry-receiver");

        RednetDeliveryRuntime.Delivery delivery = sender.transmitReliableTo(
                "retry-receiver", 73, 72, "available-later", 2, 1);
        helper.assertTrue(delivery != null
                        && delivery.state() == RednetDeliveryRuntime.State.ACCEPTED
                        && delivery.attempts() == 1
                        && !delivery.lastError().isEmpty(),
                "an initially unavailable destination port must retain a retryable delivery");
        helper.assertTrue(RednetNetwork.pendingCount(helper.getLevel(), receiver.getModemId()) == 0,
                "a rejected initial attempt must not expose an application message");
        receiver.openChannel(73);

        helper.runAfterDelay(3, () -> {
            RednetNetwork.tickDeliveries(helper.getLevel());
            RednetDeliveryRuntime.Delivery retried = RednetNetwork.delivery(
                    helper.getLevel(), delivery.messageId()).orElseThrow();
            java.util.List<RednetNetwork.PendingMessage> messages = RednetNetwork.receive(
                    helper.getLevel(), receiver.getModemId(), 2);
            helper.assertTrue(retried.attempts() == 2
                            && retried.state() == RednetDeliveryRuntime.State.ACCEPTED
                            && messages.size() == 1
                            && messages.get(0).envelope.messageId().equals(delivery.messageId()),
                    "the first due retry must deliver the original message identity exactly once");
            helper.assertTrue(receiver.acknowledge(messages.get(0)),
                    "the recovered delivery must remain acknowledgeable");
            helper.runAfterDelay(1, () -> {
                RednetNetwork.tickDeliveries(helper.getLevel());
                helper.assertTrue(RednetNetwork.delivery(helper.getLevel(), delivery.messageId())
                                .map(value -> value.state() == RednetDeliveryRuntime.State.ACKNOWLEDGED)
                                .orElse(false),
                        "the retried delivery must complete after its queued acknowledgement");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void reliableRetrySuppressesDuplicateApplicationDelivery(GameTestHelper helper) {
        BlockPos senderPos = new BlockPos(2, 2, 2);
        BlockPos receiverPos = new BlockPos(3, 2, 2);
        helper.setBlock(senderPos, ModRegistries.MODEM_BLOCK.get());
        helper.setBlock(receiverPos, ModRegistries.MODEM_BLOCK.get());
        ModemBlockEntity sender = (ModemBlockEntity) helper.getBlockEntity(senderPos);
        ModemBlockEntity receiver = (ModemBlockEntity) helper.getBlockEntity(receiverPos);
        sender.openChannel(74);
        receiver.openChannel(75);
        sender.setHostname("duplicate-sender");
        receiver.setHostname("duplicate-receiver");

        RednetDeliveryRuntime.Delivery delivery = sender.transmitReliableTo(
                "duplicate-receiver", 75, 74, "apply-once", 2, 1);
        RednetNetwork.PendingMessage applied = RednetNetwork.receive(
                helper.getLevel(), receiver.getModemId(), 1).stream().findFirst().orElseThrow();
        helper.assertTrue(delivery != null && applied.envelope.messageId().equals(delivery.messageId()),
                "the application must receive the initial reliable attempt");

        helper.runAfterDelay(3, () -> {
            RednetNetwork.tickDeliveries(helper.getLevel());
            RednetDeliveryRuntime.Delivery retried = RednetNetwork.delivery(
                    helper.getLevel(), delivery.messageId()).orElseThrow();
            helper.assertTrue(retried.attempts() == 2
                            && RednetNetwork.receive(helper.getLevel(), receiver.getModemId(), 2).isEmpty(),
                    "a lost acknowledgement may retry transport but must not reapply the same message ID");
            helper.assertTrue(receiver.acknowledge(applied),
                    "the application may acknowledge its retained first delivery after duplicate suppression");
            helper.runAfterDelay(1, () -> {
                RednetNetwork.tickDeliveries(helper.getLevel());
                helper.assertTrue(RednetNetwork.delivery(helper.getLevel(), delivery.messageId())
                                .map(value -> value.state() == RednetDeliveryRuntime.State.ACKNOWLEDGED)
                                .orElse(false),
                        "the delayed acknowledgement must complete the duplicate-suppressed delivery");
                helper.succeed();
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void rednetQueueAndSenderQuotasEnforceLiveBoundaries(GameTestHelper helper) {
        BlockPos receiverPos = new BlockPos(3, 2, 2);
        helper.setBlock(receiverPos, ModRegistries.MODEM_BLOCK.get());
        ModemBlockEntity receiver = (ModemBlockEntity) helper.getBlockEntity(receiverPos);
        receiver.openChannel(90);
        helper.assertTrue(receiver.setHostname("quota-receiver"),
                "quota receiver hostname must register");

        java.util.UUID firstSender = java.util.UUID.randomUUID();
        java.util.UUID secondSender = java.util.UUID.randomUUID();
        java.util.UUID overflowSender = java.util.UUID.randomUUID();
        BlockPos senderPos = helper.absolutePos(new BlockPos(2, 2, 2));
        for (int i = 0; i < RednetTrafficQuota.MAX_MESSAGES_PER_TICK; i++) {
            helper.assertTrue(RednetNetwork.transmitTo(helper.getLevel(), firstSender, senderPos,
                            "quota-receiver", 90, 91, "first-" + i, true, 16),
                    "first sender traffic within the per-tick quota must enter the queue");
            helper.assertTrue(RednetNetwork.transmitTo(helper.getLevel(), secondSender, senderPos,
                            "quota-receiver", 90, 91, "second-" + i, true, 16),
                    "second sender traffic within the per-tick quota must enter the queue");
        }
        helper.assertTrue(receiver.pendingCount() == RednetQueueBudget.MAX_ENTRIES_PER_QUEUE,
                "two isolated sender quotas must fill exactly one 64-entry application queue");
        helper.assertTrue(!RednetNetwork.transmitTo(helper.getLevel(), overflowSender, senderPos,
                        "quota-receiver", 90, 91, "queue-overflow", true, 16)
                        && receiver.pendingCount() == RednetQueueBudget.MAX_ENTRIES_PER_QUEUE,
                "the 65th application entry must fail without growing the queue");
        helper.assertTrue(!RednetNetwork.transmitTo(helper.getLevel(), firstSender, senderPos,
                        "quota-receiver", 90, 91, "sender-overflow", true, 16),
                "the 33rd message from one sender in a logical tick must be rejected");
        RednetNetwork.RejectionDiagnostics quotaDiagnostics =
                RednetNetwork.rejectionDiagnostics(helper.getLevel());
        helper.assertTrue(quotaDiagnostics.applicationQueueFull() >= 1
                        && quotaDiagnostics.rateLimited() >= 1,
                "bounded diagnostics must distinguish queue and sender-rate rejection");

        helper.assertTrue(receiver.receiveMessages(1).size() == 1,
                "dequeue must release one application queue reservation");
        helper.assertTrue(RednetNetwork.transmitTo(helper.getLevel(), overflowSender, senderPos,
                        "quota-receiver", 90, 91, "capacity-reused", true, 16)
                        && receiver.pendingCount() == RednetQueueBudget.MAX_ENTRIES_PER_QUEUE,
                "released queue capacity must be immediately reusable");
        receiver.closeAll();
        receiver.openChannel(90);
        helper.assertTrue(receiver.pendingCount() == 0
                        && RednetNetwork.transmitTo(helper.getLevel(), java.util.UUID.randomUUID(), senderPos,
                        "quota-receiver", 90, 91, "after-close", true, 16),
                "closeAll must discard queued entries and release their accounting");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void rednetMalformedAndUtf8RateIngressFailsClosed(GameTestHelper helper) {
        BlockPos receiverPos = new BlockPos(3, 2, 2);
        helper.setBlock(receiverPos, ModRegistries.MODEM_BLOCK.get());
        ModemBlockEntity receiver = (ModemBlockEntity) helper.getBlockEntity(receiverPos);
        receiver.openChannel(92);
        receiver.setHostname("utf8-receiver");
        BlockPos senderPos = helper.absolutePos(new BlockPos(2, 2, 2));
        java.util.UUID sender = java.util.UUID.randomUUID();
        String payload = "€".repeat(1_365); // 4,095 encoded bytes.

        for (int i = 0; i < 8; i++) {
            helper.assertTrue(RednetNetwork.transmitTo(helper.getLevel(), sender, senderPos,
                            "utf8-receiver", 92, 93, payload, true, 16),
                    "UTF-8 traffic within the aggregate byte quota must pass");
        }
        helper.assertTrue(!RednetNetwork.transmitTo(helper.getLevel(), sender, senderPos,
                        "utf8-receiver", 92, 93, payload, true, 16)
                        && receiver.pendingCount() == 8,
                "the next multibyte payload must exceed the sender byte quota without queue growth");
        helper.assertTrue(!RednetNetwork.transmitTo(helper.getLevel(), null, senderPos,
                        "utf8-receiver", 92, 93, "invalid", true, 16)
                        && !RednetNetwork.transmitTo(helper.getLevel(), sender, null,
                        "utf8-receiver", 92, 93, "invalid", true, 16)
                        && !RednetNetwork.transmitTo(helper.getLevel(), sender, senderPos,
                        null, 92, 93, "invalid", true, 16)
                        && !RednetNetwork.transmitTo(helper.getLevel(), sender, senderPos,
                        "utf8-receiver", 92, 93, null, true, 16)
                        && receiver.pendingCount() == 8,
                "malformed public ingress must fail closed without queue mutation");
        RednetNetwork.RejectionDiagnostics diagnostics =
                RednetNetwork.rejectionDiagnostics(helper.getLevel());
        helper.assertTrue(diagnostics.rateLimited() >= 1 && diagnostics.malformed() >= 4,
                "diagnostics must aggregate rate and malformed rejection without retaining ingress data");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void liveShellProbeAcknowledgesExactlyOnce(GameTestHelper helper) {
        BlockPos terminalPos = new BlockPos(1, 2, 2);
        BlockPos senderPos = new BlockPos(2, 2, 2);
        BlockPos receiverPos = new BlockPos(4, 2, 2);
        helper.setBlock(terminalPos, ModRegistries.TERMINAL_BLOCK.get());
        helper.setBlock(senderPos, ModRegistries.MODEM_BLOCK.get());
        helper.setBlock(receiverPos, ModRegistries.MODEM_BLOCK.get());
        TerminalBlockEntity terminal = (TerminalBlockEntity) helper.getBlockEntity(terminalPos);
        ModemBlockEntity sender = (ModemBlockEntity) helper.getBlockEntity(senderPos);
        ModemBlockEntity receiver = (ModemBlockEntity) helper.getBlockEntity(receiverPos);
        sender.openChannel(81);
        receiver.openChannel(80);
        helper.assertTrue(receiver.setHostname("shell-probe-target"),
                "live probe destination hostname must register");

        ShellCommandResult submitted = terminal.getShell().executeForResult(
                "modem probe shell-probe-target 80 81 health check");
        helper.assertTrue(submitted.exitCode() == 0 && submitted.outputLines().size() == 1
                        && submitted.outputLines().get(0).contains(" state=accepted "),
                "live shell probe must expose initial reliable admission: " + submitted.outputLines());
        String id = submitted.outputLines().get(0).substring(3, 39);
        java.util.List<RednetNetwork.PendingMessage> received = RednetNetwork.receive(
                helper.getLevel(), receiver.getModemId(), 2);
        helper.assertTrue(received.size() == 1 && received.get(0).message.equals("health check"),
                "live shell probe must enqueue exactly one application payload");
        helper.assertTrue(receiver.acknowledge(received.get(0)),
                "recipient must acknowledge the correlated live shell probe");

        helper.runAfterDelay(1, () -> {
            RednetNetwork.tickDeliveries(helper.getLevel());
            ShellCommandResult status = terminal.getShell().executeForResult("modem delivery " + id);
            helper.assertTrue(status.exitCode() == 0 && status.outputLines().size() == 1
                            && status.outputLines().get(0).contains(" state=acknowledged ")
                            && RednetNetwork.receive(helper.getLevel(), receiver.getModemId(), 2).isEmpty(),
                    "live shell status must expose acknowledgement without duplicate application delivery: "
                            + status.outputLines());
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void liveShellProbeReachesBoundedTimeout(GameTestHelper helper) {
        BlockPos terminalPos = new BlockPos(1, 2, 2);
        BlockPos senderPos = new BlockPos(2, 2, 2);
        BlockPos receiverPos = new BlockPos(4, 2, 2);
        helper.setBlock(terminalPos, ModRegistries.TERMINAL_BLOCK.get());
        helper.setBlock(senderPos, ModRegistries.MODEM_BLOCK.get());
        helper.setBlock(receiverPos, ModRegistries.MODEM_BLOCK.get());
        TerminalBlockEntity terminal = (TerminalBlockEntity) helper.getBlockEntity(terminalPos);
        ModemBlockEntity sender = (ModemBlockEntity) helper.getBlockEntity(senderPos);
        ModemBlockEntity receiver = (ModemBlockEntity) helper.getBlockEntity(receiverPos);
        sender.openChannel(83);
        receiver.openChannel(82);
        receiver.setHostname("shell-timeout-target");

        ShellCommandResult submitted = terminal.getShell().executeForResult(
                "modem probe shell-timeout-target 82 83 no ack");
        helper.assertTrue(submitted.exitCode() == 0 && submitted.outputLines().size() == 1,
                "live timeout probe must be admitted: " + submitted.outputLines());
        String id = submitted.outputLines().get(0).substring(3, 39);
        helper.assertTrue(RednetNetwork.receive(helper.getLevel(), receiver.getModemId(), 1).size() == 1,
                "initial timeout probe attempt must reach the application");

        helper.runAfterDelay(65, () -> {
            RednetNetwork.tickDeliveries(helper.getLevel());
            ShellCommandResult status = terminal.getShell().executeForResult("modem delivery " + id);
            helper.assertTrue(status.exitCode() == 0 && status.outputLines().size() == 1
                            && status.outputLines().get(0).contains(" state=timed_out ")
                            && status.outputLines().get(0).contains(" attempts=3 "),
                    "unacknowledged live shell probe must terminate after the bounded retry budget: "
                            + status.outputLines());
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void dimensionsIsolateNamesServicesQueuesAndReliableState(GameTestHelper helper) {
        net.minecraft.server.level.ServerLevel overworld = helper.getLevel();
        net.minecraft.server.level.ServerLevel nether = overworld.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        helper.assertTrue(nether != null, "the GameTest server must expose the Nether isolation scope");

        java.util.UUID sender = java.util.UUID.randomUUID();
        java.util.UUID overworldReceiver = java.util.UUID.randomUUID();
        java.util.UUID netherReceiver = java.util.UUID.randomUUID();
        BlockPos senderPos = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos receiverPos = helper.absolutePos(new BlockPos(3, 2, 2));
        BlockPos netherPos = new BlockPos(receiverPos.getX(), 64, receiverPos.getZ());

        RednetNetwork.open(overworld, sender, 140, senderPos, true, 32);
        RednetNetwork.open(overworld, overworldReceiver, 141, receiverPos, true, 32);
        RednetNetwork.open(nether, sender, 140, netherPos.west(), true, 32);
        RednetNetwork.open(nether, netherReceiver, 141, netherPos, true, 32);
        helper.assertTrue(RednetNetwork.registerHost(overworld, overworldReceiver, "shared-host")
                        && RednetNetwork.registerHost(nether, netherReceiver, "shared-host"),
                "identical host aliases must register independently in separate dimensions");
        helper.assertTrue(RednetNetwork.registerService(overworld, overworldReceiver,
                        "shared-service", 141)
                        && RednetNetwork.registerService(nether, netherReceiver,
                        "shared-service", 141),
                "identical service aliases must register independently in separate dimensions");

        helper.assertTrue(RednetNetwork.transmitTo(overworld, sender, senderPos,
                        "shared-host", 141, 140, "overworld-only", true, 32),
                "directed delivery must resolve the destination in the caller dimension");
        helper.assertTrue(RednetNetwork.pendingCount(overworld, overworldReceiver) == 1
                        && RednetNetwork.pendingCount(nether, netherReceiver) == 0,
                "a same-named Nether endpoint must not observe Overworld queue traffic");
        helper.assertTrue(RednetNetwork.reachableServices(overworld, sender, senderPos,
                        true, 32, 8).equals(java.util.List.of("shared-service 141")),
                "service discovery must use only the caller dimension directory and subscriptions");

        RednetDeliveryRuntime.Delivery delivery = RednetNetwork.transmitReliableTo(overworld,
                sender, senderPos, "shared-host", 141, 140, "scoped-reliable",
                true, 32, 5, 0);
        helper.assertTrue(delivery != null
                        && RednetNetwork.delivery(overworld, sender, delivery.messageId()).isPresent()
                        && RednetNetwork.delivery(nether, sender, delivery.messageId()).isEmpty(),
                "reliable state and sender ownership must remain dimension-local");
        helper.assertTrue(RednetNetwork.receive(nether, netherReceiver, 8).isEmpty(),
                "cross-dimensional matching UUIDs, names, ports, and wireless range never imply a gateway");

        RednetNetwork.RejectionDiagnostics overworldBefore = RednetNetwork.rejectionDiagnostics(overworld);
        RednetNetwork.RejectionDiagnostics netherBefore = RednetNetwork.rejectionDiagnostics(nether);
        helper.assertTrue(!RednetNetwork.transmitTo(overworld, sender, senderPos,
                        null, 141, 140, "malformed", true, 32),
                "malformed directed ingress must fail closed in its caller dimension");
        RednetNetwork.RejectionDiagnostics overworldAfter = RednetNetwork.rejectionDiagnostics(overworld);
        RednetNetwork.RejectionDiagnostics netherAfter = RednetNetwork.rejectionDiagnostics(nether);
        helper.assertTrue(overworldAfter.malformed() == overworldBefore.malformed() + 1
                        && netherAfter.equals(netherBefore),
                "rejection diagnostics must mutate only the caller dimension scope");

        for (int attempt = 0; attempt < RednetTrafficQuota.MAX_MESSAGES_PER_TICK; attempt++) {
            RednetNetwork.transmitTo(overworld, sender, senderPos,
                    "shared-host", 141, 140, "quota-" + attempt, true, 32);
        }
        RednetNetwork.RejectionDiagnostics quotaBefore = RednetNetwork.rejectionDiagnostics(overworld);
        helper.assertTrue(!RednetNetwork.transmitTo(overworld, sender, senderPos,
                        "shared-host", 141, 140, "quota-rejected", true, 32),
                "traffic beyond the per-scope sender budget must be rejected");
        RednetNetwork.RejectionDiagnostics quotaAfter = RednetNetwork.rejectionDiagnostics(overworld);
        helper.assertTrue(quotaAfter.rateLimited() == quotaBefore.rateLimited() + 1
                        && RednetNetwork.rejectionDiagnostics(nether).equals(netherBefore),
                "traffic quotas and their diagnostics must remain dimension-local");
        helper.assertTrue(RednetNetwork.transmitTo(nether, sender, netherPos.west(),
                        "shared-host", 141, 140, "nether-quota-independent", true, 32)
                        && RednetNetwork.pendingCount(nether, netherReceiver) == 1,
                "exhausting a sender quota in one dimension must not consume the same UUID's quota elsewhere");

        RednetNetwork.closeAll(overworld, sender);
        RednetNetwork.closeAll(overworld, overworldReceiver);
        RednetNetwork.closeAll(nether, sender);
        RednetNetwork.closeAll(nether, netherReceiver);
        RednetNetwork.unregisterHost(overworld, overworldReceiver);
        RednetNetwork.unregisterHost(nether, netherReceiver);
        RednetNetwork.unregisterServices(overworld, overworldReceiver);
        RednetNetwork.unregisterServices(nether, netherReceiver);
        helper.succeed();
    }


    @GameTest(template = "empty", timeoutTicks = 80)
    public static void wiredRoutesRequireWiredEndpointsAndEnabledRouterFaces(GameTestHelper helper) {
        BlockPos firstPos = new BlockPos(1, 2, 2);
        BlockPos westCable = new BlockPos(2, 2, 2);
        BlockPos routerPos = new BlockPos(3, 2, 2);
        BlockPos eastCable = new BlockPos(4, 2, 2);
        BlockPos secondPos = new BlockPos(5, 2, 2);
        helper.setBlock(firstPos, ModRegistries.MODEM_BLOCK.get());
        helper.setBlock(westCable, ModRegistries.NETWORK_CABLE_BLOCK.get());
        helper.setBlock(routerPos, ModRegistries.NETWORK_ROUTER_BLOCK.get());
        helper.setBlock(eastCable, ModRegistries.NETWORK_CABLE_BLOCK.get());
        helper.setBlock(secondPos, ModRegistries.MODEM_BLOCK.get());

        ModemBlockEntity first = (ModemBlockEntity) helper.getBlockEntity(firstPos);
        ModemBlockEntity second = (ModemBlockEntity) helper.getBlockEntity(secondPos);
        NetworkRouterBlockEntity router = (NetworkRouterBlockEntity) helper.getBlockEntity(routerPos);
        first.setWireless(false);
        second.setWireless(false);

        helper.assertTrue(WiredNetworkTopology.connected(helper.getLevel(),
                        helper.absolutePos(firstPos), helper.absolutePos(secondPos)),
                "two wired endpoints must traverse the enabled physical router path");

        router.setInterfaceEnabled(Direction.WEST, false);
        helper.assertTrue(!WiredNetworkTopology.connected(helper.getLevel(),
                        helper.absolutePos(firstPos), helper.absolutePos(secondPos)),
                "disabling the ingress router face must cut the route immediately");
        router.setInterfaceEnabled(Direction.WEST, true);
        router.setInterfaceEnabled(Direction.EAST, false);
        helper.assertTrue(!WiredNetworkTopology.connected(helper.getLevel(),
                        helper.absolutePos(firstPos), helper.absolutePos(secondPos)),
                "disabling the egress router face must cut the route immediately");
        router.setInterfaceEnabled(Direction.EAST, true);
        helper.assertTrue(WiredNetworkTopology.connected(helper.getLevel(),
                        helper.absolutePos(firstPos), helper.absolutePos(secondPos)),
                "re-enabling both router faces must restore the live route");

        second.setWireless(true);
        helper.assertTrue(!WiredNetworkTopology.connected(helper.getLevel(),
                        helper.absolutePos(firstPos), helper.absolutePos(secondPos)),
                "a wireless modem touching cable must not become a wired route endpoint");
        WiredNetworkTopology.Subnet subnet = WiredNetworkTopology.subnet(
                helper.getLevel(), helper.absolutePos(eastCable)).orElseThrow();
        helper.assertTrue(subnet.modemCount() == 0,
                "wireless modems must not contribute to wired subnet endpoint counts");
        helper.assertTrue(WiredNetworkTopology.modemSubnets(helper.getLevel(),
                        helper.absolutePos(secondPos)).isEmpty(),
                "wireless modems must not expose wired subnet attachments through diagnostics");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void disabledRouterLinkSplitsRoutesGroupsAndDiagnostics(GameTestHelper helper) {
        BlockPos first = new BlockPos(1, 2, 2);
        BlockPos westCable = new BlockPos(2, 2, 2);
        BlockPos westRouter = new BlockPos(3, 2, 2);
        BlockPos eastRouter = new BlockPos(4, 2, 2);
        BlockPos eastCable = new BlockPos(5, 2, 2);
        BlockPos second = new BlockPos(6, 2, 2);
        helper.setBlock(first, ModRegistries.MODEM_BLOCK.get());
        helper.setBlock(westCable, ModRegistries.NETWORK_CABLE_BLOCK.get());
        helper.setBlock(westRouter, ModRegistries.NETWORK_ROUTER_BLOCK.get());
        helper.setBlock(eastRouter, ModRegistries.NETWORK_ROUTER_BLOCK.get());
        helper.setBlock(eastCable, ModRegistries.NETWORK_CABLE_BLOCK.get());
        helper.setBlock(second, ModRegistries.MODEM_BLOCK.get());
        ((ModemBlockEntity) helper.getBlockEntity(first)).setWireless(false);
        ((ModemBlockEntity) helper.getBlockEntity(second)).setWireless(false);
        NetworkRouterBlockEntity router = (NetworkRouterBlockEntity) helper.getBlockEntity(westRouter);

        helper.assertTrue(router.setInterfaceEnabled(Direction.EAST, false),
                "router-to-router interface must support administrative shutdown");
        helper.assertTrue(!WiredNetworkTopology.connected(helper.getLevel(), helper.absolutePos(first),
                        helper.absolutePos(second)),
                "a disabled internal router link must partition the forwarding path");
        WiredNetworkTopology.RouterView splitView = WiredNetworkTopology.routerInterfaces(
                helper.getLevel(), helper.absolutePos(westRouter));
        WiredNetworkTopology.Component splitComponent = WiredNetworkTopology.inspect(
                helper.getLevel(), helper.absolutePos(westRouter));
        helper.assertTrue(splitView.routerNodeCount() == 1 && splitView.interfaces().size() == 1,
                "active router-group diagnostics must stop at a disabled inter-router face: " + splitView);
        helper.assertTrue(splitComponent.nodeCount() == 2 && splitComponent.modemCount() == 1,
                "component diagnostics must report only the enabled side of a partition: " + splitComponent);

        router.setInterfaceEnabled(Direction.EAST, true);
        helper.assertTrue(WiredNetworkTopology.connected(helper.getLevel(), helper.absolutePos(first),
                        helper.absolutePos(second)),
                "re-enabling the internal link must merge the route immediately");
        helper.assertTrue(WiredNetworkTopology.routerInterfaces(helper.getLevel(),
                        helper.absolutePos(westRouter)).routerNodeCount() == 2,
                "router-group diagnostics must merge again after re-enabling the link");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void multipartFaceRemovalInvalidatesOnlyAffectedPath(GameTestHelper helper) {
        BlockPos cable = new BlockPos(3, 2, 3);
        BlockPos first = cable.east();
        BlockPos second = cable.above();
        helper.setBlock(cable.below(), net.minecraft.world.level.block.Blocks.STONE);
        helper.setBlock(cable.west(), net.minecraft.world.level.block.Blocks.STONE);
        helper.setBlock(first, ModRegistries.MODEM_BLOCK.get());
        helper.setBlock(second, ModRegistries.MODEM_BLOCK.get());
        helper.setBlock(cable, ModRegistries.NETWORK_CABLE_BLOCK.get().defaultBlockState()
                .setValue(NetworkCableBlock.FACE, Direction.UP));
        ((ModemBlockEntity) helper.getBlockEntity(first)).setWireless(false);
        ((ModemBlockEntity) helper.getBlockEntity(second)).setWireless(false);
        helper.assertTrue(NetworkCableBlock.addFace(helper.getLevel(), helper.absolutePos(cable), Direction.EAST),
                "test setup must add a supported second cable face");
        helper.assertTrue(WiredNetworkTopology.connected(helper.getLevel(), helper.absolutePos(first),
                        helper.absolutePos(second)),
                "multipart faces in one space must bridge their respective planes");

        helper.assertTrue(NetworkCableBlock.removeFace(helper.getLevel(), helper.absolutePos(cable),
                        Direction.EAST, false),
                "one multipart face must be removable without deleting the remaining cable");
        helper.assertTrue(!WiredNetworkTopology.connected(helper.getLevel(), helper.absolutePos(first),
                        helper.absolutePos(second)),
                "removing one face must invalidate the path that depended on that face immediately");
        helper.assertTrue(NetworkCableBlock.hasFace(helper.getLevel(), helper.absolutePos(cable), Direction.UP)
                        && !NetworkCableBlock.hasFace(helper.getLevel(), helper.absolutePos(cable), Direction.EAST),
                "the unrelated face must remain present after targeted removal");

        helper.assertTrue(NetworkCableBlock.removeFace(helper.getLevel(), helper.absolutePos(cable),
                        Direction.UP, false)
                        && helper.getBlockState(cable).isAir(),
                "removing the final face must remove the cable block");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void routeCacheInvalidatesAcrossTopologyLifecycle(GameTestHelper helper) {
        BlockPos first = new BlockPos(1, 2, 2);
        BlockPos cable = new BlockPos(2, 2, 2);
        BlockPos routerPos = new BlockPos(3, 2, 2);
        BlockPos second = new BlockPos(4, 2, 2);
        helper.setBlock(first, ModRegistries.MODEM_BLOCK.get());
        helper.setBlock(cable, ModRegistries.NETWORK_CABLE_BLOCK.get());
        helper.setBlock(routerPos, ModRegistries.NETWORK_ROUTER_BLOCK.get());
        helper.setBlock(second, ModRegistries.MODEM_BLOCK.get());
        ((ModemBlockEntity) helper.getBlockEntity(first)).setWireless(false);
        ((ModemBlockEntity) helper.getBlockEntity(second)).setWireless(false);

        BlockPos absoluteFirst = helper.absolutePos(first);
        BlockPos absoluteSecond = helper.absolutePos(second);
        WiredNetworkTopology.CacheDiagnostics before =
                WiredNetworkTopology.cacheDiagnostics(helper.getLevel());
        helper.assertTrue(WiredNetworkTopology.connected(helper.getLevel(), absoluteFirst, absoluteSecond),
                "the initial route must be computed from live topology");
        WiredNetworkTopology.CacheDiagnostics computed =
                WiredNetworkTopology.cacheDiagnostics(helper.getLevel());
        helper.assertTrue(computed.computations() == before.computations() + 1
                        && computed.entries() >= 1,
                "the first route query must create one bounded snapshot: " + computed);

        helper.assertTrue(WiredNetworkTopology.connected(helper.getLevel(), absoluteFirst, absoluteSecond),
                "an unchanged route must remain reachable");
        WiredNetworkTopology.CacheDiagnostics reused =
                WiredNetworkTopology.cacheDiagnostics(helper.getLevel());
        helper.assertTrue(reused.computations() == computed.computations()
                        && reused.hits() == computed.hits() + 1,
                "steady-state routing must reuse the snapshot without another topology scan: " + reused);

        NetworkRouterBlockEntity router = (NetworkRouterBlockEntity) helper.getBlockEntity(routerPos);
        router.setInterfaceEnabled(Direction.WEST, false);
        WiredNetworkTopology.CacheDiagnostics configured =
                WiredNetworkTopology.cacheDiagnostics(helper.getLevel());
        helper.assertTrue(configured.revision() > reused.revision() && configured.entries() == 0
                        && !WiredNetworkTopology.connected(helper.getLevel(), absoluteFirst, absoluteSecond),
                "router configuration must invalidate cached reachability immediately");
        router.setInterfaceEnabled(Direction.WEST, true);
        helper.assertTrue(WiredNetworkTopology.connected(helper.getLevel(), absoluteFirst, absoluteSecond),
                "re-enabling the router face must recompute and restore the route");

        helper.destroyBlock(cable);
        helper.assertTrue(!WiredNetworkTopology.connected(helper.getLevel(), absoluteFirst, absoluteSecond),
                "block break must invalidate and recompute an unreachable route");
        helper.setBlock(cable, ModRegistries.NETWORK_CABLE_BLOCK.get());
        helper.assertTrue(WiredNetworkTopology.connected(helper.getLevel(), absoluteFirst, absoluteSecond),
                "block replacement must invalidate and restore the route");

        helper.setBlock(routerPos, ModRegistries.NETWORK_CABLE_BLOCK.get());
        WiredNetworkTopology.Route cableRoute = WiredNetworkTopology.route(
                helper.getLevel(), absoluteFirst, absoluteSecond);
        helper.assertTrue(cableRoute.reachable() && cableRoute.routerHops() == 0,
                "in-place router replacement must reclassify the route as a zero-hop cable segment");
        helper.setBlock(routerPos, ModRegistries.NETWORK_ROUTER_BLOCK.get());
        WiredNetworkTopology.Route restoredRouterRoute = WiredNetworkTopology.route(
                helper.getLevel(), absoluteFirst, absoluteSecond);
        helper.assertTrue(restoredRouterRoute.reachable() && restoredRouterRoute.routerHops() == 1,
                "in-place cable replacement must restore router-hop classification");

        WiredNetworkTopology.CacheDiagnostics lifecycle =
                WiredNetworkTopology.cacheDiagnostics(helper.getLevel());
        WiredNetworkTopology.invalidateChunk(helper.getLevel(),
                new net.minecraft.world.level.ChunkPos(helper.absolutePos(cable)));
        WiredNetworkTopology.CacheDiagnostics chunkInvalidated =
                WiredNetworkTopology.cacheDiagnostics(helper.getLevel());
        helper.assertTrue(chunkInvalidated.revision() > lifecycle.revision()
                        && chunkInvalidated.entries() == 0,
                "chunk load/unload invalidation must discard snapshots before later route queries");
        helper.succeed();
    }


    @GameTest(template = "empty", timeoutTicks = 80)
    public static void indexedColdRouteDoesNotRefreshWorldSnapshots(GameTestHelper helper) {
        BlockPos first = new BlockPos(1, 2, 2);
        BlockPos cable = new BlockPos(2, 2, 2);
        BlockPos second = new BlockPos(3, 2, 2);
        helper.setBlock(first, ModRegistries.MODEM_BLOCK.get());
        helper.setBlock(cable, ModRegistries.NETWORK_CABLE_BLOCK.get());
        helper.setBlock(second, ModRegistries.MODEM_BLOCK.get());
        ((ModemBlockEntity) helper.getBlockEntity(first)).setWireless(false);
        ((ModemBlockEntity) helper.getBlockEntity(second)).setWireless(false);

        WiredNetworkTopology.IndexDiagnostics indexed =
                WiredNetworkTopology.indexDiagnostics(helper.getLevel());
        helper.assertTrue(indexed.nodes() >= 3 && !indexed.truncated(),
                "placement and block-entity lifecycle must populate the bounded loaded index: " + indexed);
        BlockPos absoluteFirst = helper.absolutePos(first);
        BlockPos absoluteSecond = helper.absolutePos(second);
        helper.assertTrue(WiredNetworkTopology.connected(helper.getLevel(), absoluteFirst, absoluteSecond),
                "a cold route must resolve from the loaded topology index");
        WiredNetworkTopology.IndexDiagnostics afterColdRoute =
                WiredNetworkTopology.indexDiagnostics(helper.getLevel());
        helper.assertTrue(afterColdRoute.refreshedPositions() == indexed.refreshedPositions()
                        && afterColdRoute.revisions() == indexed.revisions(),
                "cold route lookup must not read or refresh world topology: " + afterColdRoute);

        WiredNetworkTopology.unloadChunk(helper.getLevel(),
                new net.minecraft.world.level.ChunkPos(absoluteFirst));
        WiredNetworkTopology.IndexDiagnostics unloaded =
                WiredNetworkTopology.indexDiagnostics(helper.getLevel());
        helper.assertTrue(unloaded.nodes() < afterColdRoute.nodes()
                        && !WiredNetworkTopology.connected(helper.getLevel(), absoluteFirst, absoluteSecond),
                "chunk unload must remove indexed nodes without consulting unloaded world state: " + unloaded);

        WiredNetworkTopology.loadChunk(helper.getLevel(), helper.getLevel().getChunkAt(absoluteFirst));
        WiredNetworkTopology.IndexDiagnostics reloaded =
                WiredNetworkTopology.indexDiagnostics(helper.getLevel());
        helper.assertTrue(reloaded.nodes() >= afterColdRoute.nodes()
                        && WiredNetworkTopology.connected(helper.getLevel(), absoluteFirst, absoluteSecond),
                "chunk load must repopulate cable-only topology and repair boundary snapshots: " + reloaded);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void rapidPartitionMergeNeverUsesStaleRoutes(GameTestHelper helper) {
        BlockPos firstPos = new BlockPos(1, 2, 2);
        BlockPos bridgePos = new BlockPos(2, 2, 2);
        BlockPos secondPos = new BlockPos(3, 2, 2);
        helper.setBlock(firstPos, ModRegistries.MODEM_BLOCK.get());
        helper.setBlock(bridgePos, ModRegistries.NETWORK_CABLE_BLOCK.get());
        helper.setBlock(secondPos, ModRegistries.MODEM_BLOCK.get());
        ModemBlockEntity first = (ModemBlockEntity) helper.getBlockEntity(firstPos);
        ModemBlockEntity second = (ModemBlockEntity) helper.getBlockEntity(secondPos);
        first.setWireless(false);
        second.setWireless(false);
        first.openChannel(144);
        second.openChannel(144);
        helper.assertTrue(second.setHostname("rapid-target"), "target hostname must register");

        BlockPos absoluteFirst = helper.absolutePos(firstPos);
        BlockPos absoluteSecond = helper.absolutePos(secondPos);
        long previousRevision = WiredNetworkTopology.cacheDiagnostics(helper.getLevel()).revision();
        for (int cycle = 0; cycle < 8; cycle++) {
            helper.destroyBlock(bridgePos);
            WiredNetworkTopology.CacheDiagnostics partitioned =
                    WiredNetworkTopology.cacheDiagnostics(helper.getLevel());
            helper.assertTrue(partitioned.revision() > previousRevision
                            && !WiredNetworkTopology.connected(helper.getLevel(), absoluteFirst, absoluteSecond)
                            && !first.transmitTo("rapid-target", 144, 145, "blocked-" + cycle),
                    "partition cycle " + cycle + " must reject stale reachability immediately");

            helper.setBlock(bridgePos, ModRegistries.NETWORK_CABLE_BLOCK.get());
            WiredNetworkTopology.CacheDiagnostics merged =
                    WiredNetworkTopology.cacheDiagnostics(helper.getLevel());
            helper.assertTrue(merged.revision() > partitioned.revision()
                            && WiredNetworkTopology.connected(helper.getLevel(), absoluteFirst, absoluteSecond)
                            && first.transmitTo("rapid-target", 144, 145, "merged-" + cycle),
                    "merge cycle " + cycle + " must restore a fresh route immediately");
            previousRevision = merged.revision();
        }

        java.util.List<String> received = second.receiveMessages(16);
        helper.assertTrue(received.size() == 8,
                "only one payload from each merged state may enter the recipient queue: " + received);
        for (int cycle = 0; cycle < 8; cycle++) {
            int expected = cycle;
            helper.assertTrue(received.stream().filter(line -> line.contains("msg=merged-" + expected)).count() == 1
                            && received.stream().noneMatch(line -> line.contains("msg=blocked-" + expected)),
                    "partition/merge cycle " + cycle + " must preserve exact delivery isolation");
        }
        helper.succeed();
    }

}
