package no.beint.vev.pg;

import no.beint.vev.pg.spi.PgEntityPlan;

import java.util.Objects;

/**
 * Generated index token for a non-null scalar component.
 *
 * @param <M> closed-model marker type
 * @param <E> entity snapshot type
 * @param <K> primary-key type
 * @param <V> indexed value type
 */
public final class PgRequiredIndex<M, E, K, V> implements PgIndex<M, E, K, V> {
    private final PgEntityPlan<M, E, K, ?> entityPlan;
    private final String indexName;
    private final int columnIndex;
    private final Class<V> valueType;

    /**
     * Creates metadata emitted by Vev's annotation processor.
     *
     * <p>Application-created instances are outside the generated-plan profile and are rejected by identity before
     * SQL preparation.</p>
     *
     * @param entityPlan generated owning plan
     * @param indexName exact migration index name
     * @param columnIndex zero-based mapped column position
     * @param valueType exact boxed query-value class
     */
    public PgRequiredIndex(
            PgEntityPlan<M, E, K, ?> entityPlan,
            String indexName,
            int columnIndex,
            Class<V> valueType) {
        this.entityPlan = Objects.requireNonNull(entityPlan, "entityPlan");
        this.indexName = Objects.requireNonNull(indexName, "indexName");
        this.columnIndex = columnIndex;
        this.valueType = Objects.requireNonNull(valueType, "valueType");
    }

    @Override
    public PgEntityPlan<M, E, K, ?> entityPlan() {
        return entityPlan;
    }

    @Override
    public String indexName() {
        return indexName;
    }

    @Override
    public int columnIndex() {
        return columnIndex;
    }

    @Override
    public Class<V> valueType() {
        return valueType;
    }
}
