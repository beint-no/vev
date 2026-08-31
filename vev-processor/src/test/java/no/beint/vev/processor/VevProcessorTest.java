package no.beint.vev.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class VevProcessorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void claimsOnlyTheVevModelTriggerAnnotation() {
        assertEquals(java.util.Set.of(VevProcessor.VEV_MODEL), new VevProcessor().getSupportedAnnotationTypes());
    }

    @Test
    void generatesDeterministicDirectPostgresPlansAndClosedRegistry() throws IOException {
        Map<String, String> sources = positiveSources();

        Compilation first = compile(sources);
        Compilation second = compile(sources);

        assertTrue(first.success(), first.diagnostics());
        assertTrue(second.success(), second.diagnostics());
        String accountPlan = first.generated("example/AccountVev.java");
        String auditPlan = first.generated("example/AuditEventVev.java");
        String registry = first.generated("example/BillingModelVev.java");
        assertEquals(accountPlan, second.generated("example/AccountVev.java"));
        assertEquals(registry, second.generated("example/BillingModelVev.java"));
        assertTrue(accountPlan.contains("implements no.beint.vev.pg.spi.PgVersionedEntityPlan<example.BillingModelVev.Model, example.Account, java.lang.Long, java.util.UUID, java.lang.Integer>"));
        assertTrue(accountPlan.contains("return new example.Account("));
        assertTrue(accountPlan.contains("new no.beint.vev.pg.PgColumn(\"id\""));
        assertTrue(accountPlan.contains("public Object columnValue(example.Account entity, int columnIndex)"));
        assertTrue(accountPlan.contains("public example.Account instantiate(Object[] columnValues)"));
        assertFalse(accountPlan.contains("PreparedStatement"));
        assertFalse(accountPlan.contains("ResultSet"));
        assertFalse(accountPlan.contains("bindUpdate("));
        assertFalse(accountPlan.contains("bindUpsert("));
        assertFalse(accountPlan.contains("SELECT "));
        assertFalse(accountPlan.contains("INSERT INTO"));
        assertFalse(accountPlan.contains("String findSql()"));
        assertTrue(accountPlan.contains("public String schemaName()"));
        assertTrue(accountPlan.contains("return \"ledger\";"));
        assertTrue(accountPlan.contains("public String tenantColumn()"));
        assertTrue(accountPlan.contains("return \"tenant_id\";"));
        assertFalse(accountPlan.contains("VarHandle"));
        assertFalse(accountPlan.contains("reflect"));
        assertTrue(auditPlan.contains("implements no.beint.vev.pg.spi.PgEntityPlan<example.BillingModelVev.Model, example.AuditEvent, java.util.UUID, java.util.UUID>"));
        assertFalse(auditPlan.contains("bindUpdate("));
        assertTrue(registry.contains("sha256:"));
        assertTrue(registry.contains("public static final class Model"));
        assertTrue(registry.contains("public static final no.beint.vev.pg.PgModel<Model, java.util.UUID> POSTGRES"));
        assertTrue(registry.contains("public static no.beint.vev.TenantAuthority<Model, java.util.UUID> newTenantAuthority()"));
        assertTrue(registry.contains("TenantAuthority.create(Model.class, IDENTITY, java.util.UUID.class)"));
        assertTrue(registry.indexOf("example.AccountVev.INSTANCE") < registry.indexOf("example.AuditEventVev.INSTANCE"));
    }

    @Test
    void rejectsEveryImplicitOrUnsafeMappingAtCompilation() throws IOException {
        Map<String, NegativeCase> cases = new LinkedHashMap<>();
        cases.put("implicitTable", new NegativeCase(
                recordSource("@Table(schema = \"ledger\")", validComponents(), ""),
                "Implicit table names are forbidden"));
        cases.put("implicitSchema", new NegativeCase(
                recordSource("@Table(name = \"broken\")", validComponents(), ""),
                "Implicit schemas and search_path are forbidden"));
        cases.put("implicitColumn", new NegativeCase(
                recordSource(validTable(), validComponents().replace(
                        "@Id @Column(name = \"id\", nullable = false)", "@Id"), ""),
                "Implicit column names are forbidden"));
        cases.put("entityName", new NegativeCase(
                recordSource(validTable(), validComponents(), "")
                        .replace("@Entity\n", "@Entity(name = \"Broken\")\n"),
                "@Entity.name is forbidden"));
        cases.put("tableIndex", new NegativeCase(
                recordSource("@Table(name = \"broken\", schema = \"ledger\", indexes = @Index(columnList = \"display_name\"))",
                        validComponents(), "").replace("@Entity(name = \"Broken\")", "@Entity"),
                "@Table.indexes is forbidden"));
        cases.put("columnDefinition", new NegativeCase(
                recordSource(validTable(), validComponents().replace(
                        "@Column(name = \"display_name\", nullable = false, length = 255)",
                        "@Column(name = \"display_name\", nullable = false, length = 255, columnDefinition = \"text\")"), "")
                        .replace("@Entity(name = \"Broken\")", "@Entity"),
                "@Column.columnDefinition is forbidden"));
        cases.put("columnLength", new NegativeCase(
                recordSource(validTable(), validComponents().replace(
                        "@Column(name = \"display_name\", nullable = false, length = 255)",
                        "@Column(name = \"display_name\", nullable = false, length = 0)"), "")
                        .replace("@Entity(name = \"Broken\")", "@Entity"),
                "String @Column.length must be between 1 and 65535"));
        cases.put("unsupportedOffsetDateTime", new NegativeCase(
                recordSource(validTable(), validComponents().replace(
                        "String displayName", "java.time.OffsetDateTime displayName"), ""),
                "No safe PostgreSQL codec exists for java.time.OffsetDateTime"));
        cases.put("missingBigDecimalBounds", new NegativeCase(
                recordSource(validTable(), validComponents().replace(
                        "@Column(name = \"display_name\", nullable = false, length = 255) String displayName",
                        "@Column(name = \"display_name\", nullable = false) java.math.BigDecimal displayName"), ""),
                "BigDecimal @Column.precision must be between 1 and 128"));
        cases.put("invalidBigDecimalPrecision", new NegativeCase(
                recordSource(validTable(), validComponents().replace(
                        "@Column(name = \"display_name\", nullable = false, length = 255) String displayName",
                        "@Column(name = \"display_name\", nullable = false, precision = 129, scale = 4) java.math.BigDecimal displayName"), ""),
                "BigDecimal @Column.precision must be between 1 and 128"));
        cases.put("invalidBigDecimalScale", new NegativeCase(
                recordSource(validTable(), validComponents().replace(
                        "@Column(name = \"display_name\", nullable = false, length = 255) String displayName",
                        "@Column(name = \"display_name\", nullable = false, precision = 4, scale = 5) java.math.BigDecimal displayName"), ""),
                "BigDecimal @Column.scale must be between 0 and its precision"));
        cases.put("unstableId", new NegativeCase(
                recordSource(validTable(), validComponents().replace("Long id", "java.math.BigDecimal id"), ""),
                "@Id must use equality-stable"));
        cases.put("unstableTenant", new NegativeCase(
                recordSource(validTable(), validComponents().replace("UUID tenantId", "java.time.Instant tenantId"), ""),
                "@TenantKey must use equality-stable"));
        cases.put("missingId", new NegativeCase(
                recordSource(validTable(), validComponents().replace("@Id ", ""), ""),
                "must declare exactly one @Id"));
        cases.put("compoundId", new NegativeCase(
                recordSource(validTable(), validComponents().replace(
                        "@Column(name = \"display_name\", nullable = false, length = 255)",
                        "@Id @Column(name = \"display_name\", nullable = false, length = 255)"), ""),
                "Compound identifiers are forbidden"));
        cases.put("missingTenant", new NegativeCase(
                recordSource(validTable(), validComponents().replace("@TenantKey ", ""), ""),
                "must declare exactly one @TenantKey"));
        cases.put("nullableTenant", new NegativeCase(
                recordSource(validTable(), validComponents().replace(
                        "@Column(name = \"tenant_id\", nullable = false)",
                        "@Column(name = \"tenant_id\")"), ""),
                "Tenant columns must declare @Column(nullable = false)"));
        cases.put("missingVersion", new NegativeCase(
                recordSource(validTable(), validComponents().replace("@Version ", ""), ""),
                "Mutable Vev entities require exactly one @Version"));
        cases.put("unsafeIdentity", new NegativeCase(
                recordSource(validTable(), validComponents().replace(
                        "@Id @Column(name = \"id\", nullable = false) Long id",
                        "@Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column(name = \"id\", nullable = false) UUID id"), ""),
                "@GeneratedValue is forbidden"));
        cases.put("association", new NegativeCase(
                recordSource(validTable(), validComponents().replace(
                        "@Column(name = \"display_name\", nullable = false, length = 255)",
                        "@ManyToOne @Column(name = \"display_name\", nullable = false, length = 255)"), ""),
                "Associations, cascades, and lazy loading are forbidden"));
        cases.put("callback", new NegativeCase(
                recordSource(validTable(), validComponents(), "@PrePersist public void callback() {}"),
                "Persistence callbacks are forbidden"));
        cases.put("inheritance", new NegativeCase(
                recordSource("@Inheritance " + validTable(), validComponents(), ""),
                "Inheritance, embedded identifiers, and secondary tables are forbidden"));
        cases.put("appendOnlyVersion", new NegativeCase(
                recordSource("@AppendOnly " + validTable(), validComponents(), ""),
                "@AppendOnly entities must not declare @Version"));
        cases.put("inaccessibleHydration", new NegativeCase(nonRecordSource(),
                "must be immutable Java records"));
        cases.put("compactConstructor", new NegativeCase(
                recordSource(validTable(), validComponents(), "public Broken { displayName = displayName.strip(); }"),
                "Explicit compact or canonical record constructors are forbidden"));
        cases.put("canonicalConstructor", new NegativeCase(
                recordSource(validTable(), validComponents(), """
                        public Broken(Long id, UUID tenantId, int version, String displayName) {
                            this.id = id;
                            this.tenantId = tenantId;
                            this.version = version;
                            this.displayName = displayName;
                        }
                        """),
                "Explicit compact or canonical record constructors are forbidden"));
        cases.put("staticInitializer", new NegativeCase(
                recordSource(validTable(), validComponents(), "static { System.setProperty(\"vev.test\", \"unsafe\"); }"),
                "Record initializer blocks are forbidden"));
        cases.put("runtimeStaticField", new NegativeCase(
                recordSource(validTable(), validComponents(), "static final Object STATE = new Object();"),
                "Static entity fields must be compile-time constants"));

        for (Map.Entry<String, NegativeCase> entry : cases.entrySet()) {
            Compilation compilation = compile(Map.of(
                    "example/Broken.java", entry.getValue().source(),
                    "example/BrokenModel.java", brokenModelSource()));
            assertFalse(compilation.success(), entry.getKey() + " unexpectedly compiled");
            assertTrue(compilation.diagnostics().contains(entry.getValue().diagnostic()),
                    entry.getKey() + " diagnostics were:\n" + compilation.diagnostics());
        }
    }

    @Test
    void generatedModelMarkersRejectCrossModelEntityOperationsAtCompilation() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("example/FirstModel.java", modelSource("FirstModel", "FirstEntity"));
        sources.put("example/SecondModel.java", modelSource("SecondModel", "SecondEntity"));
        sources.put("example/FirstEntity.java", appendOnlyEntitySource("FirstEntity", "first_entity"));
        sources.put("example/SecondEntity.java", appendOnlyEntitySource("SecondEntity", "second_entity"));
        sources.put("example/CrossModelUse.java", """
                package example;

                import no.beint.vev.WriteEntities;

                public final class CrossModelUse {
                    public static void insertSecondIntoFirst(
                            WriteEntities<FirstModelVev.Model> entities,
                            SecondEntity entity) {
                        entities.insert(SecondEntityVev.INSTANCE, entity);
                    }
                }
                """);

        Compilation compilation = compile(sources);

        assertFalse(compilation.success(), "Cross-model operation unexpectedly compiled");
        assertTrue(compilation.diagnostics().contains("insert"), compilation.diagnostics());
    }

    @Test
    void generatedModelMarkersRejectCrossModelTenantScopesAtCompilation() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("example/FirstModel.java", modelSource("FirstModel", "FirstEntity"));
        sources.put("example/SecondModel.java", modelSource("SecondModel", "SecondEntity"));
        sources.put("example/FirstEntity.java", appendOnlyEntitySource("FirstEntity", "first_entity"));
        sources.put("example/SecondEntity.java", appendOnlyEntitySource("SecondEntity", "second_entity"));
        sources.put("example/CrossModelScopeUse.java", """
                package example;

                import java.util.UUID;
                import no.beint.vev.TenantScope;
                import no.beint.vev.TransactionExecutor;

                public final class CrossModelScopeUse {
                    public static void readFirstWithSecondScope(
                            TransactionExecutor<FirstModelVev.Model, UUID> first,
                            TenantScope<SecondModelVev.Model, UUID> secondScope) {
                        first.read(secondScope, transaction -> null);
                    }
                }
                """);

        Compilation compilation = compile(sources);

        assertFalse(compilation.success(), "Cross-model tenant scope unexpectedly compiled");
        assertTrue(compilation.diagnostics().contains("TenantScope<example.SecondModelVev.Model"),
                compilation.diagnostics());
        assertTrue(compilation.diagnostics().contains("TenantScope<example.FirstModelVev.Model"),
                compilation.diagnostics());
    }

    @Test
    void rejectsOversizedClosedModelsBeforeGeneratingEntityPlans() throws IOException {
        String entities = String.join(", ", java.util.Collections.nCopies(129, "Broken.class"));
        String model = """
                package example;

                import no.beint.vev.VevModel;

                @VevModel(entities = {%s})
                public final class BrokenModel {
                    private BrokenModel() {
                    }
                }
                """.formatted(entities);

        Compilation compilation = compile(Map.of(
                "example/Broken.java", recordSource(validTable(), validComponents(), ""),
                "example/BrokenModel.java", model));

        assertFalse(compilation.success(), "Oversized model unexpectedly compiled");
        assertTrue(compilation.diagnostics().contains("@VevModel must not exceed 128 entities"),
                compilation.diagnostics());
    }

    @Test
    void rejectsOversizedEntityShapesBeforeScanningProperties() throws IOException {
        List<String> components = new ArrayList<>();
        components.add("@Id @Column(name = \"id\", nullable = false) Long id");
        components.add("@TenantKey @Column(name = \"tenant_id\", nullable = false) UUID tenantId");
        components.add("@Version @Column(name = \"version\", nullable = false) Long version");
        for (int index = components.size(); index < 65; index++) {
            components.add("@Column(name = \"value_" + index
                    + "\", nullable = false, length = 1) String value" + index);
        }
        String entity = """
                package example;

                import jakarta.persistence.Column;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.Table;
                import jakarta.persistence.Version;
                import java.util.UUID;
                import no.beint.vev.TenantKey;

                @Entity
                @Table(name = "broken", schema = "ledger")
                public record Broken(
                        %s) {
                }
                """.formatted(String.join(",\n        ", components));

        Compilation compilation = compile(Map.of(
                "example/Broken.java", entity,
                "example/BrokenModel.java", brokenModelSource()));

        assertFalse(compilation.success(), "Oversized entity unexpectedly compiled");
        assertTrue(compilation.diagnostics().contains("must not exceed 64 mapped components"),
                compilation.diagnostics());
    }

    @Test
    void rejectsEntityRecordsThatImplementInterfaces() throws IOException {
        String entity = recordSource(validTable(), validComponents(), "")
                .replace(") {", ") implements java.io.Serializable {");

        Compilation compilation = compile(Map.of(
                "example/Broken.java", entity,
                "example/BrokenModel.java", brokenModelSource()));

        assertFalse(compilation.success(), "Entity interface unexpectedly compiled");
        assertTrue(
                compilation.diagnostics().contains("Vev entity records must not implement interfaces"),
                compilation.diagnostics());
    }

    private Compilation compile(Map<String, String> sources) throws IOException {
        Path root = Files.createTempDirectory(temporaryDirectory, "javac-");
        Path sourceDirectory = Files.createDirectories(root.resolve("source"));
        Path classesDirectory = Files.createDirectories(root.resolve("classes"));
        Path generatedDirectory = Files.createDirectories(root.resolve("generated"));
        List<Path> sourcePaths = new ArrayList<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path path = sourceDirectory.resolve(source.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, source.getValue());
            sourcePaths.add(path);
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromPaths(sourcePaths);
            List<String> options = List.of(
                    "--release", "26",
                    "-classpath", System.getProperty("java.class.path"),
                    "-processor", VevProcessor.class.getName(),
                    "-proc:full",
                    "-Xlint:all,-processing",
                    "-Werror",
                    "-d", classesDirectory.toString(),
                    "-s", generatedDirectory.toString());
            boolean success = Boolean.TRUE.equals(compiler.getTask(null, fileManager, diagnostics, options, null, units).call());
            String diagnosticText = diagnostics.getDiagnostics().stream()
                    .map(VevProcessorTest::formatDiagnostic)
                    .collect(java.util.stream.Collectors.joining("\n"));
            return new Compilation(success, diagnosticText, generatedDirectory);
        }
    }

    private static String formatDiagnostic(Diagnostic<? extends JavaFileObject> diagnostic) {
        return diagnostic.getKind() + ":" + diagnostic.getLineNumber() + ": " + diagnostic.getMessage(Locale.ROOT);
    }

    private static Map<String, String> positiveSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("example/BillingModel.java", """
                package example;

                import no.beint.vev.VevModel;

                @VevModel(entities = {AuditEvent.class, Account.class})
                public final class BillingModel {
                    private BillingModel() {
                    }
                }
                """);
        sources.put("example/Account.java", """
                package example;

                import jakarta.persistence.Column;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.Table;
                import jakarta.persistence.Version;
                import java.math.BigDecimal;
                import java.util.UUID;
                import no.beint.vev.TenantKey;

                @Entity
                @Table(name = "account", schema = "ledger")
                public record Account(
                        @Id @Column(name = "id", nullable = false) Long id,
                        @TenantKey @Column(name = "tenant_id", nullable = false) UUID tenantId,
                        @Version @Column(name = "version", nullable = false) int version,
                        @Column(name = "display_name", nullable = false, length = 255) String displayName,
                        @Column(name = "balance", nullable = false, precision = 19, scale = 2) BigDecimal balance) {
                    private static final String ENTITY_KIND = "account";
                }
                """);
        sources.put("example/Account_.java", """
                package example;

                public final class Account_ {
                    private Account_() {
                    }
                }
                """);
        sources.put("example/AuditEvent.java", """
                package example;

                import jakarta.persistence.Column;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.Table;
                import java.time.Instant;
                import java.util.UUID;
                import no.beint.vev.AppendOnly;
                import no.beint.vev.TenantKey;

                @Entity
                @AppendOnly
                @Table(name = "audit_event", schema = "ledger")
                public record AuditEvent(
                        @Id @Column(name = "id", nullable = false) UUID id,
                        @TenantKey @Column(name = "tenant_id", nullable = false) UUID tenantId,
                        @Column(name = "occurred_at", nullable = false) Instant occurredAt) {
                }
                """);
        return sources;
    }

    private static String recordSource(String tableAnnotation, String components, String body) {
        return """
                package example;

                import jakarta.persistence.*;
                import java.util.UUID;
                import no.beint.vev.AppendOnly;
                import no.beint.vev.TenantKey;

                @Entity
                %s
                public record Broken(%s) {
                    %s
                }
                """.formatted(tableAnnotation, components, body);
    }

    private static String nonRecordSource() {
        return """
                package example;

                import jakarta.persistence.*;
                import java.util.UUID;
                import no.beint.vev.TenantKey;

                @Entity
                @Table(name = "broken", schema = "ledger")
                public final class Broken {
                    @Id @Column(name = "id", nullable = false) private final Long id;
                    @TenantKey @Column(name = "tenant_id", nullable = false) private final UUID tenantId;
                    @Version @Column(name = "version", nullable = false) private final int version;

                    private Broken(Long id, UUID tenantId, int version) {
                        this.id = id;
                        this.tenantId = tenantId;
                        this.version = version;
                    }
                }
                """;
    }

    private static String brokenModelSource() {
        return """
                package example;

                import no.beint.vev.VevModel;

                @VevModel(entities = Broken.class)
                public final class BrokenModel {
                    private BrokenModel() {
                    }
                }
                """;
    }

    private static String modelSource(String modelName, String entityName) {
        return """
                package example;

                import no.beint.vev.VevModel;

                @VevModel(entities = %s.class)
                public final class %s {
                    private %s() {
                    }
                }
                """.formatted(entityName, modelName, modelName);
    }

    private static String appendOnlyEntitySource(String entityName, String tableName) {
        return """
                package example;

                import jakarta.persistence.Column;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.Table;
                import java.util.UUID;
                import no.beint.vev.AppendOnly;
                import no.beint.vev.TenantKey;

                @Entity
                @AppendOnly
                @Table(name = "%s", schema = "ledger")
                public record %s(
                        @Id @Column(name = "id", nullable = false) UUID id,
                        @TenantKey @Column(name = "tenant_id", nullable = false) UUID tenantId) {
                }
                """.formatted(tableName, entityName);
    }

    private static String validComponents() {
        return """
                @Id @Column(name = "id", nullable = false) Long id,
                @TenantKey @Column(name = "tenant_id", nullable = false) UUID tenantId,
                @Version @Column(name = "version", nullable = false) int version,
                @Column(name = "display_name", nullable = false, length = 255) String displayName
                """;
    }

    private static String validTable() {
        return "@Table(name = \"broken\", schema = \"ledger\")";
    }

    private record NegativeCase(String source, String diagnostic) {
    }

    private record Compilation(boolean success, String diagnostics, Path generatedDirectory) {
        String generated(String relativePath) throws IOException {
            return Files.readString(generatedDirectory.resolve(relativePath));
        }
    }
}
