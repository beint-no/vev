package no.beint.vev.processor;

import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import javax.annotation.processing.FilerException;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;

final class MappingCompiler {
    private static final int MAXIMUM_ENTITIES = 128;
    private static final int MAXIMUM_COLUMNS = 64;
    private static final long MAXIMUM_MATERIALIZED_RESULT_BYTES = 64L * 1_024L * 1_024L;
    private static final String ENTITY = "jakarta.persistence.Entity";
    private static final String TABLE = "jakarta.persistence.Table";
    private static final String ID = "jakarta.persistence.Id";
    private static final String COLUMN = "jakarta.persistence.Column";
    private static final String VERSION = "jakarta.persistence.Version";
    private static final String GENERATED_VALUE = "jakarta.persistence.GeneratedValue";
    private static final String TENANT_KEY = "no.beint.vev.TenantKey";
    private static final String APPEND_ONLY = "no.beint.vev.AppendOnly";
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,62}");
    private static final Set<String> ASSOCIATIONS = Set.of(
            "jakarta.persistence.OneToOne",
            "jakarta.persistence.OneToMany",
            "jakarta.persistence.ManyToOne",
            "jakarta.persistence.ManyToMany",
            "jakarta.persistence.ElementCollection",
            "jakarta.persistence.Embedded",
            "jakarta.persistence.EmbeddedId");
    private static final Set<String> CALLBACKS = Set.of(
            "jakarta.persistence.PrePersist",
            "jakarta.persistence.PostPersist",
            "jakarta.persistence.PreUpdate",
            "jakarta.persistence.PostUpdate",
            "jakarta.persistence.PreRemove",
            "jakarta.persistence.PostRemove",
            "jakarta.persistence.PostLoad",
            "jakarta.persistence.EntityListeners");
    private static final Set<String> INHERITANCE = Set.of(
            "jakarta.persistence.Inheritance",
            "jakarta.persistence.DiscriminatorColumn",
            "jakarta.persistence.DiscriminatorValue",
            "jakarta.persistence.MappedSuperclass",
            "jakarta.persistence.SecondaryTable",
            "jakarta.persistence.SecondaryTables",
            "jakarta.persistence.IdClass");
    private static final Set<String> STRING_QUERIES = Set.of(
            "jakarta.persistence.NamedQuery",
            "jakarta.persistence.NamedQueries",
            "jakarta.persistence.NamedNativeQuery",
            "jakarta.persistence.NamedNativeQueries",
            "jakarta.persistence.StaticQuery",
            "jakarta.persistence.StaticQueries",
            "jakarta.persistence.StaticNativeQuery",
            "jakarta.persistence.StaticNativeQueries");
    private static final Map<String, CodecMapping> CODECS = codecs();
    private static final Set<String> KEY_TYPES = Set.of(
            "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.String", "java.util.UUID");
    private static final Map<String, Set<String>> ANNOTATION_MEMBERS = Map.of(
            VevProcessor.VEV_MODEL, Set.of("entities"),
            ENTITY, Set.of("name"),
            TABLE, Set.of("name", "catalog", "schema", "uniqueConstraints", "indexes", "check", "comment", "type", "options"),
            ID, Set.of(),
            COLUMN, Set.of("name", "unique", "nullable", "insertable", "updatable", "columnDefinition", "options",
                    "table", "length", "precision", "scale", "secondPrecision", "check", "comment"),
            VERSION, Set.of(),
            GENERATED_VALUE, Set.of("strategy", "generator"),
            TENANT_KEY, Set.of(),
            APPEND_ONLY, Set.of());

    private final ProcessingEnvironment processingEnvironment;
    private final Messager messager;
    private final Set<String> claimedEntities;
    private final Set<String> sourceTypes;
    private final Trees trees;
    private boolean invalid;

    MappingCompiler(
            ProcessingEnvironment processingEnvironment,
            Set<String> claimedEntities,
            Set<String> sourceTypes) {
        this.processingEnvironment = processingEnvironment;
        this.messager = processingEnvironment.getMessager();
        this.claimedEntities = claimedEntities;
        this.sourceTypes = sourceTypes;
        this.trees = compilerTrees(processingEnvironment);
    }

    void compile(TypeElement modelDeclaration) {
        invalid = false;
        validateTopLevelPublicType(modelDeclaration, "@VevModel declaration");
        AnnotationMirror modelAnnotation = annotation(modelDeclaration, VevProcessor.VEV_MODEL);
        if (modelAnnotation == null) {
            return;
        }
        validateAnnotationShape(modelDeclaration, modelAnnotation);
        validateStableModelName(modelDeclaration);
        List<TypeElement> entityDeclarations = entityDeclarations(modelDeclaration, modelAnnotation);
        entityDeclarations.sort(Comparator.comparing(entity -> entity.getQualifiedName().toString()));
        String modelPackage = packageName(modelDeclaration);
        String modelSimpleName = modelDeclaration.getSimpleName() + "Vev";
        String modelQualifiedName = qualify(modelPackage, modelSimpleName);
        rejectGeneratedTypeCollision(modelDeclaration, modelQualifiedName);

        List<EntityMapping> entities = new ArrayList<>();
        for (TypeElement entityDeclaration : entityDeclarations) {
            entities.add(compileEntity(entityDeclaration, modelQualifiedName));
        }
        entities.removeIf(Objects::isNull);
        validateSingleTenantType(modelDeclaration, entities);
        validateUniqueTables(modelDeclaration, entities);
        if (invalid) {
            return;
        }
        String fingerprint = fingerprint(modelDeclaration.getQualifiedName().toString(), entities);
        CompiledModel model = new CompiledModel(
                modelDeclaration,
                modelPackage,
                modelSimpleName,
                modelDeclaration.getQualifiedName().toString(),
                List.copyOf(entities),
                fingerprint);
        JavaSourceGenerator generator = new JavaSourceGenerator();
        for (EntityMapping entity : entities) {
            writeSource(entity.planQualifiedName(), generator.entityPlan(entity), entity.declaration());
        }
        writeSource(modelQualifiedName, generator.modelRegistry(model), modelDeclaration);
    }

    private List<TypeElement> entityDeclarations(TypeElement modelDeclaration, AnnotationMirror modelAnnotation) {
        AnnotationValue entitiesValue = annotationValue(modelAnnotation, "entities");
        if (entitiesValue == null || !(entitiesValue.getValue() instanceof List<?> values) || values.isEmpty()) {
            error(modelDeclaration, "@VevModel must declare at least one entity in its closed entities set");
            return new ArrayList<>();
        }
        if (values.size() > MAXIMUM_ENTITIES) {
            error(modelDeclaration, "@VevModel must not exceed " + MAXIMUM_ENTITIES + " entities");
            return new ArrayList<>();
        }
        Map<String, TypeElement> declarations = new LinkedHashMap<>();
        int encountered = 0;
        for (Object item : values) {
            if (encountered++ == MAXIMUM_ENTITIES) {
                error(modelDeclaration, "@VevModel must not exceed " + MAXIMUM_ENTITIES + " entities");
                return new ArrayList<>();
            }
            if (!(item instanceof AnnotationValue annotationValue)
                    || !(annotationValue.getValue() instanceof TypeMirror entityType)) {
                error(modelDeclaration, "@VevModel entities must be concrete entity classes");
                continue;
            }
            Element element = processingEnvironment.getTypeUtils().asElement(entityType);
            if (!(element instanceof TypeElement entityDeclaration)) {
                error(modelDeclaration, "@VevModel entities must resolve to declared entity classes");
                continue;
            }
            String qualifiedName = entityDeclaration.getQualifiedName().toString();
            if (declarations.putIfAbsent(qualifiedName, entityDeclaration) != null) {
                error(modelDeclaration, "@VevModel contains duplicate entity " + qualifiedName);
            } else if (!claimedEntities.add(qualifiedName)) {
                error(modelDeclaration, "Entity " + qualifiedName + " is already owned by another closed @VevModel");
            }
        }
        return new ArrayList<>(declarations.values());
    }

    private EntityMapping compileEntity(TypeElement entity, String modelQualifiedName) {
        if (!sourceTypes.contains(entity.getQualifiedName().toString())) {
            error(entity, "Every Vev entity must be compiled from source in the same javac invocation as its closed model");
        }
        validateTopLevelPublicType(entity, "Entity");
        if (entity.getKind() != ElementKind.RECORD) {
            error(entity, "Vev entities must be immutable Java records so hydration uses the canonical constructor without reflection");
            return null;
        }
        List<? extends RecordComponentElement> components = entity.getRecordComponents();
        if (components.isEmpty()) {
            error(entity, "Vev entity records must declare persistent components");
            return null;
        }
        if (components.size() > MAXIMUM_COLUMNS) {
            error(entity, "Vev entity records must not exceed " + MAXIMUM_COLUMNS + " mapped components");
            return null;
        }
        rejectImplementedInterfaces(entity);
        rejectExplicitCanonicalConstructor(entity);
        rejectInitializationSideEffects(entity);
        if (annotation(entity, ENTITY) == null) {
            error(entity, "Entity in @VevModel must declare @jakarta.persistence.Entity");
        } else if (!stringValue(annotation(entity, ENTITY), "name").isEmpty()) {
            error(entity, "@Entity.name is forbidden because Vev has no string-based entity query namespace");
        }
        AnnotationMirror table = annotation(entity, TABLE);
        String tableName = table == null ? "" : stringValue(table, "name");
        String schemaName = table == null ? "" : stringValue(table, "schema");
        String catalogName = table == null ? "" : stringValue(table, "catalog");
        if (table == null || tableName.isBlank()) {
            error(entity, "Implicit table names are forbidden; declare @Table(name = \"...\")");
        } else {
            validateIdentifier(entity, tableName, "table");
        }
        if (schemaName.isBlank()) {
            error(entity, "Implicit schemas and search_path are forbidden; declare @Table(schema = \"...\")");
        } else {
            validateIdentifier(entity, schemaName, "schema");
        }
        if (!catalogName.isEmpty()) {
            error(entity, "PostgreSQL catalogs cannot be selected per entity; @Table.catalog must be empty");
        }
        rejectNonEmptyList(entity, table, "uniqueConstraints", "@Table.uniqueConstraints");
        rejectNonEmptyList(entity, table, "indexes", "@Table.indexes");
        rejectNonEmptyList(entity, table, "check", "@Table.check");
        rejectNonEmptyString(entity, table, "comment", "@Table.comment");
        rejectNonEmptyString(entity, table, "type", "@Table.type");
        rejectNonEmptyString(entity, table, "options", "@Table.options");
        scanTypeAnnotations(entity);

        List<PropertyMapping> properties = new ArrayList<>();
        Map<String, PropertyMapping> columns = new HashMap<>();
        for (RecordComponentElement component : components) {
            PropertyMapping property = compileProperty(entity, component);
            if (property != null) {
                PropertyMapping duplicate = columns.putIfAbsent(property.columnName(), property);
                if (duplicate != null) {
                    error(component, "Duplicate explicit column name \"" + property.columnName() + "\"");
                }
                properties.add(property);
            }
        }
        validateMaterializedResultBudget(entity, properties);

        List<PropertyMapping> ids = properties.stream().filter(PropertyMapping::id).toList();
        List<PropertyMapping> tenants = properties.stream().filter(PropertyMapping::tenant).toList();
        List<PropertyMapping> versions = properties.stream().filter(PropertyMapping::version).toList();
        if (ids.isEmpty()) {
            error(entity, "Every Vev entity must declare exactly one @Id; implicit or embedded identifiers are forbidden");
        } else if (ids.size() > 1) {
            error(entity, "Compound identifiers are forbidden; declare exactly one scalar @Id");
        }
        if (tenants.isEmpty()) {
            error(entity, "Every Vev entity must declare exactly one @TenantKey");
        } else if (tenants.size() > 1) {
            error(entity, "Every Vev entity must declare exactly one @TenantKey, but found " + tenants.size());
        }
        boolean appendOnly = annotation(entity, APPEND_ONLY) != null;
        if (appendOnly && !versions.isEmpty()) {
            error(entity, "@AppendOnly entities must not declare @Version because update and delete plans do not exist");
        } else if (!appendOnly && versions.isEmpty()) {
            error(entity, "Mutable Vev entities require exactly one @Version; use @AppendOnly to opt out of mutation");
        } else if (versions.size() > 1) {
            error(entity, "Mutable Vev entities must declare exactly one @Version");
        }

        PropertyMapping id = ids.size() == 1 ? ids.getFirst() : null;
        PropertyMapping tenant = tenants.size() == 1 ? tenants.getFirst() : null;
        PropertyMapping version = versions.size() == 1 ? versions.getFirst() : null;
        if (id != null && id.nullable()) {
            error(id.declaration(), "Assigned identifier columns must declare @Column(nullable = false)");
        }
        if (id != null && !KEY_TYPES.contains(id.boxedType())) {
            error(id.declaration(), "@Id must use equality-stable Integer, Long, Short, String, or UUID semantics");
        }
        if (tenant != null && tenant.nullable()) {
            error(tenant.declaration(), "Tenant columns must declare @Column(nullable = false)");
        }
        if (tenant != null && !KEY_TYPES.contains(tenant.boxedType())) {
            error(tenant.declaration(), "@TenantKey must use equality-stable Integer, Long, Short, String, or UUID semantics");
        }
        if (version != null) {
            if (version.nullable()) {
                error(version.declaration(), "Version columns must declare @Column(nullable = false)");
            }
            if (!Set.of("java.lang.Integer", "java.lang.Long", "java.lang.Short").contains(version.boxedType())) {
                error(version.declaration(), "@Version must use Integer, Long, or Short for atomic PostgreSQL increment semantics");
            }
        }

        String entityPackage = packageName(entity);
        String planQualifiedName = qualify(entityPackage, entity.getSimpleName() + "Vev");
        rejectGeneratedTypeCollision(entity, planQualifiedName);
        if (invalid || id == null || tenant == null || (!appendOnly && version == null)
                || tableName.isBlank() || schemaName.isBlank()) {
            return null;
        }
        String tableSql = quote(schemaName) + "." + quote(tableName);
        return new EntityMapping(
                entity,
                entityPackage,
                entity.getSimpleName().toString(),
                entity.getQualifiedName().toString(),
                planQualifiedName,
                modelQualifiedName,
                schemaName,
                tableName,
                tableSql,
                List.copyOf(properties),
                id,
                tenant,
                version,
                appendOnly);
    }

    private PropertyMapping compileProperty(TypeElement entity, RecordComponentElement component) {
        rejectExplicitAccessor(entity, component);
        scanMemberAnnotations(entity, component);
        List<Element> annotationSources = componentSources(entity, component);
        AnnotationMirror column = consistentAnnotation(component, annotationSources, COLUMN);
        if (column == null || stringValue(column, "name").isBlank()) {
            error(component, "Implicit column names are forbidden; every record component must declare @Column(name = \"...\")");
            return null;
        }
        String columnName = stringValue(column, "name");
        validateIdentifier(component, columnName, "column");
        if (!stringValue(column, "table").isEmpty()) {
            error(component, "Per-column secondary tables are forbidden; @Column.table must be empty");
        }
        if (!booleanValue(column, "insertable") || !booleanValue(column, "updatable")) {
            error(component, "@Column.insertable and @Column.updatable must both remain true in immutable Vev snapshots");
        }
        if (booleanValue(column, "unique")) {
            error(component, "@Column.unique is forbidden; declare and review unique constraints in the database migration");
        }
        rejectNonEmptyString(component, column, "columnDefinition", "@Column.columnDefinition");
        rejectNonEmptyString(component, column, "options", "@Column.options");
        rejectNonEmptyString(component, column, "comment", "@Column.comment");
        rejectNonEmptyList(component, column, "check", "@Column.check");
        rejectNonDefaultInt(component, column, "secondPrecision", -1);
        boolean id = consistentAnnotation(component, annotationSources, ID) != null;
        boolean tenant = consistentAnnotation(component, annotationSources, TENANT_KEY) != null;
        boolean version = consistentAnnotation(component, annotationSources, VERSION) != null;
        AnnotationMirror generatedValue = consistentAnnotation(component, annotationSources, GENERATED_VALUE);
        if (generatedValue != null) {
            error(component,
                    "@GeneratedValue is forbidden because shared database generators leak cross-tenant activity; use an assigned stable identifier");
        }
        int roles = (id ? 1 : 0) + (tenant ? 1 : 0) + (version ? 1 : 0);
        if (roles > 1) {
            error(component, "@Id, @TenantKey, and @Version must identify distinct record components");
        }
        CodecMapping codec = CODECS.get(component.asType().toString());
        if (codec == null) {
            error(component, "No safe PostgreSQL codec exists for " + component.asType()
                    + "; supported scalar types are Boolean, Integer, Long, Short, String, UUID, BigDecimal, LocalDate, LocalDateTime, and Instant");
            return null;
        }
        boolean nullable = booleanValue(column, "nullable");
        if (component.asType().getKind().isPrimitive() && nullable) {
            error(component, "Primitive record components must declare @Column(nullable = false)");
        }
        int maximumLength = 0;
        int numericPrecision = 0;
        int numericScale = 0;
        if (codec.codec().endsWith(".STRING")) {
            maximumLength = intValue(column, "length");
            if (maximumLength < 1 || maximumLength > 65_535) {
                error(component, "String @Column.length must be between 1 and 65535");
            }
            if ((id || tenant) && maximumLength != 128) {
                error(component, "String @Id and @TenantKey columns must declare @Column(length = 128)");
            }
            rejectNonDefaultInt(component, column, "precision", 0);
            rejectNonDefaultInt(component, column, "scale", 0);
        } else if (codec.codec().endsWith(".BIG_DECIMAL")) {
            rejectNonDefaultInt(component, column, "length", 255);
            numericPrecision = intValue(column, "precision");
            numericScale = intValue(column, "scale");
            if (numericPrecision < 1 || numericPrecision > 128) {
                error(component, "BigDecimal @Column.precision must be between 1 and 128");
            }
            if (numericScale < 0 || numericScale > numericPrecision) {
                error(component, "BigDecimal @Column.scale must be between 0 and its precision");
            }
        } else {
            rejectNonDefaultInt(component, column, "length", 255);
            rejectNonDefaultInt(component, column, "precision", 0);
            rejectNonDefaultInt(component, column, "scale", 0);
        }
        return new PropertyMapping(
                component,
                component.getSimpleName().toString(),
                component.asType().toString(),
                codec.boxedType(),
                codec.codec(),
                codec.arrayElementType(),
                columnName,
                nullable,
                maximumLength,
                numericPrecision,
                numericScale,
                id,
                tenant,
                version);
    }

    private void validateMaterializedResultBudget(TypeElement entity, List<PropertyMapping> properties) {
        long maximumRowBytes = Math.addExact(128L, Math.multiplyExact(16L, properties.size()));
        for (PropertyMapping property : properties) {
            long maximumColumnBytes;
            if (property.maximumLength() > 0) {
                maximumColumnBytes = Math.addExact(64L, Math.multiplyExact(4L, property.maximumLength()));
            } else if (property.numericPrecision() > 0) {
                maximumColumnBytes = Math.addExact(64L, Math.multiplyExact(2L, property.numericPrecision()));
            } else {
                maximumColumnBytes = 64L;
            }
            maximumRowBytes = Math.addExact(maximumRowBytes, maximumColumnBytes);
        }
        if (Math.multiplyExact(maximumRowBytes, 1_001L) > MAXIMUM_MATERIALIZED_RESULT_BYTES) {
            error(entity, "Mapped row shape can exceed Vev's 64 MiB materialized-result safety budget");
        }
    }

    private void validateSingleTenantType(TypeElement model, List<EntityMapping> entities) {
        Set<String> tenantTypes = new HashSet<>();
        for (EntityMapping entity : entities) {
            tenantTypes.add(entity.tenant().boxedType());
        }
        if (tenantTypes.size() > 1) {
            error(model, "All entities in one closed @VevModel must use the same tenant key type, found " + tenantTypes);
        }
    }

    private void validateUniqueTables(TypeElement model, List<EntityMapping> entities) {
        Set<String> tables = new HashSet<>();
        for (EntityMapping entity : entities) {
            String table = entity.schemaName() + '.' + entity.tableName();
            if (!tables.add(table)) {
                error(model, "Multiple entities in one closed @VevModel map PostgreSQL table " + table);
            }
        }
    }

    private void validateStableModelName(TypeElement model) {
        String name = model.getQualifiedName().toString();
        if (name.length() > 128 || name.codePoints().anyMatch(Character::isISOControl)) {
            error(model, "@VevModel qualified name must be at most 128 characters without control characters");
        }
    }

    private void scanTypeAnnotations(TypeElement entity) {
        for (AnnotationMirror annotation : entity.getAnnotationMirrors()) {
            String name = annotationName(annotation);
            if (name.equals(ENTITY) || name.equals(TABLE) || name.equals(APPEND_ONLY)) {
                validateAnnotationShape(entity, annotation);
                continue;
            }
            if (name.startsWith("jakarta.persistence.") || name.startsWith("org.hibernate.")) {
                rejectUnsupportedAnnotation(entity, name);
            }
        }
        Set<Element> componentElements = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (RecordComponentElement component : entity.getRecordComponents()) {
            componentElements.addAll(componentSources(entity, component));
        }
        for (Element member : entity.getEnclosedElements()) {
            for (AnnotationMirror annotation : member.getAnnotationMirrors()) {
                String name = annotationName(annotation);
                if (name.equals(ID) || name.equals(COLUMN) || name.equals(VERSION)
                        || name.equals(GENERATED_VALUE) || name.equals(TENANT_KEY)) {
                    if (!componentElements.contains(member)) {
                        error(member, "Persistence mapping @" + simpleName(name)
                                + " is forbidden on members unrelated to a record component");
                    } else {
                        validateAnnotationShape(member, annotation);
                    }
                    continue;
                }
                if (name.startsWith("jakarta.persistence.") || name.startsWith("org.hibernate.")) {
                    rejectUnsupportedAnnotation(member, name);
                }
            }
        }
    }

    private void scanMemberAnnotations(TypeElement entity, RecordComponentElement component) {
        for (Element source : componentSources(entity, component)) {
            for (AnnotationMirror annotation : source.getAnnotationMirrors()) {
                String name = annotationName(annotation);
                if (name.equals(ID) || name.equals(COLUMN) || name.equals(VERSION)
                        || name.equals(GENERATED_VALUE) || name.equals(TENANT_KEY)) {
                    validateAnnotationShape(component, annotation);
                    continue;
                }
                if (name.startsWith("jakarta.persistence.") || name.startsWith("org.hibernate.")) {
                    rejectUnsupportedAnnotation(component, name);
                }
            }
        }
    }

    private void rejectUnsupportedAnnotation(Element element, String annotationName) {
        String simpleName = simpleName(annotationName);
        if (ASSOCIATIONS.contains(annotationName) || annotationName.equals("jakarta.persistence.Basic")) {
            error(element, "Associations, cascades, and lazy loading are forbidden; map foreign-key scalar values explicitly instead of @" + simpleName);
        } else if (CALLBACKS.contains(annotationName)) {
            error(element, "Persistence callbacks are forbidden because entity snapshots have no hidden lifecycle: @" + simpleName);
        } else if (INHERITANCE.contains(annotationName)) {
            error(element, "Inheritance, embedded identifiers, and secondary tables are forbidden: @" + simpleName);
        } else if (STRING_QUERIES.contains(annotationName)) {
            error(element, "Unchecked annotation query strings are forbidden: @" + simpleName);
        } else if (annotationName.startsWith("org.hibernate.")) {
            error(element, "Hibernate extensions are forbidden in a portable Vev model: @" + simpleName);
        } else {
            error(element, "Unsupported Jakarta Persistence mapping @" + simpleName
                    + "; Vev accepts only explicit scalar @Id, @Column, and @Version mappings");
        }
    }

    private void rejectExplicitAccessor(TypeElement entity, RecordComponentElement component) {
        for (ExecutableElement method : ElementFilter.methodsIn(entity.getEnclosedElements())) {
            if (method.getSimpleName().contentEquals(component.getSimpleName())
                    && method.getParameters().isEmpty()
                    && hasSourcePosition(entity, method)) {
                error(method, "Explicit record component accessors are forbidden because generated plans require pure snapshots");
            }
        }
    }

    private void rejectImplementedInterfaces(TypeElement entity) {
        if (!entity.getInterfaces().isEmpty()) {
            error(entity,
                    "Vev entity records must not implement interfaces because interface initialization can execute hidden behavior during hydration");
        }
    }

    private void rejectExplicitCanonicalConstructor(TypeElement entity) {
        List<? extends RecordComponentElement> components = entity.getRecordComponents();
        Tree sourceTree = trees.getTree(entity);
        TreePath entityPath = trees.getPath(entity);
        if (!(sourceTree instanceof ClassTree classTree) || entityPath == null) {
            error(entity, "Vev could not verify the record canonical constructor from source");
            return;
        }
        long typeStart = trees.getSourcePositions()
                .getStartPosition(entityPath.getCompilationUnit(), classTree);
        for (Tree member : classTree.getMembers()) {
            if (!(member instanceof MethodTree methodTree) || methodTree.getReturnType() != null) {
                continue;
            }
            long constructorStart = trees.getSourcePositions()
                    .getStartPosition(entityPath.getCompilationUnit(), member);
            long constructorEnd = trees.getSourcePositions()
                    .getEndPosition(entityPath.getCompilationUnit(), member);
            if (constructorStart == Diagnostic.NOPOS
                    || constructorEnd == Diagnostic.NOPOS
                    || constructorStart <= typeStart) {
                continue;
            }
            Element memberElement = trees.getElement(new TreePath(entityPath, member));
            if (!(memberElement instanceof ExecutableElement constructor)) {
                error(entity, "Vev could not resolve a source record constructor");
                continue;
            }
            List<? extends VariableElement> parameters = constructor.getParameters();
            if (parameters.size() != components.size()) {
                continue;
            }
            boolean canonical = true;
            for (int index = 0; index < components.size(); index++) {
                if (!processingEnvironment.getTypeUtils().isSameType(
                        parameters.get(index).asType(), components.get(index).asType())) {
                    canonical = false;
                    break;
                }
            }
            if (canonical) {
                error(constructor,
                        "Explicit compact or canonical record constructors are forbidden because hydration requires an unmodified pure snapshot");
            }
        }
    }

    private void rejectInitializationSideEffects(TypeElement entity) {
        Tree sourceTree = trees.getTree(entity);
        if (!(sourceTree instanceof ClassTree classTree)) {
            error(entity, "Vev could not verify entity initialization purity from source");
            return;
        }
        for (Tree member : classTree.getMembers()) {
            if (member instanceof BlockTree) {
                error(entity, "Record initializer blocks are forbidden because hydration must not trigger hidden side effects");
            }
        }
        for (VariableElement field : ElementFilter.fieldsIn(entity.getEnclosedElements())) {
            if (field.getModifiers().contains(Modifier.STATIC)
                    && hasSourcePosition(entity, field)
                    && field.getConstantValue() == null) {
                error(field,
                        "Static entity fields must be compile-time constants so first hydration cannot trigger hidden initialization");
            }
        }
    }

    private boolean hasSourcePosition(TypeElement entity, Element member) {
        var methodTree = trees.getTree(member);
        var entityPath = trees.getPath(entity);
        return methodTree != null
                && entityPath != null
                && trees.getSourcePositions().getStartPosition(entityPath.getCompilationUnit(), methodTree) != Diagnostic.NOPOS;
    }

    private static Trees compilerTrees(ProcessingEnvironment environment) {
        ProcessingEnvironment candidate = environment;
        for (int depth = 0; depth < 8; depth++) {
            try {
                return Trees.instance(candidate);
            } catch (IllegalArgumentException unsupportedWrapper) {
                ProcessingEnvironment delegate = processingEnvironmentDelegate(candidate);
                if (delegate == null || delegate == candidate) {
                    throw new IllegalStateException("Vev requires javac source-tree access for purity verification", unsupportedWrapper);
                }
                candidate = delegate;
            }
        }
        throw new IllegalStateException("Vev could not resolve the javac processing environment");
    }

    private static ProcessingEnvironment processingEnvironmentDelegate(ProcessingEnvironment environment) {
        for (Class<?> type = environment.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!ProcessingEnvironment.class.isAssignableFrom(field.getType()) || !field.trySetAccessible()) {
                    continue;
                }
                try {
                    return (ProcessingEnvironment) field.get(environment);
                } catch (IllegalAccessException inaccessible) {
                    throw new IllegalStateException("Vev could not access the wrapped javac environment", inaccessible);
                }
            }
        }
        return null;
    }

    private AnnotationMirror consistentAnnotation(
            Element component, List<Element> sources, String qualifiedName) {
        AnnotationMirror selected = null;
        String selectedShape = null;
        for (Element source : sources) {
            AnnotationMirror candidate = annotation(source, qualifiedName);
            if (candidate == null) {
                continue;
            }
            validateAnnotationShape(component, candidate);
            String shape = annotationValues(candidate);
            if (selected == null) {
                selected = candidate;
                selectedShape = shape;
            } else if (!selectedShape.equals(shape)) {
                error(component, "Conflicting @" + simpleName(qualifiedName)
                        + " metadata is forbidden across a record component, field, and accessor");
            }
        }
        return selected;
    }

    private String annotationValues(AnnotationMirror annotation) {
        return processingEnvironment.getElementUtils().getElementValuesWithDefaults(annotation).entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(entry -> entry.getSimpleName().toString())))
                .map(entry -> entry.getKey().getSimpleName() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(";"));
    }

    private void validateAnnotationShape(Element use, AnnotationMirror annotation) {
        String annotationName = annotationName(annotation);
        Set<String> expected = ANNOTATION_MEMBERS.get(annotationName);
        if (expected == null) {
            return;
        }
        Element annotationType = annotation.getAnnotationType().asElement();
        if (!(annotationType instanceof TypeElement type)) {
            error(use, "Could not verify annotation shape for " + annotationName);
            return;
        }
        Set<String> actual = ElementFilter.methodsIn(type.getEnclosedElements()).stream()
                .map(method -> method.getSimpleName().toString())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!actual.equals(expected)) {
            error(use, "Unsupported " + annotationName + " API shape; Vev is pinned to Jakarta Persistence 4.0.0-M6");
        }
    }

    private static String simpleName(String qualifiedName) {
        return qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
    }

    private List<Element> componentSources(TypeElement entity, RecordComponentElement component) {
        List<Element> sources = new ArrayList<>();
        sources.add(component);
        Name name = component.getSimpleName();
        for (VariableElement field : ElementFilter.fieldsIn(entity.getEnclosedElements())) {
            if (field.getSimpleName().contentEquals(name)) {
                sources.add(field);
            }
        }
        for (ExecutableElement method : ElementFilter.methodsIn(entity.getEnclosedElements())) {
            if (method.getSimpleName().contentEquals(name) && method.getParameters().isEmpty()) {
                sources.add(method);
            }
        }
        return sources;
    }

    private void validateTopLevelPublicType(TypeElement type, String label) {
        if (type.getNestingKind() != NestingKind.TOP_LEVEL) {
            error(type, label + " must be a top-level type so generated names remain deterministic");
        }
        if (!type.getModifiers().contains(Modifier.PUBLIC)) {
            error(type, label + " must be public so generated registries never require reflective access");
        }
        if (!type.getTypeParameters().isEmpty()) {
            error(type, label + " must not declare type parameters");
        }
    }

    private void rejectGeneratedTypeCollision(Element source, String generatedQualifiedName) {
        if (processingEnvironment.getElementUtils().getTypeElement(generatedQualifiedName) != null) {
            error(source, "Generated type name already exists: " + generatedQualifiedName);
        }
    }

    private void validateIdentifier(Element element, String identifier, String role) {
        if (!IDENTIFIER.matcher(identifier).matches()) {
            error(element, "Explicit " + role + " identifier \"" + identifier
                    + "\" must match [a-z][a-z0-9_]{0,62}");
        }
    }

    private void writeSource(String qualifiedName, String source, Element originatingElement) {
        try {
            JavaFileObject file = processingEnvironment.getFiler().createSourceFile(qualifiedName, originatingElement);
            try (Writer writer = file.openWriter()) {
                writer.write(source);
            }
        } catch (FilerException duplicate) {
            error(originatingElement, "Could not generate " + qualifiedName + ": " + duplicate.getMessage());
        } catch (IOException failure) {
            error(originatingElement, "Could not write generated source " + qualifiedName + ": " + failure.getMessage());
        }
    }

    private String fingerprint(String modelName, List<EntityMapping> entities) {
        StringBuilder canonical = new StringBuilder("vev-model-v3\n").append(modelName).append('\n');
        for (EntityMapping entity : entities) {
            canonical.append(entity.qualifiedName()).append('|')
                    .append(entity.tableSql()).append('|')
                    .append(entity.appendOnly()).append('\n');
            for (PropertyMapping property : entity.properties()) {
                canonical.append(property.name()).append('|')
                        .append(property.boxedType()).append('|')
                        .append(property.columnName()).append('|')
                        .append(property.nullable()).append('|')
                        .append(property.maximumLength()).append('|')
                        .append(property.numericPrecision()).append('|')
                        .append(property.numericScale()).append('|')
                        .append(property.id()).append('|')
                        .append(property.tenant()).append('|')
                        .append(property.version()).append('\n');
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK does not provide SHA-256", impossible);
        }
    }

    private String packageName(TypeElement type) {
        return processingEnvironment.getElementUtils().getPackageOf(type).getQualifiedName().toString();
    }

    private AnnotationMirror annotation(Element element, String qualifiedName) {
        for (AnnotationMirror candidate : element.getAnnotationMirrors()) {
            if (annotationName(candidate).equals(qualifiedName)) {
                return candidate;
            }
        }
        return null;
    }

    private AnnotationMirror annotation(List<Element> elements, String qualifiedName) {
        for (Element element : elements) {
            AnnotationMirror found = annotation(element, qualifiedName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private String annotationName(AnnotationMirror annotation) {
        Element element = annotation.getAnnotationType().asElement();
        return element instanceof TypeElement type ? type.getQualifiedName().toString() : element.toString();
    }

    private AnnotationValue annotationValue(AnnotationMirror annotation, String name) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry
                : processingEnvironment.getElementUtils().getElementValuesWithDefaults(annotation).entrySet()) {
            if (entry.getKey().getSimpleName().contentEquals(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private String stringValue(AnnotationMirror annotation, String name) {
        AnnotationValue value = annotationValue(annotation, name);
        return value == null ? "" : String.valueOf(value.getValue());
    }

    private boolean booleanValue(AnnotationMirror annotation, String name) {
        AnnotationValue value = annotationValue(annotation, name);
        return value != null && Boolean.TRUE.equals(value.getValue());
    }

    private int intValue(AnnotationMirror annotation, String name) {
        AnnotationValue value = annotationValue(annotation, name);
        return value != null && value.getValue() instanceof Integer integer ? integer : 0;
    }

    private void rejectNonEmptyString(Element element, AnnotationMirror annotation, String attribute, String label) {
        if (annotation != null && !stringValue(annotation, attribute).isEmpty()) {
            error(element, label + " is forbidden because Vev never silently ignores schema-generation metadata");
        }
    }

    private void rejectNonEmptyList(Element element, AnnotationMirror annotation, String attribute, String label) {
        AnnotationValue value = annotation == null ? null : annotationValue(annotation, attribute);
        if (value != null && value.getValue() instanceof List<?> values && !values.isEmpty()) {
            error(element, label + " is forbidden; declare and review this constraint in the database migration");
        }
    }

    private void rejectNonDefaultInt(
            Element element, AnnotationMirror annotation, String attribute, int expectedDefault) {
        if (intValue(annotation, attribute) != expectedDefault) {
            error(element, "@Column." + attribute
                    + " is forbidden because Vev does not infer production DDL from mapping annotations");
        }
    }

    private String enumValue(AnnotationMirror annotation, String name) {
        AnnotationValue value = annotationValue(annotation, name);
        if (value == null) {
            return "";
        }
        Object raw = value.getValue();
        return raw instanceof VariableElement constant ? constant.getSimpleName().toString() : raw.toString();
    }

    private void error(Element element, String message) {
        invalid = true;
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private static String qualify(String packageName, String simpleName) {
        return packageName.isEmpty() ? simpleName : packageName + '.' + simpleName;
    }

    private static String quote(String identifier) {
        return '"' + identifier + '"';
    }

    private static Map<String, CodecMapping> codecs() {
        Map<String, CodecMapping> codecs = new HashMap<>();
        addCodec(codecs, "boolean", "java.lang.Boolean", "BOOLEAN", "boolean");
        addCodec(codecs, "java.lang.Boolean", "java.lang.Boolean", "BOOLEAN", "boolean");
        addCodec(codecs, "int", "java.lang.Integer", "INTEGER", "integer");
        addCodec(codecs, "java.lang.Integer", "java.lang.Integer", "INTEGER", "integer");
        addCodec(codecs, "long", "java.lang.Long", "LONG", "bigint");
        addCodec(codecs, "java.lang.Long", "java.lang.Long", "LONG", "bigint");
        addCodec(codecs, "short", "java.lang.Short", "SHORT", "smallint");
        addCodec(codecs, "java.lang.Short", "java.lang.Short", "SHORT", "smallint");
        addCodec(codecs, "java.lang.String", "java.lang.String", "STRING", "character varying");
        addCodec(codecs, "java.util.UUID", "java.util.UUID", "UUID", "uuid");
        addCodec(codecs, "java.math.BigDecimal", "java.math.BigDecimal", "BIG_DECIMAL", "numeric");
        addCodec(codecs, "java.time.LocalDate", "java.time.LocalDate", "LOCAL_DATE", "date");
        addCodec(codecs, "java.time.LocalDateTime", "java.time.LocalDateTime", "LOCAL_DATE_TIME", "timestamp");
        addCodec(codecs, "java.time.Instant", "java.time.Instant", "INSTANT", "timestamptz");
        return Map.copyOf(codecs);
    }

    private static void addCodec(
            Map<String, CodecMapping> codecs,
            String type,
            String boxedType,
            String field,
            String arrayElementType) {
        codecs.put(type, new CodecMapping(boxedType, "no.beint.vev.pg.PgCodecs." + field, arrayElementType));
    }

    private record CodecMapping(String boxedType, String codec, String arrayElementType) {
    }
}
