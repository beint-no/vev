package no.beint.vev.pg;

import no.beint.vev.EntityKey;

/**
 * Generated equality-query index ordered by a non-null scalar value and the identifier as a tie-breaker.
 *
 * @param <M> closed-model marker type
 * @param <E> entity snapshot type
 * @param <K> primary-key type
 * @param <V> equality value type
 * @param <S> ordering value type
 */
public sealed interface PgOrderedIndex<M, E, K, V, S> extends PgQueryIndex<M, E, K, V>
        permits PgRequiredOrderedIndex, PgNullableOrderedIndex {
    /**
     * Returns the mapped position of the ordering value.
     * @return zero-based column position distinct from filter and structural columns
     */
    int orderColumnIndex();

    /**
     * Returns the exact boxed ordering value class.
     * @return generated ordering codec's Java type
     */
    Class<S> orderType();

    /**
     * Returns the fixed direction of both ordering value and identifier.
     * @return generated query direction
     */
    no.beint.vev.VevIndex.Direction direction();

    /**
     * Creates an exclusive cursor bound to this index and entity key.
     * @param orderValue last observed ordering value
     * @param key last observed entity key
     * @return immutable cursor relative to the active lexical scope
     */
    default PgOrderedCursor<M, E, K, V, S> cursor(S orderValue, EntityKey<M, E, K> key) {
        return new PgOrderedCursor<>(this, orderValue, key);
    }
}
