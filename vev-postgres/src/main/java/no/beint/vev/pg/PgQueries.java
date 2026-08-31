package no.beint.vev.pg;

import no.beint.vev.BoundedQuery;
import no.beint.vev.EntityKey;
import no.beint.vev.EntityType;
import no.beint.vev.QueryLimit;
import no.beint.vev.pg.spi.PgEntityPlan;

import java.util.Objects;

/** Factory for bounded PostgreSQL queries whose SQL structure comes exclusively from generated plans. */
public final class PgQueries {
    private PgQueries() {
    }

    /**
     * Creates a tenant-scoped scan of the first entities in ascending primary-key order.
     *
     * <p>The runtime fetches at most one additional row to report {@link no.beint.vev.Rows#hasMore()} without
     * materializing an unbounded result.</p>
     *
     * @param entityType generated PostgreSQL entity type to scan
     * @param limit maximum number of snapshots to return
     * @param <M> closed-model marker type
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     * @return opaque bounded query executable by the matching PostgreSQL runtime
     */
    public static <M, E, K> BoundedQuery<M, E> scanById(EntityType<M, E, K> entityType, QueryLimit limit) {
        PgEntityPlan<M, E, K, ?> plan = generatedPlan(entityType);
        return new PgIdScan<>(plan, limit);
    }

    /**
     * Creates a tenant-scoped scan after one exclusive primary-key cursor.
     *
     * <p>The cursor is bound to the generated model, entity, and key types. Tenant scope still comes exclusively
     * from the lexical transaction, so a cursor is relative to the active tenant rather than carrying tenant
     * authority. Callers normally construct the cursor from the last row of a preceding {@code scanById} or
     * {@code scanByIdAfter} result. Execute every page inside one lexical transaction when traversal must observe
     * one PostgreSQL snapshot; separate transactions may observe intervening writes.</p>
     *
     * @param afterExclusive generated type-bound key after which scanning begins
     * @param limit maximum number of snapshots to return
     * @param <M> closed-model marker type
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     * @return opaque bounded query executable by the matching PostgreSQL runtime
     */
    public static <M, E, K> BoundedQuery<M, E> scanByIdAfter(
            EntityKey<M, E, K> afterExclusive,
            QueryLimit limit) {
        Objects.requireNonNull(afterExclusive, "afterExclusive");
        PgEntityPlan<M, E, K, ?> plan = generatedPlan(afterExclusive.entityType());
        return new PgIdScan<>(plan, afterExclusive.value(), Objects.requireNonNull(limit, "limit"));
    }

    private static <M, E, K> PgEntityPlan<M, E, K, ?> generatedPlan(EntityType<M, E, K> entityType) {
        Objects.requireNonNull(entityType, "entityType");
        if (!(entityType instanceof PgEntityPlan<?, ?, ?, ?> rawPlan)) {
            throw new IllegalArgumentException("Entity type was not generated for the PostgreSQL Vev runtime");
        }
        @SuppressWarnings("unchecked")
        PgEntityPlan<M, E, K, ?> plan = (PgEntityPlan<M, E, K, ?>) rawPlan;
        return plan;
    }
}
