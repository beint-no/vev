package no.beint.vev.pg.spi;

import no.beint.vev.pg.PgCodec;
import no.beint.vev.pg.PgColumn;

/**
 * Generated mapping for rows owned by exactly one tenant in the closed model.
 * Tenant metadata is separate from common snapshot metadata; a missing tenant capability never grants access.
 *
 * @param <M> closed-model marker type
 * @param <E> entity snapshot type
 * @param <K> primary-key type
 * @param <T> tenant-key type
 */
public interface PgTenantEntityPlan<M, E, K, T> extends PgEntityPlan<M, E, K, T> {
    /**
     * Returns the standard codec for tenant keys.
     *
     * @return tenant-key codec shared by the closed model
     */
    PgCodec<T> tenantCodec();

    /**
     * Returns the generated tenant-isolation column identifier.
     *
     * @return name of the sole {@link PgColumn.Role#TENANT} column
     */
    String tenantColumn();

    /**
     * Reads the tenant key from an entity snapshot.
     *
     * @param entity entity snapshot of the exact generated type
     * @return non-null tenant key
     */
    T tenantKeyOf(E entity);
}
