package no.beint.vev.pg;

import no.beint.vev.EntityKey;
import java.util.Objects;

/**
 * Exclusive ordered-index cursor, bound to an exact index token, ordering value, and entity key.
 * The cursor does not carry tenant authority; related pages share a snapshot only inside one transaction.
 *
 * @param index generated ordered index
 * @param orderValue last observed non-null ordering value
 * @param key last observed generated entity key
 * @param <M> closed-model marker type
 * @param <E> entity snapshot type
 * @param <K> primary-key type
 * @param <V> equality value type
 * @param <S> ordering value type
 */
public record PgOrderedCursor<M, E, K, V, S>(PgOrderedIndex<M, E, K, V, S> index, S orderValue, EntityKey<M, E, K> key) {
    /**
     * Validates the cursor's exact value type and generated entity ownership.
     * @param index generated ordered index
     * @param orderValue non-null ordering value
     * @param key generated entity key belonging to the index's plan
     */
    public PgOrderedCursor {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(orderValue, "orderValue");
        Objects.requireNonNull(key, "key");
        if (orderValue.getClass() != index.orderType()
                && !(orderValue instanceof Enum<?> value && value.getDeclaringClass() == index.orderType())) {
            throw new IllegalArgumentException("Cursor ordering value does not match the generated codec type");
        }
        if (key.entityType() != index.entityPlan()) {
            throw new IllegalArgumentException("Cursor key is not from the indexed generated entity plan");
        }
    }
}
