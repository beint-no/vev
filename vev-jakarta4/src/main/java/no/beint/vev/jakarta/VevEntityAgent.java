package no.beint.vev.jakarta;

import jakarta.persistence.CacheRetrieveMode;
import jakarta.persistence.CacheStoreMode;
import jakarta.persistence.ConnectionConsumer;
import jakarta.persistence.ConnectionFunction;
import jakarta.persistence.EntityAgent;
import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.FindOption;
import jakarta.persistence.LockModeType;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.Statement;
import jakarta.persistence.StatementOrTypedQuery;
import jakarta.persistence.StatementReference;
import jakarta.persistence.StoredProcedureQuery;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.TypedQueryReference;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaSelect;
import jakarta.persistence.criteria.CriteriaStatement;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.sql.ResultSetMapping;
import no.beint.vev.Batch;
import no.beint.vev.DeleteResult;
import no.beint.vev.EntityLookup;
import no.beint.vev.VersionedKey;
import no.beint.vev.WriteEntities;
import no.beint.vev.pg.PgModel;
import no.beint.vev.pg.spi.PgEntityPlan;
import no.beint.vev.pg.spi.PgVersionedEntityPlan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Thread-confined, callback-scoped Jakarta Persistence {@link EntityAgent} over Vev's immutable typed runtime.
 *
 * <p>The adapter implements a deliberately narrow safe profile: detached get/find operations, bounded multi-find,
 * cache bypass, and optimistic versioned delete. Session state, dirty checking, lazy loading, dynamic query strings,
 * runtime Criteria trees, entity graphs, stored procedures, raw connections, and manual transactions are rejected.
 * Mutations that would discard Vev's replacement snapshot or explicit outcome are also rejected; use
 * {@link WriteEntities} for those operations.</p>
 *
 * <p>Instances are created by {@link VevEntityAgents}, are valid only on the callback's thread, and are closed when
 * the callback exits.</p>
 *
 * @param <M> closed-model marker type
 * @param <Tenant> tenant-key type
 */
public final class VevEntityAgent<M, Tenant> implements EntityAgent {
    private final PgModel<M, Tenant> model;
    private final WriteEntities<M> entities;
    private final Tenant tenantKey;
    private final Thread ownerThread;
    private final Map<Class<?>, Option> options = new LinkedHashMap<>();
    private CacheRetrieveMode cacheRetrieveMode = CacheRetrieveMode.BYPASS;
    private CacheStoreMode cacheStoreMode = CacheStoreMode.BYPASS;
    private OptimisticLockException optimisticFailure;
    private volatile boolean open = true;

    VevEntityAgent(PgModel<M, Tenant> model, WriteEntities<M> entities, Tenant tenantKey) {
        this.model = Objects.requireNonNull(model, "model");
        this.entities = Objects.requireNonNull(entities, "entities");
        this.tenantKey = Objects.requireNonNull(tenantKey, "tenantKey");
        this.ownerThread = Thread.currentThread();
        addOption(cacheRetrieveMode);
        addOption(cacheStoreMode);
    }

    @Override
    public <T> T get(Class<T> entityClass, Object id) {
        T entity = find(entityClass, id);
        if (entity == null) {
            throw new EntityNotFoundException(entityClass.getName() + " with the requested identifier does not exist");
        }
        return entity;
    }

    @Override
    public <T> T get(Class<T> entityClass, Object id, FindOption... findOptions) {
        validateFindOptions(findOptions);
        return get(entityClass, id);
    }

    @Override
    public <T> T get(EntityGraph<T> graph, Object id, FindOption... findOptions) {
        throw unsupported("Entity graphs are intentionally absent from the Vev safe profile");
    }

    @Override
    public <T> List<T> getMultiple(Class<T> entityClass, List<?> ids, FindOption... findOptions) {
        List<T> found = findMultiple(entityClass, ids, findOptions);
        for (int index = 0; index < found.size(); index++) {
            if (found.get(index) == null) {
                throw new EntityNotFoundException(entityClass.getName() + " at identifier position " + index + " does not exist");
            }
        }
        return found;
    }

    @Override
    public <T> List<T> getMultiple(EntityGraph<T> graph, List<?> ids, FindOption... findOptions) {
        throw unsupported("Entity graphs are intentionally absent from the Vev safe profile");
    }

    @Override
    public <T> T find(Class<T> entityClass, Object id) {
        requireOpen();
        PgEntityPlan<M, T, Object, Tenant> plan = plan(entityClass);
        Object typedId = requireIdentifier(plan, id);
        return entities.find(plan.key(typedId)).orElse(null);
    }

    @Override
    public <T> T find(Class<T> entityClass, Object id, FindOption... findOptions) {
        validateFindOptions(findOptions);
        return find(entityClass, id);
    }

    @Override
    public <T> T find(EntityGraph<T> graph, Object id, FindOption... findOptions) {
        throw unsupported("Entity graphs are intentionally absent from the Vev safe profile");
    }

    @Override
    public <T> List<T> findMultiple(Class<T> entityClass, List<?> ids, FindOption... findOptions) {
        requireOpen();
        validateFindOptions(findOptions);
        List<?> requestedIds = boundedSnapshot(ids, "identifier");
        PgEntityPlan<M, T, Object, Tenant> plan = plan(entityClass);
        List<Object> typedIds = new ArrayList<>(requestedIds.size());
        for (Object id : requestedIds) {
            typedIds.add(requireIdentifier(plan, id));
        }
        Batch<EntityLookup<M, T, Object>> lookups = entities.findMultiple(plan, Batch.copyOf(typedIds));
        List<T> result = new ArrayList<>(lookups.size());
        for (EntityLookup<M, T, Object> lookup : lookups) {
            if (lookup instanceof EntityLookup.Found<?, ?, ?> found) {
                result.add(entityClass.cast(found.entity()));
            } else {
                result.add(null);
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public <T> List<T> findMultiple(EntityGraph<T> graph, List<?> ids, FindOption... findOptions) {
        throw unsupported("Entity graphs are intentionally absent from the Vev safe profile");
    }

    @Override
    public void insert(Object entity) {
        requireOpen();
        throw immutableMutationUnsupported(plan(entity.getClass()));
    }

    @Override
    public void insertMultiple(List<?> values) {
        requireOpen();
        List<?> entities = boundedSnapshot(values, "entity");
        for (Object entity : entities) {
            insert(entity);
        }
    }

    @Override
    public void update(Object entity) {
        requireOpen();
        throw immutableMutationUnsupported(versionedPlan(entity));
    }

    @Override
    public void updateMultiple(List<?> values) {
        requireOpen();
        List<?> entities = boundedSnapshot(values, "entity");
        for (Object entity : entities) {
            update(entity);
        }
    }

    @Override
    public void delete(Object entity) {
        requireOpen();
        deleteTyped(versionedPlan(entity), entity);
    }

    @Override
    public void deleteMultiple(List<?> values) {
        requireOpen();
        List<?> entities = boundedSnapshot(values, "entity");
        if (!entities.isEmpty()) {
            throw unsupported(
                    "Ordered Jakarta batch delete can expose partial effects; use Vev's typed deleteMultiple outcomes");
        }
    }

    @Override
    public void upsert(Object entity) {
        requireOpen();
        throw immutableMutationUnsupported(versionedPlan(entity));
    }

    @Override
    public void upsertMultiple(List<?> values) {
        requireOpen();
        List<?> entities = boundedSnapshot(values, "entity");
        for (Object entity : entities) {
            upsert(entity);
        }
    }

    @Override
    public void refresh(Object entity) {
        requireOpen();
        throw immutableMutationUnsupported(plan(entity.getClass()));
    }

    @Override
    public void refreshMultiple(List<?> values) {
        requireOpen();
        List<?> entities = boundedSnapshot(values, "entity");
        for (Object entity : entities) {
            refresh(entity);
        }
    }

    @Override
    public void refresh(Object entity, LockModeType lockMode) {
        requireOpen();
        requireNoLock(lockMode);
        refresh(entity);
    }

    @Override
    public <T> T fetch(T association) {
        requireOpen();
        return Objects.requireNonNull(association, "association");
    }

    @Override
    public void addOption(Option option) {
        requireOpen();
        Option required = Objects.requireNonNull(option, "option");
        if (required instanceof CacheRetrieveMode retrieveMode) {
            if (retrieveMode != CacheRetrieveMode.BYPASS) {
                throw unsupported("Vev EntityAgent has no shared cache and requires CacheRetrieveMode.BYPASS");
            }
            cacheRetrieveMode = retrieveMode;
        } else if (required instanceof CacheStoreMode storeMode) {
            if (storeMode != CacheStoreMode.BYPASS) {
                throw unsupported("Vev EntityAgent has no shared cache and requires CacheStoreMode.BYPASS");
            }
            cacheStoreMode = storeMode;
        } else {
            throw unsupported("Unsupported EntityAgent option: " + required.getClass().getName());
        }
        options.put(required.getClass(), required);
    }

    @Override
    public Set<Option> getOptions() {
        requireOpen();
        return Set.copyOf(options.values());
    }

    @Override
    public void setCacheRetrieveMode(CacheRetrieveMode cacheRetrieveMode) {
        addOption(Objects.requireNonNull(cacheRetrieveMode, "cacheRetrieveMode"));
    }

    @Override
    public void setCacheStoreMode(CacheStoreMode cacheStoreMode) {
        addOption(Objects.requireNonNull(cacheStoreMode, "cacheStoreMode"));
    }

    @Override
    public CacheRetrieveMode getCacheRetrieveMode() {
        requireOpen();
        return cacheRetrieveMode;
    }

    @Override
    public CacheStoreMode getCacheStoreMode() {
        requireOpen();
        return cacheStoreMode;
    }

    @Override
    public void setProperty(String propertyName, Object value) {
        throw unsupported("Stringly typed runtime properties are intentionally absent from the Vev safe profile");
    }

    @Override
    public Map<String, Object> getProperties() {
        requireOpen();
        return Map.of();
    }

    @Override
    public Statement createStatement(String query) {
        throw dynamicQueriesUnsupported();
    }

    @Override
    public StatementOrTypedQuery createQuery(String query) {
        throw dynamicQueriesUnsupported();
    }

    @Override
    public <T> TypedQuery<T> createQuery(CriteriaSelect<T> criteria) {
        throw unsupported("Runtime Criteria trees are intentionally absent; use generated Vev queries");
    }

    @Override
    public Statement createStatement(CriteriaStatement<?> criteria) {
        throw unsupported("Runtime Criteria trees are intentionally absent; use generated Vev queries");
    }

    @Override
    public <T> TypedQuery<T> createQuery(String query, Class<T> resultClass) {
        throw dynamicQueriesUnsupported();
    }

    @Override
    public <T> TypedQuery<T> createQuery(String query, EntityGraph<T> graph) {
        throw dynamicQueriesUnsupported();
    }

    @Override
    public Statement createNamedStatement(String name) {
        throw unsupported("Named runtime query lookup is intentionally absent; use generated static query references");
    }

    @Override
    public StatementOrTypedQuery createNamedQuery(String name) {
        throw unsupported("Named runtime query lookup is intentionally absent; use generated static query references");
    }

    @Override
    public <T> TypedQuery<T> createNamedQuery(String name, Class<T> resultClass) {
        throw unsupported("Named runtime query lookup is intentionally absent; use generated static query references");
    }

    @Override
    public Statement createStatement(StatementReference reference) {
        throw unsupported("Jakarta static statement execution is not implemented in this experimental slice");
    }

    @Override
    public <T> TypedQuery<T> createQuery(TypedQueryReference<T> reference) {
        throw unsupported("Jakarta static query execution is not implemented in this experimental slice");
    }

    @Override
    public Statement createNativeStatement(String sql) {
        throw dynamicQueriesUnsupported();
    }

    @Override
    public StatementOrTypedQuery createNativeQuery(String sql) {
        throw dynamicQueriesUnsupported();
    }

    @Override
    public <T> TypedQuery<T> createNativeQuery(String sql, Class<T> resultClass) {
        throw dynamicQueriesUnsupported();
    }

    @Override
    public StatementOrTypedQuery createNativeQuery(String sql, String resultSetMapping) {
        throw dynamicQueriesUnsupported();
    }

    @Override
    public <T> TypedQuery<T> createNativeQuery(String sql, ResultSetMapping<T> resultSetMapping) {
        throw dynamicQueriesUnsupported();
    }

    @Override
    public StoredProcedureQuery createNamedStoredProcedureQuery(String name) {
        throw unsupported("Stored procedures are intentionally absent from the Vev safe profile");
    }

    @Override
    public StoredProcedureQuery createStoredProcedureQuery(String name) {
        throw unsupported("Stored procedures are intentionally absent from the Vev safe profile");
    }

    @Override
    public StoredProcedureQuery createStoredProcedureQuery(String name, Class<?>... resultClasses) {
        throw unsupported("Stored procedures are intentionally absent from the Vev safe profile");
    }

    @Override
    public StoredProcedureQuery createStoredProcedureQuery(String name, String... resultSetMappings) {
        throw unsupported("Stored procedures are intentionally absent from the Vev safe profile");
    }

    @Override
    public <T> T unwrap(Class<T> type) {
        requireOpen();
        Objects.requireNonNull(type, "type");
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        throw unsupported("Vev EntityAgent cannot be unwrapped to " + type.getName());
    }

    @Override
    public void close() {
        requireOwner();
        open = false;
    }

    @Override
    public boolean isOpen() {
        requireOwner();
        return open;
    }

    @Override
    public EntityTransaction getTransaction() {
        throw unsupported("Vev transactions are lexical callbacks and cannot be manually committed");
    }

    @Override
    public EntityManagerFactory getEntityManagerFactory() {
        throw unsupported("Vev does not expose a stateful EntityManagerFactory");
    }

    @Override
    public CriteriaBuilder getCriteriaBuilder() {
        throw unsupported("Runtime Criteria trees are intentionally absent; use generated Vev queries");
    }

    @Override
    public Metamodel getMetamodel() {
        throw unsupported("Use the generated Vev model instead of runtime metamodel discovery");
    }

    @Override
    public <T> EntityGraph<T> createEntityGraph(Class<T> rootType) {
        throw unsupported("Entity graphs are intentionally absent from the Vev safe profile");
    }

    @Override
    public EntityGraph<?> getEntityGraph(String graphName) {
        throw unsupported("Entity graphs are intentionally absent from the Vev safe profile");
    }

    @Override
    public <T> EntityGraph<T> getEntityGraph(Class<T> rootType, String graphName) {
        throw unsupported("Entity graphs are intentionally absent from the Vev safe profile");
    }

    @Override
    public <T> List<EntityGraph<? super T>> getEntityGraphs(Class<T> entityClass) {
        requireOpen();
        return List.of();
    }

    @Override
    public <C> void runWithConnection(ConnectionConsumer<C> action) {
        throw unsupported("Raw connection access is intentionally absent from the Vev safe profile");
    }

    @Override
    public <C, T> T callWithConnection(ConnectionFunction<C, T> function) {
        throw unsupported("Raw connection access is intentionally absent from the Vev safe profile");
    }

    @SuppressWarnings("unchecked")
    private <T> PgEntityPlan<M, T, Object, Tenant> plan(Class<T> entityClass) {
        Objects.requireNonNull(entityClass, "entityClass");
        return (PgEntityPlan<M, T, Object, Tenant>) model.plan(entityClass);
    }

    @SuppressWarnings("unchecked")
    private PgEntityPlan<M, Object, Object, Tenant> planForEntity(Object entity) {
        Objects.requireNonNull(entity, "entity");
        return plan((Class<Object>) entity.getClass());
    }

    @SuppressWarnings("unchecked")
    private <E, K, V> PgVersionedEntityPlan<M, E, K, Tenant, V> versionedPlan(Object entity) {
        PgEntityPlan<M, Object, Object, Tenant> plan = planForEntity(entity);
        if (!(plan instanceof PgVersionedEntityPlan<?, ?, ?, ?, ?> versioned)) {
            throw new IllegalArgumentException(plan.logicalName() + " is append-only");
        }
        return (PgVersionedEntityPlan<M, E, K, Tenant, V>) versioned;
    }

    private <E, K> Object requireIdentifier(PgEntityPlan<M, E, K, Tenant> plan, Object id) {
        Objects.requireNonNull(id, "id");
        if (!plan.keyType().isInstance(id)) {
            throw new IllegalArgumentException("Identifier for " + plan.logicalName() + " must be " + plan.keyType().getName());
        }
        return id;
    }

    @SuppressWarnings("unchecked")
    private <E, K, V> void deleteTyped(PgVersionedEntityPlan<M, E, K, Tenant, V> plan, Object value) {
        E entity = plan.javaType().cast(value);
        requireEntityTenant(plan, entity);
        K key = Objects.requireNonNull(plan.keyOf(entity), "entity identifier");
        V version = Objects.requireNonNull(plan.versionOf(entity), "entity version");
        VersionedKey<M, E, K, V> versionedKey = plan.versionedKey(key, version);
        DeleteResult<M, E, K, V> result = entities.delete(versionedKey);
        if (!(result instanceof DeleteResult.Deleted<?, ?, ?, ?>)) {
            optimisticFailure = new OptimisticLockException("Optimistic delete failed for " + plan.logicalName());
            throw optimisticFailure;
        }
    }

    private <E, K> void requireEntityTenant(PgEntityPlan<M, E, K, Tenant> plan, E entity) {
        Object entityTenant = Objects.requireNonNull(plan.tenantKeyOf(entity), "entity tenant key");
        if (!tenantKey.equals(entityTenant)) {
            throw new IllegalArgumentException("Entity tenant does not match the lexical EntityAgent tenant");
        }
    }

    private UnsupportedOperationException immutableMutationUnsupported(PgEntityPlan<M, ?, ?, Tenant> plan) {
        return new UnsupportedOperationException(
                plan.logicalName() + " is immutable; use Vev's typed mutation API which returns the new value");
    }

    private static List<?> boundedSnapshot(List<?> values, String valueName) {
        Objects.requireNonNull(values, "values");
        int declaredSize = values.size();
        if (declaredSize < 0 || declaredSize > Batch.MAX_SIZE) {
            throw new IllegalArgumentException("EntityAgent batch must not exceed " + Batch.MAX_SIZE + " values");
        }
        List<Object> snapshot = new ArrayList<>(declaredSize);
        for (Object value : values) {
            if (snapshot.size() == Batch.MAX_SIZE) {
                throw new IllegalArgumentException("EntityAgent batch must not exceed " + Batch.MAX_SIZE + " values");
            }
            snapshot.add(Objects.requireNonNull(value, valueName));
        }
        return List.copyOf(snapshot);
    }

    private void validateFindOptions(FindOption... findOptions) {
        requireOpen();
        Objects.requireNonNull(findOptions, "findOptions");
        for (FindOption option : findOptions) {
            Objects.requireNonNull(option, "findOption");
            if (option instanceof LockModeType lockMode) {
                requireNoLock(lockMode);
            } else if (option == CacheRetrieveMode.BYPASS || option == CacheStoreMode.BYPASS) {
                continue;
            } else {
                throw unsupported("Unsupported EntityAgent find option: " + option.getClass().getName());
            }
        }
    }

    private static void requireNoLock(LockModeType lockMode) {
        Objects.requireNonNull(lockMode, "lockMode");
        if (lockMode != LockModeType.NONE) {
            throw new UnsupportedOperationException("Vev EntityAgent supports only LockModeType.NONE");
        }
    }

    private void requireOpen() {
        requireOwner();
        if (!open) {
            throw new IllegalStateException("Vev EntityAgent is closed");
        }
        if (optimisticFailure != null) {
            throw new IllegalStateException("Vev EntityAgent transaction must roll back after optimistic failure");
        }
    }

    void requireCommittable() {
        requireOwner();
        if (optimisticFailure != null) {
            throw optimisticFailure;
        }
    }

    private void requireOwner() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Vev EntityAgent belongs to a different thread");
        }
    }

    private UnsupportedOperationException dynamicQueriesUnsupported() {
        requireOpen();
        return unsupported("Runtime query strings are disabled; use a generated typed Vev query");
    }

    private UnsupportedOperationException unsupported(String message) {
        requireOpen();
        return new UnsupportedOperationException(message);
    }
}
