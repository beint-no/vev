package no.beint.vev.pg;

import no.beint.vev.pg.spi.PgEntityPlan;

/**
 * Generated identity for one required PostgreSQL equality-query index.
 *
 * <p>The runtime accepts only the exact token captured in its closed model. Constructing another token cannot add
 * executable SQL or bypass live-catalog index attestation.</p>
 *
 * @param <M> closed-model marker type
 * @param <E> entity snapshot type
 * @param <K> primary-key type
 * @param <V> indexed value type
 */
public sealed interface PgIndex<M, E, K, V> permits PgRequiredIndex, PgNullableIndex {
    /**
     * Returns the generated entity plan which owns this index.
     *
     * @return exact generated entity plan
     */
    PgEntityPlan<M, E, K, ?> entityPlan();

    /**
     * Returns the exact migration-installed PostgreSQL index name.
     *
     * @return safe unquoted index identifier
     */
    String indexName();

    /**
     * Returns the zero-based indexed component position in the entity plan.
     *
     * @return mapped column position
     */
    int columnIndex();

    /**
     * Returns the exact boxed query-value class.
     *
     * @return exact indexed value class
     */
    Class<V> valueType();
}
