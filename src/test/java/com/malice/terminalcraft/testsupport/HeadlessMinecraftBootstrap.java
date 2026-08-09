package com.malice.terminalcraft.testsupport;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * Initializes vanilla registries for direct JavaExec tests.
 *
 * <p>Forge's patched bootstrap initializes networking last. Direct JavaExec tests do not run under
 * ModLauncher, so EventBus's event-subclass transformer is absent and NetworkHooks cannot create
 * its listener metadata. Registry initialization has completed by that point and is sufficient for
 * these pure persistence/value tests. Only that exact transformer-related failure is tolerated.</p>
 */
public final class HeadlessMinecraftBootstrap {
    private static boolean initialized;

    private HeadlessMinecraftBootstrap() {}

    public static synchronized void initialize() {
        if (initialized) return;
        SharedConstants.tryDetectVersion();
        try {
            Bootstrap.bootStrap();
        } catch (ExceptionInInitializerError error) {
            if (!causedByMissingNetworkEventConstructor(error)) throw error;
        }
        initialized = true;
    }

    private static boolean causedByMissingNetworkEventConstructor(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof NoSuchMethodException
                    && current.getMessage() != null
                    && current.getMessage().contains("net.minecraftforge.network.NetworkEvent.<init>()")) {
                return true;
            }
        }
        return false;
    }
}
