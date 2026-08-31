package no.beint.vev;

/**
 * Generated mapping capability for an entity with mandatory optimistic versioning.
 *
 * @param <M> closed-model marker type
 * @param <E> entity snapshot type
 * @param <K> primary-key type
 * @param <V> version-token type
 */
public interface VersionedEntityType<M, E, K, V> extends EntityType<M, E, K> {
    /**
     * Returns the version-token type used by this mapping.
     *
     * @return exact non-primitive version-token class
     */
    Class<V> versionType();

    /**
     * Creates a version-qualified mutation target.
     *
     * @param key primary-key value
     * @param expectedVersion version that must still be stored
     * @return validated version-qualified key
     */
    default VersionedKey<M, E, K, V> versionedKey(K key, V expectedVersion) {
        return new VersionedKey<>(key(key), expectedVersion);
    }
}
