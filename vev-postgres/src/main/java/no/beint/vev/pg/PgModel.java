package no.beint.vev.pg;

import no.beint.vev.EntityType;
import no.beint.vev.ModelIdentity;
import no.beint.vev.QueryLimit;
import no.beint.vev.VevIndex;
import no.beint.vev.VevModel;
import no.beint.vev.pg.spi.PgEntityPlan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validated, immutable PostgreSQL execution model compiled from generated entity plans.
 *
 * <p>Construction captures structural metadata, enforces Vev's bounded schema profile, and precompiles fixed SQL.
 * Applications normally use the model exposed by their generated {@link VevModel} registry. Structural validation
 * does not establish provenance or attest executable methods of a handwritten plan; only unmodified processor output
 * is inside the generated-plan safety profile.</p>
 *
 * @param <M> closed-model marker type
 * @param <T> tenant-key type shared by every entity
 */
public final class PgModel<M, T> {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    private static final long MAXIMUM_MATERIALIZED_RESULT_BYTES = 64L * 1_024L * 1_024L;
    private static final Set<String> DATABASE_TYPES = Set.of(
            "boolean", "integer", "bigint", "smallint", "character varying", "uuid", "numeric",
            "date", "timestamp", "timestamptz");
    private static final Set<Class<?>> KEY_TYPES = Set.of(
            Integer.class, Long.class, Short.class, String.class, java.util.UUID.class);
    private static final Set<PgCodec<?>> VERSION_CODECS = Set.of(
            PgCodecs.INTEGER, PgCodecs.LONG, PgCodecs.SHORT);

    private final ModelIdentity identity;
    private final Class<T> tenantType;
    private final Map<EntityType<M, ?, ?>, PgPlan<M, ?, ?, T>> plans;
    private final Map<Class<?>, PgPlan<M, ?, ?, T>> plansByJavaType;
    private final List<PgPlan<M, ?, ?, T>> orderedPlans;
    private final List<PgEntityPlan<M, ?, ?, T>> orderedSources;

    /**
     * Captures and validates the complete generated plan set for one closed model.
     *
     * @param identity generated identity shared by every entity plan
     * @param plans non-empty complete set of generated PostgreSQL plans
     */
    public PgModel(ModelIdentity identity, Collection<? extends PgEntityPlan<M, ?, ?, T>> plans) {
        this.identity = Objects.requireNonNull(identity, "identity");
        List<PgPlan<M, ?, ?, T>> snapshots = new ArrayList<>(VevModel.MAXIMUM_ENTITIES);
        for (PgEntityPlan<M, ?, ?, T> source : Objects.requireNonNull(plans, "plans")) {
            if (snapshots.size() == VevModel.MAXIMUM_ENTITIES) {
                throw new IllegalArgumentException(
                        "A Vev model must not exceed " + VevModel.MAXIMUM_ENTITIES + " entity plans");
            }
            snapshots.add(capture(Objects.requireNonNull(source, "plan")));
        }
        if (snapshots.isEmpty()) {
            throw new IllegalArgumentException("A Vev model must contain at least one entity plan");
        }
        snapshots.sort(Comparator.comparing((PgPlan<M, ?, ?, T> plan) -> plan.schemaName())
                .thenComparing(PgPlan::tableName)
                .thenComparing(PgPlan::logicalName));

        Map<EntityType<M, ?, ?>, PgPlan<M, ?, ?, T>> byIdentity = new IdentityHashMap<>();
        Map<Class<?>, PgPlan<M, ?, ?, T>> byJavaType = new java.util.HashMap<>();
        Set<String> mappedTables = new HashSet<>();
        Set<String> mappedIndexes = new HashSet<>();
        Set<String> logicalNames = new HashSet<>();
        Class<T> discoveredTenantType = null;
        for (PgPlan<M, ?, ?, T> plan : snapshots) {
            validatePlan(plan);
            plan.installSql(PgSql.compile(plan));
            if (!identity.equals(plan.modelIdentity())) {
                throw new IllegalArgumentException(
                        "Entity plan belongs to a different generated model: " + plan.logicalName());
            }
            if (byIdentity.put(plan.source(), plan) != null || byJavaType.put(plan.javaType(), plan) != null) {
                throw new IllegalArgumentException("Duplicate entity plan: " + plan.logicalName());
            }
            if (!logicalNames.add(plan.logicalName())) {
                throw new IllegalArgumentException("Duplicate entity logical name: " + plan.logicalName());
            }
            if (!mappedTables.add(plan.schemaName() + '.' + plan.tableName())) {
                throw new IllegalArgumentException("Multiple entity plans map the same PostgreSQL table: "
                        + plan.schemaName() + '.' + plan.tableName());
            }
            for (PgIndex<M, ?, ?, ?> index : plan.indexes()) {
                String qualifiedIndex = plan.schemaName() + '.' + index.indexName();
                if (!mappedIndexes.add(qualifiedIndex)) {
                    throw new IllegalArgumentException(
                            "Duplicate PostgreSQL index name in schema " + plan.schemaName() + ": "
                                    + index.indexName());
                }
            }
            if (discoveredTenantType == null) {
                discoveredTenantType = plan.tenantCodec().javaType();
            } else if (discoveredTenantType != plan.tenantCodec().javaType()) {
                throw new IllegalArgumentException("All entities in one Vev model must use the same tenant key type");
            }
        }
        for (String mappedIndex : mappedIndexes) {
            if (mappedTables.contains(mappedIndex)) {
                throw new IllegalArgumentException(
                        "Generated PostgreSQL index collides with a mapped relation: " + mappedIndex);
            }
        }
        this.tenantType = Objects.requireNonNull(discoveredTenantType, "tenantType");
        if (!KEY_TYPES.contains(tenantType)) {
            throw new IllegalArgumentException(
                    "Tenant keys require an equality-stable Integer, Long, Short, String, or UUID codec");
        }
        this.plans = Collections.unmodifiableMap(byIdentity);
        this.plansByJavaType = Map.copyOf(byJavaType);
        this.orderedPlans = List.copyOf(snapshots);
        List<PgEntityPlan<M, ?, ?, T>> sources = new ArrayList<>(snapshots.size());
        for (PgPlan<M, ?, ?, T> plan : snapshots) {
            sources.add(plan.source());
        }
        this.orderedSources = List.copyOf(sources);
    }

    @SuppressWarnings("unchecked")
    private static <M, T> PgPlan<M, ?, ?, T> capture(PgEntityPlan<M, ?, ?, T> source) {
        return (PgPlan<M, ?, ?, T>) PgPlan.capture(source);
    }

    private static void validatePlan(PgPlan<?, ?, ?, ?> plan) {
        if (plan.keyType() != plan.keyCodec().javaType()) {
            throw new IllegalArgumentException(
                    "Entity key type does not match its PostgreSQL codec: " + plan.logicalName());
        }
        requireIdentifier(plan.schemaName(), "schema", plan.logicalName());
        requireIdentifier(plan.tableName(), "table", plan.logicalName());
        requireIdentifier(plan.tenantColumn(), "tenant column", plan.logicalName());
        List<PgColumn> columns = plan.columns();
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Entity plan has no mapped columns: " + plan.logicalName());
        }
        if (columns.size() > VevModel.MAXIMUM_COLUMNS) {
            throw new IllegalArgumentException(
                    "Entity plan exceeds Vev's " + VevModel.MAXIMUM_COLUMNS
                            + "-column safety bound: " + plan.logicalName());
        }
        Set<String> names = new HashSet<>();
        long maximumRowBytes = Math.addExact(128L, Math.multiplyExact(16L, columns.size()));
        PgColumn id = null;
        PgColumn tenant = null;
        PgColumn version = null;
        for (PgColumn column : columns) {
            maximumRowBytes = Math.addExact(maximumRowBytes, column.maximumRetainedBytes());
            if (!names.add(column.name())) {
                throw new IllegalArgumentException(
                        "Duplicate mapped column " + column.name() + " for " + plan.logicalName());
            }
            if (!DATABASE_TYPES.contains(column.codec().databaseType())) {
                throw new IllegalArgumentException(
                        "Unsafe PostgreSQL type for " + plan.logicalName() + '.' + column.name());
            }
            if (!PgCodecs.isStandard(column.codec())) {
                throw new IllegalArgumentException("Entity plans may use only Vev's non-extensible PostgreSQL codecs");
            }
            switch (column.role()) {
                case ID -> id = uniqueRole(id, column, plan, "ID");
                case TENANT -> tenant = uniqueRole(tenant, column, plan, "tenant");
                case VERSION -> version = uniqueRole(version, column, plan, "version");
                case VALUE -> {
                }
            }
        }
        long maximumPageBytes = Math.multiplyExact(maximumRowBytes, Math.addExact(QueryLimit.MAX_VALUE, 1));
        if (maximumPageBytes > MAXIMUM_MATERIALIZED_RESULT_BYTES) {
            throw new IllegalArgumentException(
                    "Entity plan can exceed Vev's 64 MiB materialized-result safety budget: " + plan.logicalName());
        }
        if (id == null || tenant == null) {
            throw new IllegalArgumentException(
                    "Entity plan must have exactly one ID and tenant column: " + plan.logicalName());
        }
        if (id.codec() != plan.keyCodec() || id.codec().javaType() != plan.keyType()) {
            throw new IllegalArgumentException(
                    "Entity ID metadata does not match its key codec: " + plan.logicalName());
        }
        if (!KEY_TYPES.contains(id.codec().javaType())) {
            throw new IllegalArgumentException(
                    "Entity IDs require an equality-stable Integer, Long, Short, String, or UUID codec");
        }
        if (tenant.codec() != plan.tenantCodec() || !tenant.name().equals(plan.tenantColumn())) {
            throw new IllegalArgumentException(
                    "Entity tenant metadata does not match its tenant codec: " + plan.logicalName());
        }
        if (id.codec() == PgCodecs.STRING && id.maximumLength() != 128) {
            throw new IllegalArgumentException("String entity IDs must declare an exact 128-character bound");
        }
        if (tenant.codec() == PgCodecs.STRING && tenant.maximumLength() != 128) {
            throw new IllegalArgumentException("String tenant keys must declare an exact 128-character bound");
        }
        if (plan instanceof PgVersionPlan<?, ?, ?, ?, ?> versionedPlan) {
            if (version == null
                    || versionedPlan.versionType() != versionedPlan.versionCodec().javaType()
                    || version.codec() != versionedPlan.versionCodec()
                    || !VERSION_CODECS.contains(versionedPlan.versionCodec())) {
                throw new IllegalArgumentException(
                        "Entity version metadata requires Vev's Integer, Long, or Short codec: "
                                + plan.logicalName());
            }
        } else if (version != null) {
            throw new IllegalArgumentException(
                    "Append-only entity plan must not expose a version column: " + plan.logicalName());
        }
        validateIndexes(plan, id, tenant);
    }

    private static void validateIndexes(PgPlan<?, ?, ?, ?> plan, PgColumn id, PgColumn tenant) {
        Set<String> names = new HashSet<>();
        Set<Integer> indexedColumns = new HashSet<>();
        for (PgIndex<?, ?, ?, ?> index : plan.indexes()) {
            requireIdentifier(index.indexName(), "index", plan.logicalName());
            if (!names.add(index.indexName())) {
                throw new IllegalArgumentException(
                        "Duplicate generated index name for " + plan.logicalName() + ": " + index.indexName());
            }
            if (index.entityPlan() != plan.source()) {
                throw new IllegalArgumentException(
                        "Generated index does not belong to its captured entity plan: " + index.indexName());
            }
            int columnIndex = index.columnIndex();
            if (columnIndex < 0 || columnIndex >= plan.columns().size() || !indexedColumns.add(columnIndex)) {
                throw new IllegalArgumentException(
                        "Generated index has an invalid or duplicate component position: " + index.indexName());
            }
            PgColumn value = plan.columns().get(columnIndex);
            if (value.role() != PgColumn.Role.VALUE) {
                throw new IllegalArgumentException(
                        "Generated indexes may target only ordinary value columns: " + index.indexName());
            }
            if (index.valueType() != value.codec().javaType()) {
                throw new IllegalArgumentException(
                        "Generated index value type does not match its PostgreSQL codec: " + index.indexName());
            }
            boolean nullableToken = index instanceof PgNullableIndex<?, ?, ?, ?>;
            if (nullableToken != value.nullable()) {
                throw new IllegalArgumentException(
                        "Generated index nullability does not match its mapped column: " + index.indexName());
            }
            if (value.codec() == PgCodecs.STRING
                    && value.maximumLength() > VevIndex.MAXIMUM_STRING_LENGTH) {
                throw new IllegalArgumentException(
                        "Indexed String columns must not exceed " + VevIndex.MAXIMUM_STRING_LENGTH
                                + " code points: " + index.indexName());
            }
            long maximumKeyBytes = Math.addExact(
                    Math.addExact(maximumIndexKeyBytes(id), maximumIndexKeyBytes(tenant)),
                    maximumIndexKeyBytes(value));
            if (maximumKeyBytes > VevIndex.MAXIMUM_RETAINED_KEY_BYTES) {
                throw new IllegalArgumentException(
                        "Generated index can exceed Vev's conservative B-tree key budget: " + index.indexName());
            }
        }
    }

    private static long maximumIndexKeyBytes(PgColumn column) {
        if (column.codec() == PgCodecs.STRING) {
            return Math.multiplyExact(4L, column.maximumLength());
        }
        if (column.codec() == PgCodecs.BIG_DECIMAL) {
            return Math.addExact(64L, Math.multiplyExact(2L, column.numericPrecision()));
        }
        return 64L;
    }

    private static PgColumn uniqueRole(
            PgColumn existing, PgColumn candidate, PgPlan<?, ?, ?, ?> plan, String role) {
        if (existing != null) {
            throw new IllegalArgumentException(
                    "Entity plan has multiple " + role + " columns: " + plan.logicalName());
        }
        return candidate;
    }

    private static void requireIdentifier(String value, String label, String entityName) {
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException("Unsafe generated " + label + " for " + entityName);
        }
    }

    /**
     * Captures and validates a varargs plan set for one closed model.
     *
     * @param identity generated identity shared by every entity plan
     * @param plans non-empty complete set of generated PostgreSQL plans
     * @param <M> closed-model marker type
     * @param <T> tenant-key type shared by every entity
     * @return validated immutable PostgreSQL model
     */
    @SafeVarargs
    public static <M, T> PgModel<M, T> of(ModelIdentity identity, PgEntityPlan<M, ?, ?, T>... plans) {
        Objects.requireNonNull(plans, "plans");
        if (plans.length > VevModel.MAXIMUM_ENTITIES) {
            throw new IllegalArgumentException(
                    "A Vev model must not exceed " + VevModel.MAXIMUM_ENTITIES + " entity plans");
        }
        List<PgEntityPlan<M, ?, ?, T>> copy = new ArrayList<>(plans.length);
        for (PgEntityPlan<M, ?, ?, T> plan : plans) {
            copy.add(plan);
        }
        return new PgModel<>(identity, copy);
    }

    /**
     * Returns the generated identity verified for this model.
     *
     * @return closed-model identity
     */
    public ModelIdentity identity() {
        return identity;
    }

    /**
     * Returns the tenant-key type shared by every plan.
     *
     * @return exact boxed tenant-key type
     */
    public Class<T> tenantType() {
        return tenantType;
    }

    /**
     * Returns the generated source plans in canonical schema, table, and logical-name order.
     *
     * @return immutable complete plan collection
     */
    public Collection<PgEntityPlan<M, ?, ?, T>> plans() {
        return orderedSources;
    }

    List<PgPlan<M, ?, ?, T>> frozenPlans() {
        return orderedPlans;
    }

    /**
     * Finds the plan for an exact entity snapshot class in this closed model.
     *
     * @param entityType exact entity snapshot class
     * @return generated plan for that class
     * @throws IllegalArgumentException when the class is outside this model
     */
    public PgEntityPlan<M, ?, ?, T> plan(Class<?> entityType) {
        return frozenPlan(entityType).source();
    }

    PgPlan<M, ?, ?, T> frozenPlan(Class<?> entityType) {
        PgPlan<M, ?, ?, T> plan = plansByJavaType.get(Objects.requireNonNull(entityType, "entityType"));
        if (plan == null) {
            throw new IllegalArgumentException("Entity is not part of the closed Vev model: " + entityType.getName());
        }
        return plan;
    }

    /**
     * Resolves a generated entity type by identity within this closed model.
     *
     * @param entityType generated entity type owned by this model
     * @param <E> entity snapshot type
     * @param <K> primary-key type
     * @return the corresponding generated PostgreSQL plan
     * @throws IllegalArgumentException when the entity type is not owned by this model
     */
    public <E, K> PgEntityPlan<M, E, K, T> plan(EntityType<M, E, K> entityType) {
        return frozenPlan(entityType).source();
    }

    @SuppressWarnings("unchecked")
    <E, K> PgPlan<M, E, K, T> frozenPlan(EntityType<M, E, K> entityType) {
        Objects.requireNonNull(entityType, "entityType");
        PgPlan<M, ?, ?, T> plan = plans.get(entityType);
        if (plan == null) {
            throw new IllegalArgumentException(
                    "Entity type is not from this generated Vev model: " + entityType.logicalName());
        }
        return (PgPlan<M, E, K, T>) plan;
    }
}
