package com.malice.terminalcraft.server;

/** Server-owned runtime limits; hard persistence limits remain independent of admission policy. */
public record RackResourcePolicy(int jobs, int jobsPerOwner, int jobsPerTick,
                                 int services, int servicesPerOwner, int minimumServiceInterval,
                                 boolean remoteShellEnabled, int remoteOutputCharacters) {
    public static final RackResourcePolicy DEFAULT = new RackResourcePolicy(128, 32, 64, 32, 8, 1, true, 4096);

    public RackResourcePolicy {
        if (jobs < 0 || jobs > 128 || jobsPerOwner < 0 || jobsPerOwner > 32
                || jobsPerTick < 0 || jobsPerTick > 64 || services < 0 || services > 32
                || servicesPerOwner < 0 || servicesPerOwner > 8 || minimumServiceInterval < 1
                || minimumServiceInterval > 12096000 || remoteOutputCharacters < 32 || remoteOutputCharacters > 4096)
            throw new IllegalArgumentException("rack resource limits outside supported ranges");
    }

    public String diagnostics() {
        return "jobs=" + jobs + " jobs_per_owner=" + jobsPerOwner + " jobs_per_tick=" + jobsPerTick
                + " services=" + services + " services_per_owner=" + servicesPerOwner
                + " minimum_service_interval=" + minimumServiceInterval
                + " remote_shell=" + remoteShellEnabled + " remote_output_chars=" + remoteOutputCharacters;
    }
}
