package com.malice.terminalcraft.scada;

/** Authorization boundary for SCADA configuration and operation. */
public enum ScadaAction {
    VIEW(ScadaRole.VIEWER),
    ACKNOWLEDGE(ScadaRole.OPERATOR),
    CONTROL(ScadaRole.OPERATOR),
    CONFIGURE(ScadaRole.ENGINEER),
    MANAGE_SECURITY(ScadaRole.ADMIN);

    private final ScadaRole minimumRole;

    ScadaAction(ScadaRole minimumRole) { this.minimumRole = minimumRole; }

    public ScadaRole minimumRole() { return minimumRole; }
}
