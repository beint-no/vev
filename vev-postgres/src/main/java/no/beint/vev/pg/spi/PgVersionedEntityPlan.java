package no.beint.vev.pg.spi;

import no.beint.vev.VersionedEntityType;
import no.beint.vev.pg.PgCodec;

/**
 * Generated PostgreSQL plan for an entity with mandatory optimistic versioning.
 *
 * @param <M> closed-model marker type
 * @param <E> entity snapshot type
 * @param <K> primary-key type
 * @param <T> tenant-key type
 * @param <V> version-token type
 */
public interface PgVersionedEntityPlan<M, E, K, T, V>
        extends PgEntityPlan<M, E, K, T>, VersionedEntityType<M, E, K, V> {
    /**
     * Returns the standard codec for version tokens.
     *
     * @return version codec matching {@link #versionType()}
     */
    PgCodec<V> versionCodec();

    /**
     * Reads the expected version from an entity snapshot.
     *
     * @param entity entity snapshot of the exact generated type
     * @return non-null optimistic version token
     */
    V versionOf(E entity);
}
