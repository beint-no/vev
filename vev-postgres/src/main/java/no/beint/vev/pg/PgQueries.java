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

    /**
     * Creates a tenant-scoped indexed equality page ordered by ascending primary key.
     *
     * @param index generated and live-attested index token
     * @param value exact non-null indexed value
     * @param limit maximum number of snapshots to return
     * @param <M> closed-model marker type
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     * @param <V> indexed value type
     * @return opaque bounded equality query
     */
    public static <M, E, K, V> BoundedQuery<M, E> equal(
            PgIndex<M, E, K, V> index,
            V value,
            QueryLimit limit) {
        PgIndex<M, E, K, V> generated = generatedIndex(index);
        requireValue(generated, value);
        return new PgIndexScan<>(
                generated, PgIndexScan.Predicate.EQUAL, value, null, Objects.requireNonNull(limit, "limit"));
    }

    /**
     * Creates an indexed equality page after an exclusive generated primary key.
     *
     * <p>Execute related pages in one lexical transaction when they must share one PostgreSQL snapshot.</p>
     *
     * @param index generated and live-attested index token
     * @param value exact non-null indexed value
     * @param afterExclusive type-bound exclusive primary key
     * @param limit maximum number of snapshots to return
     * @param <M> closed-model marker type
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     * @param <V> indexed value type
     * @return opaque bounded equality continuation query
     */
    public static <M, E, K, V> BoundedQuery<M, E> equalAfter(
            PgIndex<M, E, K, V> index,
            V value,
            EntityKey<M, E, K> afterExclusive,
            QueryLimit limit) {
        PgIndex<M, E, K, V> generated = generatedIndex(index);
        requireValue(generated, value);
        K key = requireKey(generated, afterExclusive);
        return new PgIndexScan<>(
                generated, PgIndexScan.Predicate.EQUAL, value, key, Objects.requireNonNull(limit, "limit"));
    }

    /**
     * Creates a tenant-scoped {@code IS NULL} page for a generated nullable index.
     *
     * @param index generated nullable index token
     * @param limit maximum number of snapshots to return
     * @param <M> closed-model marker type
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     * @param <V> indexed value type
     * @return opaque bounded null query
     */
    public static <M, E, K, V> BoundedQuery<M, E> isNull(
            PgNullableIndex<M, E, K, V> index,
            QueryLimit limit) {
        PgNullableIndex<M, E, K, V> generated = generatedNullableIndex(index);
        return new PgIndexScan<>(
                generated, PgIndexScan.Predicate.IS_NULL, null, null, Objects.requireNonNull(limit, "limit"));
    }

    /**
     * Creates a nullable-index page after an exclusive generated primary key.
     *
     * @param index generated nullable index token
     * @param afterExclusive type-bound exclusive primary key
     * @param limit maximum number of snapshots to return
     * @param <M> closed-model marker type
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     * @param <V> indexed value type
     * @return opaque bounded null-query continuation
     */
    public static <M, E, K, V> BoundedQuery<M, E> isNullAfter(
            PgNullableIndex<M, E, K, V> index,
            EntityKey<M, E, K> afterExclusive,
            QueryLimit limit) {
        PgNullableIndex<M, E, K, V> generated = generatedNullableIndex(index);
        K key = requireKey(generated, afterExclusive);
        return new PgIndexScan<>(
                generated, PgIndexScan.Predicate.IS_NULL, null, key, Objects.requireNonNull(limit, "limit"));
    }

    /**
     * Creates an equality page ordered by the declared scalar value and identifier tie-breaker.
     * @param index generated ordered index
     * @param value non-null equality value
     * @param limit maximum returned rows
     * @param <M> model marker
     * @param <E> snapshot type
     * @param <K> identifier type
     * @param <V> equality value type
     * @param <S> ordering value type
     * @return opaque bounded ordered query
     */
    public static <M, E, K, V, S> BoundedQuery<M, E> equal(
            PgOrderedIndex<M, E, K, V, S> index, V value, QueryLimit limit) {
        Objects.requireNonNull(index, "index");
        requireValue(index, value);
        return new PgOrderedIndexScan<>(index, PgIndexScan.Predicate.EQUAL, value, null, limit);
    }

    /**
     * Continues an ordered equality page after an exact index-bound value/identifier cursor.
     * @param index generated ordered index
     * @param value non-null equality value
     * @param afterExclusive index-bound cursor
     * @param limit maximum returned rows
     * @param <M> model marker
     * @param <E> snapshot type
     * @param <K> identifier type
     * @param <V> equality value type
     * @param <S> ordering value type
     * @return opaque bounded continuation query
     */
    public static <M, E, K, V, S> BoundedQuery<M, E> equalAfter(
            PgOrderedIndex<M, E, K, V, S> index, V value, PgOrderedCursor<M, E, K, V, S> afterExclusive, QueryLimit limit) {
        Objects.requireNonNull(index, "index");
        requireValue(index, value);
        return new PgOrderedIndexScan<>(index, PgIndexScan.Predicate.EQUAL, value,
                Objects.requireNonNull(afterExclusive, "afterExclusive"), limit);
    }

    /**
     * Creates an ordered page for a nullable filter whose value is SQL NULL.
     * @param index generated nullable ordered index
     * @param limit maximum returned rows
     * @param <M> model marker
     * @param <E> snapshot type
     * @param <K> identifier type
     * @param <V> equality value type
     * @param <S> ordering value type
     * @return opaque bounded null-filter query
     */
    public static <M, E, K, V, S> BoundedQuery<M, E> isNull(
            PgNullableOrderedIndex<M, E, K, V, S> index, QueryLimit limit) {
        return new PgOrderedIndexScan<>(index, PgIndexScan.Predicate.IS_NULL, null, null, limit);
    }

    /**
     * Continues a nullable ordered index page after an exact value/identifier cursor.
     * @param index generated nullable ordered index
     * @param afterExclusive index-bound cursor
     * @param limit maximum returned rows
     * @param <M> model marker
     * @param <E> snapshot type
     * @param <K> identifier type
     * @param <V> equality value type
     * @param <S> ordering value type
     * @return opaque bounded null-filter continuation
     */
    public static <M, E, K, V, S> BoundedQuery<M, E> isNullAfter(
            PgNullableOrderedIndex<M, E, K, V, S> index, PgOrderedCursor<M, E, K, V, S> afterExclusive, QueryLimit limit) {
        return new PgOrderedIndexScan<>(index, PgIndexScan.Predicate.IS_NULL, null,
                Objects.requireNonNull(afterExclusive, "afterExclusive"), limit);
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

    private static <M, E, K, V> PgIndex<M, E, K, V> generatedIndex(PgIndex<M, E, K, V> index) {
        return Objects.requireNonNull(index, "index");
    }

    private static <M, E, K, V> PgNullableIndex<M, E, K, V> generatedNullableIndex(
            PgNullableIndex<M, E, K, V> index) {
        return Objects.requireNonNull(index, "index");
    }

    private static <M, E, K, V> void requireValue(PgQueryIndex<M, E, K, V> index, V value) {
        Objects.requireNonNull(value, "value");
        if (value.getClass() != index.valueType()
                && !(value instanceof Enum<?> constant && constant.getDeclaringClass() == index.valueType())) {
            throw new IllegalArgumentException("Query value does not match the exact generated PostgreSQL codec");
        }
    }

    private static <M, E, K, V> K requireKey(
            PgIndex<M, E, K, V> index,
            EntityKey<M, E, K> afterExclusive) {
        Objects.requireNonNull(afterExclusive, "afterExclusive");
        if (afterExclusive.entityType() != index.entityPlan()) {
            throw new IllegalArgumentException("Continuation key is not from the indexed generated entity plan");
        }
        return afterExclusive.value();
    }
}
