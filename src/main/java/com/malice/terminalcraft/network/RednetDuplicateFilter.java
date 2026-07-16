package com.malice.terminalcraft.network;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded recipient-local duplicate filter for at-least-once RedNet delivery.
 * Entries expire on logical game time and insertion order provides deterministic eviction.
 */
public final class RednetDuplicateFilter {
    public static final int MAX_RECIPIENTS = 256;
    public static final int MAX_IDS_PER_RECIPIENT = 256;
    public static final long MIN_RETENTION_TICKS = 1;
    public static final long MAX_RETENTION_TICKS = 72_000;

    private final Map<UUID, LinkedHashMap<UUID, Long>> seen = new LinkedHashMap<>();

    /** Returns true exactly once for a message ID within the recipient's retention window. */
    public synchronized boolean admit(UUID recipient, UUID messageId, long now, long retentionTicks) {
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(messageId, "messageId");
        if (now < 0) throw new IllegalArgumentException("logical time must not be negative");
        if (retentionTicks < MIN_RETENTION_TICKS || retentionTicks > MAX_RETENTION_TICKS) {
            throw new IllegalArgumentException("retention outside supported range");
        }
        purgeExpired(now);
        LinkedHashMap<UUID, Long> recipientIds = seen.get(recipient);
        if (recipientIds == null) {
            if (seen.size() >= MAX_RECIPIENTS) {
                Iterator<UUID> recipients = seen.keySet().iterator();
                if (recipients.hasNext()) {
                    recipients.next();
                    recipients.remove();
                }
            }
            recipientIds = new LinkedHashMap<>();
            seen.put(recipient, recipientIds);
        }
        Long expiresAt = recipientIds.get(messageId);
        if (expiresAt != null && expiresAt > now) return false;
        recipientIds.remove(messageId);
        while (recipientIds.size() >= MAX_IDS_PER_RECIPIENT) {
            Iterator<UUID> ids = recipientIds.keySet().iterator();
            if (!ids.hasNext()) break;
            ids.next();
            ids.remove();
        }
        recipientIds.put(messageId, saturatedAdd(now, retentionTicks));
        return true;
    }

    public synchronized int recipientCount() {
        return seen.size();
    }

    public synchronized int retainedCount(UUID recipient, long now) {
        if (now < 0) throw new IllegalArgumentException("logical time must not be negative");
        purgeExpired(now);
        Map<UUID, Long> ids = seen.get(recipient);
        return ids == null ? 0 : ids.size();
    }

    public synchronized void removeRecipient(UUID recipient) {
        if (recipient != null) seen.remove(recipient);
    }

    public synchronized void clear() {
        seen.clear();
    }

    private void purgeExpired(long now) {
        Iterator<Map.Entry<UUID, LinkedHashMap<UUID, Long>>> recipients = seen.entrySet().iterator();
        while (recipients.hasNext()) {
            LinkedHashMap<UUID, Long> ids = recipients.next().getValue();
            ids.entrySet().removeIf(entry -> entry.getValue() <= now);
            if (ids.isEmpty()) recipients.remove();
        }
    }

    private static long saturatedAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }
}
