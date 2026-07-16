package com.malice.terminalcraft;

import com.malice.terminalcraft.world.TerminalChunkTicketPolicy;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/**
 * Runtime configuration for TerminalCraft.
 */
@Mod.EventBusSubscriber(modid = TerminalCraftMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.IntValue MAX_HISTORY_LINES = BUILDER
            .comment("Maximum number of lines kept in each terminal scrollback buffer")
            .defineInRange("maxHistoryLines", 200, 20, 2000);

    private static final ForgeConfigSpec.BooleanValue SHOW_WELCOME_BANNER = BUILDER
            .comment("Show the TerminalCraft welcome banner when a shell starts")
            .define("showWelcomeBanner", true);

    private static final ForgeConfigSpec.IntValue MAX_COMMAND_LENGTH = BUILDER
            .comment("Maximum characters accepted for a single shell command line")
            .defineInRange("maxCommandLength", 512, 64, 4096);

    private static final ForgeConfigSpec.IntValue MAX_COMMANDS_PER_SECOND = BUILDER
            .comment("Maximum terminal command packets accepted from one player per second")
            .defineInRange("maxCommandsPerSecond", 20, 1, 200);

    private static final ForgeConfigSpec.BooleanValue CRT_SCANLINES = BUILDER
            .comment("Draw subtle CRT scanline overlay on the terminal GUI")
            .define("crtScanlines", true);

    private static final ForgeConfigSpec.BooleanValue TERMINAL_SOUNDS = BUILDER
            .comment("Play soft UI click sounds when submitting commands")
            .define("terminalSounds", true);

    private static final ForgeConfigSpec.IntValue CRT_TEXT_COLOR = BUILDER
            .comment("Default terminal text color (RGB hex, e.g. 0x00FF66)")
            .defineInRange("crtTextColor", 0x00FF66, 0x000000, 0xFFFFFF);

    private static final ForgeConfigSpec.IntValue MAX_DISK_FILES = BUILDER
            .comment("Soft limit used by docs/selftest for portable disk libraries")
            .defineInRange("maxDiskFiles", 256, 16, 4096);

    private static final ForgeConfigSpec.BooleanValue TERMINAL_CHUNK_LOADING = BUILDER
            .comment("Allow placed terminals to request persistent ticking chunk tickets. Disabled by default; enabling this is an explicit server policy decision.")
            .define("terminalChunkLoading", false);

    private static final ForgeConfigSpec.IntValue MAX_TERMINAL_CHUNK_TICKETS_PER_DIMENSION = BUILDER
            .comment("Maximum TerminalCraft-owned terminal chunk tickets in one dimension. Zero denies all tickets in that dimension.")
            .defineInRange("maxTerminalChunkTicketsPerDimension", 16, 0, 4096);

    private static final ForgeConfigSpec.IntValue MAX_TERMINAL_CHUNK_TICKETS_PER_SERVER = BUILDER
            .comment("Maximum TerminalCraft-owned terminal chunk tickets across the server. Zero denies all terminal tickets.")
            .defineInRange("maxTerminalChunkTicketsPerServer", 64, 0, 16384);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static int maxHistoryLines = 200;
    public static boolean showWelcomeBanner = true;
    public static int maxCommandLength = 512;
    public static int maxCommandsPerSecond = 20;
    public static boolean crtScanlines = true;
    public static boolean terminalSounds = true;
    public static int crtTextColor = 0x00FF66;
    public static int maxDiskFiles = 256;
    public static boolean terminalChunkLoading = false;
    public static int maxTerminalChunkTicketsPerDimension = 16;
    public static int maxTerminalChunkTicketsPerServer = 64;

    private Config() {}

    /** Returns one internally consistent snapshot for server-thread admission decisions. */
    public static TerminalChunkTicketPolicy terminalChunkTicketPolicy() {
        return new TerminalChunkTicketPolicy(terminalChunkLoading,
                maxTerminalChunkTicketsPerDimension, maxTerminalChunkTicketsPerServer);
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        maxHistoryLines = MAX_HISTORY_LINES.get();
        showWelcomeBanner = SHOW_WELCOME_BANNER.get();
        maxCommandLength = MAX_COMMAND_LENGTH.get();
        maxCommandsPerSecond = MAX_COMMANDS_PER_SECOND.get();
        crtScanlines = CRT_SCANLINES.get();
        terminalSounds = TERMINAL_SOUNDS.get();
        crtTextColor = CRT_TEXT_COLOR.get();
        maxDiskFiles = MAX_DISK_FILES.get();
        terminalChunkLoading = TERMINAL_CHUNK_LOADING.get();
        maxTerminalChunkTicketsPerDimension = MAX_TERMINAL_CHUNK_TICKETS_PER_DIMENSION.get();
        maxTerminalChunkTicketsPerServer = MAX_TERMINAL_CHUNK_TICKETS_PER_SERVER.get();
    }
}
