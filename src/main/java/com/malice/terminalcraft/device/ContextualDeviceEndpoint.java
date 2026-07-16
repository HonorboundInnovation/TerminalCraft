package com.malice.terminalcraft.device;

import java.util.List;

/** Device endpoint that needs the server-authenticated caller for an invocation. */
public interface ContextualDeviceEndpoint extends DeviceEndpoint {
    DeviceResult call(DeviceCallContext context, String method, List<DeviceValue> arguments);

    /** Direct unbound invocation is deliberately least-privilege. */
    @Override
    default DeviceResult call(String method, List<DeviceValue> arguments) {
        return call(DeviceCallContext.readOnly("unbound-device-call"), method, arguments);
    }
}
