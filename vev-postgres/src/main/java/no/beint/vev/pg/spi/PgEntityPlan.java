package no.beint.vev.pg.spi;

import no.beint.vev.EntityType;
import no.beint.vev.pg.PgCodec;
import no.beint.vev.pg.PgColumn;

import java.util.List;

/**
 * Build-time generated PostgreSQL mapping plan for one immutable entity snapshot type.
 *
 * <p>This SPI is exported so annotation-processor output can link to the runtime. Applications should consume the
 * generated singleton and closed-model registry instead of implementing plans manually. Implementations must return
 * stable metadata and perform only direct, deterministic snapshot access.</p>
 *
 * <p><strong>Trust boundary:</strong> only exact output from Vev's annotation processor is inside the generated-plan
 * safety profile. {@code PgModel} validates a plan's captured structural metadata, but it cannot attest executable
 * behavior in a handwritten, transformed, proxied, or otherwise substituted implementation. Such an implementation
 * is fully trusted application code and is unsupported as a Vev safety boundary.</p>
 *
 * @param <M> closed-model marker type
 * @param <E> entity snapshot type
 * @param <K> primary-key type
 * @param <T> tenant-key type
 */
public interface PgEntityPlan<M, E, K, T> extends EntityType<M, E, K> {
    /**
     * Returns the standard codec for entity primary keys.
     *
     * @return key codec matching {@link #keyType()}
     */
    PgCodec<K> keyCodec();

    /**
     * Returns the standard codec for tenant keys.
     *
     * @return tenant-key codec shared by the closed model
     */
    PgCodec<T> tenantCodec();

    /**
     * Returns the generated PostgreSQL schema identifier.
     *
     * @return safe unquoted schema identifier
     */
    String schemaName();

    /**
     * Returns the generated PostgreSQL table identifier.
     *
     * @return safe unquoted table identifier
     */
    String tableName();

    /**
     * Returns the generated tenant-isolation column identifier.
     *
     * @return name of the sole {@link PgColumn.Role#TENANT} column
     */
    String tenantColumn();

    /**
     * Returns column metadata in entity-constructor and result-set order.
     *
     * @return stable, non-empty ordered column list
     */
    List<PgColumn> columns();

    /**
     * Reads one mapped value from a detached entity snapshot.
     *
     * @param entity entity snapshot of the exact generated type
     * @param columnIndex zero-based index into {@link #columns()}
     * @return column value, possibly {@code null} only when that column is nullable
     */
    Object columnValue(E entity, int columnIndex);

    /**
     * Creates a detached entity snapshot from values in {@link #columns()} order.
     *
     * @param columnValues one value per mapped column
     * @return newly constructed detached snapshot
     */
    E instantiate(Object[] columnValues);

    /**
     * Reads the primary key from an entity snapshot.
     *
     * @param entity entity snapshot of the exact generated type
     * @return non-null primary key
     */
    K keyOf(E entity);

    /**
     * Reads the tenant key from an entity snapshot.
     *
     * @param entity entity snapshot of the exact generated type
     * @return non-null tenant key
     */
    T tenantKeyOf(E entity);
}
