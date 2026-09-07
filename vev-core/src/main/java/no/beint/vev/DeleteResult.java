package no.beint.vev;

import java.util.Objects;

/**
 * Exhaustive outcome of an explicit version-checked physical deletion.
 *
 * @param <M> closed-model marker type
 * @param <E> entity snapshot type
 * @param <K> primary-key type
 * @param <V> version-token type
 */
public sealed interface DeleteResult<M, E, K, V>
        permits DeleteResult.Deleted, DeleteResult.Conflict, DeleteResult.Missing {
    /**
     * Returns the exact requested deletion.
     *
     * @return type-bound target and expected version
     */
    DeleteTarget<M, E, K, V> target();

    /**
     * Confirms deletion of the requested version; the identifier cannot be recreated through Vev.
     *
     * @param target requested deletion
     * @param <M> closed-model marker type
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     * @param <V> version-token type
     */
    record Deleted<M, E, K, V>(DeleteTarget<M, E, K, V> target) implements DeleteResult<M, E, K, V> {
        /**
         * Validates a successful deletion outcome.
         * @param target requested deletion
         */
        public Deleted {
            target = Objects.requireNonNull(target, "target");
        }
    }

    /**
     * Reports a tenant-visible row with a different version, without deleting it.
     *
     * @param target requested deletion
     * @param <M> closed-model marker type
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     * @param <V> version-token type
     */
    record Conflict<M, E, K, V>(DeleteTarget<M, E, K, V> target) implements DeleteResult<M, E, K, V> {
        /**
         * Validates an optimistic conflict.
         * @param target requested deletion
         */
        public Conflict {
            target = Objects.requireNonNull(target, "target");
        }
    }

    /**
     * Reports that no tenant-visible row exists for the requested key.
     *
     * @param target requested deletion
     * @param <M> closed-model marker type
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     * @param <V> version-token type
     */
    record Missing<M, E, K, V>(DeleteTarget<M, E, K, V> target) implements DeleteResult<M, E, K, V> {
        /**
         * Validates a missing-row outcome.
         * @param target requested deletion
         */
        public Missing {
            target = Objects.requireNonNull(target, "target");
        }
    }
}
