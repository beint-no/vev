package no.beint.vev;

/**
 * Explicit mutations available inside a lexical write transaction.
 *
 * <p>There is deliberately no {@code save}, {@code merge}, dirty checking, cascade, flush, physical delete, or
 * create-capable upsert. Mutable operations accept only generated versioned entity types. Lifecycle retirement must
 * be modeled as an explicit versioned update, so the runtime cannot accidentally make an assigned identifier
 * reusable.</p>
 *
 * @param <M> closed-model marker type
 */
public interface WriteEntities<M> extends ReadEntities<M> {
    /**
     * Inserts one entity and returns the detached snapshot verified against PostgreSQL's {@code RETURNING} row.
     *
     * @param type generated mapping for the entity
     * @param entity detached entity snapshot to insert
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     * @return inserted snapshot exactly matching the validated input
     */
    <E, K> E insert(EntityType<M, E, K> type, E entity);

    /**
     * Inserts one bounded batch atomically through one set-based PostgreSQL statement.
     *
     * @param type generated mapping shared by every entity
     * @param entities detached entity snapshots in insertion order
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     * @return verified inserted snapshots in input order
     */
    <E, K> Batch<E> insertMultiple(EntityType<M, E, K> type, Batch<E> entities);

    /**
     * Updates one versioned entity or returns an explicit conflict or missing result.
     *
     * @param type generated versioned mapping for the entity
     * @param entity detached snapshot carrying the expected version
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     * @param <V> version-token type
     * @return explicit updated, conflict, or missing outcome
     */
    <E, K, V> MutationResult<M, E, K, V> update(VersionedEntityType<M, E, K, V> type, E entity);

    /**
     * Updates one bounded batch atomically through one set-based PostgreSQL statement.
     *
     * <p>Every input must apply. A stale or missing member rejects and rolls back the complete lexical transaction;
     * duplicate keys are rejected before SQL. This deliberately prevents a partially applied default batch.</p>
     *
     * @param type generated versioned mapping shared by every entity
     * @param entities detached snapshots carrying their expected versions
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     * @param <V> version-token type
     * @return one applied result per input snapshot, in input order
     */
    <E, K, V> Batch<MutationResult.Applied<M, E, K, V>> updateMultiple(
            VersionedEntityType<M, E, K, V> type, Batch<E> entities);
}
