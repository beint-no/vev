package no.beint.vev.pg.spi;

import no.beint.vev.GeneratedEntityType;

/**
 * Generated PostgreSQL identity insertion plan with direct immutable creation-input access.
 *
 * <p>The generated-plan trust boundary documented by {@link PgEntityPlan} also applies here.</p>
 *
 * @param <M> closed-model marker type
 * @param <E> persisted snapshot type
 * @param <K> generated integer primary-key type
 * @param <T> tenant-key type
 * @param <N> generated creation-input type
 */
public interface PgGeneratedEntityPlan<M, E, K, T, N>
        extends PgIdentityEntityPlan<M, E, K, T>, GeneratedEntityType<M, E, K, N> {
    /**
     * Reads an application value in the plan's complete column order.
     *
     * @param input exact generated immutable creation input
     * @param columnIndex index of a VALUE column; identity, tenant, and version indexes are rejected
     * @return application value, nullable only according to its column metadata
     */
    Object creationColumnValue(N input, int columnIndex);
}
