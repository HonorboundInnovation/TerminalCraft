package com.malice.terminalcraft.scada;

import com.malice.terminalcraft.device.DeviceCallContext;
import com.malice.terminalcraft.device.DeviceValue;
import com.malice.terminalcraft.device.PrincipalIdentity;
import com.malice.terminalcraft.persistence.PersistedDataVersions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-global, bounded process database for SCADA configuration and runtime state.
 * Definitions, latest values, historian points, alarm lifecycle, dashboards, roles and audit
 * records are deliberately saved together so a world backup is an internally consistent snapshot.
 */
public final class ScadaSavedData extends SavedData {
    public static final String FILE_ID = "terminalcraft_scada";
    public static final int MAX_TAGS = 256;
    public static final int MAX_ALARMS = 256;
    public static final int MAX_DASHBOARDS = 64;
    public static final int MAX_HMI_DASHBOARDS = 64;
    public static final int MAX_ROLE_ASSIGNMENTS = 256;
    public static final int MAX_AUDIT_ENTRIES = 1024;
    public static final int MAX_HISTORY_PER_TAG = 2048;
    public static final int MAX_HISTORY_SAMPLES = 65_536;
    public static final int MAX_ENUMERATION = 256;

    private final Map<String, ScadaTag> tags = new LinkedHashMap<>();
    private final Map<String, ScadaSnapshot> snapshots = new LinkedHashMap<>();
    private final Map<String, Deque<ScadaSample>> history = new LinkedHashMap<>();
    private final Map<String, AlarmRuntime> alarms = new LinkedHashMap<>();
    private final Map<UUID, ScadaDashboard> dashboards = new LinkedHashMap<>();
    private final Map<String, ScadaHmiDashboard> hmiDashboards = new LinkedHashMap<>();
    private final Map<String, RoleAssignment> roles = new LinkedHashMap<>();
    private final Deque<AuditEntry> audit = new ArrayDeque<>();
    private int historySamples;
    private int acquisitionCursor;

    public enum AlarmState { NORMAL, ACTIVE, ACKNOWLEDGED, SHELVED }

    public record Operation(boolean success, String message) {
        public Operation {
            message = Objects.requireNonNullElse(message, "");
        }
        public static Operation ok(String message) { return new Operation(true, message); }
        public static Operation fail(String message) { return new Operation(false, message); }
    }

    public record RoleAssignment(PrincipalIdentity principal, ScadaRole role) {
        public RoleAssignment {
            principal = Objects.requireNonNull(principal, "principal");
            role = Objects.requireNonNull(role, "role");
        }
    }

    public record AuditEntry(long gameTime, String principal, String action, String detail) {
        public AuditEntry {
            if (gameTime < 0) throw new IllegalArgumentException("audit time must not be negative");
            principal = bounded(principal, 160);
            action = bounded(action, 64);
            detail = bounded(detail, 256);
        }
    }

    public record AlarmView(ScadaAlarmRule rule, AlarmState state, long activeSince,
                            long stateChangedAt, long shelvedUntil, String acknowledgedBy) {}

    /** Transition information used by the virtual device endpoint to publish bounded events. */
    public record Update(boolean tagChanged, List<String> changedAlarms) {
        public Update { changedAlarms = List.copyOf(changedAlarms); }
    }

    public record StaleUpdate(String tagName, List<String> changedAlarms) {
        public StaleUpdate { changedAlarms = List.copyOf(changedAlarms); }
    }

    public static ScadaSavedData get(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return server.overworld().getDataStorage().computeIfAbsent(
                ScadaSavedData::load, ScadaSavedData::new, FILE_ID);
    }

    public synchronized boolean initialized() { return !roles.isEmpty(); }

    public synchronized Optional<ScadaRole> role(PrincipalIdentity principal) {
        if (principal == null) return Optional.empty();
        RoleAssignment assignment = roles.get(principal.authorityKey());
        return assignment == null ? Optional.empty() : Optional.of(assignment.role());
    }

    public synchronized boolean authorized(DeviceCallContext context, ScadaAction action) {
        if (context == null || action == null) return false;
        return role(context.principal()).map(candidate -> candidate.allows(action)).orElse(false);
    }

    /** The first authenticated player claims the plant as its initial administrator. */
    public synchronized Operation initialize(DeviceCallContext context, long gameTime) {
        if (initialized()) return Operation.fail("SCADA is already initialized");
        if (context == null || context.principalKind() != PrincipalIdentity.Kind.PLAYER) {
            return Operation.fail("SCADA initialization requires an authenticated player");
        }
        RoleAssignment assignment = new RoleAssignment(context.principal(), ScadaRole.ADMIN);
        roles.put(context.authorityKey(), assignment);
        appendAudit(gameTime, context.principal(), "security.initialize", "initial administrator");
        setDirty();
        return Operation.ok("SCADA initialized; " + context.principalName() + " is administrator");
    }

    public synchronized Operation putTag(DeviceCallContext context, ScadaTag tag, long gameTime) {
        Operation denied = require(context, ScadaAction.CONFIGURE);
        if (denied != null) return denied;
        Objects.requireNonNull(tag, "tag");
        boolean replacing = tags.containsKey(tag.name());
        if (!replacing && tags.size() >= MAX_TAGS) return Operation.fail("SCADA tag capacity reached");
        tags.put(tag.name(), tag);
        snapshots.remove(tag.name());
        removeHistory(tag.name());
        if (replacing) {
            alarms.replaceAll((ignored, runtime) -> runtime.rule.tagName().equals(tag.name())
                    ? new AlarmRuntime(runtime.rule) : runtime);
        }
        appendAudit(gameTime, context.principal(), replacing ? "tag.update" : "tag.create", tag.name());
        setDirty();
        return Operation.ok((replacing ? "updated " : "created ") + tag.name());
    }

    public synchronized Operation removeTag(DeviceCallContext context, String requestedName, long gameTime) {
        Operation denied = require(context, ScadaAction.CONFIGURE);
        if (denied != null) return denied;
        String name;
        try { name = ScadaTag.canonicalName(requestedName); }
        catch (IllegalArgumentException invalid) { return Operation.fail(invalid.getMessage()); }
        if (tags.remove(name) == null) return Operation.fail("tag not found: " + name);
        snapshots.remove(name);
        removeHistory(name);
        alarms.entrySet().removeIf(entry -> entry.getValue().rule.tagName().equals(name));
        appendAudit(gameTime, context.principal(), "tag.remove", name);
        setDirty();
        return Operation.ok("removed " + name + " and its alarm bindings");
    }

    public synchronized List<ScadaTag> tags(String requestedPrefix, int maximum) {
        String prefix = normalizePrefix(requestedPrefix);
        int limit = boundLimit(maximum);
        return tags.values().stream().filter(tag -> prefix.isEmpty() || tag.name().equals(prefix)
                        || tag.name().startsWith(prefix + "."))
                .sorted(Comparator.comparing(ScadaTag::name)).limit(limit).toList();
    }

    public synchronized Optional<ScadaTag> tag(String requestedName) {
        try { return Optional.ofNullable(tags.get(ScadaTag.canonicalName(requestedName))); }
        catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }

    public synchronized Optional<ScadaSnapshot> snapshot(String requestedName, long gameTime) {
        ScadaTag tag = tag(requestedName).orElse(null);
        if (tag == null) return Optional.empty();
        ScadaSnapshot snapshot = snapshots.get(tag.name());
        return snapshot == null ? Optional.empty() : Optional.of(snapshot.effective(gameTime, tag.staleAfterTicks()));
    }

    public synchronized List<ScadaSample> history(String requestedName, int maximum) {
        String name;
        try { name = ScadaTag.canonicalName(requestedName); }
        catch (IllegalArgumentException invalid) { return List.of(); }
        int limit = boundLimit(maximum);
        Deque<ScadaSample> samples = history.get(name);
        if (samples == null || limit == 0) return List.of();
        List<ScadaSample> result = new ArrayList<>(samples);
        return List.copyOf(result.subList(Math.max(0, result.size() - limit), result.size()));
    }

    public synchronized Operation putAlarm(DeviceCallContext context, ScadaAlarmRule rule, long gameTime) {
        Operation denied = require(context, ScadaAction.CONFIGURE);
        if (denied != null) return denied;
        Objects.requireNonNull(rule, "rule");
        if (!tags.containsKey(rule.tagName())) return Operation.fail("alarm tag not found: " + rule.tagName());
        boolean replacing = alarms.containsKey(rule.name());
        if (!replacing && alarms.size() >= MAX_ALARMS) return Operation.fail("SCADA alarm capacity reached");
        alarms.put(rule.name(), new AlarmRuntime(rule));
        appendAudit(gameTime, context.principal(), replacing ? "alarm.update" : "alarm.create", rule.name());
        setDirty();
        return Operation.ok((replacing ? "updated " : "created ") + rule.name());
    }

    public synchronized Operation removeAlarm(DeviceCallContext context, String requestedName, long gameTime) {
        Operation denied = require(context, ScadaAction.CONFIGURE);
        if (denied != null) return denied;
        String name;
        try { name = ScadaAlarmRule.canonicalRuleName(requestedName); }
        catch (IllegalArgumentException invalid) { return Operation.fail(invalid.getMessage()); }
        if (alarms.remove(name) == null) return Operation.fail("alarm not found: " + name);
        appendAudit(gameTime, context.principal(), "alarm.remove", name);
        setDirty();
        return Operation.ok("removed " + name);
    }

    public synchronized List<AlarmView> alarms(int maximum, long gameTime) {
        int limit = boundLimit(maximum);
        return alarms.values().stream().sorted(Comparator.comparing(runtime -> runtime.rule.name()))
                .limit(limit).map(runtime -> runtime.view(gameTime)).toList();
    }

    public synchronized Optional<AlarmView> alarm(String requestedName, long gameTime) {
        try {
            AlarmRuntime runtime = alarms.get(ScadaAlarmRule.canonicalRuleName(requestedName));
            return runtime == null ? Optional.empty() : Optional.of(runtime.view(gameTime));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    public synchronized Operation acknowledgeAlarm(DeviceCallContext context, String requestedName, long gameTime) {
        Operation denied = require(context, ScadaAction.ACKNOWLEDGE);
        if (denied != null) return denied;
        AlarmRuntime runtime;
        try { runtime = alarms.get(ScadaAlarmRule.canonicalRuleName(requestedName)); }
        catch (IllegalArgumentException invalid) { return Operation.fail(invalid.getMessage()); }
        if (runtime == null) return Operation.fail("alarm not found");
        if (!runtime.active) return Operation.fail("alarm is not active");
        if (runtime.acknowledged) return Operation.ok("alarm already acknowledged");
        runtime.acknowledged = true;
        runtime.acknowledgedBy = context.principal().authorityKey();
        runtime.stateChangedAt = gameTime;
        appendAudit(gameTime, context.principal(), "alarm.acknowledge", runtime.rule.name());
        setDirty();
        return Operation.ok("acknowledged " + runtime.rule.name());
    }

    public synchronized Operation shelveAlarm(DeviceCallContext context, String requestedName,
                                               long durationTicks, long gameTime) {
        Operation denied = require(context, ScadaAction.ACKNOWLEDGE);
        if (denied != null) return denied;
        if (durationTicks < 0 || durationTicks > 20L * 60L * 60L) {
            return Operation.fail("shelve duration must be 0..72000 ticks");
        }
        AlarmRuntime runtime;
        try { runtime = alarms.get(ScadaAlarmRule.canonicalRuleName(requestedName)); }
        catch (IllegalArgumentException invalid) { return Operation.fail(invalid.getMessage()); }
        if (runtime == null) return Operation.fail("alarm not found");
        runtime.shelvedUntil = durationTicks == 0 ? 0 : gameTime + durationTicks;
        runtime.stateChangedAt = gameTime;
        appendAudit(gameTime, context.principal(), durationTicks == 0 ? "alarm.unshelve" : "alarm.shelve",
                runtime.rule.name() + (durationTicks == 0 ? "" : " for " + durationTicks + " ticks"));
        setDirty();
        return Operation.ok(durationTicks == 0 ? "unshelved " + runtime.rule.name()
                : "shelved " + runtime.rule.name() + " until " + runtime.shelvedUntil);
    }

    public synchronized Operation putDashboard(DeviceCallContext context, ScadaDashboard dashboard, long gameTime) {
        Operation denied = require(context, ScadaAction.CONFIGURE);
        if (denied != null) return denied;
        Objects.requireNonNull(dashboard, "dashboard");
        boolean replacing = dashboards.containsKey(dashboard.monitorId());
        if (!replacing && dashboards.size() >= MAX_DASHBOARDS) return Operation.fail("SCADA dashboard capacity reached");
        dashboards.put(dashboard.monitorId(), dashboard);
        appendAudit(gameTime, context.principal(), replacing ? "dashboard.update" : "dashboard.create",
                dashboard.monitorId().toString());
        setDirty();
        return Operation.ok((replacing ? "updated " : "created ") + "dashboard " + dashboard.monitorId());
    }

    public synchronized Operation removeDashboard(DeviceCallContext context, UUID monitorId, long gameTime) {
        Operation denied = require(context, ScadaAction.CONFIGURE);
        if (denied != null) return denied;
        if (monitorId == null || dashboards.remove(monitorId) == null) return Operation.fail("dashboard not found");
        appendAudit(gameTime, context.principal(), "dashboard.remove", monitorId.toString());
        setDirty();
        return Operation.ok("removed dashboard " + monitorId);
    }

    public synchronized List<ScadaDashboard> dashboards() {
        return dashboards.values().stream().sorted(Comparator.comparing(value -> value.monitorId().toString())).toList();
    }

    public synchronized Operation putHmiDashboard(DeviceCallContext context, ScadaHmiDashboard dashboard,
                                                   long gameTime) {
        Operation denied = require(context, ScadaAction.CONFIGURE);
        if (denied != null) return denied;
        Objects.requireNonNull(dashboard, "dashboard");
        boolean replacing = hmiDashboards.containsKey(dashboard.name());
        if (!replacing && hmiDashboards.size() >= MAX_HMI_DASHBOARDS) {
            return Operation.fail("advanced HMI dashboard capacity reached");
        }
        boolean monitorConflict = hmiDashboards.values().stream().anyMatch(existing ->
                !existing.name().equals(dashboard.name()) && existing.monitorId().equals(dashboard.monitorId()));
        if (monitorConflict) return Operation.fail("monitor wall is already assigned to another advanced HMI");
        hmiDashboards.put(dashboard.name(), dashboard);
        // Advanced HMI owns the wall surface; remove a legacy auto-list binding for the same wall.
        dashboards.remove(dashboard.monitorId());
        appendAudit(gameTime, context.principal(), replacing ? "hmi.update" : "hmi.create", dashboard.name());
        setDirty();
        return Operation.ok((replacing ? "updated " : "created ") + "advanced HMI " + dashboard.name());
    }

    public synchronized Operation removeHmiDashboard(DeviceCallContext context, String requestedName, long gameTime) {
        Operation denied = require(context, ScadaAction.CONFIGURE);
        if (denied != null) return denied;
        String name;
        try { name = ScadaHmiDashboard.canonicalName(requestedName); }
        catch (IllegalArgumentException invalid) { return Operation.fail(invalid.getMessage()); }
        if (hmiDashboards.remove(name) == null) return Operation.fail("advanced HMI not found: " + name);
        appendAudit(gameTime, context.principal(), "hmi.remove", name);
        setDirty();
        return Operation.ok("removed advanced HMI " + name);
    }

    public synchronized List<ScadaHmiDashboard> hmiDashboards() {
        return hmiDashboards.values().stream().sorted(Comparator.comparing(ScadaHmiDashboard::name)).toList();
    }

    public synchronized Optional<ScadaHmiDashboard> hmiDashboard(String requestedName) {
        try { return Optional.ofNullable(hmiDashboards.get(ScadaHmiDashboard.canonicalName(requestedName))); }
        catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }

    public synchronized Optional<ScadaHmiDashboard> hmiDashboard(UUID monitorId) {
        if (monitorId == null) return Optional.empty();
        return hmiDashboards.values().stream().filter(value -> value.monitorId().equals(monitorId)).findFirst();
    }

    public synchronized Operation putHmiPage(DeviceCallContext context, String dashboardName,
                                              ScadaHmiPage page, long gameTime) {
        Operation denied = require(context, ScadaAction.CONFIGURE);
        if (denied != null) return denied;
        ScadaHmiDashboard dashboard = hmiDashboard(dashboardName).orElse(null);
        if (dashboard == null) return Operation.fail("advanced HMI not found: " + dashboardName);
        boolean replacing = dashboard.page(page.name()) != null;
        try { hmiDashboards.put(dashboard.name(), dashboard.withPage(page)); }
        catch (IllegalArgumentException invalid) { return Operation.fail(invalid.getMessage()); }
        appendAudit(gameTime, context.principal(), replacing ? "hmi.page.update" : "hmi.page.create",
                dashboard.name() + "/" + page.name());
        setDirty();
        return Operation.ok((replacing ? "updated " : "created ") + "HMI page " + page.name());
    }

    public synchronized Operation removeHmiPage(DeviceCallContext context, String dashboardName,
                                                 String pageName, long gameTime) {
        Operation denied = require(context, ScadaAction.CONFIGURE);
        if (denied != null) return denied;
        ScadaHmiDashboard dashboard = hmiDashboard(dashboardName).orElse(null);
        if (dashboard == null) return Operation.fail("advanced HMI not found: " + dashboardName);
        try { hmiDashboards.put(dashboard.name(), dashboard.withoutPage(pageName)); }
        catch (IllegalArgumentException invalid) { return Operation.fail(invalid.getMessage()); }
        appendAudit(gameTime, context.principal(), "hmi.page.remove", dashboard.name() + "/" + pageName);
        setDirty();
        return Operation.ok("removed HMI page " + pageName);
    }

    public synchronized Operation selectHmiPage(DeviceCallContext context, String dashboardName,
                                                 String pageName, long gameTime) {
        Operation denied = require(context, ScadaAction.VIEW);
        if (denied != null) return denied;
        ScadaHmiDashboard dashboard = hmiDashboard(dashboardName).orElse(null);
        if (dashboard == null) return Operation.fail("advanced HMI not found: " + dashboardName);
        try { hmiDashboards.put(dashboard.name(), dashboard.withActivePage(pageName)); }
        catch (IllegalArgumentException invalid) { return Operation.fail(invalid.getMessage()); }
        appendAudit(gameTime, context.principal(), "hmi.page.select", dashboard.name() + "/" + pageName);
        setDirty();
        return Operation.ok("selected HMI page " + pageName);
    }

    public synchronized Operation putHmiWidget(DeviceCallContext context, String dashboardName,
                                                String pageName, ScadaHmiWidget widget, long gameTime) {
        Operation denied = require(context, ScadaAction.CONFIGURE);
        if (denied != null) return denied;
        ScadaHmiDashboard dashboard = hmiDashboard(dashboardName).orElse(null);
        if (dashboard == null) return Operation.fail("advanced HMI not found: " + dashboardName);
        ScadaHmiPage page = dashboard.page(pageName);
        if (page == null) return Operation.fail("HMI page not found: " + pageName);
        Operation invalid = validateWidgetBinding(dashboard, widget);
        if (invalid != null) return invalid;
        boolean replacing = page.widget(widget.id()) != null;
        try { hmiDashboards.put(dashboard.name(), dashboard.withPage(page.withWidget(widget))); }
        catch (IllegalArgumentException rejected) { return Operation.fail(rejected.getMessage()); }
        appendAudit(gameTime, context.principal(), replacing ? "hmi.widget.update" : "hmi.widget.create",
                dashboard.name() + "/" + page.name() + "/" + widget.id());
        setDirty();
        return Operation.ok((replacing ? "updated " : "created ") + "HMI widget " + widget.id());
    }

    public synchronized Operation removeHmiWidget(DeviceCallContext context, String dashboardName,
                                                   String pageName, String widgetId, long gameTime) {
        Operation denied = require(context, ScadaAction.CONFIGURE);
        if (denied != null) return denied;
        ScadaHmiDashboard dashboard = hmiDashboard(dashboardName).orElse(null);
        if (dashboard == null) return Operation.fail("advanced HMI not found: " + dashboardName);
        ScadaHmiPage page = dashboard.page(pageName);
        if (page == null) return Operation.fail("HMI page not found: " + pageName);
        try { hmiDashboards.put(dashboard.name(), dashboard.withPage(page.withoutWidget(widgetId))); }
        catch (IllegalArgumentException invalid) { return Operation.fail(invalid.getMessage()); }
        appendAudit(gameTime, context.principal(), "hmi.widget.remove",
                dashboard.name() + "/" + page.name() + "/" + widgetId);
        setDirty();
        return Operation.ok("removed HMI widget " + widgetId);
    }

    private Operation validateWidgetBinding(ScadaHmiDashboard dashboard, ScadaHmiWidget widget) {
        Objects.requireNonNull(widget, "widget");
        if (widget.type().tagBound()) {
            ScadaTag tag = tags.get(widget.source());
            if (tag == null) return Operation.fail("HMI widget tag not found: " + widget.source());
            if (widget.type() == ScadaHmiWidget.Type.BUTTON) {
                if (tag.writeMethod().isEmpty()) return Operation.fail("HMI button tag is read-only: " + tag.name());
                if (tag.writeRequiresValue() != (widget.actionValue() != null)) {
                    return Operation.fail(tag.writeRequiresValue()
                            ? "HMI button requires a typed action value"
                            : "HMI command button must use action 'command'");
                }
            }
        }
        if (widget.type() == ScadaHmiWidget.Type.PAGE_LINK && dashboard.page(widget.source()) == null) {
            return Operation.fail("HMI page-link target not found: " + widget.source());
        }
        return null;
    }

    public synchronized Operation grantRole(DeviceCallContext context, PrincipalIdentity principal,
                                             ScadaRole role, long gameTime) {
        Operation denied = require(context, ScadaAction.MANAGE_SECURITY);
        if (denied != null) return denied;
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(role, "role");
        if (!roles.containsKey(principal.authorityKey()) && roles.size() >= MAX_ROLE_ASSIGNMENTS) {
            return Operation.fail("SCADA role capacity reached");
        }
        RoleAssignment previous = roles.get(principal.authorityKey());
        long administrators = roles.values().stream().filter(entry -> entry.role() == ScadaRole.ADMIN).count();
        if (previous != null && previous.role() == ScadaRole.ADMIN && role != ScadaRole.ADMIN
                && administrators <= 1) {
            return Operation.fail("cannot demote the final SCADA administrator");
        }
        roles.put(principal.authorityKey(), new RoleAssignment(principal, role));
        appendAudit(gameTime, context.principal(), "security.grant",
                principal.authorityKey() + " " + role.name().toLowerCase(Locale.ROOT));
        setDirty();
        return Operation.ok("granted " + role.name().toLowerCase(Locale.ROOT) + " to " + principal.name());
    }

    public synchronized Operation revokeRole(DeviceCallContext context, PrincipalIdentity principal, long gameTime) {
        Operation denied = require(context, ScadaAction.MANAGE_SECURITY);
        if (denied != null) return denied;
        if (principal == null || !roles.containsKey(principal.authorityKey())) return Operation.fail("role assignment not found");
        long administrators = roles.values().stream().filter(entry -> entry.role() == ScadaRole.ADMIN).count();
        if (roles.get(principal.authorityKey()).role() == ScadaRole.ADMIN && administrators <= 1) {
            return Operation.fail("cannot remove the final SCADA administrator");
        }
        roles.remove(principal.authorityKey());
        appendAudit(gameTime, context.principal(), "security.revoke", principal.authorityKey());
        setDirty();
        return Operation.ok("revoked SCADA access from " + principal.name());
    }

    public synchronized List<RoleAssignment> roles() {
        return roles.values().stream().sorted(Comparator.comparing(value -> value.principal().authorityKey())).toList();
    }

    public synchronized List<AuditEntry> audit(int maximum) {
        int limit = boundLimit(maximum);
        List<AuditEntry> entries = new ArrayList<>(audit);
        return List.copyOf(entries.subList(Math.max(0, entries.size() - limit), entries.size()));
    }

    synchronized void recordControl(DeviceCallContext context, String tagName, ScadaScalar value,
                                    boolean success, String detail, long gameTime) {
        appendAudit(gameTime, context == null ? null : context.principal(),
                success ? value == null ? "control.command" : "control.write" : "control.reject",
                tagName + (value == null ? " (command)" : "=" + value.display()) + " " + bounded(detail, 160));
        setDirty();
    }

    /** Selects at most {@code maximum} due definitions in stable round-robin order. */
    synchronized List<ScadaTag> dueTags(long gameTime, int maximum) {
        if (tags.isEmpty() || maximum <= 0) return List.of();
        List<ScadaTag> ordered = tags.values().stream().sorted(Comparator.comparing(ScadaTag::name)).toList();
        List<ScadaTag> due = new ArrayList<>();
        int start = Math.floorMod(acquisitionCursor, ordered.size());
        for (int offset = 0; offset < ordered.size() && due.size() < maximum; offset++) {
            ScadaTag tag = ordered.get((start + offset) % ordered.size());
            ScadaSnapshot last = snapshots.get(tag.name());
            if (last == null || gameTime - last.sampledAt() >= tag.sampleIntervalTicks()) due.add(tag);
        }
        acquisitionCursor = (start + Math.max(1, due.size())) % ordered.size();
        return List.copyOf(due);
    }

    synchronized Update recordAcquisition(String tagName, ScadaScalar value, ScadaQuality quality,
                                          String detail, long gameTime) {
        ScadaTag tag = tags.get(tagName);
        if (tag == null) return new Update(false, List.of());
        ScadaSnapshot previous = snapshots.get(tagName);
        ScadaScalar retained = value != null ? value : previous == null ? null : previous.value();
        long lastGood = quality == ScadaQuality.GOOD ? gameTime : previous == null ? -1 : previous.lastGoodAt();
        ScadaSnapshot next = new ScadaSnapshot(retained, quality, gameTime, lastGood, detail);
        boolean changed = previous == null || previous.quality() != next.quality()
                || !equivalent(previous.value(), next.value());
        snapshots.put(tagName, next);
        appendHistory(tagName, new ScadaSample(gameTime, retained, quality));
        List<String> alarmChanges = evaluateAlarms(tagName, next, gameTime);
        if (changed) appendAudit(gameTime, systemPrincipal(), "tag.update",
                tagName + " " + next.display(tag.unit()));
        setDirty();
        return new Update(changed, alarmChanges);
    }

    /** Materializes stale quality so bad-quality alarms and events transition exactly once. */
    synchronized List<StaleUpdate> markStale(long gameTime) {
        List<StaleUpdate> updates = new ArrayList<>();
        for (ScadaTag tag : tags.values()) {
            ScadaSnapshot current = snapshots.get(tag.name());
            if (current == null || current.quality() != ScadaQuality.GOOD
                    || gameTime - current.sampledAt() <= tag.staleAfterTicks()) continue;
            ScadaSnapshot stale = new ScadaSnapshot(current.value(), ScadaQuality.STALE,
                    current.sampledAt(), current.lastGoodAt(),
                    "last update exceeded " + tag.staleAfterTicks() + " ticks");
            snapshots.put(tag.name(), stale);
            appendHistory(tag.name(), new ScadaSample(gameTime, stale.value(), ScadaQuality.STALE));
            List<String> alarmChanges = evaluateAlarms(tag.name(), stale, gameTime);
            appendAudit(gameTime, systemPrincipal(), "tag.stale", tag.name());
            updates.add(new StaleUpdate(tag.name(), alarmChanges));
        }
        if (!updates.isEmpty()) setDirty();
        return List.copyOf(updates);
    }

    /** Expires bounded shelves on logical game time and returns alarms whose visible state changed. */
    synchronized List<String> expireShelves(long gameTime) {
        List<String> changed = new ArrayList<>();
        for (AlarmRuntime runtime : alarms.values()) {
            if (runtime.shelvedUntil <= 0 || runtime.shelvedUntil > gameTime) continue;
            runtime.shelvedUntil = 0;
            runtime.stateChangedAt = gameTime;
            appendAudit(gameTime, systemPrincipal(), "alarm.unshelve", runtime.rule.name() + " timeout");
            changed.add(runtime.rule.name());
        }
        if (!changed.isEmpty()) setDirty();
        return List.copyOf(changed);
    }

    private List<String> evaluateAlarms(String tagName, ScadaSnapshot snapshot, long gameTime) {
        List<String> changed = new ArrayList<>();
        for (AlarmRuntime runtime : alarms.values()) {
            if (!runtime.rule.tagName().equals(tagName)) continue;
            boolean nextActive = alarmCondition(runtime, snapshot);
            if (nextActive == runtime.active) continue;
            runtime.active = nextActive;
            runtime.acknowledged = false;
            runtime.acknowledgedBy = "";
            runtime.stateChangedAt = gameTime;
            if (nextActive) {
                runtime.activeSince = gameTime;
                appendAudit(gameTime, systemPrincipal(), "alarm.activate",
                        runtime.rule.name() + " severity=" + runtime.rule.severity().name().toLowerCase(Locale.ROOT));
            } else {
                runtime.activeSince = -1;
                appendAudit(gameTime, systemPrincipal(), "alarm.clear", runtime.rule.name());
            }
            changed.add(runtime.rule.name());
        }
        return List.copyOf(changed);
    }

    private static boolean alarmCondition(AlarmRuntime runtime, ScadaSnapshot snapshot) {
        ScadaAlarmRule rule = runtime.rule;
        if (rule.operator() == ScadaAlarmRule.Operator.BAD_QUALITY) return snapshot.quality() != ScadaQuality.GOOD;
        ScadaScalar value = snapshot.value();
        ScadaScalar threshold = rule.threshold();
        if (snapshot.quality() != ScadaQuality.GOOD || value == null || threshold == null) return false;
        if (rule.operator() == ScadaAlarmRule.Operator.ABOVE || rule.operator() == ScadaAlarmRule.Operator.BELOW) {
            if (value.type() != ScadaScalar.Type.NUMBER || threshold.type() != ScadaScalar.Type.NUMBER) return false;
            double measured = value.numberValue();
            double limit = threshold.numberValue();
            if (rule.operator() == ScadaAlarmRule.Operator.ABOVE) {
                return runtime.active ? measured > limit - rule.deadband() : measured > limit;
            }
            return runtime.active ? measured < limit + rule.deadband() : measured < limit;
        }
        boolean equal;
        if (value.type() == ScadaScalar.Type.NUMBER && threshold.type() == ScadaScalar.Type.NUMBER) {
            equal = Math.abs(value.numberValue() - threshold.numberValue()) <= rule.deadband();
        } else {
            equal = value.equivalent(threshold);
        }
        return rule.operator() == ScadaAlarmRule.Operator.EQUAL ? equal : !equal;
    }

    private void appendHistory(String name, ScadaSample sample) {
        Deque<ScadaSample> samples = history.computeIfAbsent(name, ignored -> new ArrayDeque<>());
        samples.addLast(sample);
        historySamples++;
        while (samples.size() > MAX_HISTORY_PER_TAG) {
            samples.removeFirst();
            historySamples--;
        }
        while (historySamples > MAX_HISTORY_SAMPLES) removeGloballyOldestHistorySample();
    }

    private void removeGloballyOldestHistorySample() {
        String oldestName = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<String, Deque<ScadaSample>> entry : history.entrySet()) {
            ScadaSample first = entry.getValue().peekFirst();
            if (first != null && first.gameTime() < oldestTime) {
                oldestName = entry.getKey();
                oldestTime = first.gameTime();
            }
        }
        if (oldestName == null) {
            historySamples = 0;
            return;
        }
        Deque<ScadaSample> samples = history.get(oldestName);
        samples.removeFirst();
        historySamples--;
        if (samples.isEmpty()) history.remove(oldestName);
    }

    private void removeHistory(String name) {
        Deque<ScadaSample> removed = history.remove(name);
        if (removed != null) historySamples -= removed.size();
    }

    private Operation require(DeviceCallContext context, ScadaAction action) {
        return authorized(context, action) ? null : Operation.fail("SCADA " + action.name().toLowerCase(Locale.ROOT)
                + " requires role " + action.minimumRole().name().toLowerCase(Locale.ROOT) + " or higher");
    }

    private void appendAudit(long gameTime, PrincipalIdentity principal, String action, String detail) {
        appendAudit(gameTime, principal == null ? "unknown" : principal.authorityKey(), action, detail);
    }

    private void appendAudit(long gameTime, String principal, String action, String detail) {
        audit.addLast(new AuditEntry(gameTime, principal, action, detail));
        while (audit.size() > MAX_AUDIT_ENTRIES) audit.removeFirst();
    }

    private static String systemPrincipal() { return "service:terminalcraft-scada"; }

    private static boolean equivalent(ScadaScalar left, ScadaScalar right) {
        return left == null ? right == null : left.equivalent(right);
    }

    private static int boundLimit(int maximum) { return Math.max(0, Math.min(maximum, MAX_ENUMERATION)); }

    private static String normalizePrefix(String prefix) {
        String value = Objects.requireNonNullElse(prefix, "").trim().toLowerCase(Locale.ROOT);
        return "*".equals(value) || "-".equals(value) ? "" : value;
    }

    private static String bounded(String value, int maximum) {
        String safe = Objects.requireNonNullElse(value, "").replace('\n', ' ').replace('\r', ' ');
        return safe.length() <= maximum ? safe : safe.substring(0, maximum);
    }

    @Override
    public synchronized CompoundTag save(CompoundTag root) {
        PersistedDataVersions.stampCurrent(root);
        ListTag savedTags = new ListTag();
        tags.values().stream().sorted(Comparator.comparing(ScadaTag::name)).forEach(tag -> savedTags.add(saveTag(tag)));
        root.put("Tags", savedTags);

        ListTag savedSnapshots = new ListTag();
        snapshots.forEach((name, snapshot) -> {
            CompoundTag entry = saveSnapshot(snapshot);
            entry.putString("Name", name);
            savedSnapshots.add(entry);
        });
        root.put("Snapshots", savedSnapshots);

        ListTag savedHistory = new ListTag();
        history.forEach((name, samples) -> {
            CompoundTag series = new CompoundTag();
            series.putString("Name", name);
            ListTag points = new ListTag();
            samples.forEach(sample -> points.add(saveSample(sample)));
            series.put("Points", points);
            savedHistory.add(series);
        });
        root.put("History", savedHistory);

        ListTag savedAlarms = new ListTag();
        alarms.values().stream().sorted(Comparator.comparing(value -> value.rule.name()))
                .forEach(value -> savedAlarms.add(saveAlarm(value)));
        root.put("Alarms", savedAlarms);

        ListTag savedDashboards = new ListTag();
        dashboards.values().forEach(value -> savedDashboards.add(saveDashboard(value)));
        root.put("Dashboards", savedDashboards);

        ListTag savedHmiDashboards = new ListTag();
        hmiDashboards.values().stream().sorted(Comparator.comparing(ScadaHmiDashboard::name))
                .forEach(value -> savedHmiDashboards.add(saveHmiDashboard(value)));
        root.put("HmiDashboards", savedHmiDashboards);

        ListTag savedRoles = new ListTag();
        roles.values().forEach(value -> savedRoles.add(saveRole(value)));
        root.put("Roles", savedRoles);

        ListTag savedAudit = new ListTag();
        audit.forEach(value -> savedAudit.add(saveAudit(value)));
        root.put("Audit", savedAudit);
        return root;
    }

    static ScadaSavedData load(CompoundTag root) {
        ScadaSavedData data = new ScadaSavedData();
        ListTag savedTags = root.getList("Tags", Tag.TAG_COMPOUND);
        for (int index = 0; index < savedTags.size() && data.tags.size() < MAX_TAGS; index++) {
            try {
                ScadaTag tag = loadTag(savedTags.getCompound(index));
                data.tags.putIfAbsent(tag.name(), tag);
            } catch (RuntimeException ignored) {}
        }
        ListTag savedSnapshots = root.getList("Snapshots", Tag.TAG_COMPOUND);
        for (int index = 0; index < savedSnapshots.size() && data.snapshots.size() < MAX_TAGS; index++) {
            try {
                CompoundTag entry = savedSnapshots.getCompound(index);
                String name = ScadaTag.canonicalName(entry.getString("Name"));
                if (data.tags.containsKey(name)) data.snapshots.put(name, loadSnapshot(entry));
            } catch (RuntimeException ignored) {}
        }
        ListTag savedHistory = root.getList("History", Tag.TAG_COMPOUND);
        for (int index = 0; index < savedHistory.size() && data.historySamples < MAX_HISTORY_SAMPLES; index++) {
            CompoundTag series = savedHistory.getCompound(index);
            try {
                String name = ScadaTag.canonicalName(series.getString("Name"));
                if (!data.tags.containsKey(name)) continue;
                Deque<ScadaSample> samples = new ArrayDeque<>();
                ListTag points = series.getList("Points", Tag.TAG_COMPOUND);
                int start = Math.max(0, points.size() - MAX_HISTORY_PER_TAG);
                for (int point = start; point < points.size() && data.historySamples < MAX_HISTORY_SAMPLES; point++) {
                    samples.addLast(loadSample(points.getCompound(point)));
                    data.historySamples++;
                }
                if (!samples.isEmpty()) data.history.put(name, samples);
            } catch (RuntimeException ignored) {}
        }
        ListTag savedAlarms = root.getList("Alarms", Tag.TAG_COMPOUND);
        for (int index = 0; index < savedAlarms.size() && data.alarms.size() < MAX_ALARMS; index++) {
            try {
                AlarmRuntime alarm = loadAlarm(savedAlarms.getCompound(index));
                if (data.tags.containsKey(alarm.rule.tagName())) data.alarms.putIfAbsent(alarm.rule.name(), alarm);
            } catch (RuntimeException ignored) {}
        }
        ListTag savedDashboards = root.getList("Dashboards", Tag.TAG_COMPOUND);
        for (int index = 0; index < savedDashboards.size() && data.dashboards.size() < MAX_DASHBOARDS; index++) {
            try {
                ScadaDashboard dashboard = loadDashboard(savedDashboards.getCompound(index));
                data.dashboards.putIfAbsent(dashboard.monitorId(), dashboard);
            } catch (RuntimeException ignored) {}
        }
        ListTag savedHmiDashboards = root.getList("HmiDashboards", Tag.TAG_COMPOUND);
        for (int index = 0; index < savedHmiDashboards.size()
                && data.hmiDashboards.size() < MAX_HMI_DASHBOARDS; index++) {
            try {
                ScadaHmiDashboard dashboard = loadHmiDashboard(savedHmiDashboards.getCompound(index));
                boolean monitorFree = data.hmiDashboards.values().stream()
                        .noneMatch(existing -> existing.monitorId().equals(dashboard.monitorId()));
                if (monitorFree) data.hmiDashboards.putIfAbsent(dashboard.name(), dashboard);
            } catch (RuntimeException ignored) {}
        }
        ListTag savedRoles = root.getList("Roles", Tag.TAG_COMPOUND);
        for (int index = 0; index < savedRoles.size() && data.roles.size() < MAX_ROLE_ASSIGNMENTS; index++) {
            try {
                RoleAssignment assignment = loadRole(savedRoles.getCompound(index));
                data.roles.putIfAbsent(assignment.principal().authorityKey(), assignment);
            } catch (RuntimeException ignored) {}
        }
        ListTag savedAudit = root.getList("Audit", Tag.TAG_COMPOUND);
        for (int index = Math.max(0, savedAudit.size() - MAX_AUDIT_ENTRIES); index < savedAudit.size(); index++) {
            try { data.audit.addLast(loadAudit(savedAudit.getCompound(index))); }
            catch (RuntimeException ignored) {}
        }
        return data;
    }

    private static CompoundTag saveTag(ScadaTag value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", value.name());
        tag.putUUID("Device", value.deviceId());
        tag.putString("ReadMethod", value.readMethod());
        tag.putString("Path", value.valuePath());
        tag.putString("Unit", value.unit());
        tag.putInt("Interval", value.sampleIntervalTicks());
        tag.putInt("Stale", value.staleAfterTicks());
        tag.putString("WriteMethod", value.writeMethod());
        ListTag arguments = new ListTag();
        value.arguments().forEach(argument -> ScadaScalar.from(argument).ifPresent(scalar -> arguments.add(saveScalar(scalar))));
        tag.put("Arguments", arguments);
        return tag;
    }

    private static ScadaTag loadTag(CompoundTag tag) {
        if (!tag.hasUUID("Device")) throw new IllegalArgumentException("missing device");
        List<DeviceValue> arguments = new ArrayList<>();
        ListTag saved = tag.getList("Arguments", Tag.TAG_COMPOUND);
        for (int index = 0; index < saved.size() && arguments.size() < ScadaTag.MAX_ARGUMENTS; index++) {
            arguments.add(loadScalar(saved.getCompound(index)).toDeviceValue());
        }
        return new ScadaTag(tag.getString("Name"), tag.getUUID("Device"), tag.getString("ReadMethod"),
                arguments, tag.getString("Path"), tag.getString("Unit"), tag.getInt("Interval"),
                tag.getInt("Stale"), tag.getString("WriteMethod"));
    }

    private static CompoundTag saveScalar(ScadaScalar value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Type", value.type().name());
        switch (value.type()) {
            case NUMBER -> tag.putDouble("Number", value.numberValue());
            case BOOLEAN -> tag.putBoolean("Boolean", value.booleanValue());
            case STRING -> tag.putString("Text", value.textValue());
        }
        return tag;
    }

    private static ScadaScalar loadScalar(CompoundTag tag) {
        return switch (ScadaScalar.Type.valueOf(tag.getString("Type"))) {
            case NUMBER -> ScadaScalar.number(tag.getDouble("Number"));
            case BOOLEAN -> ScadaScalar.bool(tag.getBoolean("Boolean"));
            case STRING -> ScadaScalar.text(tag.getString("Text"));
        };
    }

    private static CompoundTag saveSnapshot(ScadaSnapshot value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Quality", value.quality().name());
        tag.putLong("SampledAt", value.sampledAt());
        tag.putLong("LastGoodAt", value.lastGoodAt());
        tag.putString("Detail", value.detail());
        if (value.value() != null) tag.put("Value", saveScalar(value.value()));
        return tag;
    }

    private static ScadaSnapshot loadSnapshot(CompoundTag tag) {
        ScadaScalar value = tag.contains("Value", Tag.TAG_COMPOUND) ? loadScalar(tag.getCompound("Value")) : null;
        return new ScadaSnapshot(value, ScadaQuality.valueOf(tag.getString("Quality")),
                tag.getLong("SampledAt"), tag.getLong("LastGoodAt"), tag.getString("Detail"));
    }

    private static CompoundTag saveSample(ScadaSample value) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Time", value.gameTime());
        tag.putString("Quality", value.quality().name());
        if (value.value() != null) tag.put("Value", saveScalar(value.value()));
        return tag;
    }

    private static ScadaSample loadSample(CompoundTag tag) {
        ScadaScalar value = tag.contains("Value", Tag.TAG_COMPOUND) ? loadScalar(tag.getCompound("Value")) : null;
        return new ScadaSample(tag.getLong("Time"), value, ScadaQuality.valueOf(tag.getString("Quality")));
    }

    private static CompoundTag saveAlarm(AlarmRuntime runtime) {
        ScadaAlarmRule value = runtime.rule;
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", value.name());
        tag.putString("Tag", value.tagName());
        tag.putString("Operator", value.operator().name());
        tag.putString("Severity", value.severity().name());
        tag.putDouble("Deadband", value.deadband());
        tag.putString("Message", value.message());
        if (value.threshold() != null) tag.put("Threshold", saveScalar(value.threshold()));
        tag.putBoolean("Active", runtime.active);
        tag.putBoolean("Acknowledged", runtime.acknowledged);
        tag.putLong("ActiveSince", runtime.activeSince);
        tag.putLong("ChangedAt", runtime.stateChangedAt);
        tag.putLong("ShelvedUntil", runtime.shelvedUntil);
        tag.putString("AcknowledgedBy", runtime.acknowledgedBy);
        return tag;
    }

    private static AlarmRuntime loadAlarm(CompoundTag tag) {
        ScadaScalar threshold = tag.contains("Threshold", Tag.TAG_COMPOUND)
                ? loadScalar(tag.getCompound("Threshold")) : null;
        ScadaAlarmRule rule = new ScadaAlarmRule(tag.getString("Name"), tag.getString("Tag"),
                ScadaAlarmRule.Operator.valueOf(tag.getString("Operator")), threshold,
                ScadaAlarmRule.Severity.valueOf(tag.getString("Severity")),
                tag.getDouble("Deadband"), tag.getString("Message"));
        AlarmRuntime runtime = new AlarmRuntime(rule);
        runtime.active = tag.getBoolean("Active");
        runtime.acknowledged = tag.getBoolean("Acknowledged");
        runtime.activeSince = tag.getLong("ActiveSince");
        runtime.stateChangedAt = tag.getLong("ChangedAt");
        runtime.shelvedUntil = tag.getLong("ShelvedUntil");
        runtime.acknowledgedBy = bounded(tag.getString("AcknowledgedBy"), 160);
        return runtime;
    }

    private static CompoundTag saveDashboard(ScadaDashboard value) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Monitor", value.monitorId());
        tag.putString("Prefix", value.tagPrefix());
        tag.putString("Title", value.title());
        tag.putInt("Refresh", value.refreshTicks());
        return tag;
    }

    private static ScadaDashboard loadDashboard(CompoundTag tag) {
        if (!tag.hasUUID("Monitor")) throw new IllegalArgumentException("missing monitor");
        return new ScadaDashboard(tag.getUUID("Monitor"), tag.getString("Prefix"),
                tag.getString("Title"), tag.getInt("Refresh"));
    }

    private static CompoundTag saveHmiDashboard(ScadaHmiDashboard value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", value.name());
        tag.putUUID("Monitor", value.monitorId());
        tag.putString("Title", value.title());
        tag.putInt("Refresh", value.refreshTicks());
        tag.putString("ActivePage", value.activePage());
        ListTag pages = new ListTag();
        value.pages().forEach(page -> pages.add(saveHmiPage(page)));
        tag.put("Pages", pages);
        return tag;
    }

    private static ScadaHmiDashboard loadHmiDashboard(CompoundTag tag) {
        if (!tag.hasUUID("Monitor")) throw new IllegalArgumentException("missing HMI monitor");
        List<ScadaHmiPage> pages = new ArrayList<>();
        ListTag savedPages = tag.getList("Pages", Tag.TAG_COMPOUND);
        for (int index = 0; index < savedPages.size()
                && pages.size() < ScadaHmiPage.MAX_PAGES_PER_DASHBOARD; index++) {
            try { pages.add(loadHmiPage(savedPages.getCompound(index))); }
            catch (RuntimeException ignored) {}
        }
        return new ScadaHmiDashboard(tag.getString("Name"), tag.getUUID("Monitor"), tag.getString("Title"),
                tag.getInt("Refresh"), tag.getString("ActivePage"), pages);
    }

    private static CompoundTag saveHmiPage(ScadaHmiPage value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", value.name());
        tag.putString("Title", value.title());
        ListTag widgets = new ListTag();
        value.widgets().forEach(widget -> widgets.add(saveHmiWidget(widget)));
        tag.put("Widgets", widgets);
        return tag;
    }

    private static ScadaHmiPage loadHmiPage(CompoundTag tag) {
        List<ScadaHmiWidget> widgets = new ArrayList<>();
        ListTag savedWidgets = tag.getList("Widgets", Tag.TAG_COMPOUND);
        for (int index = 0; index < savedWidgets.size()
                && widgets.size() < ScadaHmiPage.MAX_WIDGETS_PER_PAGE; index++) {
            try { widgets.add(loadHmiWidget(savedWidgets.getCompound(index))); }
            catch (RuntimeException ignored) {}
        }
        return new ScadaHmiPage(tag.getString("Name"), tag.getString("Title"), widgets);
    }

    private static CompoundTag saveHmiWidget(ScadaHmiWidget value) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", value.id());
        tag.putString("Type", value.type().name());
        tag.putInt("X", value.x());
        tag.putInt("Y", value.y());
        tag.putInt("Width", value.width());
        tag.putInt("Height", value.height());
        tag.putString("Source", value.source());
        tag.putString("Label", value.label());
        tag.putDouble("Minimum", value.minimum());
        tag.putDouble("Maximum", value.maximum());
        if (value.actionValue() != null) tag.put("ActionValue", saveScalar(value.actionValue()));
        return tag;
    }

    private static ScadaHmiWidget loadHmiWidget(CompoundTag tag) {
        ScadaScalar actionValue = tag.contains("ActionValue", Tag.TAG_COMPOUND)
                ? loadScalar(tag.getCompound("ActionValue")) : null;
        return new ScadaHmiWidget(tag.getString("Id"), ScadaHmiWidget.Type.valueOf(tag.getString("Type")),
                tag.getInt("X"), tag.getInt("Y"), tag.getInt("Width"), tag.getInt("Height"),
                tag.getString("Source"), tag.getString("Label"), tag.getDouble("Minimum"),
                tag.getDouble("Maximum"), actionValue);
    }

    private static CompoundTag saveRole(RoleAssignment value) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", value.principal().id());
        tag.putString("Kind", value.principal().kind().name());
        tag.putString("Name", value.principal().name());
        tag.putString("Role", value.role().name());
        return tag;
    }

    private static RoleAssignment loadRole(CompoundTag tag) {
        if (!tag.hasUUID("Id")) throw new IllegalArgumentException("missing principal");
        PrincipalIdentity principal = new PrincipalIdentity(PrincipalIdentity.Kind.valueOf(tag.getString("Kind")),
                tag.getUUID("Id"), tag.getString("Name"));
        return new RoleAssignment(principal, ScadaRole.valueOf(tag.getString("Role")));
    }

    private static CompoundTag saveAudit(AuditEntry value) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Time", value.gameTime());
        tag.putString("Principal", value.principal());
        tag.putString("Action", value.action());
        tag.putString("Detail", value.detail());
        return tag;
    }

    private static AuditEntry loadAudit(CompoundTag tag) {
        return new AuditEntry(tag.getLong("Time"), tag.getString("Principal"),
                tag.getString("Action"), tag.getString("Detail"));
    }

    private static final class AlarmRuntime {
        private final ScadaAlarmRule rule;
        private boolean active;
        private boolean acknowledged;
        private long activeSince = -1;
        private long stateChangedAt;
        private long shelvedUntil;
        private String acknowledgedBy = "";

        private AlarmRuntime(ScadaAlarmRule rule) { this.rule = Objects.requireNonNull(rule, "rule"); }

        private AlarmView view(long gameTime) {
            AlarmState state = shelvedUntil > gameTime ? AlarmState.SHELVED
                    : active ? acknowledged ? AlarmState.ACKNOWLEDGED : AlarmState.ACTIVE : AlarmState.NORMAL;
            return new AlarmView(rule, state, activeSince, stateChangedAt, shelvedUntil, acknowledgedBy);
        }
    }
}
