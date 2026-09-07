package no.beint.vev.pg;

/**
 * Generated identity for an equality-query index whose pages use ascending identifier order.
 *
 * <p>The runtime accepts only the exact token captured in its closed model. Constructing another token cannot add
 * executable SQL or bypass live-catalog index attestation.</p>
 *
 * @param <M> closed-model marker type
 * @param <E> entity snapshot type
 * @param <K> primary-key type
 * @param <V> indexed value type
 */
public sealed interface PgIndex<M, E, K, V> extends PgQueryIndex<M, E, K, V> permits PgRequiredIndex, PgNullableIndex {
}
