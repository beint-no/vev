package no.beint.vev;

import java.util.Objects;

/**
 * Exhaustive optimistic result of an explicit delete.
 *
 * @param <M> closed-model marker type
 * @param <E> entity snapshot type
 * @param <K> primary-key type
 * @param <V> version-token type
 */
public sealed interface DeleteResult<M, E, K, V>
        permits DeleteResult.Deleted, DeleteResult.Conflict, DeleteResult.Missing {
    /**
     * Returns the entity key targeted by the delete.
     *
     * @return type-bound entity key
     */
    EntityKey<M, E, K> key();

    /**
     * Reports that the row with the requested version was deleted.
     *
     * @param key type-bound entity key
     * @param version deleted version token
     * @param <M> closed-model marker type
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     * @param <V> version-token type
     */
    record Deleted<M, E, K, V>(EntityKey<M, E, K> key, V version) implements DeleteResult<M, E, K, V> {
        /**
         * Validates a successful delete outcome.
         *
         * @param key type-bound entity key
         * @param version deleted version token
         */
        public Deleted {
            key = Objects.requireNonNull(key, "key");
            version = Objects.requireNonNull(version, "version");
        }
    }

    /**
     * Reports that a row exists with a different stored version.
     *
     * @param key type-bound entity key
     * @param expectedVersion version required by the caller
     * @param <M> closed-model marker type
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     * @param <V> version-token type
     */
    record Conflict<M, E, K, V>(EntityKey<M, E, K> key, V expectedVersion)
            implements DeleteResult<M, E, K, V> {
        /**
         * Validates an optimistic-delete conflict.
         *
         * @param key type-bound entity key
         * @param expectedVersion version required by the caller
         */
        public Conflict {
            key = Objects.requireNonNull(key, "key");
            expectedVersion = Objects.requireNonNull(expectedVersion, "expectedVersion");
        }
    }

    /**
     * Reports that no tenant-visible row exists for the key.
     *
     * @param key type-bound entity key
     * @param <M> closed-model marker type
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     * @param <V> version-token type
     */
    record Missing<M, E, K, V>(EntityKey<M, E, K> key) implements DeleteResult<M, E, K, V> {
        /**
         * Validates a missing-row delete outcome.
         *
         * @param key type-bound entity key
         */
        public Missing {
            key = Objects.requireNonNull(key, "key");
        }
    }
}
