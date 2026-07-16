package com.malice.terminalcraft.device;

import java.util.List;

/** Runtime endpoint exposed by a device adapter. Calls execute on the logical server. */
public interface DeviceEndpoint {
    DeviceDescriptor descriptor();

    DeviceResult call(String method, List<DeviceValue> arguments);
}
