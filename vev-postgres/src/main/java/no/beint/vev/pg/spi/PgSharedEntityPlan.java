package no.beint.vev.pg.spi;

/**
 * Generated read-only mapping for reference rows intentionally visible to every tenant in the model.
 * This capability has no tenant column or tenant snapshot accessor and cannot be combined with tenant ownership.
 *
 * @param <M> closed-model marker type
 * @param <E> entity snapshot type
 * @param <K> primary-key type
 * @param <T> ambient transaction tenant-key type; shared rows do not store this value
 */
public interface PgSharedEntityPlan<M, E, K, T> extends PgReadOnlyEntityPlan<M, E, K, T> {
}
