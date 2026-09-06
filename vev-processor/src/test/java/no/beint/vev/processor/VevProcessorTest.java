package no.beint.vev.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        assertTrue(accountPlan.contains("public static final no.beint.vev.pg.PgRequiredIndex<example.BillingModelVev.Model, example.Account, java.lang.Long, java.lang.String> DISPLAY_NAME"));
        assertTrue(accountPlan.contains("new no.beint.vev.pg.PgRequiredIndex<>(INSTANCE, \"account_display_name_idx\", 3, java.lang.String.class)"));
        assertTrue(accountPlan.contains("public static final no.beint.vev.pg.PgNullableIndex<example.BillingModelVev.Model, example.Account, java.lang.Long, java.lang.String> ALIAS"));
        assertTrue(accountPlan.contains("new no.beint.vev.pg.PgNullableIndex<>(INSTANCE, \"account_alias_idx\", 5, java.lang.String.class)"));
        assertTrue(accountPlan.contains("public java.util.List<no.beint.vev.pg.PgIndex<example.BillingModelVev.Model, example.Account, java.lang.Long, ?>> indexes()"));
        assertTrue(accountPlan.contains("return INDEXES;"));
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
    void indexMetadataChangesTheClosedModelFingerprint() throws IOException {
        Map<String, String> changedIndex = new LinkedHashMap<>(positiveSources());
        changedIndex.computeIfPresent("example/Account.java", (path, source) ->
                source.replace("account_display_name_idx", "account_display_name_v2_idx"));

        Compilation original = compile(positiveSources());
        Compilation changed = compile(changedIndex);

        assertTrue(original.success(), original.diagnostics());
        assertTrue(changed.success(), changed.diagnostics());
        assertNotEquals(
                original.generated("example/BillingModelVev.java"),
                changed.generated("example/BillingModelVev.java"));
        assertNotEquals(original.manifest("example.BillingModel"), changed.manifest("example.BillingModel"));
    }

    @Test
    void packagesDeterministicSchemaContractWithTheCompiledModel() throws IOException {
        var sources = positiveSources();
        var reordered = new LinkedHashMap<String, String>();
        sources.entrySet().stream().sorted(Map.Entry.<String, String>comparingByKey().reversed())
                .forEach(entry -> reordered.put(entry.getKey(), entry.getValue()));
        Compilation first = compile(sources);
        Compilation second = compile(reordered);
        assertTrue(first.success(), first.diagnostics());
        assertTrue(second.success(), second.diagnostics());
        String manifest = first.manifest("example.BillingModel");
        assertEquals(manifest, second.manifest("example.BillingModel"));
        assertFalse(manifest.contains(temporaryDirectory.toString()));
        var fingerprint = java.util.regex.Pattern.compile("sha256:[a-f0-9]{64}").matcher(manifest);
        assertTrue(fingerprint.find());
        assertTrue(first.generated("example/BillingModelVev.java").contains(fingerprint.group()));
        assertFalse(fingerprint.find());
        try (var expected = getClass().getResourceAsStream("/schema/billing.schema.json")) {
            assertTrue(expected != null, "Schema contract fixture must be packaged with the tests");
            assertEquals(new String(expected.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8),
                    manifest.replaceAll("sha256:[a-f0-9]{64}", "<model fingerprint>"));
        }
    }

    @Test
    void invalidModelDoesNotEmitASchemaManifest() throws IOException {
        var sources = new LinkedHashMap<>(positiveSources());
        sources.computeIfPresent("example/Account.java", (path, source) -> source.replace("@Version", ""));
        Compilation compilation = compile(sources);
        assertFalse(compilation.success());
        assertFalse(Files.exists(compilation.classesDirectory().resolve("META-INF/vev/example.BillingModel.schema.json")));
    }

    @Test
    void enumNamesGenerateTypedQueriesAndOrderIndependentMappingIdentity() throws IOException {
        Compilation first = compile(enumSources("OPEN, CLOSED"));
        Compilation reordered = compile(enumSources("CLOSED, OPEN"));
        Compilation renamed = compile(enumSources("OPEN, ARCHIVED"));
        assertTrue(first.success(), first.diagnostics());
        assertTrue(reordered.success(), reordered.diagnostics());
        assertTrue(renamed.success(), renamed.diagnostics());
        assertEquals(first.manifest("example.BillingModel"), reordered.manifest("example.BillingModel"));
        assertNotEquals(first.generated("example/BillingModelVev.java"), renamed.generated("example/BillingModelVev.java"));
        assertTrue(first.generated("example/AccountVev.java").contains(
                "no.beint.vev.pg.PgCodecs.enumNames(example.State.class, example.State.values())"));
        assertTrue(first.generated("example/AccountVev.java").contains(
                "PgRequiredIndex<example.BillingModelVev.Model, example.Account, java.lang.Long, example.State> DISPLAY_NAME"));
        assertTrue(first.manifest("example.BillingModel").contains("\"enumNames\": [\"CLOSED\", \"OPEN\"]"));

        var wrongPredicate = enumSources("OPEN, CLOSED");
        wrongPredicate.put("example/WrongPredicate.java", """
                package example;
                public class WrongPredicate {
                    Object query() {
                        return no.beint.vev.pg.PgQueries.equal(AccountVev.DISPLAY_NAME, "OPEN", new no.beint.vev.QueryLimit(1));
                    }
                }
                """);
        assertFalse(compile(wrongPredicate).success(), "Enum query tokens must not accept arbitrary strings");
    }

    @Test
    void rejectsAmbiguousOrdinalAndTruncatedEnumMappings() throws IOException {
        var cases = new LinkedHashMap<String, Map<String, String>>();
        var implicit = enumSources("OPEN, CLOSED");
        implicit.computeIfPresent("example/Account.java", (path, source) -> source.replace(
                "@jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)", ""));
        cases.put("require explicit STRING", implicit);
        var ordinal = enumSources("OPEN, CLOSED");
        ordinal.computeIfPresent("example/Account.java", (path, source) -> source.replace("EnumType.STRING", "EnumType.ORDINAL"));
        cases.put("reject ordinal", ordinal);
        var nonEnum = enumSources("OPEN, CLOSED");
        nonEnum.computeIfPresent("example/Account.java", (path, source) -> source.replace("State displayName", "String displayName"));
        cases.put("reject non-enum annotation", nonEnum);
        var tooShort = enumSources("OPEN, CLOSED");
        tooShort.computeIfPresent("example/Account.java", (path, source) -> source.replace("length = 255", "length = 4"));
        cases.put("reject truncated names", tooShort);
        cases.put("reject empty enum", enumSources(""));
        cases.put("reject custom EnumeratedValue", enumSources("""
                OPEN("o"), CLOSED("c");
                @jakarta.persistence.EnumeratedValue final String code;
                State(String code) { this.code = code; }
                """));
        for (var entry : cases.entrySet()) {
            Compilation compilation = compile(entry.getValue());
            assertFalse(compilation.success(), entry.getKey());
            assertFalse(Files.exists(compilation.classesDirectory()
                    .resolve("META-INF/vev/example.BillingModel.schema.json")), entry.getKey());
        }
    }

    private static Map<String, String> enumSources(String constants) {
        var sources = new LinkedHashMap<>(positiveSources());
        sources.put("example/State.java", "package example; public enum State { " + constants + " }");
        sources.computeIfPresent("example/Account.java", (path, source) -> source
                .replace("@VevIndex(name = \"account_display_name_idx\")",
                        "@jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)\n"
                                + "@VevIndex(name = \"account_display_name_idx\")")
                .replace("String displayName", "State displayName"));
        return sources;
    }

    @Test
    void compilesReferencesWithinTheClosedModelAndEmitsCompositeForeignKeys() throws IOException {
        Compilation compilation = compile(referenceSources());
        assertTrue(compilation.success(), compilation.diagnostics());
        assertTrue(compilation.generated("example/AccountVev.java").contains(
                "new no.beint.vev.pg.PgReference(\"account_audit_fk\", 5, example.AuditEvent.class)"));
        String manifest = compilation.manifest("example.BillingModel");
        assertTrue(manifest.contains("\"columns\": [\"tenant_id\", \"alias\"], \"targetSchema\": \"ledger\","
                + " \"targetTable\": \"audit_event\", \"targetColumns\": [\"tenant_id\", \"id\"]"));
        var renamed = referenceSources();
        renamed.computeIfPresent("example/Account.java", (path, source) -> source.replace("account_audit_fk", "account_event_fk"));
        Compilation changed = compile(renamed);
        assertTrue(changed.success(), changed.diagnostics());
        assertNotEquals(compilation.generated("example/BillingModelVev.java"), changed.generated("example/BillingModelVev.java"));
        var selfReference = referenceSources();
        selfReference.computeIfPresent("example/Account.java", (path, source) -> source
                .replace("target = AuditEvent.class", "target = Account.class").replace("UUID alias", "Long alias"));
        Compilation self = compile(selfReference);
        assertTrue(self.success(), self.diagnostics());
    }

    @Test
    void rejectsForeignReferenceTargetsTypesRolesAndNames() throws IOException {
        for (var replacement : Map.of(
                "foreign target", List.of("target = AuditEvent.class", "target = java.lang.String.class"),
                "wrong key type", List.of("UUID alias", "Long alias"),
                "unsafe name", List.of("account_audit_fk", "account.audit.fk"),
                "structural column", List.of("@Column(name = \"alias\", nullable = true)",
                        "@Id @Column(name = \"alias\", nullable = true)")).entrySet()) {
            var sources = referenceSources();
            sources.computeIfPresent("example/Account.java", (path, source) -> source
                    .replace(replacement.getValue().get(0), replacement.getValue().get(1)));
            Compilation compilation = compile(sources);
            assertFalse(compilation.success(), replacement.getKey());
            assertFalse(Files.exists(compilation.classesDirectory()
                    .resolve("META-INF/vev/example.BillingModel.schema.json")));
        }
    }

    @Test
    void compilesNamedTenantUniqueConstraintsWithCanonicalFingerprintAndManifest() throws IOException {
        String first = "@jakarta.persistence.UniqueConstraint(name = \"account_alias_key\", columnNames = {\"tenant_id\", \"alias\"})";
        String second = "@jakarta.persistence.UniqueConstraint(name = \"account_name_balance_key\", columnNames = {\"tenant_id\", \"display_name\", \"balance\"})";
        Compilation original = compile(uniqueSources(first + ", " + second));
        Compilation reordered = compile(uniqueSources(second + ", " + first));
        assertTrue(original.success(), original.diagnostics());
        assertTrue(reordered.success(), reordered.diagnostics());
        assertEquals(original.manifest("example.BillingModel"), reordered.manifest("example.BillingModel"));
        assertEquals(original.generated("example/AccountVev.java"), reordered.generated("example/AccountVev.java"));
        assertTrue(original.generated("example/AccountVev.java").contains(
                "new no.beint.vev.pg.PgUnique(\"account_name_balance_key\", java.util.List.of(1, 3, 4))"));
        assertTrue(original.manifest("example.BillingModel").contains(
                "\"columns\": [\"tenant_id\", \"display_name\", \"balance\"], \"method\": \"btree\", \"nullsDistinct\": true, \"deferrable\": false"));
        Compilation renamed = compile(uniqueSources(first.replace("account_alias_key", "account_alias_unique") + ", " + second));
        assertTrue(renamed.success(), renamed.diagnostics());
        assertNotEquals(original.generated("example/BillingModelVev.java"), renamed.generated("example/BillingModelVev.java"));
        Compilation differentColumns = compile(uniqueSources(first));
        assertTrue(differentColumns.success(), differentColumns.diagnostics());
        assertNotEquals(original.generated("example/BillingModelVev.java"), differentColumns.generated("example/BillingModelVev.java"));
    }

    @Test
    void rejectsImplicitGlobalAmbiguousAndUnboundedUniqueConstraints() throws IOException {
        String valid = "@jakarta.persistence.UniqueConstraint(name = \"account_alias_key\", columnNames = {\"tenant_id\", \"alias\"})";
        for (String declaration : List.of(
                valid.replace("name = \"account_alias_key\", ", ""),
                valid.replace("account_alias_key", "account.alias.key"),
                valid.replace("\"tenant_id\", \"alias\"", "\"alias\""),
                valid.replace("\"tenant_id\", \"alias\"", "\"alias\", \"tenant_id\""),
                valid.replace("\"alias\"", "\"unknown\""),
                valid.replace("\"alias\"", "\"version\""),
                valid.replace("\"alias\"", "\"id\""),
                valid.replace("\"alias\"", "\"alias\", \"alias\""),
                valid.replace("})", "}, options = \"DEFERRABLE\")"),
                valid.replace("account_alias_key", "account_display_name_idx"),
                valid.replace("account_alias_key", "account"),
                valid + ", " + valid)) {
            Compilation compilation = compile(uniqueSources(declaration));
            assertFalse(compilation.success(), declaration);
            assertFalse(Files.exists(compilation.classesDirectory().resolve("META-INF/vev/example.BillingModel.schema.json")));
        }
        var oversized = uniqueSources(valid);
        oversized.computeIfPresent("example/Account.java", (path, source) -> source
                .replace("@VevIndex(name = \"account_alias_idx\")", "").replace("length = 64", "length = 1024"));
        Compilation unbounded = compile(oversized);
        assertFalse(unbounded.success());
        assertTrue(unbounded.diagnostics().contains("B-tree key budget"), unbounded.diagnostics());
        Compilation tooMany = compile(uniqueSources(java.util.stream.IntStream.range(0, 17)
                .mapToObj(index -> valid.replace("account_alias_key", "account_alias_key_" + index))
                .collect(java.util.stream.Collectors.joining(", "))));
        assertFalse(tooMany.success());
    }

    private static Map<String, String> uniqueSources(String constraints) {
        var sources = positiveSources();
        sources.computeIfPresent("example/Account.java", (path, source) -> source
                .replace("@Table(name = \"account\", schema = \"ledger\")",
                        "@Table(name = \"account\", schema = \"ledger\", uniqueConstraints = {" + constraints + "})"));
        return sources;
    }

    private static Map<String, String> referenceSources() {
        var sources = new LinkedHashMap<>(positiveSources());
        sources.computeIfPresent("example/Account.java", (path, source) -> source
                .replace("@VevIndex(name = \"account_alias_idx\")",
                        "@no.beint.vev.VevReference(name = \"account_audit_fk\", target = AuditEvent.class)\n"
                                + "@VevIndex(name = \"account_alias_idx\")")
                .replace("@Column(name = \"alias\", nullable = true, length = 64) String alias",
                        "@Column(name = \"alias\", nullable = true) UUID alias"));
        return sources;
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
        cases.put("implicitNullability", new NegativeCase(
                recordSource(validTable(), validComponents().replace(
                        "@Column(name = \"display_name\", nullable = false, length = 255)",
                        "@Column(name = \"display_name\", length = 255)"), ""),
                "Every mapped component must explicitly declare @Column(nullable = true) or @Column(nullable = false)"));
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
                        "@Column(name = \"tenant_id\", nullable = true)"), ""),
                "Tenant columns must declare @Column(nullable = false)"));
        cases.put("indexOnIdentifier", new NegativeCase(
                recordSource(validTable(), validComponents().replace(
                        "@Id @Column(name = \"id\", nullable = false)",
                        "@VevIndex(name = \"broken_id_idx\") @Id @Column(name = \"id\", nullable = false)"), ""),
                "@VevIndex may only map an ordinary VALUE component"));
        cases.put("invalidIndexName", new NegativeCase(
                recordSource(validTable(), validComponents().replace(
                        "@Column(name = \"display_name\", nullable = false, length = 255)",
                        "@VevIndex(name = \"Bad-Index\") @Column(name = \"display_name\", nullable = false, length = 255)"), ""),
                "Explicit index identifier \"Bad-Index\" must match"));
        cases.put("duplicateIndexName", new NegativeCase(
                recordSource(validTable(), validComponents().replace(
                        "@Column(name = \"display_name\", nullable = false, length = 255) String displayName",
                        "@VevIndex(name = \"broken_value_idx\") @Column(name = \"display_name\", nullable = false, length = 255) String displayName,\n"
                                + "@VevIndex(name = \"broken_value_idx\") @Column(name = \"alias\", nullable = true, length = 64) String alias"), ""),
                "Duplicate explicit index name \"broken_value_idx\""));
        cases.put("oversizedIndexedString", new NegativeCase(
                recordSource(validTable(), validComponents().replace(
                        "@Column(name = \"display_name\", nullable = false, length = 255)",
                        "@VevIndex(name = \"broken_display_name_idx\") @Column(name = \"display_name\", nullable = false, length = 257)"), ""),
                "Indexed String components must declare @Column(length <= 256)"));
        cases.put("oversizedCompositeIndexKey", new NegativeCase(
                recordSource(validTable(), validComponents()
                        .replace("Long id", "String id")
                        .replace("UUID tenantId", "String tenantId")
                        .replace("@Column(name = \"id\", nullable = false)",
                                "@Column(name = \"id\", nullable = false, length = 128)")
                        .replace("@Column(name = \"tenant_id\", nullable = false)",
                                "@Column(name = \"tenant_id\", nullable = false, length = 128)")
                        .replace("@Column(name = \"display_name\", nullable = false, length = 255)",
                                "@VevIndex(name = \"broken_display_name_idx\") @Column(name = \"display_name\", nullable = false, length = 255)"), ""),
                "1536-byte retained-key budget"));
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
        cases.put("customAccessor", new NegativeCase(
                recordSource(validTable(), validComponents(), "public String displayName() { return displayName.strip(); }"),
                "Explicit record accessors are forbidden"));
        cases.put("unrelatedIndex", new NegativeCase(
                recordSource(validTable(), validComponents(),
                        "@VevIndex(name = \"unrelated_idx\") public String helper() { return displayName; }"),
                "Persistence mapping @VevIndex is forbidden on members unrelated to a record component"));
        cases.put("unrelatedReference", new NegativeCase(
                recordSource(validTable(), validComponents(),
                        "@no.beint.vev.VevReference(name = \"unrelated_fk\", target = Broken.class) public Long helper() { return id; }"),
                "Persistence mapping @VevReference is forbidden on members unrelated to a record component"));
        cases.put("reservedIndexToken", new NegativeCase(
                recordSource(validTable(), validComponents().replace(
                        "@Column(name = \"display_name\", nullable = false, length = 255) String displayName",
                        "@VevIndex(name = \"broken_instance_idx\") @Column(name = \"display_name\", nullable = false, length = 255) String instance"), ""),
                "generates reserved token name INSTANCE"));

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
    void rejectsMoreThanSixteenCompileTimeIndexes() throws IOException {
        List<String> components = new ArrayList<>();
        components.add("@Id @Column(name = \"id\", nullable = false) Long id");
        components.add("@TenantKey @Column(name = \"tenant_id\", nullable = false) UUID tenantId");
        components.add("@Version @Column(name = \"version\", nullable = false) int version");
        for (int index = 0; index < 17; index++) {
            components.add("@VevIndex(name = \"broken_value_" + index
                    + "_idx\") @Column(name = \"value_" + index
                    + "\", nullable = false) int value" + index);
        }

        Compilation compilation = compile(Map.of(
                "example/Broken.java", recordSource(validTable(), String.join(",\n", components), ""),
                "example/BrokenModel.java", brokenModelSource()));

        assertFalse(compilation.success(), "Seventeen indexes unexpectedly compiled");
        assertTrue(compilation.diagnostics().contains("must not exceed 16 compile-time indexes"),
                compilation.diagnostics());
    }

    @Test
    void rejectsDuplicatePostgresIndexNamesAcrossOneSchema() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("example/FirstEntity.java", appendOnlyIndexedEntitySource("FirstEntity", "first_entity"));
        sources.put("example/SecondEntity.java", appendOnlyIndexedEntitySource("SecondEntity", "second_entity"));
        sources.put("example/SharedIndexModel.java", """
                package example;

                import no.beint.vev.VevModel;

                @VevModel(entities = {FirstEntity.class, SecondEntity.class})
                public final class SharedIndexModel {
                    private SharedIndexModel() {
                    }
                }
                """);

        Compilation compilation = compile(sources);

        assertFalse(compilation.success(), "A schema-global duplicate index name unexpectedly compiled");
        assertTrue(compilation.diagnostics().contains(
                "Multiple entities in one closed @VevModel require PostgreSQL index ledger.shared_value_idx"),
                compilation.diagnostics());
    }

    @Test
    void rejectsIndexNamesThatCollideWithMappedRelations() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("example/FirstEntity.java", appendOnlyIndexedEntitySource("FirstEntity", "shared_value_idx"));
        sources.put("example/SecondEntity.java", appendOnlyEntitySource("SecondEntity", "second_entity"));
        sources.put("example/CollidingRelationModel.java", """
                package example;

                import no.beint.vev.VevModel;

                @VevModel(entities = {FirstEntity.class, SecondEntity.class})
                public final class CollidingRelationModel {
                    private CollidingRelationModel() {
                    }
                }
                """);

        Compilation compilation = compile(sources);

        assertFalse(compilation.success(), "A relation/index namespace collision unexpectedly compiled");
        assertTrue(compilation.diagnostics().contains(
                "PostgreSQL index ledger.shared_value_idx collides with a mapped relation"),
                compilation.diagnostics());
    }

    @Test
    void insertionRequiresTheAssignedIdentifierCapability() throws IOException {
        String usage = """
                package example;

                public final class InsertUse {
                    public static void insert(no.beint.vev.WriteEntities<BillingModelVev.Model> entities,
                            CAPABILITY<BillingModelVev.Model, Account, Long> type, Account account) {
                        entities.insert(type, account);
                        entities.insertMultiple(type, no.beint.vev.Batch.copyOf(java.util.List.of(account)));
                    }

                    public static void generated(no.beint.vev.WriteEntities<BillingModelVev.Model> entities,
                            Account account, AuditEvent audit) {
                        insert(entities, AccountVev.INSTANCE, account);
                        entities.insert(AuditEventVev.INSTANCE, audit);
                    }
                }
                """;
        for (String capability : List.of("AssignedEntityType", "EntityType")) {
            var sources = new LinkedHashMap<>(positiveSources());
            sources.put("example/InsertUse.java", usage.replace("CAPABILITY", "no.beint.vev." + capability));
            Compilation compilation = compile(sources);
            if (capability.equals("AssignedEntityType")) {
                assertTrue(compilation.success(), compilation.diagnostics());
            } else {
                assertFalse(compilation.success(), "Read capability unexpectedly permitted assigned insertion");
                assertTrue(compilation.diagnostics().contains("insert"), compilation.diagnostics());
                assertTrue(compilation.diagnostics().contains("insertMultiple"), compilation.diagnostics());
            }
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
        return compile(sources, "", true);
    }

    private Compilation compile(Map<String, String> sources, String extraClassPath, boolean process) throws IOException {
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
            List<String> options = new ArrayList<>(List.of(
                    "--release", "27",
                    "-classpath", extraClassPath.isEmpty() ? System.getProperty("java.class.path")
                            : extraClassPath + java.io.File.pathSeparator + System.getProperty("java.class.path"),
                    process ? "-proc:full" : "-proc:none",
                    "-Xlint:all,-processing",
                    "-Werror",
                    "-d", classesDirectory.toString(),
                    "-s", generatedDirectory.toString()));
            if (process) options.addAll(List.of("-processor", VevProcessor.class.getName()));
            boolean success = Boolean.TRUE.equals(compiler.getTask(null, fileManager, diagnostics, options, null, units).call());
            String diagnosticText = diagnostics.getDiagnostics().stream()
                    .map(VevProcessorTest::formatDiagnostic)
                    .collect(java.util.stream.Collectors.joining("\n"));
            return new Compilation(success, diagnosticText, generatedDirectory, classesDirectory);
        }
    }

    @Test
    void verifiesSeparatelyCompiledRecordSnapshotsWithoutChangingGeneratedContracts() throws IOException {
        for (var sources : List.of(positiveSources(), referenceSources(), enumSources("OPEN, CLOSED"),
                uniqueSources("@jakarta.persistence.UniqueConstraint(name = \"account_alias_key\", columnNames = {\"tenant_id\", \"alias\"})"))) {
            Compilation fromSource = compile(sources);
            assertTrue(fromSource.success(), fromSource.diagnostics());
            String model = sources.remove("example/BillingModel.java");
            Compilation dependency = compile(sources, "", false);
            assertTrue(dependency.success(), dependency.diagnostics());
            Compilation fromDependency = compile(Map.of("example/BillingModel.java", model), dependency.classesDirectory().toString(), true);
            assertTrue(fromDependency.success(), fromDependency.diagnostics());
            assertEquals(fromSource.manifest("example.BillingModel"), fromDependency.manifest("example.BillingModel"));
            assertEquals(fromSource.generated("example/AccountVev.java"), fromDependency.generated("example/AccountVev.java"));
        }
    }

    @Test
    void compiledRecordsCannotHideConstructorAccessorOrInitializationBehavior() throws IOException {
        for (String body : List.of(
                "public Account { displayName = displayName.strip(); }",
                "public Account { System.setProperty(\"vev.verifier.executed\", \"yes\"); }",
                "public String displayName() { return displayName.toUpperCase(java.util.Locale.ROOT); }",
                "public String displayName() { return alias; }",
                "public int version() { return version + 1; }",
                "static { System.setProperty(\"vev.verifier.executed\", \"yes\"); }",
                "private static final String VALUE = System.getProperty(\"java.version\");")) {
            var sources = positiveSources();
            String model = sources.remove("example/BillingModel.java");
            sources.computeIfPresent("example/Account.java", (path, source) -> source.replace(
                    "private static final String ENTITY_KIND = \"account\";", body));
            Compilation dependency = compile(sources, "", false);
            assertTrue(dependency.success(), dependency.diagnostics());
            Compilation consumer = compile(Map.of("example/BillingModel.java", model), dependency.classesDirectory().toString(), true);
            assertFalse(consumer.success(), body);
            assertTrue(consumer.diagnostics().contains("could not verify compiled record"), consumer.diagnostics());
            assertFalse(Files.exists(consumer.classesDirectory().resolve("META-INF/vev/example.BillingModel.schema.json")));
            assertEquals(null, System.getProperty("vev.verifier.executed"));
        }
    }

    @Test
    void verifiesPackagedRecordsIncludingTheSelectedMultiReleaseClass() throws IOException {
        var sources = positiveSources();
        String model = sources.remove("example/BillingModel.java");
        Compilation dependency = compile(sources, "", false);
        assertTrue(dependency.success(), dependency.diagnostics());
        var changedSources = new LinkedHashMap<>(sources);
        changedSources.computeIfPresent("example/Account.java", (path, source) -> source.replace(
                "private static final String ENTITY_KIND = \"account\";", "public String displayName() { return alias; }"));
        Compilation changed = compile(changedSources, "", false);
        assertTrue(changed.success(), changed.diagnostics());
        for (boolean multiRelease : List.of(false, true)) {
            Path jar = temporaryDirectory.resolve(multiRelease ? "changed-version.jar" : "records.jar");
            var manifest = new java.util.jar.Manifest();
            manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
            if (multiRelease) manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MULTI_RELEASE, "true");
            try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(jar), manifest);
                 var paths = Files.walk(dependency.classesDirectory())) {
                for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                    output.putNextEntry(new java.util.jar.JarEntry(dependency.classesDirectory().relativize(file).toString()));
                    Files.copy(file, output);
                    output.closeEntry();
                }
                if (multiRelease) {
                    output.putNextEntry(new java.util.jar.JarEntry("META-INF/versions/27/example/Account.class"));
                    Files.copy(changed.classesDirectory().resolve("example/Account.class"), output);
                    output.closeEntry();
                }
            }
            Compilation consumer = compile(Map.of("example/BillingModel.java", model), jar.toString(), true);
            assertEquals(!multiRelease, consumer.success(), consumer.diagnostics());
            if (multiRelease) {
                assertTrue(consumer.diagnostics().contains("accessor must directly return"), consumer.diagnostics());
            }
        }
    }

    @Test
    void compiledRecordAnnotationsRemainSubjectToTheExactMappingProfile() throws IOException {
        for (String declaration : List.of(
                "@Column(name = \"alias\", length = 64)",
                "@jakarta.persistence.Basic @Column(name = \"alias\", nullable = true, length = 64)",
                "@Column(name = \"alias\", nullable = true, length = 64, unique = true)")) {
            var sources = positiveSources();
            String model = sources.remove("example/BillingModel.java");
            sources.computeIfPresent("example/Account.java", (path, source) -> source.replace(
                    "@Column(name = \"alias\", nullable = true, length = 64)", declaration));
            Compilation dependency = compile(sources, "", false);
            assertTrue(dependency.success(), dependency.diagnostics());
            Compilation consumer = compile(Map.of("example/BillingModel.java", model), dependency.classesDirectory().toString(), true);
            assertFalse(consumer.success(), declaration);
            assertFalse(Files.exists(consumer.classesDirectory().resolve("META-INF/vev/example.BillingModel.schema.json")));
        }
    }

    @Test
    void uncalledRecordHelpersDoNotAlterTheGeneratedPersistenceContract() throws IOException {
        var sources = positiveSources();
        Compilation original = compile(sources);
        sources.computeIfPresent("example/Account.java", (path, source) -> source.replace(
                "private static final String ENTITY_KIND = \"account\";", """
                        public boolean equals(Object other) { throw new AssertionError(); }
                        public int hashCode() { throw new AssertionError(); }
                        public String toString() { throw new AssertionError(); }
                        public String label() { return displayName.strip(); }
                        """));
        Compilation fromSource = compile(sources);
        assertTrue(fromSource.success(), fromSource.diagnostics());
        assertEquals(original.manifest("example.BillingModel"), fromSource.manifest("example.BillingModel"));
        String model = sources.remove("example/BillingModel.java");
        Compilation dependency = compile(sources, "", false);
        assertTrue(dependency.success(), dependency.diagnostics());
        Compilation consumer = compile(Map.of("example/BillingModel.java", model), dependency.classesDirectory().toString(), true);
        assertTrue(consumer.success(), consumer.diagnostics());
        assertEquals(original.generated("example/AccountVev.java"), consumer.generated("example/AccountVev.java"));
    }

    @Test
    void compilesKotlinJvmRecordsAndRejectsNullabilityAndConstructorMismatches() throws IOException {
        Compilation valid = compile(Map.of("example/KotlinModel.java",
                modelSource("KotlinModel", "no.beint.vev.fixtures.KotlinEntry")));
        assertTrue(valid.success(), valid.diagnostics());
        assertTrue(valid.manifest("example.KotlinModel").contains("\"javaType\": \"no.beint.vev.fixtures.KotlinEntry\""));
        assertTrue(valid.generated("no/beint/vev/fixtures/KotlinEntryVev.java").contains("-> entity.label();"));
        for (String entity : List.of("KotlinNullableMismatch", "KotlinRequiredMismatch", "KotlinConstructor")) {
            Compilation invalid = compile(Map.of("example/KotlinModel.java",
                    modelSource("KotlinModel", "no.beint.vev.fixtures." + entity)));
            assertFalse(invalid.success(), entity);
            assertTrue(invalid.diagnostics().contains(entity.equals("KotlinConstructor")
                    ? "canonical constructor must only assign" : "Kotlin record nullability must exactly match"), invalid.diagnostics());
            assertFalse(Files.exists(invalid.classesDirectory().resolve("META-INF/vev/example.KotlinModel.schema.json")));
        }
    }

    @Test
    void kotlinNullChecksRequireVerifiedCalleeInitializationAndNonNullControlFlow() throws IOException {
        String safe = "if (value == null) throwParameterIsNullNPE(name);";
        for (String body : List.of(
                "public static void checkNotNullParameter(Object value, String name) { " + safe + " }",
                "static { System.setProperty(\"vev.fake.intrinsics.executed\", \"yes\"); } public static void checkNotNullParameter(Object value, String name) { " + safe + " }",
                "public static void checkNotNullParameter(Object value, String name) { if (value != null) throwParameterIsNullNPE(name); }",
                "public static void checkNotNullParameter(Object value, String name) { System.setProperty(\"vev.fake.intrinsics.executed\", \"yes\"); }",
                "public static synchronized void checkNotNullParameter(Object value, String name) { " + safe + " }")) {
            String source = "package kotlin.jvm.internal; public final class Intrinsics { " + body
                    + " private static void throwParameterIsNullNPE(String name) { throw new NullPointerException(name); } }";
            Compilation dependency = compile(Map.of("kotlin/jvm/internal/Intrinsics.java", source), "", false);
            assertTrue(dependency.success(), dependency.diagnostics());
            Compilation consumer = compile(Map.of("example/KotlinModel.java",
                    modelSource("KotlinModel", "no.beint.vev.fixtures.KotlinEntry")), dependency.classesDirectory().toString(), true);
            boolean expected = body.equals("public static void checkNotNullParameter(Object value, String name) { " + safe + " }");
            assertEquals(expected, consumer.success(), consumer.diagnostics());
            if (!expected) {
                assertTrue(consumer.diagnostics().contains("could not verify compiled record"), consumer.diagnostics());
            }
            assertEquals(null, System.getProperty("vev.fake.intrinsics.executed"));
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
                import no.beint.vev.VevIndex;

                @Entity
                @Table(name = "account", schema = "ledger")
                public record Account(
                        @Id @Column(name = "id", nullable = false) Long id,
                        @TenantKey @Column(name = "tenant_id", nullable = false) UUID tenantId,
                        @Version @Column(name = "version", nullable = false) int version,
                        @VevIndex(name = "account_display_name_idx")
                        @Column(name = "display_name", nullable = false, length = 255) String displayName,
                        @Column(name = "balance", nullable = false, precision = 19, scale = 2) BigDecimal balance,
                        @VevIndex(name = "account_alias_idx")
                        @Column(name = "alias", nullable = true, length = 64) String alias) {
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
                import no.beint.vev.VevIndex;

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

    private static String appendOnlyIndexedEntitySource(String entityName, String tableName) {
        return """
                package example;

                import jakarta.persistence.Column;
                import jakarta.persistence.Entity;
                import jakarta.persistence.Id;
                import jakarta.persistence.Table;
                import java.util.UUID;
                import no.beint.vev.AppendOnly;
                import no.beint.vev.TenantKey;
                import no.beint.vev.VevIndex;

                @Entity
                @AppendOnly
                @Table(name = "%s", schema = "ledger")
                public record %s(
                        @Id @Column(name = "id", nullable = false) UUID id,
                        @TenantKey @Column(name = "tenant_id", nullable = false) UUID tenantId,
                        @VevIndex(name = "shared_value_idx")
                        @Column(name = "value", nullable = false, length = 64) String value) {
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

    private record Compilation(boolean success, String diagnostics, Path generatedDirectory, Path classesDirectory) {
        String generated(String relativePath) throws IOException {
            return Files.readString(generatedDirectory.resolve(relativePath));
        }

        String manifest(String modelName) throws IOException {
            return Files.readString(classesDirectory.resolve("META-INF/vev/" + modelName + ".schema.json"));
        }
    }
}
