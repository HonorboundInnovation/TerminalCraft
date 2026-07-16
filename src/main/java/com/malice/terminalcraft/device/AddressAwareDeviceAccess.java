package com.malice.terminalcraft.device;

/** Internal authority query used to prevent a local capability overlay from shadowing a registered endpoint. */
interface AddressAwareDeviceAccess {
    boolean ownsAddress(String address);
}
