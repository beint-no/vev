package no.beint.vev.pg;

import no.beint.vev.BoundedQuery;
import no.beint.vev.ModelIdentity;
import no.beint.vev.QueryLimit;
import no.beint.vev.pg.spi.PgEntityPlan;

import java.util.Objects;

final class PgIdScan<M, E> implements BoundedQuery<M, E> {
    private final PgEntityPlan<M, E, ?, ?> plan;
    private final QueryLimit limit;

    PgIdScan(PgEntityPlan<M, E, ?, ?> plan, QueryLimit limit) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.limit = Objects.requireNonNull(limit, "limit");
    }

    PgEntityPlan<M, E, ?, ?> plan() {
        return plan;
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
