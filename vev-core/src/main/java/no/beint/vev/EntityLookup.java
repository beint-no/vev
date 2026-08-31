package no.beint.vev;

import java.util.Objects;

/**
 * One ordered result from a bulk primary-key lookup.
 *
 * @param <M> closed-model marker type
 * @param <E> entity snapshot type
 * @param <K> primary-key type
 */
public sealed interface EntityLookup<M, E, K>
        permits EntityLookup.Found, EntityLookup.Missing {
    /**
     * Returns the requested entity key.
     *
     * @return type-bound entity key
     */
    EntityKey<M, E, K> key();

    /**
     * Contains a found detached entity snapshot.
     *
     * @param key requested entity key
     * @param entity detached entity snapshot
     * @param <M> closed-model marker type
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     */
    record Found<M, E, K>(EntityKey<M, E, K> key, E entity) implements EntityLookup<M, E, K> {
        /**
         * Validates a found lookup result.
         *
         * @param key requested entity key
         * @param entity detached entity snapshot
         */
        public Found {
            key = Objects.requireNonNull(key, "key");
            entity = Objects.requireNonNull(entity, "entity");
        }
    }

    /**
     * Contains a key for which no tenant-visible row exists.
     *
     * @param key requested entity key
     * @param <M> closed-model marker type
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     */
    record Missing<M, E, K>(EntityKey<M, E, K> key) implements EntityLookup<M, E, K> {
        /**
         * Validates a missing lookup result.
         *
         * @param key requested entity key
         */
        public Missing {
            key = Objects.requireNonNull(key, "key");
        }
    }
}
