package no.beint.vev.pg;

import no.beint.vev.EntityKey;
import no.beint.vev.ModelIdentity;
import no.beint.vev.VevIndex;
import no.beint.vev.VevModel;
import no.beint.vev.pg.spi.PgEntityPlan;
import no.beint.vev.pg.spi.PgVersionedEntityPlan;
import no.beint.vev.pg.spi.PgGeneratedEntityPlan;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

class PgPlan<M, E, K, T> {
    private final PgEntityPlan<M, E, K, T> source;
    private final Class<E> javaType;
    private final Class<K> keyType;
    private final String logicalName;
    private final ModelIdentity modelIdentity;
    private final int maximumRows;
    private final PgCodec<K> keyCodec;
    private final PgCodec<T> tenantCodec;
    private final String schemaName;
    private final String tableName;
    private final String tenantColumn;
    private final List<PgColumn> columns;
    private final List<PgIndex<M, E, K, ?>> indexes;
    private final List<PgReference> references;
    private final List<PgUnique> uniqueConstraints;
    private final List<PgCheck> checkConstraints;
    private final Class<?> creationType;
    private final no.beint.vev.VevPrimaryKey.Shape primaryKeyShape;
    private final Map<PgIndex<M, E, K, ?>, PgIndexSql> indexSql;
    private PgSql sql;
    private String creationSql;

    PgPlan(PgEntityPlan<M, E, K, T> source) {
        this.source = Objects.requireNonNull(source, "source");
        int generatedAbi = source.generatedPlanAbi();
        if (generatedAbi != PgEntityPlan.ABI_VERSION) {
            throw new IllegalArgumentException("Generated PostgreSQL plan ABI mismatch: runtime requires "
                    + PgEntityPlan.ABI_VERSION + ", plan declares " + generatedAbi
                    + "; recompile mappings with matching Vev processor/runtime versions");
        }
        this.javaType = Objects.requireNonNull(source.javaType(), "javaType");
        this.keyType = Objects.requireNonNull(source.keyType(), "keyType");
        this.logicalName = Objects.requireNonNull(source.logicalName(), "logicalName");
        this.modelIdentity = Objects.requireNonNull(source.modelIdentity(), "modelIdentity");
        this.maximumRows = source.maximumRows();
        if (maximumRows < 1 || maximumRows > no.beint.vev.QueryLimit.MAX_VALUE || maximumRows > no.beint.vev.Batch.MAX_SIZE) {
            throw new IllegalArgumentException("Entity row limit must be between 1 and 1000");
        }
        this.keyCodec = Objects.requireNonNull(source.keyCodec(), "keyCodec");
        this.tenantCodec = Objects.requireNonNull(source.tenantCodec(), "tenantCodec");
        this.schemaName = Objects.requireNonNull(source.schemaName(), "schemaName");
        this.tableName = Objects.requireNonNull(source.tableName(), "tableName");
        this.tenantColumn = Objects.requireNonNull(source.tenantColumn(), "tenantColumn");
        this.primaryKeyShape = Objects.requireNonNull(source.primaryKeyShape(), "primaryKeyShape");
        this.creationType = source instanceof PgGeneratedEntityPlan<?, ?, ?, ?, ?> generated
                ? Objects.requireNonNull(generated.creationType(), "creationType") : null;
        if (creationType != null && source instanceof no.beint.vev.AssignedEntityType<?, ?, ?>) {
            throw new IllegalArgumentException("An entity cannot expose both assigned and generated identity insertion");
        }
        List<PgColumn> boundedColumns = new ArrayList<>(VevModel.MAXIMUM_COLUMNS);
        for (PgColumn column : Objects.requireNonNull(source.columns(), "columns")) {
            if (boundedColumns.size() == VevModel.MAXIMUM_COLUMNS) {
                throw new IllegalArgumentException(
                        "Entity plan exceeds Vev's " + VevModel.MAXIMUM_COLUMNS + "-column safety bound");
            }
            boundedColumns.add(Objects.requireNonNull(column, "column"));
        }
        this.columns = List.copyOf(boundedColumns);
        List<PgIndex<M, E, K, ?>> boundedIndexes = new ArrayList<>(VevIndex.MAXIMUM_INDEXES_PER_ENTITY);
        for (PgIndex<M, E, K, ?> index : Objects.requireNonNull(source.indexes(), "indexes")) {
            if (boundedIndexes.size() == VevIndex.MAXIMUM_INDEXES_PER_ENTITY) {
                throw new IllegalArgumentException("Entity plan exceeds Vev's "
                        + VevIndex.MAXIMUM_INDEXES_PER_ENTITY + "-index safety bound");
            }
            boundedIndexes.add(Objects.requireNonNull(index, "index"));
        }
        this.indexes = List.copyOf(boundedIndexes);
        List<PgReference> boundedReferences = new ArrayList<>();
        for (PgReference reference : Objects.requireNonNull(source.references(), "references")) {
            if (boundedReferences.size() == VevModel.MAXIMUM_COLUMNS) {
                throw new IllegalArgumentException("Entity plan exceeds the generated reference bound");
            }
            boundedReferences.add(Objects.requireNonNull(reference, "reference"));
        }
        this.references = List.copyOf(boundedReferences);
        List<PgUnique> boundedUnique = new ArrayList<>();
        for (PgUnique unique : Objects.requireNonNull(source.uniqueConstraints(), "uniqueConstraints")) {
            if (boundedUnique.size() + indexes.size() == VevIndex.MAXIMUM_INDEXES_PER_ENTITY) {
                throw new IllegalArgumentException("Unique constraints and query indexes exceed Vev's index bound");
            }
            boundedUnique.add(Objects.requireNonNull(unique, "uniqueConstraint"));
        }
        this.uniqueConstraints = List.copyOf(boundedUnique);
        List<PgCheck> boundedChecks = new ArrayList<>();
        for (PgCheck check : Objects.requireNonNull(source.checkConstraints(), "checkConstraints")) {
            if (boundedChecks.size() == PgCheck.MAXIMUM_PER_ENTITY) {
                throw new IllegalArgumentException("Entity plan exceeds Vev's check-constraint bound");
            }
            boundedChecks.add(Objects.requireNonNull(check, "checkConstraint"));
        }
        this.checkConstraints = List.copyOf(boundedChecks);
        this.indexSql = new IdentityHashMap<>();
    }

    List<PgReference> references() {
        return references;
    }

    boolean generatedIdentity() {
        return creationType != null;
    }

    no.beint.vev.VevPrimaryKey.Shape primaryKeyShape() {
        return primaryKeyShape;
    }

    List<String> primaryKeyColumns() {
        String id = columns.stream().filter(column -> column.role() == PgColumn.Role.ID).findFirst().orElseThrow().name();
        return switch (primaryKeyShape) {
            case TENANT_ID -> List.of(tenantColumn, id);
            case ID_TENANT -> List.of(id, tenantColumn);
            case ID -> List.of(id);
        };
    }

    boolean tenantIdentityUnique(PgUnique unique) {
        if (unique.columnIndexes().size() != 2) return false;
        if (unique.columnIndexes().get(0) >= columns.size() || unique.columnIndexes().get(1) >= columns.size()) return false;
        PgColumn first = columns.get(unique.columnIndexes().get(0));
        PgColumn second = columns.get(unique.columnIndexes().get(1));
        return first.role() == PgColumn.Role.ID && second.role() == PgColumn.Role.TENANT
                || first.role() == PgColumn.Role.TENANT && second.role() == PgColumn.Role.ID;
    }

    Class<?> creationType() {
        return creationType;
    }

    @SuppressWarnings("unchecked")
    Object creationColumnValue(Object input, int columnIndex) {
        return ((PgGeneratedEntityPlan<M, E, K, T, Object>) source).creationColumnValue(input, columnIndex);
    }

    List<PgUnique> uniqueConstraints() {
        return uniqueConstraints;
    }

    List<PgCheck> checkConstraints() {
        return checkConstraints;
    }

    static PgPlan<?, ?, ?, ?> capture(PgEntityPlan<?, ?, ?, ?> source) {
        if (source instanceof PgVersionedEntityPlan<?, ?, ?, ?, ?> versioned) {
            return captureVersioned(versioned);
        }
        return capturePlain(source);
    }

    private static <M, E, K, T> PgPlan<M, E, K, T> capturePlain(PgEntityPlan<M, E, K, T> source) {
        return new PgPlan<>(source);
    }

    private static <M, E, K, T, V> PgVersionPlan<M, E, K, T, V> captureVersioned(
            PgVersionedEntityPlan<M, E, K, T, V> source) {
        return new PgVersionPlan<>(source);
    }

    PgEntityPlan<M, E, K, T> source() {
        return source;
    }

    Class<E> javaType() {
        return javaType;
    }

    Class<K> keyType() {
        return keyType;
    }

    String logicalName() {
        return logicalName;
    }

    ModelIdentity modelIdentity() {
        return modelIdentity;
    }

    int maximumRows() {
        return maximumRows;
    }

    void requireRowCount(int count) {
        if (count < 0 || count > maximumRows) {
            throw new IllegalArgumentException("Entity batch or query page exceeds its generated " + maximumRows + "-row bound");
        }
    }

    PgCodec<K> keyCodec() {
        return keyCodec;
    }

    PgCodec<T> tenantCodec() {
        return tenantCodec;
    }

    String schemaName() {
        return schemaName;
    }

    String tableName() {
        return tableName;
    }

    String tenantColumn() {
        return tenantColumn;
    }

    List<PgColumn> columns() {
        return columns;
    }

    List<PgIndex<M, E, K, ?>> indexes() {
        return indexes;
    }

    Object columnValue(E entity, int columnIndex) {
        return source.columnValue(entity, columnIndex);
    }

    E readRow(java.sql.ResultSet resultSet, int firstColumn) throws java.sql.SQLException {
        return source.readRow(resultSet, firstColumn);
    }

    K keyOf(E entity) {
        return source.keyOf(entity);
    }

    T tenantKeyOf(E entity) {
        return source.tenantKeyOf(entity);
    }

    EntityKey<M, E, K> key(K value) {
        return source.key(value);
    }

    void installSql(PgSql compiledSql) {
        if (sql != null) {
            throw new IllegalStateException("PostgreSQL SQL was already compiled for " + logicalName);
        }
        sql = Objects.requireNonNull(compiledSql, "compiledSql");
        creationSql = generatedIdentity() ? PgCreationSql.compile(this) : null;
        for (PgIndex<M, E, K, ?> index : indexes) {
            indexSql.put(index, compiledSql.index(index));
        }
    }

    PgSql sql() {
        return Objects.requireNonNull(sql, "sql");
    }

    String creationSql() {
        return Objects.requireNonNull(creationSql, "creationSql");
    }

    PgIndexSql indexSql(PgIndex<M, E, K, ?> index) {
        PgIndexSql statements = indexSql.get(Objects.requireNonNull(index, "index"));
        if (statements == null) {
            throw new IllegalArgumentException("Index token is not from this generated Vev model");
        }
        return statements;
    }
}
