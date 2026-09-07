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
 * @param <S> ordering value type
 */
public final class PgRequiredOrderedIndex<M, E, K, V, S> implements PgOrderedIndex<M, E, K, V, S> {
    private final PgEntityPlan<M, E, K, ?> entityPlan;
    private final String indexName;
    private final int columnIndex;
    private final Class<V> valueType;
    private final int orderColumnIndex;
    private final Class<S> orderType;
    private final no.beint.vev.VevIndex.Direction direction;

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
     * @param orderColumnIndex zero-based ordering column position
     * @param orderType exact boxed ordering value class
     * @param direction fixed direction for ordering value and identifier
     */
    public PgRequiredOrderedIndex(
            PgEntityPlan<M, E, K, ?> entityPlan,
            String indexName,
            int columnIndex,
            Class<V> valueType,
            int orderColumnIndex,
            Class<S> orderType,
            no.beint.vev.VevIndex.Direction direction) {
        this.entityPlan = Objects.requireNonNull(entityPlan, "entityPlan");
        this.indexName = Objects.requireNonNull(indexName, "indexName");
        this.columnIndex = columnIndex;
        this.valueType = Objects.requireNonNull(valueType, "valueType");
        this.orderColumnIndex = orderColumnIndex;
        this.orderType = Objects.requireNonNull(orderType, "orderType");
        this.direction = Objects.requireNonNull(direction, "direction");
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
    @Override
    public int orderColumnIndex() {
        return orderColumnIndex;
    }

    @Override
    public Class<S> orderType() {
        return orderType;
    }

    @Override
    public no.beint.vev.VevIndex.Direction direction() {
        return direction;
    }
}
