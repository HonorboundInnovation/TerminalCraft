package com.malice.terminalcraft.device;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Logical, paginated item-storage query contract independent of physical slots.
 *
 * <p>Counts remain {@code long} internally and are serialized as decimal strings at the Device API
 * boundary so large storage networks cannot silently lose precision. Tags use canonical
 * {@code namespace:path} identifiers and are optional metadata: adapters that cannot expose tags
 * return an empty set and therefore cannot satisfy a tag-filtered query.</p>
 */
public interface GenericItemStorage {
    int MAX_PAGE_SIZE = 64;
    int MAX_FILTER_LENGTH = 128;
    int MAX_CURSOR_LENGTH = 32;

    ItemPage queryItems(ItemQuery query);

    /** Returns the exact logical aggregate for one resource using the same view as queries. */
    default long countItem(String resourceId) {
        ItemPage page = queryItems(new ItemQuery(resourceId, "", "", "", 1));
        return page.entries().isEmpty() ? 0 : page.entries().get(0).count();
    }

    record ItemQuery(String resourceId, String namespace, String text, String cursor, int limit,
                     String tag) {
        /** Backward-compatible query constructor used by adapters predating tag filtering. */
        public ItemQuery(String resourceId, String namespace, String text, String cursor, int limit) {
            this(resourceId, namespace, text, cursor, limit, "");
        }

        public ItemQuery {
            resourceId = normalize(resourceId);
            namespace = normalize(namespace);
            text = normalize(text);
            cursor = normalize(cursor);
            tag = normalizeTag(tag);
            if (resourceId.length() > MAX_FILTER_LENGTH || namespace.length() > MAX_FILTER_LENGTH
                    || text.length() > MAX_FILTER_LENGTH || tag.length() > MAX_FILTER_LENGTH) {
                throw new IllegalArgumentException("storage query filter exceeds limit");
            }
            if (cursor.length() > MAX_CURSOR_LENGTH) {
                throw new IllegalArgumentException("storage query cursor exceeds limit");
            }
            validateCursor(cursor);
            if (limit < 1 || limit > MAX_PAGE_SIZE) {
                throw new IllegalArgumentException("storage query limit must be from 1 to " + MAX_PAGE_SIZE);
            }
            if (!resourceId.isEmpty() && !isIdentifier(resourceId)) {
                throw new IllegalArgumentException("resource filter must be a namespaced identifier");
            }
            if (!namespace.isEmpty() && !namespace.matches("[a-z0-9_.-]+")) {
                throw new IllegalArgumentException("namespace filter must be a lowercase identifier");
            }
            if (!tag.isEmpty() && !isIdentifier(tag)) {
                throw new IllegalArgumentException("tag filter must be a namespaced identifier");
            }
            text = text.toLowerCase(Locale.ROOT);
        }

        public int offset() {
            return cursor.isEmpty() ? 0 : Integer.parseInt(cursor);
        }

        public boolean matches(String candidate) {
            return matches(candidate, Set.of());
        }

        public boolean matches(String candidate, Set<String> candidateTags) {
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(candidateTags, "candidateTags");
            int separator = candidate.indexOf(':');
            String candidateNamespace = separator < 0 ? "" : candidate.substring(0, separator);
            return (resourceId.isEmpty() || resourceId.equals(candidate))
                    && (namespace.isEmpty() || namespace.equals(candidateNamespace))
                    && (text.isEmpty() || candidate.toLowerCase(Locale.ROOT).contains(text))
                    && (tag.isEmpty() || candidateTags.contains(tag));
        }

        private static String normalize(String value) {
            return value == null ? "" : value.trim();
        }

        private static String normalizeTag(String value) {
            String normalized = normalize(value);
            return normalized.startsWith("#") ? normalized.substring(1) : normalized;
        }

        private static void validateCursor(String cursor) {
            if (cursor.isEmpty()) return;
            try {
                if (Integer.parseInt(cursor) < 0) throw new NumberFormatException("negative");
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("storage query cursor is invalid");
            }
        }
    }

    record ItemResource(String resourceId, long count, Set<String> tags) {
        /** Backward-compatible resource constructor for adapters without tag metadata. */
        public ItemResource(String resourceId, long count) {
            this(resourceId, count, Set.of());
        }

        public ItemResource {
            resourceId = Objects.requireNonNull(resourceId, "resourceId");
            if (!isIdentifier(resourceId)) {
                throw new IllegalArgumentException("item resource must be a namespaced identifier");
            }
            if (count < 0) throw new IllegalArgumentException("item count must not be negative");
            TreeSet<String> normalizedTags = new TreeSet<>();
            for (String tag : Objects.requireNonNull(tags, "tags")) {
                String normalized = ItemQuery.normalizeTag(tag);
                if (!isIdentifier(normalized)) {
                    throw new IllegalArgumentException("item tag must be a namespaced identifier");
                }
                normalizedTags.add(normalized);
            }
            tags = Set.copyOf(normalizedTags);
        }
    }

    record ItemPage(List<ItemResource> entries, String nextCursor) {
        public ItemPage {
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
            nextCursor = nextCursor == null ? "" : nextCursor;
            if (entries.size() > MAX_PAGE_SIZE) {
                throw new IllegalArgumentException("storage page exceeds limit");
            }
            if (nextCursor.length() > MAX_CURSOR_LENGTH) {
                throw new IllegalArgumentException("storage cursor exceeds limit");
            }
            ItemQuery.validateCursor(nextCursor);
        }

        public boolean hasMore() {
            return !nextCursor.isEmpty();
        }
    }

    private static boolean isIdentifier(String value) {
        return value.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+");
    }
}
