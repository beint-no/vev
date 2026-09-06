package no.beint.vev.pg;

import no.beint.vev.BoundedQuery;
import no.beint.vev.ModelIdentity;
import no.beint.vev.QueryLimit;
import java.util.Objects;

record PgOrderedIndexScan<M, E, K, V, S>(PgOrderedIndex<M, E, K, V, S> index,
        PgIndexScan.Predicate predicate, V value, PgOrderedCursor<M, E, K, V, S> cursor, QueryLimit limit)
        implements BoundedQuery<M, E> {
    PgOrderedIndexScan {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(limit, "limit");
        if ((predicate == PgIndexScan.Predicate.EQUAL) != (value != null)) {
            throw new IllegalArgumentException("Equality queries require one non-null generated-codec value");
        }
        if (cursor != null && cursor.index() != index) {
            throw new IllegalArgumentException("Continuation cursor is not from the exact generated ordered index");
        }
    }

    @Override
    public ModelIdentity modelIdentity() {
        return index.entityPlan().modelIdentity();
    }

    @Override
    public Class<E> resultType() {
        return index.entityPlan().javaType();
    }
}
