package com.malice.terminalcraft;

/**
 * TerminalCraft - bash-style terminal computers for Minecraft Forge 1.20.1.
 * Inspired by ComputerCraft, with an interactive shell instead of Lua.
 */
@net.minecraftforge.fml.common.Mod(TerminalCraftMod.MODID)
public final class TerminalCraftMod {
    public static final String MODID = "terminalcraft";

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    public TerminalCraftMod() {
        net.minecraftforge.eventbus.api.IEventBus modBus =
                net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus();

        com.malice.terminalcraft.registry.ModRegistries.register(modBus);
        com.malice.terminalcraft.network.ModNetwork.register();

        modBus.addListener(this::commonSetup);
        modBus.addListener(this::addCreative);

        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
        net.minecraftforge.fml.ModLoadingContext.get()
                .registerConfig(net.minecraftforge.fml.config.ModConfig.Type.COMMON, Config.SPEC);

        LOGGER.info("TerminalCraft loading - bash terminal architecture online");
    }

    private void commonSetup(final net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            com.malice.terminalcraft.integration.OptionalIntegrations.initialize();
            net.minecraftforge.common.world.ForgeChunkManager.setForcedChunkLoadingCallback(MODID,
                    com.malice.terminalcraft.world.TerminalChunkLoader::validatePersistentTickets);
        });
        LOGGER.info("TerminalCraft common setup complete");
        LOGGER.info("Max shell history: {}, welcome banner: {}",
                Config.maxHistoryLines, Config.showWelcomeBanner);
    }

    private void addCreative(final net.minecraftforge.event.BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == net.minecraft.world.item.CreativeModeTabs.REDSTONE_BLOCKS
                || event.getTabKey() == net.minecraft.world.item.CreativeModeTabs.FUNCTIONAL_BLOCKS
                || event.getTabKey() == net.minecraft.world.item.CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(com.malice.terminalcraft.registry.ModRegistries.TERMINAL_ITEM.get());
            event.accept(com.malice.terminalcraft.registry.ModRegistries.TURTLE_ITEM.get());
            event.accept(com.malice.terminalcraft.registry.ModRegistries.MONITOR_ITEM.get());
            event.accept(com.malice.terminalcraft.registry.ModRegistries.MODEM_ITEM.get());
            event.accept(com.malice.terminalcraft.registry.ModRegistries.DISK_DRIVE_ITEM.get());
            event.accept(com.malice.terminalcraft.registry.ModRegistries.BUNDLED_CABLE_ITEM.get());
            event.accept(com.malice.terminalcraft.registry.ModRegistries.NETWORK_CABLE_ITEM.get());
            event.accept(com.malice.terminalcraft.registry.ModRegistries.RED_ALLOY_WIRE_ITEM.get());
            event.accept(com.malice.terminalcraft.registry.ModRegistries.NETWORK_ROUTER_ITEM.get());
            event.accept(com.malice.terminalcraft.registry.ModRegistries.FLOPPY_DISK.get());
            event.accept(com.malice.terminalcraft.registry.ModRegistries.POCKET_TERMINAL.get());
        }
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onServerStarting(final net.minecraftforge.event.server.ServerStartingEvent event) {
        LOGGER.info("TerminalCraft server ready - shells available");
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onBlockPlaced(final net.minecraftforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            com.malice.terminalcraft.network.WiredNetworkTopology.invalidate(level, event.getPos());
        }
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onBlockBroken(final net.minecraftforge.event.level.BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            com.malice.terminalcraft.network.WiredNetworkTopology.invalidate(level, event.getPos());
        }
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onChunkLoaded(final net.minecraftforge.event.level.ChunkEvent.Load event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            com.malice.terminalcraft.network.WiredNetworkTopology.invalidateChunk(level, event.getChunk().getPos());
        }
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onChunkUnloaded(final net.minecraftforge.event.level.ChunkEvent.Unload event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
            com.malice.terminalcraft.network.WiredNetworkTopology.invalidateChunk(level, event.getChunk().getPos());
        }
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onServerTick(final net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase == net.minecraftforge.event.TickEvent.Phase.END) {
            com.malice.terminalcraft.world.TerminalChunkLoader.reconcile(event.getServer());
        }
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent
    public void onServerStopped(final net.minecraftforge.event.server.ServerStoppedEvent event) {
        com.malice.terminalcraft.device.ServerDeviceManager.clear(event.getServer());
        com.malice.terminalcraft.network.RednetNetwork.clear(event.getServer());
        com.malice.terminalcraft.network.WiredNetworkTopology.clear(event.getServer());
        com.malice.terminalcraft.world.TerminalChunkLoader.clear(event.getServer());
        LOGGER.info("TerminalCraft device registry cleared");
    }

    @net.minecraftforge.fml.common.Mod.EventBusSubscriber(
            modid = MODID,
            bus = net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus.MOD,
            value = net.minecraftforge.api.distmarker.Dist.CLIENT)
    public static final class ClientModEvents {
        private ClientModEvents() {}

        @net.minecraftforge.eventbus.api.SubscribeEvent
        public static void onClientSetup(final net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
            event.enqueueWork(() -> {
                net.minecraft.client.gui.screens.MenuScreens.register(
                        com.malice.terminalcraft.registry.ModRegistries.TERMINAL_MENU.get(),
                        com.malice.terminalcraft.client.TerminalScreen::new);
                net.minecraft.client.renderer.blockentity.BlockEntityRenderers.register(
                        com.malice.terminalcraft.registry.ModRegistries.MONITOR_BLOCK_ENTITY.get(),
                        com.malice.terminalcraft.client.MonitorBlockEntityRenderer::new);
                net.minecraft.client.renderer.blockentity.BlockEntityRenderers.register(
                        com.malice.terminalcraft.registry.ModRegistries.BUNDLED_CABLE_BLOCK_ENTITY.get(),
                        com.malice.terminalcraft.client.BundledCableBlockEntityRenderer::new);
                net.minecraft.client.renderer.blockentity.BlockEntityRenderers.register(
                        com.malice.terminalcraft.registry.ModRegistries.NETWORK_CABLE_BLOCK_ENTITY.get(),
                        com.malice.terminalcraft.client.NetworkCableBlockEntityRenderer::new);
                net.minecraft.client.renderer.blockentity.BlockEntityRenderers.register(
                        com.malice.terminalcraft.registry.ModRegistries.RED_ALLOY_WIRE_BLOCK_ENTITY.get(),
                        com.malice.terminalcraft.client.RedAlloyWireBlockEntityRenderer::new);
            });
            LOGGER.info("TerminalCraft client setup - terminal screen registered");
        }
    }
}
