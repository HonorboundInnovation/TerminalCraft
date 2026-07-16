package com.malice.terminalcraft.registry;

import com.malice.terminalcraft.TerminalCraftMod;
import com.malice.terminalcraft.block.DiskDriveBlock;
import com.malice.terminalcraft.block.BundledCableBlock;
import com.malice.terminalcraft.block.ModemBlock;
import com.malice.terminalcraft.block.MonitorBlock;
import com.malice.terminalcraft.block.NetworkCableBlock;
import com.malice.terminalcraft.block.NetworkRouterBlock;
import com.malice.terminalcraft.block.RedAlloyWireBlock;
import com.malice.terminalcraft.block.RefinedStorageBridgeBlock;
import com.malice.terminalcraft.block.TerminalBlock;
import com.malice.terminalcraft.block.ServerRackBlock;
import com.malice.terminalcraft.block.TurtleBlock;
import com.malice.terminalcraft.blockentity.DiskDriveBlockEntity;
import com.malice.terminalcraft.blockentity.BundledCableBlockEntity;
import com.malice.terminalcraft.blockentity.ModemBlockEntity;
import com.malice.terminalcraft.blockentity.MonitorBlockEntity;
import com.malice.terminalcraft.blockentity.NetworkRouterBlockEntity;
import com.malice.terminalcraft.blockentity.NetworkCableBlockEntity;
import com.malice.terminalcraft.blockentity.RedAlloyWireBlockEntity;
import com.malice.terminalcraft.blockentity.RefinedStorageBridgeBlockEntity;
import com.malice.terminalcraft.blockentity.TerminalBlockEntity;
import com.malice.terminalcraft.blockentity.ServerRackBlockEntity;
import com.malice.terminalcraft.blockentity.TurtleBlockEntity;
import com.malice.terminalcraft.item.FloppyDiskItem;
import com.malice.terminalcraft.item.BundledCableItem;
import com.malice.terminalcraft.item.PocketTerminalItem;
import com.malice.terminalcraft.item.NetworkCableItem;
import com.malice.terminalcraft.item.RackModuleItem;
import com.malice.terminalcraft.item.RedAlloyWireItem;
import com.malice.terminalcraft.server.RackModuleType;
import com.malice.terminalcraft.menu.TerminalMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Central deferred registries for TerminalCraft content.
 */
public final class ModRegistries {
    public static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(ForgeRegistries.BLOCKS, TerminalCraftMod.MODID);
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, TerminalCraftMod.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, TerminalCraftMod.MODID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, TerminalCraftMod.MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, TerminalCraftMod.MODID);

    // Blocks
    public static final RegistryObject<Block> TERMINAL_BLOCK = BLOCKS.register("terminal", TerminalBlock::new);
    public static final RegistryObject<Block> TURTLE_BLOCK = BLOCKS.register("turtle", TurtleBlock::new);
    public static final RegistryObject<Block> MONITOR_BLOCK = BLOCKS.register("monitor", MonitorBlock::new);
    public static final RegistryObject<Block> MODEM_BLOCK = BLOCKS.register("modem", ModemBlock::new);
    public static final RegistryObject<Block> DISK_DRIVE_BLOCK = BLOCKS.register("disk_drive", DiskDriveBlock::new);
    public static final RegistryObject<Block> BUNDLED_CABLE_BLOCK = BLOCKS.register("bundled_cable", BundledCableBlock::new);
    public static final RegistryObject<Block> NETWORK_CABLE_BLOCK = BLOCKS.register("network_cable", NetworkCableBlock::new);
    public static final RegistryObject<Block> RED_ALLOY_WIRE_BLOCK = BLOCKS.register("red_alloy_wire", RedAlloyWireBlock::new);
    public static final RegistryObject<Block> NETWORK_ROUTER_BLOCK = BLOCKS.register("network_router", NetworkRouterBlock::new);
    public static final RegistryObject<Block> SERVER_RACK_BLOCK = BLOCKS.register("server_rack", ServerRackBlock::new);
    public static final RegistryObject<Block> REFINED_STORAGE_BRIDGE_BLOCK = BLOCKS.register(
            "refined_storage_bridge", RefinedStorageBridgeBlock::new);

    // Items
    public static final RegistryObject<Item> TERMINAL_ITEM = ITEMS.register("terminal",
            () -> new BlockItem(TERMINAL_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> TURTLE_ITEM = ITEMS.register("turtle",
            () -> new BlockItem(TURTLE_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> MONITOR_ITEM = ITEMS.register("monitor",
            () -> new BlockItem(MONITOR_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> MODEM_ITEM = ITEMS.register("modem",
            () -> new BlockItem(MODEM_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> DISK_DRIVE_ITEM = ITEMS.register("disk_drive",
            () -> new BlockItem(DISK_DRIVE_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> BUNDLED_CABLE_ITEM = ITEMS.register("bundled_cable",
            () -> new BundledCableItem(BUNDLED_CABLE_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> NETWORK_CABLE_ITEM = ITEMS.register("network_cable",
            () -> new NetworkCableItem(NETWORK_CABLE_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> RED_ALLOY_WIRE_ITEM = ITEMS.register("red_alloy_wire",
            () -> new RedAlloyWireItem(RED_ALLOY_WIRE_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> NETWORK_ROUTER_ITEM = ITEMS.register("network_router",
            () -> new BlockItem(NETWORK_ROUTER_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> SERVER_RACK_ITEM = ITEMS.register("server_rack",
            () -> new BlockItem(SERVER_RACK_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> REFINED_STORAGE_BRIDGE_ITEM = ITEMS.register(
            "refined_storage_bridge",
            () -> new BlockItem(REFINED_STORAGE_BRIDGE_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> SERVER_BLADE = ITEMS.register("server_blade",
            () -> new RackModuleItem(RackModuleType.SERVER));
    public static final RegistryObject<Item> ROUTER_BLADE = ITEMS.register("router_blade",
            () -> new RackModuleItem(RackModuleType.ROUTER));
    public static final RegistryObject<Item> FLOPPY_DISK = ITEMS.register("floppy_disk", FloppyDiskItem::new);
    public static final RegistryObject<Item> POCKET_TERMINAL = ITEMS.register("pocket_terminal", PocketTerminalItem::new);

    // Block entities
    public static final RegistryObject<BlockEntityType<RefinedStorageBridgeBlockEntity>> REFINED_STORAGE_BRIDGE_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("refined_storage_bridge",
                    () -> BlockEntityType.Builder.of(RefinedStorageBridgeBlockEntity::new,
                            REFINED_STORAGE_BRIDGE_BLOCK.get()).build(null));
    public static final RegistryObject<BlockEntityType<NetworkCableBlockEntity>> NETWORK_CABLE_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("network_cable",
                    () -> BlockEntityType.Builder.of(NetworkCableBlockEntity::new, NETWORK_CABLE_BLOCK.get()).build(null));
    public static final RegistryObject<BlockEntityType<RedAlloyWireBlockEntity>> RED_ALLOY_WIRE_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("red_alloy_wire",
                    () -> BlockEntityType.Builder.of(RedAlloyWireBlockEntity::new, RED_ALLOY_WIRE_BLOCK.get()).build(null));
    public static final RegistryObject<BlockEntityType<BundledCableBlockEntity>> BUNDLED_CABLE_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("bundled_cable",
                    () -> BlockEntityType.Builder.of(BundledCableBlockEntity::new, BUNDLED_CABLE_BLOCK.get()).build(null));
    public static final RegistryObject<BlockEntityType<ServerRackBlockEntity>> SERVER_RACK_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("server_rack",
                    () -> BlockEntityType.Builder.of(ServerRackBlockEntity::new, SERVER_RACK_BLOCK.get()).build(null));
    public static final RegistryObject<BlockEntityType<TerminalBlockEntity>> TERMINAL_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("terminal",
                    () -> BlockEntityType.Builder.of(TerminalBlockEntity::new, TERMINAL_BLOCK.get()).build(null));
    public static final RegistryObject<BlockEntityType<TurtleBlockEntity>> TURTLE_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("turtle",
                    () -> BlockEntityType.Builder.of(TurtleBlockEntity::new, TURTLE_BLOCK.get()).build(null));
    public static final RegistryObject<BlockEntityType<MonitorBlockEntity>> MONITOR_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("monitor",
                    () -> BlockEntityType.Builder.of(MonitorBlockEntity::new, MONITOR_BLOCK.get()).build(null));
    public static final RegistryObject<BlockEntityType<ModemBlockEntity>> MODEM_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("modem",
                    () -> BlockEntityType.Builder.of(ModemBlockEntity::new, MODEM_BLOCK.get()).build(null));
    public static final RegistryObject<BlockEntityType<NetworkRouterBlockEntity>> NETWORK_ROUTER_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("network_router",
                    () -> BlockEntityType.Builder.of(NetworkRouterBlockEntity::new, NETWORK_ROUTER_BLOCK.get()).build(null));
    public static final RegistryObject<BlockEntityType<DiskDriveBlockEntity>> DISK_DRIVE_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("disk_drive",
                    () -> BlockEntityType.Builder.of(DiskDriveBlockEntity::new, DISK_DRIVE_BLOCK.get()).build(null));

    public static final RegistryObject<MenuType<TerminalMenu>> TERMINAL_MENU = MENUS.register("terminal",
            () -> IForgeMenuType.create(TerminalMenu::fromNetwork));

    public static final RegistryObject<CreativeModeTab> TERMINAL_TAB = CREATIVE_TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.terminalcraft"))
                    .icon(() -> TERMINAL_ITEM.get().getDefaultInstance())
                    .displayItems((params, output) -> {
                        output.accept(TERMINAL_ITEM.get());
                        output.accept(TURTLE_ITEM.get());
                        output.accept(MONITOR_ITEM.get());
                        output.accept(MODEM_ITEM.get());
                        output.accept(DISK_DRIVE_ITEM.get());
                        output.accept(BUNDLED_CABLE_ITEM.get());
                        output.accept(NETWORK_CABLE_ITEM.get());
                        output.accept(RED_ALLOY_WIRE_ITEM.get());
                        output.accept(NETWORK_ROUTER_ITEM.get());
                        output.accept(SERVER_RACK_ITEM.get());
                        output.accept(REFINED_STORAGE_BRIDGE_ITEM.get());
                        output.accept(SERVER_BLADE.get());
                        output.accept(ROUTER_BLADE.get());
                        output.accept(FLOPPY_DISK.get());
                        output.accept(POCKET_TERMINAL.get());
                    })
                    .build());

    private ModRegistries() {}

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        MENUS.register(modBus);
        CREATIVE_TABS.register(modBus);
    }
}
