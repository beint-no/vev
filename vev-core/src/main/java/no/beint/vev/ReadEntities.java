package no.beint.vev;

import java.util.Optional;

/**
 * Explicit detached reads available inside a lexical transaction.
 *
 * @param <M> closed-model marker type
 */
public interface ReadEntities<M> {
    /**
     * Finds one entity snapshot by generated type-bound key.
     *
     * @param key generated type-bound key
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     * @return detached snapshot, or empty when no tenant-visible row exists
     */
    <E, K> Optional<E> find(EntityKey<M, E, K> key);

    /**
     * Finds a bounded batch in input order without issuing one statement per key.
     *
     * @param type generated entity mapping
     * @param keys bounded ordered key values
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     * @return exactly one found-or-missing result per input key
     */
    <E, K> Batch<EntityLookup<M, E, K>> findMultiple(EntityType<M, E, K> type, Batch<K> keys);

    /**
     * Executes a provider-created bounded query.
     *
     * @param query opaque bounded query
     * @param <R> result snapshot type
     * @return bounded materialized detached rows
     */
    <R> Rows<R> many(BoundedQuery<M, R> query);
}
