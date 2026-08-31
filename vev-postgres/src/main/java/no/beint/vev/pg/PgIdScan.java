package no.beint.vev.pg;

import no.beint.vev.BoundedQuery;
import no.beint.vev.ModelIdentity;
import no.beint.vev.QueryLimit;
import no.beint.vev.pg.spi.PgEntityPlan;

import java.util.Objects;

final class PgIdScan<M, E, K> implements BoundedQuery<M, E> {
    private final PgEntityPlan<M, E, K, ?> plan;
    private final K afterExclusive;
    private final QueryLimit limit;

    PgIdScan(PgEntityPlan<M, E, K, ?> plan, QueryLimit limit) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.afterExclusive = null;
        this.limit = Objects.requireNonNull(limit, "limit");
    }

    PgIdScan(PgEntityPlan<M, E, K, ?> plan, K afterExclusive, QueryLimit limit) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.afterExclusive = Objects.requireNonNull(afterExclusive, "afterExclusive");
        this.limit = Objects.requireNonNull(limit, "limit");
    }

    PgEntityPlan<M, E, K, ?> plan() {
        return plan;
    }

    boolean hasAfterExclusive() {
        return afterExclusive != null;
    }

    K afterExclusive() {
        return Objects.requireNonNull(afterExclusive, "This ID scan has no exclusive cursor");
    }

    @Override
    public ModelIdentity modelIdentity() {
        return plan.modelIdentity();
    }

    @Override
    public Class<E> resultType() {
        return plan.javaType();
    }

    @Override
    public QueryLimit limit() {
        return limit;
    }
}
