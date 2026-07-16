package com.malice.terminalcraft.integration;

/**
 * TerminalCraft-owned lifecycle boundary for one optional mod integration.
 *
 * <p>Implementations may reference third-party APIs, but those implementation classes must not be
 * loaded until their required mod has been confirmed present.</p>
 */
@FunctionalInterface
public interface OptionalIntegration {
    void initialize() throws Exception;
}
