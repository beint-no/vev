package no.beint.vev;

/**
 * Explicit mutations available inside a lexical write transaction.
 *
 * <p>There is deliberately no {@code save}, {@code merge}, dirty checking, cascade, or flush.
 * Mutable operations accept only generated versioned entity types. An assigned identifier is a permanent logical
 * identity and must never be reused within one tenant; the current experimental runtime cannot attest that database
 * invariant.</p>
 *
 * @param <M> closed-model marker type
 */
public interface WriteEntities<M> extends ReadEntities<M> {
    /**
     * Inserts one entity and returns its detached database-produced snapshot.
     *
     * @param type generated mapping for the entity
     * @param entity detached entity snapshot to insert
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     * @return inserted snapshot, including any database-produced values
     */
    <E, K> E insert(EntityType<M, E, K> type, E entity);

    /**
     * Inserts one bounded batch atomically; the current PostgreSQL plan reuses one prepared statement.
     *
     * @param type generated mapping shared by every entity
     * @param entities detached entity snapshots in insertion order
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     * @return inserted snapshots in input order, including any database-produced values
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
     * Updates one bounded batch atomically and returns one ordered outcome per input snapshot.
     *
     * @param type generated versioned mapping shared by every entity
     * @param entities detached snapshots carrying their expected versions
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     * @param <V> version-token type
     * @return one explicit outcome per input snapshot, in input order
     */
    <E, K, V> Batch<MutationResult<M, E, K, V>> updateMultiple(
            VersionedEntityType<M, E, K, V> type, Batch<E> entities);

    /**
     * Explicitly inserts a missing version-zero identity or version-safely updates an existing entity.
     *
     * @param type generated versioned mapping for the entity
     * @param entity detached snapshot carrying the expected version
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     * @param <V> version-token type
     * @return explicit inserted, updated, conflict, or missing outcome
     */
    <E, K, V> MutationResult<M, E, K, V> upsert(VersionedEntityType<M, E, K, V> type, E entity);

    /**
     * Explicitly inserts or version-safely updates one bounded batch atomically.
     *
     * @param type generated versioned mapping shared by every entity
     * @param entities detached snapshots carrying their expected versions
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     * @param <V> version-token type
     * @return one explicit outcome per input snapshot, in input order
     */
    <E, K, V> Batch<MutationResult<M, E, K, V>> upsertMultiple(
            VersionedEntityType<M, E, K, V> type, Batch<E> entities);

    /**
     * Deletes one row only when its stored version matches; the identifier remains permanently retired.
     *
     * @param key entity key and expected stored version
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     * @param <V> version-token type
     * @return explicit deleted, conflict, or missing outcome
     */
    <E, K, V> DeleteResult<M, E, K, V> delete(VersionedKey<M, E, K, V> key);

    /**
     * Deletes one bounded batch atomically with one ordered outcome per key.
     *
     * @param keys entity keys and expected stored versions
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     * @param <V> version-token type
     * @return one explicit outcome per input key, in input order
     */
    <E, K, V> Batch<DeleteResult<M, E, K, V>> deleteMultiple(Batch<VersionedKey<M, E, K, V>> keys);
}
