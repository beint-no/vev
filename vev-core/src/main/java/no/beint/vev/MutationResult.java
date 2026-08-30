package no.beint.vev;

import java.util.Objects;

/**
 * Exhaustive optimistic result of an explicit update or upsert.
 *
 * @param <M> closed-model marker type
 * @param <E> entity snapshot type
 * @param <K> primary-key type
 * @param <V> version-token type
 */
public sealed interface MutationResult<M, E, K, V>
        permits MutationResult.Applied, MutationResult.Conflict, MutationResult.Missing {
    /**
     * Returns the entity key targeted by the mutation.
     *
     * @return type-bound entity key
     */
    EntityKey<M, E, K> key();

    /**
     * Returns the version required by the caller.
     *
     * @return expected version token
     */
    V expectedVersion();

    /**
     * Contains an applied mutation and its detached replacement snapshot.
     *
     * @param key type-bound entity key
     * @param expectedVersion version required by the caller
     * @param version stored version after the mutation
     * @param effect database effect that was applied
     * @param entity detached replacement snapshot
     * @param <M> closed-model marker type
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     * @param <V> version-token type
     */
    record Applied<M, E, K, V>(
            EntityKey<M, E, K> key,
            V expectedVersion,
            V version,
            MutationEffect effect,
            E entity) implements MutationResult<M, E, K, V> {
        /**
         * Validates an applied mutation outcome.
         *
         * @param key type-bound entity key
         * @param expectedVersion version required by the caller
         * @param version stored version after the mutation
         * @param effect database effect that was applied
         * @param entity detached replacement snapshot
         */
        public Applied {
            key = Objects.requireNonNull(key, "key");
            expectedVersion = Objects.requireNonNull(expectedVersion, "expectedVersion");
            version = Objects.requireNonNull(version, "version");
            effect = Objects.requireNonNull(effect, "effect");
            entity = Objects.requireNonNull(entity, "entity");
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
            implements MutationResult<M, E, K, V> {
        /**
         * Validates an optimistic-mutation conflict.
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
     * @param expectedVersion version required by the caller
     * @param <M> closed-model marker type
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     * @param <V> version-token type
     */
    record Missing<M, E, K, V>(EntityKey<M, E, K> key, V expectedVersion)
            implements MutationResult<M, E, K, V> {
        /**
         * Validates a missing-row mutation outcome.
         *
         * @param key type-bound entity key
         * @param expectedVersion version required by the caller
         */
        public Missing {
            key = Objects.requireNonNull(key, "key");
            expectedVersion = Objects.requireNonNull(expectedVersion, "expectedVersion");
        }
    }
}
