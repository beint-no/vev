package no.beint.vev;

import java.util.Objects;

/**
 * An entity key paired with the version required for an explicit delete.
 *
 * @param key type-bound entity key
 * @param expectedVersion version that must still be stored
 * @param <M> closed-model marker type
 * @param <E> entity snapshot type
 * @param <K> primary-key type
 * @param <V> version-token type
 */
public record VersionedKey<M, E, K, V>(EntityKey<M, E, K> key, V expectedVersion) {
    /**
     * Validates and creates a version-qualified key.
     *
     * @param key type-bound key for a versioned entity
     * @param expectedVersion version that must still be stored
     */
    public VersionedKey {
        key = Objects.requireNonNull(key, "key");
        expectedVersion = Objects.requireNonNull(expectedVersion, "expectedVersion");
        EntityType<M, E, K> entityType = key.entityType();
        if (!(entityType instanceof VersionedEntityType<?, ?, ?, ?> versionedEntityType)) {
            throw new IllegalArgumentException(entityType.logicalName() + " is not versioned");
        }
        if (!versionedEntityType.versionType().isInstance(expectedVersion)) {
            throw new IllegalArgumentException(
                    "Version for " + entityType.logicalName() + " must be a "
                            + versionedEntityType.versionType().getName());
        }
    }
}
