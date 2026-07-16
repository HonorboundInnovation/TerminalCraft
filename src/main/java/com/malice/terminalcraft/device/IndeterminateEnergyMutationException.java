package com.malice.terminalcraft.device;

/** Signals that a non-reversible FE execution failed after mutation may have begun. */
final class IndeterminateEnergyMutationException extends RuntimeException {
    IndeterminateEnergyMutationException(String message, Throwable cause) {
        super(message, cause);
    }
}
