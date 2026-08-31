package no.beint.vev.pg;

import no.beint.vev.BoundedQuery;
import no.beint.vev.ModelIdentity;
import no.beint.vev.QueryLimit;

import java.util.Objects;

final class PgIndexScan<M, E, K, V> implements BoundedQuery<M, E> {
    enum Predicate {
        EQUAL,
        IS_NULL
    }

    private final PgIndex<M, E, K, V> index;
    private final Predicate predicate;
    private final V value;
    private final K afterExclusive;
    private final QueryLimit limit;

    PgIndexScan(
            PgIndex<M, E, K, V> index,
            Predicate predicate,
            V value,
            K afterExclusive,
            QueryLimit limit) {
        this.index = Objects.requireNonNull(index, "index");
        this.predicate = Objects.requireNonNull(predicate, "predicate");
        this.value = value;
        this.afterExclusive = afterExclusive;
        this.limit = Objects.requireNonNull(limit, "limit");
        if ((predicate == Predicate.EQUAL) != (value != null)) {
            throw new IllegalArgumentException("Equality queries require one non-null generated-codec value");
        }
    }

    PgIndex<M, E, K, V> index() {
        return index;
    }

    Predicate predicate() {
        return predicate;
    }

    V value() {
        return Objects.requireNonNull(value, "This index query has no equality value");
    }

    boolean hasAfterExclusive() {
        return afterExclusive != null;
    }

    K afterExclusive() {
        return Objects.requireNonNull(afterExclusive, "This index query has no exclusive key");
    }

    @Override
    public ModelIdentity modelIdentity() {
        return index.entityPlan().modelIdentity();
    }

    @Override
    public Class<E> resultType() {
        return index.entityPlan().javaType();
    }

    @Override
    public QueryLimit limit() {
        return limit;
    }
}
