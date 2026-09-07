package com.malice.terminalcraft.device;

import com.malice.terminalcraft.blockentity.ServerRackBlockEntity;
import java.util.*;

/** Retains the ordinary computer API and adds authenticated rack-shell execution. */
public final class RackDeviceEndpoint implements ContextualDeviceEndpoint {
    private final ServerRackBlockEntity rack;
    private final TerminalHostDeviceEndpoint computer;

    public RackDeviceEndpoint(ServerRackBlockEntity rack) {
        this.rack = rack;
        this.computer = new TerminalHostDeviceEndpoint(rack.getDeviceId(), rack.getDeviceAddress(), rack,
                () -> !rack.isRemoved(), () -> !rack.isRemoved());
    }

    @Override public DeviceDescriptor descriptor() {
        DeviceDescriptor base = computer.descriptor();
        var methods = new ArrayList<>(base.methods());
        methods.add(new DeviceMethodDescriptor("shell.exec", "Runs a command as an allowed player on the shared rack shell",
                List.of(new DeviceParameterDescriptor("command", DeviceValueType.STRING, true, "Shell command text")),
                DeviceValueType.MAP, DeviceCallContext.WRITE));
        var capabilities = new HashSet<>(base.capabilities());
        capabilities.add("remote_shell");
        return new DeviceDescriptor(base.deviceId(), "terminalcraft:server_rack", "server_rack", base.displayName(),
                base.modSource(), base.address(), capabilities, base.properties(), methods, base.eventTypes(),
                base.permissions(), base.online(), base.loaded());
    }

    @Override public DeviceResult call(DeviceCallContext caller, String method, List<DeviceValue> arguments) {
        if (!"shell.exec".equals(method)) return computer.call(method, arguments);
        if (arguments == null || arguments.size() != 1 || !(arguments.get(0) instanceof DeviceValue.StringValue command))
            return DeviceResult.failure(DeviceErrorCode.INVALID_ARGUMENT, "shell.exec requires one command string", false);
        return rack.remoteShell().execute(rack, caller, command.value());
    }
}
