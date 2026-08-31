package no.beint.vev;

import java.util.Objects;

/**
 * A primary key bound to its generated entity mapping.
 *
 * @param entityType generated entity mapping identity
 * @param value key value
 * @param <M> closed-model marker type
 * @param <E> entity snapshot type
 * @param <K> primary-key type
 */
public record EntityKey<M, E, K>(EntityType<M, E, K> entityType, K value) {
    /**
     * Validates a type-bound entity key.
     *
     * @param entityType generated entity mapping identity
     * @param value key value
     */
    public EntityKey {
        entityType = Objects.requireNonNull(entityType, "entityType");
        value = Objects.requireNonNull(value, "value");
        if (!entityType.keyType().isInstance(value)) {
            throw new IllegalArgumentException(
                    "Key for " + entityType.logicalName() + " must be a " + entityType.keyType().getName());
        }
        if (value instanceof String stringValue) {
            Names.requireStable(stringValue, "String key for " + entityType.logicalName(), 128);
        }
    }
}
