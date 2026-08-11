package com.malice.terminalcraft.registry;

import com.malice.terminalcraft.TerminalCraftMod;
import com.malice.terminalcraft.block.DiskDriveBlock;
import com.malice.terminalcraft.block.BundledCableBlock;
import com.malice.terminalcraft.block.BundledNetworkCableBlock;
import com.malice.terminalcraft.block.ModemBlock;
import com.malice.terminalcraft.block.MonitorBlock;
import com.malice.terminalcraft.block.NetworkCableBlock;
import com.malice.terminalcraft.block.NetworkRouterBlock;
import com.malice.terminalcraft.block.RedAlloyWireBlock;
import com.malice.terminalcraft.block.RedAlloyCapacitorBlock;
import com.malice.terminalcraft.block.RefinedStorageBridgeBlock;
import com.malice.terminalcraft.block.AppliedEnergisticsBridgeBlock;
import com.malice.terminalcraft.block.TerminalBlock;
import com.malice.terminalcraft.block.ServerRackBlock;
import com.malice.terminalcraft.block.TurtleBlock;
import com.malice.terminalcraft.block.ProgrammableLogicControllerBlock;
import com.malice.terminalcraft.block.WirelessDisplayLinkBlock;
import com.malice.terminalcraft.block.VideoCableBlock;
import com.malice.terminalcraft.block.SensorArrayBlock;
import com.malice.terminalcraft.block.StandaloneSensorBlock;
import com.malice.terminalcraft.block.NetworkAccessStorageBlock;
import com.malice.terminalcraft.block.MaterializerBlock;
import com.malice.terminalcraft.blockentity.DiskDriveBlockEntity;
import com.malice.terminalcraft.blockentity.BundledCableBlockEntity;
import com.malice.terminalcraft.blockentity.ModemBlockEntity;
import com.malice.terminalcraft.blockentity.MonitorBlockEntity;
import com.malice.terminalcraft.blockentity.NetworkRouterBlockEntity;
import com.malice.terminalcraft.blockentity.NetworkCableBlockEntity;
import com.malice.terminalcraft.blockentity.RedAlloyWireBlockEntity;
import com.malice.terminalcraft.blockentity.RefinedStorageBridgeBlockEntity;
import com.malice.terminalcraft.blockentity.AppliedEnergisticsBridgeBlockEntity;
import com.malice.terminalcraft.blockentity.TerminalBlockEntity;
import com.malice.terminalcraft.blockentity.ServerRackBlockEntity;
import com.malice.terminalcraft.blockentity.TurtleBlockEntity;
import com.malice.terminalcraft.blockentity.ProgrammableLogicControllerBlockEntity;
import com.malice.terminalcraft.blockentity.WirelessDisplayLinkBlockEntity;
import com.malice.terminalcraft.blockentity.VideoCableBlockEntity;
import com.malice.terminalcraft.blockentity.SensorArrayBlockEntity;
import com.malice.terminalcraft.blockentity.StandaloneSensorBlockEntity;
import com.malice.terminalcraft.blockentity.NetworkAccessStorageBlockEntity;
import com.malice.terminalcraft.blockentity.MaterializerBlockEntity;
import com.malice.terminalcraft.sensor.SensorKind;
import com.malice.terminalcraft.item.SolidStateDriveItem;
import com.malice.terminalcraft.item.SolidStateDriveTier;
import com.malice.terminalcraft.item.FloppyDiskItem;
import com.malice.terminalcraft.item.GuideBookItem;
import com.malice.terminalcraft.item.BundledCableItem;
import com.malice.terminalcraft.item.BundledNetworkCableItem;
import com.malice.terminalcraft.item.PocketTerminalItem;
import com.malice.terminalcraft.item.NetworkCableItem;
import com.malice.terminalcraft.item.RackModuleItem;
import com.malice.terminalcraft.item.RedAlloyWireItem;
import com.malice.terminalcraft.server.RackModuleType;
import com.malice.terminalcraft.menu.TerminalMenu;
import com.malice.terminalcraft.menu.DisplayDiagnosticsMenu;
import com.malice.terminalcraft.menu.PlcProgrammingMenu;
import com.malice.terminalcraft.recipe.CableDyeRecipe;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
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
    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.RECIPE_SERIALIZERS, TerminalCraftMod.MODID);

    public static final RegistryObject<RecipeSerializer<CableDyeRecipe>> CABLE_DYE_RECIPE =
            RECIPE_SERIALIZERS.register("cable_dyeing", () -> new SimpleCraftingRecipeSerializer<>(CableDyeRecipe::new));

    // Blocks
    public static final RegistryObject<Block> TERMINAL_BLOCK = BLOCKS.register("terminal", TerminalBlock::new);
    public static final RegistryObject<Block> TURTLE_BLOCK = BLOCKS.register("turtle", TurtleBlock::new);
    public static final RegistryObject<Block> MONITOR_BLOCK = BLOCKS.register("monitor", MonitorBlock::new);
    public static final RegistryObject<Block> MODEM_BLOCK = BLOCKS.register("modem", ModemBlock::new);
    public static final RegistryObject<Block> DISK_DRIVE_BLOCK = BLOCKS.register("disk_drive", DiskDriveBlock::new);
    public static final RegistryObject<Block> BUNDLED_CABLE_BLOCK = BLOCKS.register("bundled_cable", BundledCableBlock::new);
    public static final RegistryObject<Block> BUNDLED_NETWORK_CABLE_BLOCK = BLOCKS.register(
            "bundled_network_cable", BundledNetworkCableBlock::new);
    public static final RegistryObject<Block> NETWORK_CABLE_BLOCK = BLOCKS.register("network_cable", NetworkCableBlock::new);
    public static final RegistryObject<Block> RED_ALLOY_WIRE_BLOCK = BLOCKS.register("red_alloy_wire", RedAlloyWireBlock::new);
    public static final RegistryObject<Block> RED_ALLOY_CAPACITOR_BLOCK = BLOCKS.register(
            "red_alloy_capacitor", RedAlloyCapacitorBlock::new);
    public static final RegistryObject<Block> NETWORK_ROUTER_BLOCK = BLOCKS.register("network_router", NetworkRouterBlock::new);
    public static final RegistryObject<Block> SERVER_RACK_BLOCK = BLOCKS.register("server_rack", ServerRackBlock::new);
    public static final RegistryObject<Block> REFINED_STORAGE_BRIDGE_BLOCK = BLOCKS.register(
            "refined_storage_bridge", RefinedStorageBridgeBlock::new);
    public static final RegistryObject<Block> APPLIED_ENERGISTICS_BRIDGE_BLOCK = BLOCKS.register(
            "applied_energistics_bridge", AppliedEnergisticsBridgeBlock::new);
    public static final RegistryObject<Block> PROGRAMMABLE_LOGIC_CONTROLLER_BLOCK = BLOCKS.register(
            "programmable_logic_controller", ProgrammableLogicControllerBlock::new);
    public static final RegistryObject<Block> WIRELESS_DISPLAY_LINK_BLOCK = BLOCKS.register(
            "wireless_display_link", WirelessDisplayLinkBlock::new);
    public static final RegistryObject<Block> VIDEO_CABLE_BLOCK = BLOCKS.register("video_cable", VideoCableBlock::new);
    public static final RegistryObject<Block> SENSOR_ARRAY_BLOCK = BLOCKS.register("sensor_array", SensorArrayBlock::new);
    public static final RegistryObject<Block> REDSTONE_SENSOR_BLOCK = BLOCKS.register("redstone_sensor",
            () -> new StandaloneSensorBlock(SensorKind.REDSTONE));
    public static final RegistryObject<Block> BLOCK_STATE_SENSOR_BLOCK = BLOCKS.register("block_state_sensor",
            () -> new StandaloneSensorBlock(SensorKind.BLOCK_STATE));
    public static final RegistryObject<Block> INVENTORY_SENSOR_BLOCK = BLOCKS.register("inventory_sensor",
            () -> new StandaloneSensorBlock(SensorKind.INVENTORY));
    public static final RegistryObject<Block> FLUID_SENSOR_BLOCK = BLOCKS.register("fluid_sensor",
            () -> new StandaloneSensorBlock(SensorKind.FLUID));
    public static final RegistryObject<Block> ENERGY_SENSOR_BLOCK = BLOCKS.register("energy_sensor",
            () -> new StandaloneSensorBlock(SensorKind.ENERGY));
    public static final RegistryObject<Block> ENTITY_SENSOR_BLOCK = BLOCKS.register("entity_sensor",
            () -> new StandaloneSensorBlock(SensorKind.ENTITY));
    public static final RegistryObject<Block> MACHINE_SENSOR_BLOCK = BLOCKS.register("machine_sensor",
            () -> new StandaloneSensorBlock(SensorKind.MACHINE));
    public static final RegistryObject<Block> ENVIRONMENT_SENSOR_BLOCK = BLOCKS.register("environment_sensor",
            () -> new StandaloneSensorBlock(SensorKind.ENVIRONMENT));
    public static final RegistryObject<Block> NETWORK_SENSOR_BLOCK = BLOCKS.register("network_sensor",
            () -> new StandaloneSensorBlock(SensorKind.NETWORK));
    public static final RegistryObject<Block> KINETIC_SENSOR_BLOCK = BLOCKS.register("kinetic_sensor",
            () -> new StandaloneSensorBlock(SensorKind.KINETIC));
    public static final RegistryObject<Block> CHEMICAL_SENSOR_BLOCK = BLOCKS.register("chemical_sensor",
            () -> new StandaloneSensorBlock(SensorKind.CHEMICAL));
    public static final RegistryObject<Block> NETWORK_ACCESS_STORAGE_BLOCK = BLOCKS.register(
            "network_access_storage", NetworkAccessStorageBlock::new);
    public static final RegistryObject<Block> MATERIALIZER_BLOCK = BLOCKS.register("materializer", MaterializerBlock::new);

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
    public static final RegistryObject<Item> BUNDLED_NETWORK_CABLE_ITEM = ITEMS.register("bundled_network_cable",
            () -> new BundledNetworkCableItem(BUNDLED_NETWORK_CABLE_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> NETWORK_CABLE_ITEM = ITEMS.register("network_cable",
            () -> new NetworkCableItem(NETWORK_CABLE_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> RED_ALLOY_WIRE_ITEM = ITEMS.register("red_alloy_wire",
            () -> new RedAlloyWireItem(RED_ALLOY_WIRE_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> RED_ALLOY_CAPACITOR_ITEM = ITEMS.register("red_alloy_capacitor",
            () -> new BlockItem(RED_ALLOY_CAPACITOR_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> NETWORK_ROUTER_ITEM = ITEMS.register("network_router",
            () -> new BlockItem(NETWORK_ROUTER_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> SERVER_RACK_ITEM = ITEMS.register("server_rack",
            () -> new BlockItem(SERVER_RACK_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> REFINED_STORAGE_BRIDGE_ITEM = ITEMS.register(
            "refined_storage_bridge",
            () -> new BlockItem(REFINED_STORAGE_BRIDGE_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> APPLIED_ENERGISTICS_BRIDGE_ITEM = ITEMS.register(
            "applied_energistics_bridge",
            () -> new BlockItem(APPLIED_ENERGISTICS_BRIDGE_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> PROGRAMMABLE_LOGIC_CONTROLLER_ITEM = ITEMS.register(
            "programmable_logic_controller",
            () -> new BlockItem(PROGRAMMABLE_LOGIC_CONTROLLER_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> WIRELESS_DISPLAY_LINK_ITEM = ITEMS.register("wireless_display_link",
            () -> new BlockItem(WIRELESS_DISPLAY_LINK_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> VIDEO_CABLE_ITEM = ITEMS.register("video_cable",
            () -> new BlockItem(VIDEO_CABLE_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> SENSOR_ARRAY_ITEM = ITEMS.register("sensor_array",
            () -> new BlockItem(SENSOR_ARRAY_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> REDSTONE_SENSOR_ITEM = sensorItem("redstone_sensor", REDSTONE_SENSOR_BLOCK);
    public static final RegistryObject<Item> BLOCK_STATE_SENSOR_ITEM = sensorItem("block_state_sensor", BLOCK_STATE_SENSOR_BLOCK);
    public static final RegistryObject<Item> INVENTORY_SENSOR_ITEM = sensorItem("inventory_sensor", INVENTORY_SENSOR_BLOCK);
    public static final RegistryObject<Item> FLUID_SENSOR_ITEM = sensorItem("fluid_sensor", FLUID_SENSOR_BLOCK);
    public static final RegistryObject<Item> ENERGY_SENSOR_ITEM = sensorItem("energy_sensor", ENERGY_SENSOR_BLOCK);
    public static final RegistryObject<Item> ENTITY_SENSOR_ITEM = sensorItem("entity_sensor", ENTITY_SENSOR_BLOCK);
    public static final RegistryObject<Item> MACHINE_SENSOR_ITEM = sensorItem("machine_sensor", MACHINE_SENSOR_BLOCK);
    public static final RegistryObject<Item> ENVIRONMENT_SENSOR_ITEM = sensorItem("environment_sensor", ENVIRONMENT_SENSOR_BLOCK);
    public static final RegistryObject<Item> NETWORK_SENSOR_ITEM = sensorItem("network_sensor", NETWORK_SENSOR_BLOCK);
    public static final RegistryObject<Item> KINETIC_SENSOR_ITEM = sensorItem("kinetic_sensor", KINETIC_SENSOR_BLOCK);
    public static final RegistryObject<Item> CHEMICAL_SENSOR_ITEM = sensorItem("chemical_sensor", CHEMICAL_SENSOR_BLOCK);
    public static final RegistryObject<Item> NETWORK_ACCESS_STORAGE_ITEM = ITEMS.register("network_access_storage",
            () -> new BlockItem(NETWORK_ACCESS_STORAGE_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> MATERIALIZER_ITEM = ITEMS.register("materializer",
            () -> new BlockItem(MATERIALIZER_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<Item> SOLID_STATE_DRIVE_ITEM = ITEMS.register("solid_state_drive",
            () -> new SolidStateDriveItem(SolidStateDriveTier.BASIC));
    public static final RegistryObject<Item> ADVANCED_SOLID_STATE_DRIVE_ITEM = ITEMS.register("advanced_solid_state_drive",
            () -> new SolidStateDriveItem(SolidStateDriveTier.ADVANCED));
    public static final RegistryObject<Item> QUANTUM_SOLID_STATE_DRIVE_ITEM = ITEMS.register("quantum_solid_state_drive",
            () -> new SolidStateDriveItem(SolidStateDriveTier.QUANTUM));
    public static final RegistryObject<Item> SERVER_BLADE = ITEMS.register("server_blade",
            () -> new RackModuleItem(RackModuleType.SERVER));
    public static final RegistryObject<Item> ROUTER_BLADE = ITEMS.register("router_blade",
            () -> new RackModuleItem(RackModuleType.ROUTER));
    public static final RegistryObject<Item> FLOPPY_DISK = ITEMS.register("floppy_disk", FloppyDiskItem::new);
    public static final RegistryObject<Item> POCKET_TERMINAL = ITEMS.register("pocket_terminal", PocketTerminalItem::new);
    public static final RegistryObject<Item> GUIDE_BOOK = ITEMS.register("guide_book", GuideBookItem::new);

    // Block entities
    public static final RegistryObject<BlockEntityType<RefinedStorageBridgeBlockEntity>> REFINED_STORAGE_BRIDGE_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("refined_storage_bridge",
                    () -> BlockEntityType.Builder.of(RefinedStorageBridgeBlockEntity::new,
                            REFINED_STORAGE_BRIDGE_BLOCK.get()).build(null));
    public static final RegistryObject<BlockEntityType<AppliedEnergisticsBridgeBlockEntity>> APPLIED_ENERGISTICS_BRIDGE_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("applied_energistics_bridge",
                    () -> BlockEntityType.Builder.of(AppliedEnergisticsBridgeBlockEntity::new,
                            APPLIED_ENERGISTICS_BRIDGE_BLOCK.get()).build(null));
    public static final RegistryObject<BlockEntityType<NetworkCableBlockEntity>> NETWORK_CABLE_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("network_cable",
                    () -> BlockEntityType.Builder.of(NetworkCableBlockEntity::new,
                            NETWORK_CABLE_BLOCK.get(), BUNDLED_NETWORK_CABLE_BLOCK.get()).build(null));
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
    public static final RegistryObject<BlockEntityType<ProgrammableLogicControllerBlockEntity>>
            PROGRAMMABLE_LOGIC_CONTROLLER_BLOCK_ENTITY = BLOCK_ENTITIES.register("programmable_logic_controller",
                    () -> BlockEntityType.Builder.of(ProgrammableLogicControllerBlockEntity::new,
                            PROGRAMMABLE_LOGIC_CONTROLLER_BLOCK.get()).build(null));
    public static final RegistryObject<BlockEntityType<WirelessDisplayLinkBlockEntity>> WIRELESS_DISPLAY_LINK_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("wireless_display_link",
                    () -> BlockEntityType.Builder.of(WirelessDisplayLinkBlockEntity::new,
                            WIRELESS_DISPLAY_LINK_BLOCK.get()).build(null));
    public static final RegistryObject<BlockEntityType<VideoCableBlockEntity>> VIDEO_CABLE_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("video_cable",
                    () -> BlockEntityType.Builder.of(VideoCableBlockEntity::new, VIDEO_CABLE_BLOCK.get()).build(null));
    public static final RegistryObject<BlockEntityType<SensorArrayBlockEntity>> SENSOR_ARRAY_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("sensor_array",
                    () -> BlockEntityType.Builder.of(SensorArrayBlockEntity::new, SENSOR_ARRAY_BLOCK.get()).build(null));
    public static final RegistryObject<BlockEntityType<StandaloneSensorBlockEntity>> STANDALONE_SENSOR_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("standalone_sensor",
                    () -> BlockEntityType.Builder.of(StandaloneSensorBlockEntity::new,
                            REDSTONE_SENSOR_BLOCK.get(), BLOCK_STATE_SENSOR_BLOCK.get(), INVENTORY_SENSOR_BLOCK.get(),
                            FLUID_SENSOR_BLOCK.get(), ENERGY_SENSOR_BLOCK.get(), ENTITY_SENSOR_BLOCK.get(),
                            MACHINE_SENSOR_BLOCK.get(), ENVIRONMENT_SENSOR_BLOCK.get(), NETWORK_SENSOR_BLOCK.get(),
                            KINETIC_SENSOR_BLOCK.get(), CHEMICAL_SENSOR_BLOCK.get()).build(null));
    public static final RegistryObject<BlockEntityType<NetworkAccessStorageBlockEntity>> NETWORK_ACCESS_STORAGE_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("network_access_storage",
                    () -> BlockEntityType.Builder.of(NetworkAccessStorageBlockEntity::new,
                            NETWORK_ACCESS_STORAGE_BLOCK.get()).build(null));
    public static final RegistryObject<BlockEntityType<MaterializerBlockEntity>> MATERIALIZER_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("materializer",
                    () -> BlockEntityType.Builder.of(MaterializerBlockEntity::new, MATERIALIZER_BLOCK.get()).build(null));

    public static final RegistryObject<MenuType<TerminalMenu>> TERMINAL_MENU = MENUS.register("terminal",
            () -> IForgeMenuType.create(TerminalMenu::fromNetwork));
    public static final RegistryObject<MenuType<DisplayDiagnosticsMenu>> DISPLAY_DIAGNOSTICS_MENU = MENUS.register(
            "display_diagnostics", () -> IForgeMenuType.create(DisplayDiagnosticsMenu::fromNetwork));
    public static final RegistryObject<MenuType<PlcProgrammingMenu>> PLC_PROGRAMMING_MENU = MENUS.register(
            "plc_programming", () -> IForgeMenuType.create(PlcProgrammingMenu::fromNetwork));

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
                        output.accept(BUNDLED_NETWORK_CABLE_ITEM.get());
                        output.accept(RED_ALLOY_WIRE_ITEM.get());
                        for (net.minecraft.world.item.DyeColor color : net.minecraft.world.item.DyeColor.values()) {
                            output.accept(com.malice.terminalcraft.item.NetworkCableItem.colored(
                                    NETWORK_CABLE_ITEM.get().getDefaultInstance(), color));
                            output.accept(com.malice.terminalcraft.item.RedAlloyWireItem.colored(
                                    RED_ALLOY_WIRE_ITEM.get().getDefaultInstance(), color));
                        }
                        output.accept(RED_ALLOY_CAPACITOR_ITEM.get());
                        output.accept(NETWORK_ROUTER_ITEM.get());
                        output.accept(SERVER_RACK_ITEM.get());
                        output.accept(REFINED_STORAGE_BRIDGE_ITEM.get());
                        output.accept(APPLIED_ENERGISTICS_BRIDGE_ITEM.get());
                        output.accept(PROGRAMMABLE_LOGIC_CONTROLLER_ITEM.get());
                        output.accept(WIRELESS_DISPLAY_LINK_ITEM.get());
                        output.accept(VIDEO_CABLE_ITEM.get());
                        output.accept(SENSOR_ARRAY_ITEM.get());
                        output.accept(REDSTONE_SENSOR_ITEM.get());
                        output.accept(BLOCK_STATE_SENSOR_ITEM.get());
                        output.accept(INVENTORY_SENSOR_ITEM.get());
                        output.accept(FLUID_SENSOR_ITEM.get());
                        output.accept(ENERGY_SENSOR_ITEM.get());
                        output.accept(ENTITY_SENSOR_ITEM.get());
                        output.accept(MACHINE_SENSOR_ITEM.get());
                        output.accept(ENVIRONMENT_SENSOR_ITEM.get());
                        output.accept(NETWORK_SENSOR_ITEM.get());
                        output.accept(KINETIC_SENSOR_ITEM.get());
                        output.accept(CHEMICAL_SENSOR_ITEM.get());
                        output.accept(NETWORK_ACCESS_STORAGE_ITEM.get());
                        output.accept(MATERIALIZER_ITEM.get());
                        output.accept(SOLID_STATE_DRIVE_ITEM.get());
                        output.accept(ADVANCED_SOLID_STATE_DRIVE_ITEM.get());
                        output.accept(QUANTUM_SOLID_STATE_DRIVE_ITEM.get());
                        output.accept(SERVER_BLADE.get());
                        output.accept(ROUTER_BLADE.get());
                        output.accept(FLOPPY_DISK.get());
                        output.accept(POCKET_TERMINAL.get());
                        output.accept(GUIDE_BOOK.get());
                    })
                    .build());

    private ModRegistries() {}

    private static RegistryObject<Item> sensorItem(String name, RegistryObject<Block> block) {
        return ITEMS.register(name, () -> new BlockItem(block.get(), new Item.Properties()));
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        MENUS.register(modBus);
        CREATIVE_TABS.register(modBus);
        RECIPE_SERIALIZERS.register(modBus);
    }
}
