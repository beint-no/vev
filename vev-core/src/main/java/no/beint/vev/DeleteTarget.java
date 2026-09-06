package no.beint.vev;

import java.util.Objects;

/**
 * An explicit deletion bound to one generated mapping, identifier, and expected version.
 * The lexical transaction supplies the tenant; no entity payload needs to be loaded or transmitted.
 *
 * @param entityType generated deletion capability
 * @param value primary-key value
 * @param expectedVersion version required for deletion
 * @param <M> closed-model marker type
 * @param <E> entity snapshot type
 * @param <K> primary-key type
 * @param <V> version-token type
 */
public record DeleteTarget<M, E, K, V>(DeletableEntityType<M, E, K, V> entityType, K value, V expectedVersion) {
    /**
     * Validates the mapping and exact Java types. The runtime also validates captured mapping bounds before SQL.
     *
     * @param entityType generated deletion capability
     * @param value primary-key value
     * @param expectedVersion version required for deletion
     */
    public DeleteTarget {
        entityType = Objects.requireNonNull(entityType, "entityType");
        value = Objects.requireNonNull(value, "value");
        expectedVersion = Objects.requireNonNull(expectedVersion, "expectedVersion");
        if (!entityType.keyType().isInstance(value) || !entityType.versionType().isInstance(expectedVersion)) {
            throw new IllegalArgumentException("Delete target must match its generated key and version types");
        }
    }
}
