package no.beint.vev.pg;

import no.beint.vev.BoundedQuery;
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
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(limit, "limit");
        if (!(entityType instanceof PgEntityPlan<?, ?, ?, ?> rawPlan)) {
            throw new IllegalArgumentException("Entity type was not generated for the PostgreSQL Vev runtime");
        }
        @SuppressWarnings("unchecked")
        PgEntityPlan<M, E, K, ?> plan = (PgEntityPlan<M, E, K, ?>) rawPlan;
        return new PgIdScan<>(plan, limit);
    }
}
