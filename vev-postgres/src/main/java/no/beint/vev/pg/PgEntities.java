package no.beint.vev.pg;

import no.beint.vev.Batch;
import no.beint.vev.BoundedQuery;
import no.beint.vev.EntityKey;
import no.beint.vev.EntityLookup;
import no.beint.vev.EntityType;
import no.beint.vev.MutationResult;
import no.beint.vev.ReadEntities;
import no.beint.vev.Rows;
import no.beint.vev.TenantScope;
import no.beint.vev.VersionedEntityType;
import no.beint.vev.WriteEntities;
import no.beint.vev.spi.TransactionGuard;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class PgEntities<M, T> implements WriteEntities<M> {
    private final Connection connection;
    private final PgModel<M, T> model;
    private final TenantScope<M, T> tenant;
    private final TransactionGuard guard;
    private final PgSettings settings;
    private final boolean writeAllowed;

    PgEntities(
            Connection connection,
            PgModel<M, T> model,
            TenantScope<M, T> tenant,
            TransactionGuard guard,
            PgSettings settings,
            boolean writeAllowed) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.model = Objects.requireNonNull(model, "model");
        this.tenant = Objects.requireNonNull(tenant, "tenant");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.writeAllowed = writeAllowed;
    }

    @Override
    public <E, K> Optional<E> find(EntityKey<M, E, K> key) {
        guard.checkUsable();
        Objects.requireNonNull(key, "key");
        PgPlan<M, E, K, T> plan = plan(key.entityType());
        try (PreparedStatement statement = prepare(plan.sql().find())) {
            bindUnknown(plan.keyCodec(), statement, 1, key.value());
            bindTenant(plan, statement, 2);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                E entity = readEntity(plan, resultSet, 1);
                verifyLoaded(plan, entity, key.value());
                requireNoMore(resultSet, "Primary-key lookup returned duplicate rows for " + plan.logicalName());
                return Optional.of(entity);
            }
        } catch (SQLException failure) {
            throw PgVev.databaseFailure(guard, failure);
        } catch (RuntimeException failure) {
            throw invariant("Vev PostgreSQL execution failed an internal invariant", failure);
        } catch (Error failure) {
            poison(failure);
            throw failure;
        }
    }

    @Override
    public <E, K> Batch<EntityLookup<M, E, K>> findMultiple(EntityType<M, E, K> type, Batch<K> keys) {
        guard.checkUsable();
        Objects.requireNonNull(keys, "keys");
        PgPlan<M, E, K, T> plan = plan(type);
        if (keys.isEmpty()) {
            return Batch.empty();
        }
        List<EntityKey<M, E, K>> entityKeys = new ArrayList<>(keys.size());
        for (K key : keys) {
            entityKeys.add(plan.key(key));
        }
        Object[] keyValues = entityKeys.stream().map(EntityKey::value).toArray();
        List<EntityLookup<M, E, K>> lookups = new ArrayList<>(keys.size());
        try (Array keyArray = connection.createArrayOf(plan.keyCodec().jdbcType(), keyValues);
             PreparedStatement statement = prepare(plan.sql().findMultiple())) {
            statement.setArray(1, keyArray);
            bindTenant(plan, statement, 2);
            try (ResultSet resultSet = statement.executeQuery()) {
                for (EntityKey<M, E, K> entityKey : entityKeys) {
                    if (!resultSet.next()) {
                        throw invariant("Ordered batch lookup returned fewer rows than requested for " + plan.logicalName());
                    }
                    K requestedKey = entityKey.value();
                    if (resultSet.getBoolean(1)) {
                        E entity = readEntity(plan, resultSet, 2);
                        verifyLoaded(plan, entity, requestedKey);
                        lookups.add(new EntityLookup.Found<>(entityKey, entity));
                    } else {
                        lookups.add(new EntityLookup.Missing<>(entityKey));
                    }
                }
                requireNoMore(resultSet, "Ordered batch lookup returned extra rows for " + plan.logicalName());
            }
            return Batch.copyOf(lookups);
        } catch (SQLException failure) {
            throw PgVev.databaseFailure(guard, failure);
        } catch (RuntimeException failure) {
            throw invariant("Vev PostgreSQL execution failed an internal invariant", failure);
        } catch (Error failure) {
            poison(failure);
            throw failure;
        }
    }

    @Override
    public <R> Rows<R> many(BoundedQuery<M, R> query) {
        guard.checkUsable();
        Objects.requireNonNull(query, "query");
        if (query instanceof PgIdScan<?, ?, ?> rawScan) {
            @SuppressWarnings("unchecked")
            PgIdScan<M, R, Object> scan = (PgIdScan<M, R, Object>) rawScan;
            return executeIdScan(scan);
        }
        if (query instanceof PgIndexScan<?, ?, ?, ?> rawScan) {
            @SuppressWarnings("unchecked")
            PgIndexScan<M, R, Object, Object> scan = (PgIndexScan<M, R, Object, Object>) rawScan;
            return executeIndexScan(scan);
        }
        throw new IllegalArgumentException("Only structurally generated PostgreSQL queries are executable");
    }

    private <R, K> Rows<R> executeIdScan(PgIdScan<M, R, K> scan) {
        PgPlan<M, R, K, T> entityPlan = model.frozenPlan(scan.plan());
        int limit = scan.limit().value();
        List<R> values = new ArrayList<>(limit);
        String sql = scan.hasAfterExclusive()
                ? entityPlan.sql().scanByIdAfter()
                : entityPlan.sql().scanById();
        try (PreparedStatement statement = prepare(sql)) {
            bindTenant(entityPlan, statement, 1);
            int limitIndex = 2;
            if (scan.hasAfterExclusive()) {
                bindUnknown(entityPlan.keyCodec(), statement, 2, scan.afterExclusive());
                limitIndex = 3;
            }
            statement.setInt(limitIndex, Math.addExact(limit, 1));
            statement.setFetchSize(Math.addExact(limit, 1));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (values.size() < limit && resultSet.next()) {
                    R value = readEntity(entityPlan, resultSet, 1);
                    verifyReturnedEntity(entityPlan, value);
                    values.add(value);
                }
                boolean hasMore = resultSet.next();
                return new Rows<>(values, scan.limit(), hasMore);
            }
        } catch (SQLException failure) {
            throw PgVev.databaseFailure(guard, failure);
        } catch (RuntimeException failure) {
            throw invariant("Vev PostgreSQL execution failed an internal invariant", failure);
        } catch (Error failure) {
            poison(failure);
            throw failure;
        }
    }

    private <R, K, V> Rows<R> executeIndexScan(PgIndexScan<M, R, K, V> scan) {
        PgPlan<M, R, K, T> entityPlan = model.frozenPlan(scan.index().entityPlan());
        PgIndexSql statements = entityPlan.indexSql(scan.index());
        int limit = scan.limit().value();
        List<R> values = new ArrayList<>(limit);
        boolean equality = scan.predicate() == PgIndexScan.Predicate.EQUAL;
        String sql;
        if (equality) {
            sql = scan.hasAfterExclusive() ? statements.equalAfter() : statements.equal();
        } else {
            sql = scan.hasAfterExclusive() ? statements.isNullAfter() : statements.isNull();
            if (sql == null) {
                throw new IllegalArgumentException("IS NULL requires a generated nullable-index token");
            }
        }
        PgColumn indexedColumn = entityPlan.columns().get(scan.index().columnIndex());
        if (equality) {
            indexedColumn.validateValue(scan.value());
        }
        try (PreparedStatement statement = prepare(sql)) {
            int parameter = 1;
            bindTenant(entityPlan, statement, parameter++);
            if (equality) {
                bindUnknown(indexedColumn.codec(), statement, parameter++, scan.value());
            }
            if (scan.hasAfterExclusive()) {
                bindUnknown(entityPlan.keyCodec(), statement, parameter++, scan.afterExclusive());
            }
            statement.setInt(parameter, Math.addExact(limit, 1));
            statement.setFetchSize(Math.addExact(limit, 1));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (values.size() < limit && resultSet.next()) {
                    R value = readEntity(entityPlan, resultSet, 1);
                    verifyReturnedEntity(entityPlan, value);
                    verifyIndexPredicate(entityPlan, value, scan, indexedColumn);
                    values.add(value);
                }
                boolean hasMore = resultSet.next();
                return new Rows<>(values, scan.limit(), hasMore);
            }
        } catch (SQLException failure) {
            throw PgVev.databaseFailure(guard, failure);
        } catch (RuntimeException failure) {
            throw invariant("Vev PostgreSQL execution failed an internal invariant", failure);
        } catch (Error failure) {
            poison(failure);
            throw failure;
        }
    }

    @Override
    public <E, K> E insert(EntityType<M, E, K> type, E entity) {
        requireWrite();
        PgPlan<M, E, K, T> plan = plan(type);
        validateInsert(plan, entity);
        try (PreparedStatement statement = prepare(plan.sql().insert())) {
            return executeInsert(plan, statement, entity);
        } catch (SQLException failure) {
            throw PgVev.databaseFailure(guard, failure);
        } catch (RuntimeException failure) {
            throw invariant("Vev PostgreSQL execution failed an internal invariant", failure);
        } catch (Error failure) {
            poison(failure);
            throw failure;
        }
    }

    @Override
    public <E, K> Batch<E> insertMultiple(EntityType<M, E, K> type, Batch<E> entities) {
        requireWrite();
        Objects.requireNonNull(entities, "entities");
        PgPlan<M, E, K, T> plan = plan(type);
        Set<K> keys = new HashSet<>(Math.max(16, entities.size() * 2));
        for (E entity : entities) {
            validateInsert(plan, entity);
            K key = Objects.requireNonNull(plan.keyOf(entity), "entity key");
            if (!keys.add(key)) {
                throw new IllegalArgumentException("Insert batch contains duplicate entity keys");
            }
        }
        if (entities.isEmpty()) {
            return Batch.empty();
        }
        List<E> inserted = new ArrayList<>(entities.size());
        try (JdbcArrays arrays = new JdbcArrays(connection);
             PreparedStatement statement = prepare(plan.sql().insertMultiple())) {
            int parameter = bindEntityArrays(plan, entities, arrays, statement);
            statement.setInt(parameter, entities.size());
            try (ResultSet resultSet = statement.executeQuery()) {
                for (E expected : entities) {
                    if (!resultSet.next()) {
                        throw invariant("Set-based insert returned fewer rows than requested for " + plan.logicalName());
                    }
                    E actual = readEntity(plan, resultSet, 1);
                    verifyInsertedSnapshot(plan, expected, actual);
                    inserted.add(actual);
                }
                requireNoMore(resultSet, "Set-based insert returned extra rows for " + plan.logicalName());
            }
            return Batch.copyOf(inserted);
        } catch (SQLException failure) {
            throw PgVev.databaseFailure(guard, failure);
        } catch (RuntimeException failure) {
            throw invariant("Vev PostgreSQL execution failed an internal invariant", failure);
        } catch (Error failure) {
            poison(failure);
            throw failure;
        }
    }

    @Override
    public <E, K, V> MutationResult<M, E, K, V> update(
            VersionedEntityType<M, E, K, V> type, E entity) {
        requireWrite();
        PgVersionPlan<M, E, K, T, V> plan = versionedPlan(type);
        return mutate(plan, entity);
    }

    @Override
    public <E, K, V> Batch<MutationResult.Applied<M, E, K, V>> updateMultiple(
            VersionedEntityType<M, E, K, V> type, Batch<E> entities) {
        requireWrite();
        Objects.requireNonNull(entities, "entities");
        PgVersionPlan<M, E, K, T, V> plan = versionedPlan(type);
        Set<K> keys = new HashSet<>(Math.max(16, entities.size() * 2));
        for (E entity : entities) {
            validateVersionedEntity(plan, entity);
            K key = Objects.requireNonNull(plan.keyOf(entity), "entity key");
            if (!keys.add(key)) {
                throw new IllegalArgumentException("Update batch contains duplicate entity keys");
            }
        }
        if (entities.isEmpty()) {
            return Batch.empty();
        }
        Batch<MutationResult.Applied<M, E, K, V>> applied = null;
        try (JdbcArrays arrays = new JdbcArrays(connection);
             PreparedStatement statement = prepare(plan.sql().updateMultiple())) {
            int parameter = bindEntityArrays(plan, entities, arrays, statement);
            statement.setInt(parameter, entities.size());
            try (ResultSet resultSet = statement.executeQuery()) {
                List<MutationResult.Applied<M, E, K, V>> results = new ArrayList<>(entities.size());
                for (int entityIndex = 0; entityIndex < entities.size(); entityIndex++) {
                    if (!resultSet.next()) {
                        applied = null;
                        break;
                    }
                    long ordinality = resultSet.getLong(1);
                    if (resultSet.wasNull() || ordinality != entityIndex + 1L) {
                        applied = null;
                        break;
                    }
                    E expected = entities.get(entityIndex);
                    E actual = readEntity(plan, resultSet, 2);
                    results.add(appliedUpdate(plan, expected, actual));
                }
                if (results.size() == entities.size()) {
                    requireNoMore(resultSet, "Set-based update returned extra rows for " + plan.logicalName());
                    applied = Batch.copyOf(results);
                }
            }
        } catch (SQLException failure) {
            throw PgVev.databaseFailure(guard, failure);
        } catch (RuntimeException failure) {
            throw invariant("Vev PostgreSQL execution failed an internal invariant", failure);
        } catch (Error failure) {
            poison(failure);
            throw failure;
        }
        if (applied == null) {
            IllegalStateException rejection = new IllegalStateException(
                    "Update batch was rejected atomically because one entity was stale or missing");
            poison(rejection);
            throw rejection;
        }
        return applied;
    }

    private <E, K> PgPlan<M, E, K, T> plan(EntityType<M, E, K> type) {
        guard.checkUsable();
        return model.frozenPlan(Objects.requireNonNull(type, "type"));
    }

    private void requireWrite() {
        guard.checkUsable();
        if (!writeAllowed) {
            throw new IllegalStateException("Write operation is unavailable inside a read-only Vev transaction");
        }
    }

    @SuppressWarnings("unchecked")
    private <E, K, V> PgVersionPlan<M, E, K, T, V> versionedPlan(EntityType<M, E, K> type) {
        PgPlan<M, E, K, T> plan = plan(type);
        if (!(plan instanceof PgVersionPlan<?, ?, ?, ?, ?> versionedPlan)) {
            throw new IllegalArgumentException(plan.logicalName() + " is append-only and cannot be mutated");
        }
        return (PgVersionPlan<M, E, K, T, V>) versionedPlan;
    }

    private PreparedStatement prepare(String sql) throws SQLException {
        guard.checkUsable();
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setQueryTimeout(Math.toIntExact((settings.statementTimeout().toMillis() + 999) / 1_000));
            return statement;
        } catch (SQLException failure) {
            closeAfterPrepareFailure(statement, failure);
            throw failure;
        } catch (RuntimeException | Error failure) {
            closeAfterPrepareFailure(statement, failure);
            throw failure;
        }
    }

    private static void closeAfterPrepareFailure(PreparedStatement statement, Throwable failure) {
        try {
            statement.close();
        } catch (RuntimeException | Error | SQLException closeFailure) {
            failure.addSuppressed(new IllegalStateException("PostgreSQL statement cleanup failed"));
        }
    }

    private <E, K> E executeInsert(
            PgPlan<M, E, K, T> plan, PreparedStatement statement, E entity) throws SQLException {
        bindInsert(plan, statement, entity);
        try (ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw invariant("Insert returned no row for " + plan.logicalName());
            }
            E inserted = readEntity(plan, resultSet, 1);
            verifyInsertedSnapshot(plan, entity, inserted);
            requireNoMore(resultSet, "Insert returned multiple rows for " + plan.logicalName());
            return inserted;
        }
    }

    private <E, K, V> MutationResult<M, E, K, V> mutate(
            PgVersionPlan<M, E, K, T, V> plan, E entity) {
        validateVersionedEntity(plan, entity);
        K key = Objects.requireNonNull(plan.keyOf(entity), "entity key");
        V expectedVersion = Objects.requireNonNull(plan.versionOf(entity), "entity version");
        try (PreparedStatement statement = prepare(plan.sql().update())) {
            bindUpdate(plan, statement, entity);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    int outcome = resultSet.getInt(1);
                    if (outcome < 0 || outcome > 1) {
                        throw invariant("Mutation returned an unknown outcome for " + plan.logicalName());
                    }
                    if (outcome == 1) {
                        requireNoMore(resultSet, "Mutation classification returned multiple rows for " + plan.logicalName());
                        return new MutationResult.Conflict<>(plan.key(key), expectedVersion);
                    }
                    E mutated = readEntity(plan, resultSet, 2);
                    MutationResult.Applied<M, E, K, V> applied = appliedUpdate(plan, entity, mutated);
                    requireNoMore(resultSet, "Mutation returned multiple rows for " + plan.logicalName());
                    return applied;
                }
            }
            return new MutationResult.Missing<>(plan.key(key), expectedVersion);
        } catch (SQLException failure) {
            throw PgVev.databaseFailure(guard, failure);
        } catch (RuntimeException failure) {
            throw invariant("Vev PostgreSQL execution failed an internal invariant", failure);
        } catch (Error failure) {
            poison(failure);
            throw failure;
        }
    }

    private <E, K> void validateEntity(PgPlan<M, E, K, T> plan, E entity, boolean requireKey) {
        Objects.requireNonNull(entity, "entity");
        if (!plan.javaType().isInstance(entity)) {
            throw new IllegalArgumentException("Entity must be " + plan.javaType().getName());
        }
        K entityKey = plan.keyOf(entity);
        if (requireKey && entityKey == null) {
            throw new IllegalStateException("Database returned " + plan.logicalName() + " without a key");
        }
        if (entityKey != null) {
            plan.key(entityKey);
        }
        Object entityTenant = Objects.requireNonNull(plan.tenantKeyOf(entity), "entity tenant key");
        if (!tenant.tenantId().equals(entityTenant)) {
            throw new IllegalArgumentException("Entity tenant does not match the lexical transaction tenant");
        }
        List<PgColumn> columns = plan.columns();
        for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
            PgColumn column = columns.get(columnIndex);
            Object value = switch (column.role()) {
                case ID -> entityKey;
                case TENANT -> entityTenant;
                case VERSION -> versionOf((PgVersionPlan<M, ?, ?, T, ?>) plan, entity);
                case VALUE -> plan.columnValue(entity, columnIndex);
            };
            column.validateValue(value);
        }
    }

    private <E, K> void validateInsert(PgPlan<M, E, K, T> plan, E entity) {
        validateEntity(plan, entity, false);
        K key = plan.keyOf(entity);
        if (key == null) {
            throw new IllegalArgumentException("Assigned identifier insert requires a non-null identifier");
        }
        if (plan instanceof PgVersionPlan<?, ?, ?, ?, ?> rawVersioned) {
            @SuppressWarnings("unchecked")
            PgVersionPlan<M, E, K, T, Object> versioned =
                    (PgVersionPlan<M, E, K, T, Object>) rawVersioned;
            Object version = Objects.requireNonNull(versioned.versionOf(entity), "entity version");
            requireNonNegativeVersion(versioned, version);
            if (((Number) version).longValue() != 0L) {
                throw new IllegalArgumentException("New versioned entities must start at version zero");
            }
        }
    }

    private <E, K, V> void validateVersionedEntity(PgVersionPlan<M, E, K, T, V> plan, E entity) {
        validateEntity(plan, entity, true);
        V version = Objects.requireNonNull(plan.versionOf(entity), "entity version");
        if (!plan.versionType().isInstance(version)) {
            throw new IllegalArgumentException("Entity version must be " + plan.versionType().getName());
        }
        requireNonNegativeVersion(plan, version);
        long maximum = plan.versionType() == Short.class
                ? Short.MAX_VALUE
                : plan.versionType() == Integer.class ? Integer.MAX_VALUE : Long.MAX_VALUE;
        if (((Number) version).longValue() == maximum) {
            throw new IllegalArgumentException("Entity version cannot be incremented without overflow");
        }
    }

    private <E, K, V> void requireNonNegativeVersion(PgVersionPlan<M, E, K, T, V> plan, V version) {
        if (!(version instanceof Number number) || number.longValue() < 0L) {
            throw new IllegalArgumentException("Version for " + plan.logicalName() + " must be a non-negative integer");
        }
    }

    private <E, K, V> void verifyVersionTransition(
            PgVersionPlan<M, E, K, T, V> plan,
            V expectedVersion,
            V returnedVersion) {
        try {
            long expected = ((Number) expectedVersion).longValue();
            long returned = ((Number) returnedVersion).longValue();
            long required = Math.addExact(expected, 1L);
            if (returned != required) {
                throw new IllegalStateException("Unexpected version transition");
            }
        } catch (RuntimeException failure) {
            throw invariant("Database returned an invalid version transition for " + plan.logicalName(), failure);
        }
    }

    private <E, K> void verifyLoaded(PgPlan<M, E, K, T> plan, E entity, K expectedKey) {
        try {
            validateDatabaseEntity(plan, entity);
            if (!expectedKey.equals(plan.keyOf(entity))) {
                throw new IllegalStateException("Database returned the wrong key for " + plan.logicalName());
            }
        } catch (RuntimeException failure) {
            throw invariant("Database returned an invalid " + plan.logicalName() + " identity");
        }
    }

    private <E, K> void verifyReturnedEntity(PgPlan<M, E, K, T> plan, E entity) {
        try {
            validateDatabaseEntity(plan, entity);
        } catch (RuntimeException failure) {
            throw invariant("Database returned an invalid " + plan.logicalName() + " row", failure);
        }
    }

    private <E, K> void verifyInsertedSnapshot(PgPlan<M, E, K, T> plan, E expected, E actual) {
        try {
            validateDatabaseEntity(plan, actual);
            List<PgColumn> columns = plan.columns();
            for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
                PgColumn column = columns.get(columnIndex);
                Object actualValue = entityColumnValue(plan, actual, column, columnIndex);
                Object expectedValue = entityColumnValue(plan, expected, column, columnIndex);
                if (!Objects.equals(actualValue, expectedValue)) {
                    throw new IllegalStateException("Inserted snapshot differs from its validated input");
                }
            }
        } catch (RuntimeException failure) {
            throw invariant("Database returned an unexpected inserted " + plan.logicalName() + " snapshot", failure);
        }
    }

    private <E, K, V> MutationResult.Applied<M, E, K, V> appliedUpdate(
            PgVersionPlan<M, E, K, T, V> plan,
            E expected,
            E actual) {
        try {
            validateDatabaseEntity(plan, actual);
            K expectedKey = Objects.requireNonNull(plan.keyOf(expected), "expected entity key");
            V expectedVersion = Objects.requireNonNull(plan.versionOf(expected), "expected entity version");
            V actualVersion = Objects.requireNonNull(plan.versionOf(actual), "returned entity version");
            List<PgColumn> columns = plan.columns();
            for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
                PgColumn column = columns.get(columnIndex);
                if (column.role() == PgColumn.Role.VERSION) {
                    continue;
                }
                Object expectedValue = entityColumnValue(plan, expected, column, columnIndex);
                Object actualValue = entityColumnValue(plan, actual, column, columnIndex);
                if (!Objects.equals(actualValue, expectedValue)) {
                    throw new IllegalStateException("Updated snapshot differs from its validated input");
                }
            }
            verifyVersionTransition(plan, expectedVersion, actualVersion);
            return new MutationResult.Applied<>(plan.key(expectedKey), expectedVersion, actualVersion, actual);
        } catch (RuntimeException failure) {
            throw invariant("Database returned an unexpected updated " + plan.logicalName() + " snapshot", failure);
        }
    }

    private <E, K, V> void verifyIndexPredicate(
            PgPlan<M, E, K, T> plan,
            E entity,
            PgIndexScan<M, E, K, V> scan,
            PgColumn indexedColumn) {
        try {
            Object actual = plan.columnValue(entity, scan.index().columnIndex());
            indexedColumn.validateValue(actual);
            if (scan.predicate() == PgIndexScan.Predicate.IS_NULL) {
                if (actual != null) {
                    throw new IllegalStateException("IS NULL index query returned a non-null value");
                }
            } else if (actual == null || !actual.equals(scan.value())) {
                throw new IllegalStateException("Equality index query returned a different value");
            }
        } catch (RuntimeException failure) {
            throw invariant("Database returned a row outside its generated index predicate", failure);
        }
    }

    private <E, K> E readEntity(PgPlan<M, E, K, T> plan, ResultSet resultSet, int firstColumn)
            throws SQLException {
        try {
            List<PgColumn> columns = plan.columns();
            Object[] columnValues = new Object[columns.size()];
            for (int index = 0; index < columns.size(); index++) {
                PgColumn column = columns.get(index);
                Object value = column.codec().read(resultSet, firstColumn + index);
                column.validateValue(value);
                columnValues[index] = value;
            }
            return plan.instantiate(columnValues);
        } catch (RuntimeException failure) {
            throw invariant("Generated row hydration failed for " + plan.logicalName(), failure);
        }
    }

    private <E, K> void validateDatabaseEntity(PgPlan<M, E, K, T> plan, E entity) {
        validateEntity(plan, entity, true);
        if (plan instanceof PgVersionPlan<?, ?, ?, ?, ?> rawVersionedPlan) {
            @SuppressWarnings("unchecked")
            PgVersionPlan<M, ?, ?, T, ?> versionedPlan =
                    (PgVersionPlan<M, ?, ?, T, ?>) rawVersionedPlan;
            Object version = Objects.requireNonNull(versionOf(versionedPlan, entity), "entity version");
            if (!versionedPlan.versionType().isInstance(version)
                    || !(version instanceof Number number)
                    || number.longValue() < 0L) {
                throw new IllegalStateException("Database returned an invalid version for " + plan.logicalName());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Object versionOf(PgVersionPlan<M, ?, ?, T, ?> plan, Object entity) {
        PgVersionPlan<M, Object, Object, T, Object> typedPlan =
                (PgVersionPlan<M, Object, Object, T, Object>) plan;
        return typedPlan.versionOf(entity);
    }

    private void bindTenant(PgPlan<M, ?, ?, T> plan, PreparedStatement statement, int index) throws SQLException {
        bindUnknown(plan.tenantCodec(), statement, index, tenant.tenantId());
    }

    private <E, K> void bindInsert(PgPlan<M, E, K, T> plan, PreparedStatement statement, E entity)
            throws SQLException {
        int parameter = 1;
        List<PgColumn> columns = plan.columns();
        for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
            PgColumn column = columns.get(columnIndex);
            bindEntityColumn(plan, statement, parameter++, entity, column, columnIndex);
        }
    }

    private <E, K> int bindEntityArrays(
            PgPlan<M, E, K, T> plan,
            Batch<E> entities,
            JdbcArrays arrays,
            PreparedStatement statement) throws SQLException {
        List<PgColumn> columns = plan.columns();
        for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
            PgColumn column = columns.get(columnIndex);
            Object[] values = new Object[entities.size()];
            for (int entityIndex = 0; entityIndex < entities.size(); entityIndex++) {
                Object value = entityColumnValue(plan, entities.get(entityIndex), column, columnIndex);
                column.validateValue(value);
                values[entityIndex] = column.codec().arrayElement(value);
            }
            arrays.bind(statement, columnIndex + 1, column.codec(), values);
        }
        return columns.size() + 1;
    }

    private <E, K, V> void bindUpdate(
            PgVersionPlan<M, E, K, T, V> plan,
            PreparedStatement statement,
            E entity) throws SQLException {
        int parameter = 1;
        List<PgColumn> columns = plan.columns();
        for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
            PgColumn column = columns.get(columnIndex);
            if (column.role() == PgColumn.Role.VALUE) {
                bindEntityColumn(plan, statement, parameter++, entity, column, columnIndex);
            }
        }
        K key = Objects.requireNonNull(plan.keyOf(entity), "entity key");
        V version = Objects.requireNonNull(plan.versionOf(entity), "entity version");
        bindUnknown(plan.keyCodec(), statement, parameter++, key);
        bindTenant(plan, statement, parameter++);
        bindUnknown(plan.versionCodec(), statement, parameter++, version);
        bindUnknown(plan.keyCodec(), statement, parameter++, key);
        bindTenant(plan, statement, parameter);
    }

    private <E, K> void bindEntityColumn(
            PgPlan<M, E, K, T> plan,
            PreparedStatement statement,
            int parameter,
            E entity,
            PgColumn column,
            int columnIndex) throws SQLException {
        Object value = switch (column.role()) {
            case ID -> plan.keyOf(entity);
            case TENANT -> tenant.tenantId();
            case VERSION -> versionOf((PgVersionPlan<M, ?, ?, T, ?>) plan, entity);
            case VALUE -> plan.columnValue(entity, columnIndex);
        };
        column.validateValue(value);
        bindUnknown(column.codec(), statement, parameter, value);
    }

    private <E, K> Object entityColumnValue(
            PgPlan<M, E, K, T> plan,
            E entity,
            PgColumn column,
            int columnIndex) {
        return switch (column.role()) {
            case ID -> plan.keyOf(entity);
            case TENANT -> plan.tenantKeyOf(entity);
            case VERSION -> versionOf((PgVersionPlan<M, ?, ?, T, ?>) plan, entity);
            case VALUE -> plan.columnValue(entity, columnIndex);
        };
    }

    @SuppressWarnings("unchecked")
    private static void bindUnknown(PgCodec<?> codec, PreparedStatement statement, int index, Object value)
            throws SQLException {
        if (value != null && value.getClass() != codec.javaType()) {
            throw new IllegalArgumentException("Value does not match the generated PostgreSQL codec");
        }
        ((PgCodec<Object>) codec).bind(statement, index, value);
    }

    private void requireNoMore(ResultSet resultSet, String message) throws SQLException {
        if (resultSet.next()) {
            throw invariant(message);
        }
    }

    private IllegalStateException invariant(String message) {
        return invariant(message, null);
    }

    private IllegalStateException invariant(String message, Throwable cause) {
        IllegalStateException failure = new IllegalStateException(message);
        guard.poison(failure);
        return failure;
    }

    private void poison(Throwable failure) {
        try {
            guard.poison(failure);
        } catch (IllegalStateException guardFailure) {
            if (guardFailure != failure) {
                failure.addSuppressed(new IllegalStateException("Vev transaction capability was already poisoned"));
            }
        }
    }

    private static final class JdbcArrays implements AutoCloseable {
        private final Connection connection;
        private final List<Array> arrays = new ArrayList<>();

        private JdbcArrays(Connection connection) {
            this.connection = Objects.requireNonNull(connection, "connection");
        }

        private void bind(
                PreparedStatement statement,
                int parameter,
                PgCodec<?> codec,
                Object[] values) throws SQLException {
            Array array = connection.createArrayOf(codec.jdbcType(), values);
            arrays.add(array);
            statement.setArray(parameter, array);
        }

        @Override
        public void close() throws SQLException {
            Throwable failure = null;
            for (int index = arrays.size() - 1; index >= 0; index--) {
                try {
                    arrays.get(index).free();
                } catch (SQLException | RuntimeException | Error closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else {
                        failure.addSuppressed(closeFailure);
                    }
                }
            }
            if (failure instanceof SQLException sqlFailure) {
                throw sqlFailure;
            }
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }
}
