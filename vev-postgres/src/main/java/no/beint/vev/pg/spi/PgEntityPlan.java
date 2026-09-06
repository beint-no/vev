package no.beint.vev.pg.spi;

import no.beint.vev.EntityType;
import no.beint.vev.pg.PgCodec;
import no.beint.vev.pg.PgColumn;
import no.beint.vev.pg.PgIndex;
import no.beint.vev.pg.PgReference;
import no.beint.vev.pg.PgUnique;

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
     * Returns the exact physical primary-key role order.
     *
     * @return declared primary-key shape; tenant first when not explicitly overridden
     */
    default no.beint.vev.VevPrimaryKey.Shape primaryKeyShape() {
        return no.beint.vev.VevPrimaryKey.Shape.TENANT_ID;
    }

    /**
     * Returns column metadata in entity-constructor and result-set order.
     *
     * @return stable, non-empty ordered column list
     */
    List<PgColumn> columns();

    /**
     * Returns the complete generated secondary-index requirements for this entity.
     *
     * @return stable immutable index-token list
     */
    List<PgIndex<M, E, K, ?>> indexes();

    /**
     * Returns the complete generated outgoing foreign-key requirements.
     *
     * @return immutable scalar references within this model; empty for a model without references
     */
    default List<PgReference> references() {
        return List.of();
    }

    /**
     * Returns the complete generated tenant-scoped unique constraints.
     *
     * @return immutable immediate constraints using distinct-null semantics
     */
    default List<PgUnique> uniqueConstraints() {
        return List.of();
    }

    /**
     * Returns the complete set of declared PostgreSQL check constraints.
     *
     * @return immutable checks, verified without evaluating their expressions
     */
    default List<no.beint.vev.pg.PgCheck> checkConstraints() {
        return List.of();
    }

    /**
     * Reads one mapped value from a detached entity snapshot.
     *
     * @param entity entity snapshot of the exact generated type
     * @param columnIndex zero-based index into {@link #columns()}
     * @return column value, possibly {@code null} only when that column is nullable
     */
    Object columnValue(E entity, int columnIndex);

    /**
     * Reads a detached snapshot directly from the current JDBC row in {@link #columns()} order.
     * Generated readers validate every scalar before calling the verified pure canonical constructor.
     * The result set must not be retained, advanced, closed, or exposed to entity code.
     *
     * @param resultSet current result row
     * @param firstColumn one-based position of the first mapped column
     * @return newly constructed detached snapshot
     * @throws java.sql.SQLException if a mapped value cannot be read
     */
    E readRow(java.sql.ResultSet resultSet, int firstColumn) throws java.sql.SQLException;

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
