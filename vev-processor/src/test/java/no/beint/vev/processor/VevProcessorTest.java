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
        assertTrue(accountPlan.contains("public int generatedPlanAbi() {\n        return 4;\n    }"));
        assertTrue(auditPlan.contains("public int generatedPlanAbi() {\n        return 4;\n    }"));
        assertEquals(4, no.beint.vev.pg.spi.PgEntityPlan.ABI_VERSION);
        assertTrue(accountPlan.contains("implements no.beint.vev.pg.spi.PgVersionedEntityPlan<example.BillingModelVev.Model, example.Account, java.lang.Long, java.util.UUID, java.lang.Integer>"));
        assertTrue(accountPlan.contains("return new example.Account("));
        assertTrue(accountPlan.contains("new no.beint.vev.pg.PgColumn(\"id\""));
        assertTrue(accountPlan.contains("public static final no.beint.vev.pg.PgRequiredIndex<example.BillingModelVev.Model, example.Account, java.lang.Long, java.lang.String> DISPLAY_NAME"));
        assertTrue(accountPlan.contains("new no.beint.vev.pg.PgRequiredIndex<>(INSTANCE, \"account_display_name_idx\", 3, java.lang.String.class)"));
        assertTrue(accountPlan.contains("public static final no.beint.vev.pg.PgNullableIndex<example.BillingModelVev.Model, example.Account, java.lang.Long, java.lang.String> ALIAS"));
        assertTrue(accountPlan.contains("new no.beint.vev.pg.PgNullableIndex<>(INSTANCE, \"account_alias_idx\", 5, java.lang.String.class)"));
        assertTrue(accountPlan.contains("public java.util.List<no.beint.vev.pg.PgQueryIndex<example.BillingModelVev.Model, example.Account, java.lang.Long, ?>> indexes()"));
        assertTrue(accountPlan.contains("return INDEXES;"));
        assertTrue(accountPlan.contains("public Object columnValue(example.Account entity, int columnIndex)"));
        assertTrue(accountPlan.contains("public example.Account readRow(java.sql.ResultSet resultSet, int firstColumn) throws java.sql.SQLException"));
        assertFalse(accountPlan.contains("Object[] columnValues"));
        assertFalse(accountPlan.contains("PreparedStatement"));
        assertTrue(accountPlan.contains(".readChecked(resultSet, firstColumn, COLUMNS.get(0))"));
        assertFalse(accountPlan.contains("resultSet.next("));
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
        assertTrue(auditPlan.contains("implements no.beint.vev.pg.spi.PgTenantEntityPlan<example.BillingModelVev.Model, example.AuditEvent, java.util.UUID, java.util.UUID>"));
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
    void compilesExactPrimaryKeyShapesWithTenantLeadingIdentifierIndexes() throws IOException {
        Compilation original = compile(positiveSources());
        for (String shape : List.of("TENANT_ID", "ID_TENANT", "ID")) {
            var sources = new LinkedHashMap<>(positiveSources());
            sources.computeIfPresent("example/Account.java", (path, source) -> source
                    .replace("@Entity\n", "@Entity\n@no.beint.vev.VevPrimaryKey(no.beint.vev.VevPrimaryKey.Shape." + shape + ")\n")
                    .replace("@Id @Column", "@no.beint.vev.VevIndex(name = \"account_tenant_id_idx\") @Id @Column"));
            Compilation result = compile(sources);
            assertTrue(result.success(), result.diagnostics());
            String primary = switch (shape) {
                case "TENANT_ID" -> "\"tenant_id\", \"id\"";
                case "ID_TENANT" -> "\"id\", \"tenant_id\"";
                default -> "\"id\"";
            };
            assertTrue(result.manifest("example.BillingModel").contains("\"primaryKey\": [" + primary + "]"));
            assertTrue(result.manifest("example.BillingModel").contains("\"name\": \"account_tenant_id_idx\","
                    + " \"method\": \"btree\", \"unique\": false, \"columns\": [\"tenant_id\", \"id\"]"));
            assertNotEquals(original.manifest("example.BillingModel"), result.manifest("example.BillingModel"));
            String model = sources.remove("example/BillingModel.java");
            Compilation dependency = compile(sources, "", false);
            assertTrue(dependency.success(), dependency.diagnostics());
            Compilation binary = compile(Map.of("example/BillingModel.java", model), dependency.classesDirectory().toString(), true);
            assertTrue(binary.success(), binary.diagnostics());
            assertEquals(result.generated("example/AccountVev.java"), binary.generated("example/AccountVev.java"));
            assertEquals(result.manifest("example.BillingModel"), binary.manifest("example.BillingModel"));
        }
        var explicitDefault = positiveSources();
        explicitDefault.computeIfPresent("example/Account.java", (path, source) -> source
                .replace("@Entity\n", "@Entity\n@no.beint.vev.VevPrimaryKey(no.beint.vev.VevPrimaryKey.Shape.TENANT_ID)\n"));
        Compilation defaults = compile(explicitDefault);
        assertTrue(defaults.success(), defaults.diagnostics());
        assertEquals(original.generated("example/AccountVev.java"), defaults.generated("example/AccountVev.java"));
        assertEquals(original.manifest("example.BillingModel"), defaults.manifest("example.BillingModel"));
    }

    @Test
    void idFirstPrimaryKeysRequireAnIndexedTenantTraversalPath() throws IOException {
        for (String unique : List.of("", "\"id\", \"tenant_id\"", "\"tenant_id\", \"id\"")) {
            var sources = unique.isEmpty() ? positiveSources() : uniqueSources(
                    "@jakarta.persistence.UniqueConstraint(name = \"account_tenant_id_key\", columnNames = {" + unique + "})");
            sources.computeIfPresent("example/Account.java", (path, source) -> source
                    .replace("@Entity\n", "@Entity\n@no.beint.vev.VevPrimaryKey(no.beint.vev.VevPrimaryKey.Shape.ID)\n"));
            Compilation result = compile(sources);
            assertEquals(unique.startsWith("\"tenant_id\""), result.success(), result.diagnostics());
            if (!result.success()) assertTrue(result.diagnostics().contains("for bounded scans"), result.diagnostics());
        }
    }

    @Test
    void referencesToGlobalIdentifiersRequireAnExactTenantQualifiedUniqueTarget() throws IOException {
        var sources = referenceSources();
        sources.computeIfPresent("example/AuditEvent.java", (path, source) -> source
                .replace("@Entity\n", "@Entity\n@no.beint.vev.VevPrimaryKey(no.beint.vev.VevPrimaryKey.Shape.ID)\n")
                .replace("@Id @Column", "@no.beint.vev.VevIndex(name = \"audit_tenant_id_idx\") @Id @Column"));
        Compilation missing = compile(sources);
        assertFalse(missing.success());
        assertTrue(missing.diagnostics().contains("requires a tenant-qualified @UniqueConstraint"), missing.diagnostics());
        for (String columns : List.of("\"id\", \"tenant_id\"", "\"tenant_id\", \"id\"")) {
            var valid = new LinkedHashMap<>(sources);
            valid.computeIfPresent("example/AuditEvent.java", (path, source) -> source
                    .replace("schema = \"ledger\")", "schema = \"ledger\", uniqueConstraints = "
                            + "@jakarta.persistence.UniqueConstraint(name = \"audit_tenant_key\", columnNames = {" + columns + "}))"));
            Compilation result = compile(valid);
            assertTrue(result.success(), result.diagnostics());
        }
    }

    @Test
    void compilesNamedTableAndColumnChecksWithExactEscapingAndBinaryParity() throws IOException {
        String expression = "\nCASE\n    WHEN (display_name = 'quote\"; -- \\" + "u000a\t🙂'::text) THEN true\n    ELSE false\nEND";
        String annotation = "@jakarta.persistence.CheckConstraint(name = \"account_name_check\", constraint = " + javaLiteral(expression) + ")";
        var tableSources = positiveSources();
        tableSources.computeIfPresent("example/Account.java", (path, source) -> source
                .replace("@Table(name = \"account\", schema = \"ledger\")",
                        "@Table(name = \"account\", schema = \"ledger\", check = " + annotation + ")"));
        Compilation table = compile(tableSources);
        assertTrue(table.success(), table.diagnostics());
        var columnSources = positiveSources();
        columnSources.computeIfPresent("example/Account.java", (path, source) -> source
                .replace("@Column(name = \"display_name\",", "@Column(check = " + annotation + ", name = \"display_name\","));
        Compilation column = compile(columnSources);
        assertTrue(column.success(), column.diagnostics());
        assertEquals(table.generated("example/AccountVev.java"), column.generated("example/AccountVev.java"));
        assertEquals(table.manifest("example.BillingModel"), column.manifest("example.BillingModel"));
        assertTrue(table.generated("example/AccountVev.java").contains("new no.beint.vev.pg.PgCheck(\"account_name_check\""));
        String model = tableSources.remove("example/BillingModel.java");
        Compilation dependency = compile(tableSources, "", false);
        assertTrue(dependency.success(), dependency.diagnostics());
        Compilation binary = compile(Map.of("example/BillingModel.java", model), dependency.classesDirectory().toString(), true);
        assertTrue(binary.success(), binary.diagnostics());
        assertEquals(table.generated("example/AccountVev.java"), binary.generated("example/AccountVev.java"));
        assertEquals(table.manifest("example.BillingModel"), binary.manifest("example.BillingModel"));
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{table.classesDirectory().toUri().toURL()}, getClass().getClassLoader())) {
            Object instance = loader.loadClass("example.AccountVev").getField("INSTANCE").get(null);
            var plan = (no.beint.vev.pg.spi.PgEntityPlan<?, ?, ?, ?>) instance;
            assertEquals(expression, plan.checkConstraints().getFirst().expression());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void checkOrderingIsDeterministicAndDefinitionChangesInvalidateSchemaIdentity() throws IOException {
        var sources = positiveSources();
        String first = "@jakarta.persistence.CheckConstraint(name = \"a_check\", constraint = \"(id > 0)\")";
        String second = "@jakarta.persistence.CheckConstraint(name = \"b_check\", constraint = \"(version >= 0)\")";
        sources.computeIfPresent("example/Account.java", (path, source) -> source.replace(
                "@Table(name = \"account\", schema = \"ledger\")", "@Table(name = \"account\", schema = \"ledger\", check = {" + second + ", " + first + "})"));
        Compilation original = compile(sources);
        assertTrue(original.success(), original.diagnostics());
        sources.computeIfPresent("example/Account.java", (path, source) -> source.replace(second + ", " + first, first + ", " + second));
        Compilation reordered = compile(sources);
        assertTrue(reordered.success(), reordered.diagnostics());
        assertEquals(original.manifest("example.BillingModel"), reordered.manifest("example.BillingModel"));
        sources.computeIfPresent("example/Account.java", (path, source) -> source.replace("id > 0", "id >= 0"));
        Compilation changed = compile(sources);
        assertTrue(changed.success(), changed.diagnostics());
        assertNotEquals(original.generated("example/BillingModelVev.java"), changed.generated("example/BillingModelVev.java"));
    }

    @Test
    void rejectsCheckOptionsUnsafeNamesDuplicatesAndUnboundedExpressions() throws IOException {
        String valid = "@jakarta.persistence.CheckConstraint(name = \"account_check\", constraint = \"(id > 0)\")";
        for (String metadata : List.of(valid.replace("account_check", "unsafe.name"), valid.replace("(id > 0)", ""),
                valid.replace("(id > 0)", "x".repeat(4097)), valid.replace("constraint =", "options = \"NOT VALID\", constraint ="),
                valid + ", " + valid, String.join(", ", java.util.Collections.nCopies(33, valid)))) {
            var sources = positiveSources();
            sources.computeIfPresent("example/Account.java", (path, source) -> source.replace(
                    "@Table(name = \"account\", schema = \"ledger\")", "@Table(name = \"account\", schema = \"ledger\", check = {" + metadata + "})"));
            Compilation result = compile(sources);
            assertFalse(result.success(), metadata);
            assertFalse(Files.exists(result.classesDirectory().resolve("META-INF/vev/example.BillingModel.schema.json")));
        }
        var collision = referenceSources();
        collision.computeIfPresent("example/Account.java", (path, source) -> source.replace(
                "@Table(name = \"account\", schema = \"ledger\")", "@Table(name = \"account\", schema = \"ledger\", check = "
                        + valid.replace("account_check", "account_audit_fk") + ")"));
        assertFalse(compile(collision).success());
    }

    private static String javaLiteral(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\"";
    }

    @Test
    void boundedBinaryMappingsPreserveTypedChecksAndSourceClasspathParity() throws IOException {
        String entity = binaryRecordSource(20 * 1024 * 1024).replace("@Entity", "@Entity @no.beint.vev.VevRows(1)");
        Compilation source = compile(Map.of("example/Broken.java", entity, "example/BrokenModel.java", brokenModelSource()));
        assertTrue(source.success(), source.diagnostics());
        String generated = source.generated("example/BrokenVev.java");
        assertTrue(generated.contains("no.beint.vev.pg.PgCodecs.BINARY"), generated);
        assertTrue(generated.contains("PgCheck.binaryMaximum(\"broken_payload_max\", \"payload\", 20971520)"), generated);
        String manifest = source.manifest("example.BrokenModel");
        assertTrue(manifest.contains("\"kind\": \"BINARY_MAXIMUM\""), manifest);
        assertTrue(manifest.contains("\"maximumBytes\": 20971520"), manifest);
        Compilation dependency = compile(Map.of("example/Broken.java", entity), "", false);
        assertTrue(dependency.success(), dependency.diagnostics());
        Compilation binary = compile(Map.of("example/BrokenModel.java", brokenModelSource()), dependency.classesDirectory().toString(), true);
        assertTrue(binary.success(), binary.diagnostics());
        assertEquals(generated, binary.generated("example/BrokenVev.java"));
        assertEquals(manifest, binary.manifest("example.BrokenModel"));
        Compilation changed = compile(Map.of("example/Broken.java", entity.replace("20971520", "20971519"),
                "example/BrokenModel.java", brokenModelSource()));
        assertTrue(changed.success(), changed.diagnostics());
        assertNotEquals(source.generated("example/BrokenModelVev.java"), changed.generated("example/BrokenModelVev.java"));
    }

    @Test
    void rejectsMutableBinaryMissingBoundsWrongRolesAndConflictingColumnMetadata() throws IOException {
        String valid = binaryRecordSource(32);
        for (String entity : List.of(
                valid.replace("@no.beint.vev.VevBinary(maximumBytes = 32, check = \"broken_payload_max\")", ""),
                valid.replace("no.beint.vev.Binary payload", "byte[] payload"),
                valid.replace("no.beint.vev.Binary payload", "java.nio.ByteBuffer payload"),
                valid.replace("no.beint.vev.Binary payload", "String payload"),
                valid.replace("name = \"payload\"", "length = 32, name = \"payload\""),
                valid.replace("name = \"payload\"", "precision = 32, name = \"payload\""),
                valid.replace("broken_payload_max", "bad.name"),
                valid.replace("broken_payload_max", ""),
                valid.replace("@Id @Column", "@Column").replace("@no.beint.vev.VevBinary", "@Id @no.beint.vev.VevBinary"),
                valid.replace("@TenantKey @Column", "@Column").replace("@no.beint.vev.VevBinary", "@TenantKey @no.beint.vev.VevBinary"),
                valid.replace("@Version @Column", "@Column").replace("@no.beint.vev.VevBinary", "@Version @no.beint.vev.VevBinary"),
                valid.replace("schema = \"ledger\")", "schema = \"ledger\", check = @CheckConstraint(name = \"broken_payload_max\", constraint = \"true\"))"))) {
            Compilation result = compile(Map.of("example/Broken.java", entity, "example/BrokenModel.java", brokenModelSource()));
            assertFalse(result.success(), entity);
            assertFalse(Files.exists(result.classesDirectory().resolve("META-INF/vev/example.BrokenModel.schema.json")));
        }
        for (int invalid : List.of(Integer.MIN_VALUE, -1, 0, 33554433, Integer.MAX_VALUE)) {
            Compilation result = compile(Map.of("example/Broken.java", binaryRecordSource(invalid),
                    "example/BrokenModel.java", brokenModelSource()));
            assertFalse(result.success());
            assertTrue(result.diagnostics().contains("@VevBinary.maximumBytes"), result.diagnostics());
        }
    }

    @Test
    void binaryRowAndIndexBudgetsIncludePayloadBytesAndPagingSentinels() throws IOException {
        for (int bytes : List.of(20 * 1024 * 1024, 32 * 1024 * 1024)) {
            Compilation result = compile(Map.of("example/Broken.java", binaryRecordSource(bytes),
                    "example/BrokenModel.java", brokenModelSource()));
            assertFalse(result.success());
            assertTrue(result.diagnostics().contains("64 MiB materialized-result"), result.diagnostics());
        }
        // Even one row needs space for the paging sentinel and object overhead.
        Compilation tooLarge = compile(Map.of("example/Broken.java", binaryRecordSource(32 * 1024 * 1024)
                .replace("@Entity", "@Entity @no.beint.vev.VevRows(1)"), "example/BrokenModel.java", brokenModelSource()));
        assertFalse(tooLarge.success());
        assertTrue(tooLarge.diagnostics().contains("64 MiB materialized-result"), tooLarge.diagnostics());
        for (int bytes : List.of(32, 2048)) {
            String indexed = binaryRecordSource(bytes).replace("@no.beint.vev.VevBinary", "@VevIndex(name = \"payload_idx\") @no.beint.vev.VevBinary");
            String unique = binaryRecordSource(bytes).replace("schema = \"ledger\")", "schema = \"ledger\", uniqueConstraints = "
                    + "@UniqueConstraint(name = \"payload_key\", columnNames = {\"tenant_id\", \"payload\"}))");
            for (String entity : List.of(indexed, unique)) {
                Compilation result = compile(Map.of("example/Broken.java", entity, "example/BrokenModel.java", brokenModelSource()));
                assertEquals(bytes == 32, result.success(), result.diagnostics());
                if (bytes == 32 && entity.equals(indexed)) {
                    assertTrue(result.generated("example/BrokenVev.java").contains("no.beint.vev.Binary> PAYLOAD"));
                }
            }
        }
    }

    private static String binaryRecordSource(int bytes) {
        String components = validComponents().replace(
                "@Column(name = \"display_name\", nullable = false, length = 255) String displayName",
                "@no.beint.vev.VevBinary(maximumBytes = " + bytes + ", check = \"broken_payload_max\") "
                        + "@Column(name = \"payload\", nullable = false) no.beint.vev.Binary payload");
        return recordSource(validTable(), components, "");
    }

    @Test
    void textMappingsCompileExplicitCharacterBoundsAndPreserveClasspathMetadata() throws IOException {
        String entity = textRecordSource(1048576).replace("@Entity", "@Entity @no.beint.vev.VevRows(4)");
        Compilation source = compile(Map.of("example/Broken.java", entity, "example/BrokenModel.java", brokenModelSource()));
        assertTrue(source.success(), source.diagnostics());
        assertTrue(source.generated("example/BrokenVev.java").contains("PgCheck.textMaximum(\"broken_display_name_max\", \"display_name\", 1048576)"));
        assertTrue(source.manifest("example.BrokenModel").contains("\"kind\": \"TEXT_MAXIMUM\""));
        assertTrue(source.manifest("example.BrokenModel").contains("\"maximumCodePoints\": 1048576"));
        Compilation dependency = compile(Map.of("example/Broken.java", entity), "", false);
        assertTrue(dependency.success(), dependency.diagnostics());
        Compilation binary = compile(Map.of("example/BrokenModel.java", brokenModelSource()), dependency.classesDirectory().toString(), true);
        assertTrue(binary.success(), binary.diagnostics());
        assertEquals(source.generated("example/BrokenVev.java"), binary.generated("example/BrokenVev.java"));
        assertEquals(source.manifest("example.BrokenModel"), binary.manifest("example.BrokenModel"));
        Compilation changed = compile(Map.of("example/Broken.java", entity.replace("1048576", "1048575"), "example/BrokenModel.java", brokenModelSource()));
        assertTrue(changed.success(), changed.diagnostics());
        assertNotEquals(source.generated("example/BrokenModelVev.java"), changed.generated("example/BrokenModelVev.java"));
    }

    @Test
    void textMappingsRejectImplicitBoundsWrongTypesRolesAndConflictingSelectors() throws IOException {
        String valid = textRecordSource(32);
        for (String entity : List.of(valid.replace(", length = 32", ""), valid.replace("String displayName", "Integer displayName"),
                valid.replace("@Id @Column", "@Column").replace("@no.beint.vev.VevText", "@Id @no.beint.vev.VevText"),
                valid.replace("@TenantKey @Column", "@Column").replace("@no.beint.vev.VevText", "@TenantKey @no.beint.vev.VevText"),
                valid.replace("@Version @Column", "@Column").replace("@no.beint.vev.VevText", "@Version @no.beint.vev.VevText"),
                valid.replace("broken_display_name_max", "bad.name"), valid.replace("broken_display_name_max", ""),
                valid.replace("name = \"display_name\"", "columnDefinition = \"text\", name = \"display_name\""),
                valid.replace("name = \"display_name\"", "precision = 32, name = \"display_name\""),
                valid.replace("@no.beint.vev.VevText", "@no.beint.vev.VevBinary(maximumBytes = 32, check = \"binary_max\") @no.beint.vev.VevText"),
                valid.replace("schema = \"ledger\")", "schema = \"ledger\", check = @CheckConstraint(name = \"broken_display_name_max\", constraint = \"true\"))"))) {
            Compilation result = compile(Map.of("example/Broken.java", entity, "example/BrokenModel.java", brokenModelSource()));
            assertFalse(result.success(), entity);
            assertFalse(Files.exists(result.classesDirectory().resolve("META-INF/vev/example.BrokenModel.schema.json")));
        }
        for (int invalid : List.of(-1, 0, 8388609, Integer.MAX_VALUE)) {
            Compilation result = compile(Map.of("example/Broken.java", textRecordSource(invalid), "example/BrokenModel.java", brokenModelSource()));
            assertFalse(result.success());
            assertTrue(result.diagnostics().contains("@VevText requires explicit @Column.length"), result.diagnostics());
        }
    }

    @Test
    void textMappingsRetainResultAndStringIndexBudgets() throws IOException {
        for (String entity : List.of(textRecordSource(1048576), textRecordSource(8388608).replace("@Entity", "@Entity @no.beint.vev.VevRows(1)"))) {
            Compilation result = compile(Map.of("example/Broken.java", entity, "example/BrokenModel.java", brokenModelSource()));
            assertFalse(result.success());
            assertTrue(result.diagnostics().contains("64 MiB materialized-result"), result.diagnostics());
        }
        for (int length : List.of(256, 257)) {
            String entity = textRecordSource(length).replace("@no.beint.vev.VevText", "@VevIndex(name = \"text_idx\") @no.beint.vev.VevText");
            Compilation result = compile(Map.of("example/Broken.java", entity, "example/BrokenModel.java", brokenModelSource()));
            assertEquals(length == 256, result.success(), result.diagnostics());
        }
    }

    private static String textRecordSource(int codePoints) {
        return recordSource(validTable(), validComponents().replace("@Column(name = \"display_name\"",
                "@no.beint.vev.VevText(check = \"broken_display_name_max\") @Column(name = \"display_name\"")
                .replace("length = 255", "length = " + codePoints), "");
    }

    @Test
    void localTimeMappingsCompileTypedIndexesAcrossSourceAndClasspathBoundaries() throws IOException {
        String entity = localTimeRecordSource();
        Compilation source = compile(Map.of("example/Broken.java", entity, "example/BrokenModel.java", brokenModelSource()));
        assertTrue(source.success(), source.diagnostics());
        assertTrue(source.generated("example/BrokenVev.java").contains("java.time.LocalTime> DISPLAY_NAME"));
        assertTrue(source.generated("example/BrokenVev.java").contains("PgCodecs.LOCAL_TIME"));
        assertTrue(source.manifest("example.BrokenModel").contains("\"databaseType\": \"time\""));
        Compilation dependency = compile(Map.of("example/Broken.java", entity), "", false);
        assertTrue(dependency.success(), dependency.diagnostics());
        Compilation binary = compile(Map.of("example/BrokenModel.java", brokenModelSource()), dependency.classesDirectory().toString(), true);
        assertTrue(binary.success(), binary.diagnostics());
        assertEquals(source.generated("example/BrokenVev.java"), binary.generated("example/BrokenVev.java"));
        assertEquals(source.manifest("example.BrokenModel"), binary.manifest("example.BrokenModel"));
    }

    @Test
    void localTimeRejectsLegacyTypesOffsetsStructuralKeysAndLossyPrecisionMetadata() throws IOException {
        String valid = localTimeRecordSource();
        for (String entity : List.of(valid.replace("java.time.LocalTime", "java.sql.Time"),
                valid.replace("java.time.LocalTime", "java.time.OffsetTime"),
                valid.replace("name = \"display_name\"", "secondPrecision = 3, name = \"display_name\""),
                valid.replace("name = \"display_name\"", "length = 8, name = \"display_name\""),
                valid.replace("name = \"display_name\"", "columnDefinition = \"time\", name = \"display_name\""),
                valid.replace("@VevIndex", "@Temporal(TemporalType.TIME) @VevIndex"),
                valid.replace("Long id", "java.time.LocalTime id"), valid.replace("UUID tenantId", "java.time.LocalTime tenantId"),
                valid.replace("int version", "java.time.LocalTime version"))) {
            Compilation result = compile(Map.of("example/Broken.java", entity, "example/BrokenModel.java", brokenModelSource()));
            assertFalse(result.success(), entity);
            assertFalse(Files.exists(result.classesDirectory().resolve("META-INF/vev/example.BrokenModel.schema.json")));
        }
    }

    private static String localTimeRecordSource() {
        return recordSource(validTable(), validComponents().replace("String displayName", "java.time.LocalTime displayName")
                .replace(", length = 255", "").replace("@Column(name = \"display_name\"", "@VevIndex(name = \"clock_idx\") @Column(name = \"display_name\""), "");
    }

    @Test
    void referenceColumnOrderIsExplicitAndStableAcrossCompilationBoundaries() throws IOException {
        var sources = referenceSources();
        Compilation tenantFirst = compile(sources);
        sources.computeIfPresent("example/Account.java", (path, source) -> source
                .replace("target = AuditEvent.class", "target = AuditEvent.class, tenantFirst = false"));
        Compilation idFirst = compile(sources);
        assertTrue(idFirst.success(), idFirst.diagnostics());
        assertTrue(idFirst.generated("example/AccountVev.java").contains(
                "new no.beint.vev.pg.PgReference(\"account_audit_fk\", 5, example.AuditEvent.class, false)"));
        assertTrue(idFirst.manifest("example.BillingModel").contains("\"columns\": [\"alias\", \"tenant_id\"],"
                + " \"targetSchema\": \"ledger\", \"targetTable\": \"audit_event\", \"targetColumns\": [\"id\", \"tenant_id\"]"));
        assertNotEquals(tenantFirst.generated("example/BillingModelVev.java"), idFirst.generated("example/BillingModelVev.java"));
        String model = sources.remove("example/BillingModel.java");
        Compilation dependency = compile(sources, "", false);
        assertTrue(dependency.success(), dependency.diagnostics());
        Compilation binary = compile(Map.of("example/BillingModel.java", model), dependency.classesDirectory().toString(), true);
        assertTrue(binary.success(), binary.diagnostics());
        assertEquals(idFirst.generated("example/AccountVev.java"), binary.generated("example/AccountVev.java"));
        assertEquals(idFirst.manifest("example.BillingModel"), binary.manifest("example.BillingModel"));
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
        cases.put("indexOnTenant", new NegativeCase(
                recordSource(validTable(), validComponents().replace(
                        "@TenantKey @Column(name = \"tenant_id\", nullable = false)",
                        "@VevIndex(name = \"broken_tenant_idx\") @TenantKey @Column(name = \"tenant_id\", nullable = false)"), ""),
                "@VevIndex may map an ID or VALUE component"));
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
                "PostgreSQL IDENTITY requires a Short, Integer, or Long identifier"));
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
    void orderedIndexesGenerateTypedCursorsAndIdenticalSourceAndBinaryContracts() throws IOException, ReflectiveOperationException {
        var sources = orderedSources();
        Compilation source = compile(sources);
        assertTrue(source.success(), source.diagnostics());
        String plan = source.generated("example/AccountVev.java");
        assertTrue(plan.contains("PgRequiredOrderedIndex<example.BillingModelVev.Model, example.Account, java.lang.Long, java.lang.String, java.math.BigDecimal> DISPLAY_NAME"));
        assertTrue(plan.contains("PgNullableOrderedIndex<example.BillingModelVev.Model, example.Account, java.lang.Long, java.lang.String, java.math.BigDecimal> ALIAS"));
        assertTrue(source.manifest("example.BillingModel").contains("\"columns\": [\"tenant_id\", \"display_name\", \"balance\", \"id\"]"));
        String model = sources.remove("example/BillingModel.java");
        Compilation dependency = compile(sources, "", false);
        assertTrue(dependency.success(), dependency.diagnostics());
        Compilation binary = compile(Map.of("example/BillingModel.java", model), dependency.classesDirectory().toString(), true);
        assertTrue(binary.success(), binary.diagnostics());
        assertEquals(plan, binary.generated("example/AccountVev.java"));
        assertEquals(source.manifest("example.BillingModel"), binary.manifest("example.BillingModel"));
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{source.classesDirectory().toUri().toURL()}, getClass().getClassLoader())) {
            var compiled = (no.beint.vev.pg.PgModel<?, ?>) loader.loadClass("example.BillingModelVev").getField("POSTGRES").get(null);
            assertEquals(java.util.UUID.class, compiled.tenantType());
        }
        var defaults = positiveSources();
        Compilation implicit = compile(defaults);
        defaults.computeIfPresent("example/Account.java", (path, text) -> text.replace("@VevIndex(name =", "@VevIndex(orderBy = \"\", direction = VevIndex.Direction.ASC, name ="));
        Compilation explicit = compile(defaults);
        assertTrue(implicit.success(), implicit.diagnostics());
        assertTrue(explicit.success(), explicit.diagnostics());
        assertEquals(implicit.manifest("example.BillingModel"), explicit.manifest("example.BillingModel"));
        assertEquals(implicit.generated("example/AccountVev.java"), explicit.generated("example/AccountVev.java"));
        assertNotEquals(implicit.manifest("example.BillingModel"), source.manifest("example.BillingModel"));
    }

    @Test
    void orderedDirectionsAreExplicitAndSurviveSeparateCompilation() throws IOException {
        var sources = orderedSources();
        Compilation ascending = compile(sources);
        assertTrue(ascending.success(), ascending.diagnostics());
        sources.computeIfPresent("example/Account.java", (path, text) -> text.replace("@VevIndex(orderBy =", "@VevIndex(direction = VevIndex.Direction.ASC, orderBy ="));
        Compilation explicit = compile(sources);
        assertTrue(explicit.success(), explicit.diagnostics());
        assertEquals(ascending.manifest("example.BillingModel"), explicit.manifest("example.BillingModel"));
        assertEquals(ascending.generated("example/AccountVev.java"), explicit.generated("example/AccountVev.java"));
        sources.computeIfPresent("example/Account.java", (path, text) -> text.replace("VevIndex.Direction.ASC", "VevIndex.Direction.DESC"));
        Compilation descending = compile(sources);
        assertTrue(descending.success(), descending.diagnostics());
        assertTrue(descending.manifest("example.BillingModel").contains("\"directions\": [\"ASC\", \"ASC\", \"DESC\", \"DESC\"]"));
        assertTrue(descending.generated("example/AccountVev.java").contains("no.beint.vev.VevIndex.Direction.DESC"));
        assertNotEquals(ascending.manifest("example.BillingModel"), descending.manifest("example.BillingModel"));
        String model = sources.remove("example/BillingModel.java");
        Compilation dependency = compile(sources, "", false);
        assertTrue(dependency.success(), dependency.diagnostics());
        Compilation binary = compile(Map.of("example/BillingModel.java", model), dependency.classesDirectory().toString(), true);
        assertTrue(binary.success(), binary.diagnostics());
        assertEquals(descending.manifest("example.BillingModel"), binary.manifest("example.BillingModel"));
        assertEquals(descending.generated("example/AccountVev.java"), binary.generated("example/AccountVev.java"));
        var invalid = positiveSources();
        invalid.computeIfPresent("example/Account.java", (path, text) -> text.replace("@VevIndex(name =", "@VevIndex(direction = VevIndex.Direction.DESC, name ="));
        Compilation invalidSource = compile(invalid);
        assertFalse(invalidSource.success());
        assertTrue(invalidSource.diagnostics().contains("DESC requires an explicit orderBy column"), invalidSource.diagnostics());
        String invalidModel = invalid.remove("example/BillingModel.java");
        Compilation invalidDependency = compile(invalid, "", false);
        assertTrue(invalidDependency.success(), invalidDependency.diagnostics());
        Compilation invalidBinary = compile(Map.of("example/BillingModel.java", invalidModel), invalidDependency.classesDirectory().toString(), true);
        assertFalse(invalidBinary.success());
        assertTrue(invalidBinary.diagnostics().contains("DESC requires an explicit orderBy column"), invalidBinary.diagnostics());
    }

    @Test
    void orderedIndexesAcceptBoundedBuiltInScalarOrderingCodecs() throws IOException, ReflectiveOperationException {
        var codecs = new LinkedHashMap<String, String>();
        for (String type : List.of("Boolean", "Short", "Integer", "Long", "java.util.UUID", "java.time.LocalDate", "java.time.LocalDateTime", "java.time.LocalTime", "java.time.Instant")) {
            codecs.put(type, "@jakarta.persistence.Column(name = \"ordering\", nullable = false)");
        }
        codecs.put("String", "@jakarta.persistence.Column(name = \"ordering\", nullable = false, length = 64)");
        codecs.put("java.math.BigDecimal", "@jakarta.persistence.Column(name = \"ordering\", nullable = false, precision = 19, scale = 4)");
        codecs.put("no.beint.vev.Binary", "@no.beint.vev.VevBinary(maximumBytes = 32, check = \"ordering_bound\") @jakarta.persistence.Column(name = \"ordering\", nullable = false)");
        codecs.put("Rank", "@jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING) @jakarta.persistence.Column(name = \"ordering\", nullable = false, length = 64)");
        for (var codec : codecs.entrySet()) {
            var sources = new LinkedHashMap<String, String>();
            sources.put("example/Rank.java", "package example; public enum Rank { FIRST, SECOND }");
            sources.put("example/Ordered.java", """
                    package example;
                    import jakarta.persistence.*;
                    import no.beint.vev.*;
                    @Entity @AppendOnly @Table(name = "ordered", schema = "ledger")
                    public record Ordered(
                        @Id @Column(name = "id", nullable = false) Integer id,
                        @TenantKey @Column(name = "tenant_id", nullable = false) Integer tenant,
                        @VevIndex(name = "ordered_filter_idx", orderBy = "ordering") @Column(name = "filter", nullable = false) Boolean filter,
                        %s %s ordering) {}
                    """.formatted(codec.getValue(), codec.getKey()));
            sources.put("example/OrderedModel.java", "package example; @no.beint.vev.VevModel(entities = Ordered.class) public class OrderedModel {}");
            Compilation compilation = compile(sources);
            assertTrue(compilation.success(), codec.getKey() + ": " + compilation.diagnostics());
            try (var loader = new java.net.URLClassLoader(new java.net.URL[]{compilation.classesDirectory().toUri().toURL()}, getClass().getClassLoader())) {
                var model = (no.beint.vev.pg.PgModel<?, ?>) loader.loadClass("example.OrderedModelVev").getField("POSTGRES").get(null);
                assertEquals(1, model.plans().size());
            }
        }
    }

    @Test
    void orderedIndexMetadataRejectsUnsafeRolesNamesNullabilityAndRetainedKeyBounds() throws IOException {
        for (String order : List.of("unknown", "balance DESC", "tenant_id", "id", "version", "display_name", "alias")) {
            var sources = positiveSources();
            sources.computeIfPresent("example/Account.java", (path, text) -> text.replace("name = \"account_display_name_idx\"", "name = \"account_display_name_idx\", orderBy = \"" + order + "\""));
            Compilation source = compile(sources);
            assertFalse(source.success(), order);
            String model = sources.remove("example/BillingModel.java");
            Compilation dependency = compile(sources, "", false);
            assertTrue(dependency.success(), dependency.diagnostics());
            Compilation binary = compile(Map.of("example/BillingModel.java", model), dependency.classesDirectory().toString(), true);
            assertFalse(binary.success(), order);
        }
        var longOrdering = positiveSources();
        longOrdering.computeIfPresent("example/Account.java", (path, text) -> text
                .replace("name = \"account_alias_idx\"", "name = \"account_alias_idx\", orderBy = \"display_name\"")
                .replace("@VevIndex(name = \"account_display_name_idx\")", "")
                .replace("length = 255", "length = 257"));
        Compilation longSource = compile(longOrdering);
        assertFalse(longSource.success());
        assertTrue(longSource.diagnostics().contains("ordering String columns must not exceed 256"), longSource.diagnostics());
        longOrdering.computeIfPresent("example/Account.java", (path, text) -> text.replace("length = 257", "length = 256").replace("length = 64", "length = 256"));
        Compilation oversized = compile(longOrdering);
        assertFalse(oversized.success());
        assertTrue(oversized.diagnostics().contains("1536-byte retained-key budget"), oversized.diagnostics());
    }

    @Test
    void orderedQueryCallSitesRequireTheOrderingTypeAndIndexBoundCursor() throws IOException {
        String valid = """
                package example;
                class OrderedCalls {
                    void run() {
                        var index = AccountVev.DISPLAY_NAME;
                        var cursor = index.cursor(new java.math.BigDecimal("1.00"), AccountVev.INSTANCE.key(1L));
                        no.beint.vev.pg.PgQueries.equal(index, "A", new no.beint.vev.QueryLimit(10));
                        no.beint.vev.pg.PgQueries.equalAfter(index, "A", cursor, new no.beint.vev.QueryLimit(10));
                        no.beint.vev.pg.PgQueries.isNull(AccountVev.ALIAS, new no.beint.vev.QueryLimit(10));
                        no.beint.vev.pg.PgQueries.isNullAfter(AccountVev.ALIAS, AccountVev.ALIAS.cursor(new java.math.BigDecimal("1.00"), AccountVev.INSTANCE.key(1L)), new no.beint.vev.QueryLimit(10));
                    }
                }
                """;
        var sources = orderedSources();
        sources.put("example/OrderedCalls.java", valid);
        Compilation success = compile(sources);
        assertTrue(success.success(), success.diagnostics());
        for (String operation : List.of(
                "AccountVev.DISPLAY_NAME.cursor(1L, AccountVev.INSTANCE.key(1L))",
                "AccountVev.DISPLAY_NAME.cursor(new java.math.BigDecimal(1), AuditEventVev.INSTANCE.key(java.util.UUID.randomUUID()))",
                "no.beint.vev.pg.PgQueries.equalAfter(AccountVev.DISPLAY_NAME, \"A\", AccountVev.INSTANCE.key(1L), new no.beint.vev.QueryLimit(1))",
                "no.beint.vev.pg.PgQueries.isNull(AccountVev.DISPLAY_NAME, new no.beint.vev.QueryLimit(1))",
                "no.beint.vev.pg.PgQueries.equal(AccountVev.DISPLAY_NAME, 1L, new no.beint.vev.QueryLimit(1))")) {
            sources.put("example/OrderedCalls.java", "package example; class OrderedCalls { void run() { " + operation + "; } }");
            Compilation failure = compile(sources);
            assertFalse(failure.success(), operation);
        }
    }

    private static Map<String, String> orderedSources() {
        var sources = new LinkedHashMap<>(positiveSources());
        sources.computeIfPresent("example/Account.java", (path, text) -> text.replace("@VevIndex(name =", "@VevIndex(orderBy = \"balance\", name ="));
        return sources;
    }

    @Test
    void sharedOnlyModelsRequireExplicitStableScopeTypesAndPreserveSourceBinaryContracts() throws IOException, ReflectiveOperationException {
        var manifests = new java.util.HashMap<String, String>();
        for (String literal : List.of("java.lang.Integer", "int", "java.lang.Long", "long", "java.lang.Short", "short", "java.lang.String", "java.util.UUID")) {
            String boxed = switch (literal) {
                case "int" -> "java.lang.Integer";
                case "long" -> "java.lang.Long";
                case "short" -> "java.lang.Short";
                default -> literal;
            };
            var sources = sharedOnlySources(literal);
            Compilation source = compile(sources);
            assertTrue(source.success(), source.diagnostics());
            String plan = source.generated("example/AReferenceVev.java");
            assertTrue(plan.contains("public Class<" + boxed + "> scopeType()"));
            assertTrue(plan.contains("return " + boxed + ".class;"));
            assertFalse(plan.contains("tenantCodec()"));
            String manifest = source.manifest("example.BillingModel");
            assertTrue(manifest.contains("\"tenantScopeType\": \"" + boxed + "\""), manifest);
            String previous = manifests.putIfAbsent(boxed, manifest);
            if (previous != null) assertEquals(previous, manifest);
            try (var loader = new java.net.URLClassLoader(new java.net.URL[]{source.classesDirectory().toUri().toURL()}, getClass().getClassLoader())) {
                var registry = loader.loadClass("example.BillingModelVev");
                var model = (no.beint.vev.pg.PgModel<?, ?>) registry.getField("POSTGRES").get(null);
                var authority = (no.beint.vev.TenantAuthority<?, ?>) registry.getMethod("newTenantAuthority").invoke(null);
                assertEquals(boxed, model.tenantType().getName());
                assertEquals(model.tenantType(), authority.tenantType());
                assertEquals(1, model.plans().size());
            }
            String model = sources.remove("example/BillingModel.java");
            Compilation dependency = compile(sources, "", false);
            assertTrue(dependency.success(), dependency.diagnostics());
            Compilation binary = compile(Map.of("example/BillingModel.java", model), dependency.classesDirectory().toString(), true);
            assertTrue(binary.success(), binary.diagnostics());
            assertEquals(plan, binary.generated("example/AReferenceVev.java"));
            assertEquals(manifest, binary.manifest("example.BillingModel"));
        }
        assertEquals(5, new java.util.HashSet<>(manifests.values()).size());
    }

    @Test
    void explicitTenantScopeDefaultsPreserveInferredMappingContracts() throws IOException {
        var sources = sharedSources();
        Compilation implicit = compile(sources);
        assertTrue(implicit.success(), implicit.diagnostics());
        for (String literal : List.of("void", "java.util.UUID")) {
            var explicitSources = sharedSources();
            explicitSources.computeIfPresent("example/BillingModel.java", (path, text) -> text.replace("@VevModel(", "@VevModel(tenantType = " + literal + ".class, "));
            Compilation explicit = compile(explicitSources);
            assertTrue(explicit.success(), explicit.diagnostics());
            assertEquals(implicit.manifest("example.BillingModel"), explicit.manifest("example.BillingModel"));
            assertEquals(implicit.generated("example/BillingModelVev.java"), explicit.generated("example/BillingModelVev.java"));
            assertEquals(implicit.generated("example/AReferenceVev.java"), explicit.generated("example/AReferenceVev.java"));
        }
    }

    @Test
    void explicitTenantTypesRejectUnsupportedShapesAndConflictingMappedOwnership() throws IOException {
        for (String literal : List.of("void", "java.lang.Void", "java.lang.Object", "boolean", "byte", "char", "double", "java.math.BigDecimal", "java.lang.Integer[]")) {
            var sources = sharedOnlySources(literal);
            Compilation source = compile(sources);
            assertFalse(source.success(), literal);
            assertTrue(source.diagnostics().contains("tenantType"), source.diagnostics());
            String model = sources.remove("example/BillingModel.java");
            Compilation dependency = compile(sources, "", false);
            assertTrue(dependency.success(), dependency.diagnostics());
            Compilation binary = compile(Map.of("example/BillingModel.java", model), dependency.classesDirectory().toString(), true);
            assertFalse(binary.success(), literal);
            assertTrue(binary.diagnostics().contains("tenantType"), binary.diagnostics());
        }
        var conflict = sharedSources();
        conflict.computeIfPresent("example/BillingModel.java", (path, text) -> text.replace("@VevModel(", "@VevModel(tenantType = Integer.class, "));
        Compilation conflicting = compile(conflict);
        assertFalse(conflicting.success());
        assertTrue(conflicting.diagnostics().contains("same tenant key type"), conflicting.diagnostics());
        String model = conflict.remove("example/BillingModel.java");
        Compilation dependency = compile(conflict, "", false);
        assertTrue(dependency.success(), dependency.diagnostics());
        Compilation binary = compile(Map.of("example/BillingModel.java", model), dependency.classesDirectory().toString(), true);
        assertFalse(binary.success());
        assertTrue(binary.diagnostics().contains("same tenant key type"), binary.diagnostics());
    }

    @Test
    void sharedOnlyScopesRemainTypedAndCannotCompileWriteCapabilities() throws IOException {
        for (String operation : List.of("BillingModelVev.newTenantAuthority().scope(\"wrong\")",
                "write.insert(AReferenceVev.INSTANCE, value)", "write.update(AReferenceVev.INSTANCE, value)",
                "write.create(AReferenceVev.INSTANCE, value)", "new no.beint.vev.DeleteTarget<>(AReferenceVev.INSTANCE, 1L, 0L)")) {
            var sources = sharedOnlySources("int");
            sources.put("example/ScopeCalls.java", "package example; class ScopeCalls { void run(no.beint.vev.WriteEntities<BillingModelVev.Model> write, AReference value) { " + operation + "; } }");
            Compilation compilation = compile(sources);
            assertFalse(compilation.success(), operation);
            assertTrue(compilation.diagnostics().contains(operation.contains(".scope(") ? "java.lang.Integer" : "AReferenceVev"), compilation.diagnostics());
        }
    }

    private static Map<String, String> sharedOnlySources(String scopeType) {
        var sources = sharedSources();
        sources.remove("example/Account.java");
        sources.remove("example/AuditEvent.java");
        sources.computeIfPresent("example/BillingModel.java", (path, text) -> text
                .replace("AuditEvent.class, Account.class, ", "")
                .replace("@VevModel(", "@VevModel(tenantType = " + scopeType + ".class, "));
        return sources;
    }

    @Test
    void sharedMappingsGenerateExplicitReadOnlyOwnershipAndIdenticalSourceAndBinaryPlans() throws IOException, ReflectiveOperationException {
        for (boolean identity : List.of(false, true)) {
            for (boolean version : List.of(false, true)) {
                var sources = sharedSources();
                sources.computeIfPresent("example/AReference.java", (path, text) -> {
                    String result = identity ? text.replace("@Id @Column", "@Id @GeneratedValue(strategy = GenerationType.IDENTITY) @Column") : text;
                    return version ? result : result.replace("@Version ", "");
                });
                Compilation source = compile(sources);
                assertTrue(source.success(), source.diagnostics());
                String plan = source.generated("example/AReferenceVev.java");
                assertTrue(plan.contains("PgSharedEntityPlan<example.BillingModelVev.Model, example.AReference, java.lang.Long, java.util.UUID>"));
                for (String absent : List.of("tenantCodec()", "tenantColumn()", "tenantKeyOf(", "PgTenantEntityPlan<", "AssignedEntityType", "PgGeneratedEntityPlan", "PgVersionedEntityPlan", "record New(")) {
                    assertFalse(plan.contains(absent), absent);
                }
                assertEquals(identity, plan.contains("PgIdentityEntityPlan<"));
                String manifest = source.manifest("example.BillingModel");
                assertTrue(manifest.contains("\"shared\": true"));
                assertTrue(manifest.contains("\"primaryKey\": [\"id\"]"));
                assertTrue(manifest.contains("\"columns\": [\"label\", \"id\"]"));
                assertTrue(manifest.contains("\"columns\": [\"code\"]"));
                assertTrue(manifest.contains("\"rowSecurity\": {\"enabled\": false, \"forced\": false, \"policies\": []}"));
                assertTrue(manifest.contains("\"columns\": [\"parent_id\"], \"targetSchema\": \"ledger\", \"targetTable\": \"shared_reference\", \"targetColumns\": [\"id\"]"));
                try (var loader = new java.net.URLClassLoader(new java.net.URL[]{source.classesDirectory().toUri().toURL()}, getClass().getClassLoader())) {
                    var model = (no.beint.vev.pg.PgModel<?, ?>) loader.loadClass("example.BillingModelVev").getField("POSTGRES").get(null);
                    assertEquals(java.util.UUID.class, model.tenantType());
                    assertEquals(3, model.plans().size());
                }
                String model = sources.remove("example/BillingModel.java");
                Compilation dependency = compile(sources, "", false);
                assertTrue(dependency.success(), dependency.diagnostics());
                Compilation binary = compile(Map.of("example/BillingModel.java", model), dependency.classesDirectory().toString(), true);
                assertTrue(binary.success(), binary.diagnostics());
                assertEquals(plan, binary.generated("example/AReferenceVev.java"));
                assertEquals(manifest, binary.manifest("example.BillingModel"));
            }
        }
    }

    @Test
    void sharedMappingsRejectImplicitAccessTenantOwnershipAndForeignTenantReferences() throws IOException {
        var cases = new LinkedHashMap<String, String>();
        cases.put("@VevShared requires @VevReadOnly", "noReadOnly");
        cases.put("@VevShared forbids @TenantKey", "tenant");
        cases.put("Every Vev entity must declare exactly one @TenantKey", "implicit");
        cases.put("requires at least one tenant-owned mapping", "onlyShared");
        cases.put("@VevShared requires an ID-only primary key", "primaryKey");
        cases.put("@VevShared unique constraints may contain only VALUE columns", "uniqueId");
        cases.put("@VevShared unique constraints require one to 32 VALUE columns", "emptyUnique");
        cases.put("@VevShared rows cannot reference tenant-owned rows", "tenantReference");
        cases.put("has no tenant order", "referenceOrder");
        for (var entry : cases.entrySet()) {
            var sources = sharedSources();
            sources.computeIfPresent("example/AReference.java", (path, text) -> switch (entry.getValue()) {
                case "noReadOnly" -> text.replace("@VevReadOnly", "");
                case "tenant" -> text.replace("@Column(name = \"code\"", "@TenantKey @Column(name = \"code\"");
                case "implicit" -> text.replace("@VevShared", "");
                case "primaryKey" -> text.replace("@VevShared", "@VevShared @VevPrimaryKey(VevPrimaryKey.Shape.ID_TENANT)");
                case "uniqueId" -> text.replace("columnNames = \"code\"", "columnNames = \"id\"");
                case "emptyUnique" -> text.replace("columnNames = \"code\"", "columnNames = {}");
                case "tenantReference" -> text.replace("target = AReference.class", "target = Account.class");
                case "referenceOrder" -> text.replace("target = AReference.class", "target = AReference.class, tenantFirst = false");
                default -> text;
            });
            if (entry.getValue().equals("onlyShared")) sources.computeIfPresent("example/BillingModel.java", (path, text) -> text.replace("AuditEvent.class, Account.class, ", ""));
            Compilation source = compile(sources);
            assertFalse(source.success(), entry.getValue());
            assertTrue(source.diagnostics().contains(entry.getKey()), source.diagnostics());
            String model = sources.remove("example/BillingModel.java");
            Compilation dependency = compile(sources, "", false);
            assertTrue(dependency.success(), dependency.diagnostics());
            Compilation binary = compile(Map.of("example/BillingModel.java", model), dependency.classesDirectory().toString(), true);
            assertFalse(binary.success(), entry.getValue());
            assertTrue(binary.diagnostics().contains(entry.getKey()), binary.diagnostics());
        }
    }

    @Test
    void sharedReadOnlyCapabilitiesCannotCompileNativeWrites() throws IOException {
        for (String operation : List.of("write.insert(AReferenceVev.INSTANCE, value)", "write.update(AReferenceVev.INSTANCE, value)",
                "write.create(AReferenceVev.INSTANCE, value)", "new no.beint.vev.DeleteTarget<>(AReferenceVev.INSTANCE, 1L, 0L)")) {
            var sources = sharedSources();
            sources.put("example/SharedWrite.java", "package example; class SharedWrite { void run(no.beint.vev.WriteEntities<BillingModelVev.Model> write, AReference value) { " + operation + "; } }");
            Compilation compilation = compile(sources);
            assertFalse(compilation.success(), operation);
            assertTrue(compilation.diagnostics().contains("AReferenceVev"), compilation.diagnostics());
        }
    }

    private static Map<String, String> sharedSources() {
        var sources = new LinkedHashMap<>(positiveSources());
        sources.computeIfPresent("example/BillingModel.java", (path, text) -> text.replace("AuditEvent.class, Account.class", "AuditEvent.class, Account.class, AReference.class"));
        sources.put("example/AReference.java", """
                package example;
                import jakarta.persistence.*;
                import no.beint.vev.*;
                @Entity @VevShared @VevReadOnly
                @Table(name = "shared_reference", schema = "ledger", uniqueConstraints = @UniqueConstraint(name = "shared_code_key", columnNames = "code"))
                public record AReference(
                    @Id @Column(name = "id", nullable = false) Long id,
                    @Version @Column(name = "version", nullable = false) Long version,
                    @Column(name = "code", nullable = false, length = 32) String code,
                    @VevIndex(name = "shared_label_idx") @Column(name = "label", nullable = true, length = 64) String label,
                    @VevReference(name = "shared_parent_fk", target = AReference.class) @Column(name = "parent_id", nullable = true) Long parentId) {}
                """);
        return sources;
    }

    @Test
    void readOnlyMappingsSeparateStoredIdentityAndVersionFromWriteCapabilities() throws IOException {
        for (boolean identity : List.of(false, true)) {
            for (boolean version : List.of(false, true)) {
                var sources = new LinkedHashMap<>(positiveSources());
                sources.computeIfPresent("example/Account.java", (path, source) -> {
                    String result = source.replace("@Entity", "@Entity @no.beint.vev.VevReadOnly");
                    if (identity) result = result.replace("@Id @Column", "@Id @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY) @Column");
                    if (!version) result = result.replace("@Version", "");
                    return result;
                });
                Compilation source = compile(sources);
                assertTrue(source.success(), source.diagnostics());
                String plan = source.generated("example/AccountVev.java");
                assertTrue(plan.contains("PgReadOnlyEntityPlan<"));
                assertEquals(identity, plan.contains("PgIdentityEntityPlan<"));
                for (String absent : List.of("AssignedEntityType", "PgGeneratedEntityPlan", "PgVersionedEntityPlan", "DeletableEntityType", "record New(")) {
                    assertFalse(plan.contains(absent), absent);
                }
                assertTrue(source.manifest("example.BillingModel").contains("\"readOnly\": true"));
                assertTrue(source.manifest("example.BillingModel").contains("\"insert\": [], \"update\": [], \"delete\": false"));
                if (identity) assertTrue(source.manifest("example.BillingModel").contains("\"sequencePrivileges\": []"));
                var explicitNoWrites = new LinkedHashMap<>(sources);
                explicitNoWrites.computeIfPresent("example/Account.java", (path, text) -> text
                        .replace("nullable = false", "nullable = false, insertable = false, updatable = false")
                        .replace("nullable = true", "nullable = true, insertable = false, updatable = false"));
                Compilation noWrites = compile(explicitNoWrites);
                assertTrue(noWrites.success(), noWrites.diagnostics());
                assertEquals(source.manifest("example.BillingModel"), noWrites.manifest("example.BillingModel"));
                assertEquals(plan, noWrites.generated("example/AccountVev.java"));
                String model = sources.remove("example/BillingModel.java");
                Compilation dependency = compile(sources, "", false);
                assertTrue(dependency.success(), dependency.diagnostics());
                Compilation binary = compile(Map.of("example/BillingModel.java", model), dependency.classesDirectory().toString(), true);
                assertTrue(binary.success(), binary.diagnostics());
                assertEquals(plan, binary.generated("example/AccountVev.java"));
                assertEquals(source.manifest("example.BillingModel"), binary.manifest("example.BillingModel"));
            }
        }
    }

    @Test
    void readOnlyDeclarationsRejectConflictingCapabilitiesAndAllWriteCallSites() throws IOException {
        for (String conflict : List.of("@no.beint.vev.AppendOnly", "@no.beint.vev.VevDelete")) {
            var sources = new LinkedHashMap<>(positiveSources());
            sources.computeIfPresent("example/Account.java", (path, source) -> source.replace("@Entity", "@Entity @no.beint.vev.VevReadOnly " + conflict));
            Compilation compilation = compile(sources);
            assertFalse(compilation.success());
        }
        for (String operation : List.of("write.insert(AccountVev.INSTANCE, value)", "write.update(AccountVev.INSTANCE, value)",
                "write.create(AccountVev.INSTANCE, value)", "new no.beint.vev.DeleteTarget<>(AccountVev.INSTANCE, 1L, 0)")) {
            var sources = new LinkedHashMap<>(positiveSources());
            sources.computeIfPresent("example/Account.java", (path, source) -> source.replace("@Entity", "@Entity @no.beint.vev.VevReadOnly"));
            sources.put("example/ReadOnlyWrite.java", "package example; class ReadOnlyWrite { void run(no.beint.vev.WriteEntities<BillingModelVev.Model> write, Account value) { " + operation + "; } }");
            Compilation compilation = compile(sources);
            assertFalse(compilation.success(), operation);
            assertTrue(compilation.diagnostics().contains("AccountVev"), compilation.diagnostics());
        }
    }

    @Test
    void deletionCapabilitiesPreserveSourceAndBinaryContractsAndFingerprintPrivileges() throws IOException {
        var sources = new LinkedHashMap<>(positiveSources());
        sources.computeIfPresent("example/Account.java", (path, source) -> source
                .replace("@Id @Column", "@Id @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY) @Column"));
        Compilation retained = compile(sources);
        sources.computeIfPresent("example/Account.java", (path, source) -> source.replace("@Entity", "@Entity @no.beint.vev.VevDelete"));
        Compilation source = compile(sources);
        assertTrue(source.success(), source.diagnostics());
        assertTrue(source.generated("example/AccountVev.java").contains(
                "no.beint.vev.DeletableEntityType<example.BillingModelVev.Model, example.Account, java.lang.Long, java.lang.Integer>"));
        assertTrue(source.manifest("example.BillingModel").contains("\"delete\": true"));
        assertFalse(retained.generated("example/AccountVev.java").contains("DeletableEntityType"));
        assertNotEquals(source.manifest("example.BillingModel"), retained.manifest("example.BillingModel"));
        String model = sources.remove("example/BillingModel.java");
        Compilation dependency = compile(sources, "", false);
        assertTrue(dependency.success(), dependency.diagnostics());
        Compilation binary = compile(Map.of("example/BillingModel.java", model), dependency.classesDirectory().toString(), true);
        assertTrue(binary.success(), binary.diagnostics());
        assertEquals(source.generated("example/AccountVev.java"), binary.generated("example/AccountVev.java"));
        assertEquals(source.manifest("example.BillingModel"), binary.manifest("example.BillingModel"));
    }

    @Test
    void deletionRejectsAssignedKeysAppendOnlyMappingsAndMissingCapabilitiesAtCompilation() throws IOException {
        for (String path : List.of("example/Account.java", "example/AuditEvent.java")) {
            var sources = new LinkedHashMap<>(positiveSources());
            sources.computeIfPresent(path, (name, source) -> source.replace("@Entity", "@Entity @no.beint.vev.VevDelete"));
            if (path.endsWith("AuditEvent.java")) {
                sources.computeIfPresent(path, (name, source) -> source.replace("UUID id", "Long id")
                        .replace("@Id @Column", "@Id @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY) @Column"));
            }
            Compilation rejected = compile(sources);
            assertFalse(rejected.success());
            assertTrue(rejected.diagnostics().contains("@VevDelete requires a versioned entity with a generated IDENTITY"), rejected.diagnostics());
            String model = sources.remove("example/BillingModel.java");
            Compilation dependency = compile(sources, "", false);
            assertTrue(dependency.success(), dependency.diagnostics());
            Compilation binary = compile(Map.of("example/BillingModel.java", model), dependency.classesDirectory().toString(), true);
            assertFalse(binary.success());
            assertTrue(binary.diagnostics().contains("@VevDelete requires a versioned entity with a generated IDENTITY"), binary.diagnostics());
        }
        var sources = new LinkedHashMap<>(positiveSources());
        sources.put("example/InvalidDelete.java", """
                package example;
                class InvalidDelete {
                    Object target = new no.beint.vev.DeleteTarget<>(AccountVev.INSTANCE, 1L, 0);
                }
                """);
        Compilation consumer = compile(sources);
        assertFalse(consumer.success());
        assertTrue(consumer.diagnostics().contains("DeleteTarget"), consumer.diagnostics());
    }

    @Test
    void identityMappingsGenerateTypedCreationInputsForSourceAndBinaryRecords() throws IOException {
        for (String keyType : List.of("short", "int", "long", "Short", "Integer", "Long")) {
            var sources = new LinkedHashMap<>(positiveSources());
            sources.computeIfPresent("example/Account.java", (path, source) -> source
                    .replace("Long id", keyType + " id")
                    .replace("@Entity", "@Entity @no.beint.vev.VevDelete")
                    .replace("@Id @Column", "@Id @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY) @Column"));
            Compilation source = compile(sources);
            assertTrue(source.success(), source.diagnostics());
            String plan = source.generated("example/AccountVev.java");
            assertTrue(plan.contains("public record New(java.lang.String displayName, java.math.BigDecimal balance, java.lang.String alias)"), plan);
            assertFalse(plan.contains("AssignedEntityType"), plan);
            assertTrue(source.manifest("example.BillingModel").contains("\"sequenceOwnership\": \"INTERNAL\""));
            assertNotEquals(compile(positiveSources()).manifest("example.BillingModel"), source.manifest("example.BillingModel"));
            String model = sources.remove("example/BillingModel.java");
            Compilation dependency = compile(sources, "", false);
            assertTrue(dependency.success(), dependency.diagnostics());
            Compilation binary = compile(Map.of("example/BillingModel.java", model), dependency.classesDirectory().toString(), true);
            assertTrue(binary.success(), binary.diagnostics());
            assertEquals(plan, binary.generated("example/AccountVev.java"));
            assertEquals(source.manifest("example.BillingModel"), binary.manifest("example.BillingModel"));
        }
    }

    @Test
    void identityMappingsRejectAdjacentGeneratorShapesAndAssignedInsertion() throws IOException {
        for (String annotation : List.of("@jakarta.persistence.GeneratedValue",
                "@jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.AUTO)",
                "@jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.SEQUENCE)",
                "@jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.TABLE)",
                "@jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.UUID)",
                "@jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY, generator = \"custom\")")) {
            var sources = new LinkedHashMap<>(positiveSources());
            sources.computeIfPresent("example/Account.java", (path, source) -> source.replace("@Id @Column", "@Id " + annotation + " @Column"));
            Compilation result = compile(sources);
            assertFalse(result.success(), annotation);
            assertTrue(result.diagnostics().contains("explicit strategy = IDENTITY"), result.diagnostics());
        }
        var sources = new LinkedHashMap<>(positiveSources());
        sources.computeIfPresent("example/Account.java", (path, source) -> source.replace("@Id @Column",
                "@Id @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY) @Column"));
        String usage = """
                package example;
                public final class IdentityUse {
                    public static void use(no.beint.vev.WriteEntities<BillingModelVev.Model> entities, Account snapshot) {
                        var input = new AccountVev.New("created", new java.math.BigDecimal("1.00"), null);
                        Account result = entities.create(AccountVev.INSTANCE, input);
                        entities.createMultiple(AccountVev.INSTANCE, no.beint.vev.Batch.one(input));
                        OPERATION
                    }
                }
                """;
        for (String operation : List.of("", "entities.insert(AccountVev.INSTANCE, snapshot);",
                "entities.insertMultiple(AccountVev.INSTANCE, no.beint.vev.Batch.one(snapshot));",
                "entities.create(AccountVev.INSTANCE, snapshot);",
                "entities.create(AuditEventVev.INSTANCE, input);")) {
            sources.put("example/IdentityUse.java", usage.replace("OPERATION", operation));
            Compilation result = compile(sources);
            assertEquals(operation.isEmpty(), result.success(), operation + ": " + result.diagnostics());
        }
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
    void declaredRowLimitsPermitLargerSnapshotsWithBinaryParityAndStableDefaults() throws IOException {
        String entity = recordSource(validTable(), validComponents().replace("length = 255", "length = 65535"), "");
        Compilation unlimited = compile(Map.of("example/Broken.java", entity, "example/BrokenModel.java", brokenModelSource()));
        assertFalse(unlimited.success());
        assertTrue(unlimited.diagnostics().contains("64 MiB materialized-result"), unlimited.diagnostics());
        String bounded = entity.replace("@Entity", "@Entity @no.beint.vev.VevRows(8)");
        Compilation source = compile(Map.of("example/Broken.java", bounded, "example/BrokenModel.java", brokenModelSource()));
        assertTrue(source.success(), source.diagnostics());
        assertTrue(source.generated("example/BrokenVev.java").contains("public int maximumRows() {\n        return 8;"));
        assertTrue(source.manifest("example.BrokenModel").contains("\"maximumRows\": 8"));
        Compilation dependency = compile(Map.of("example/Broken.java", bounded), "", false);
        assertTrue(dependency.success(), dependency.diagnostics());
        Compilation binary = compile(Map.of("example/BrokenModel.java", brokenModelSource()), dependency.classesDirectory().toString(), true);
        assertTrue(binary.success(), binary.diagnostics());
        assertEquals(source.manifest("example.BrokenModel"), binary.manifest("example.BrokenModel"));
        assertEquals(source.generated("example/BrokenVev.java"), binary.generated("example/BrokenVev.java"));
        Compilation changed = compile(Map.of("example/Broken.java", bounded.replace("VevRows(8)", "VevRows(16)"),
                "example/BrokenModel.java", brokenModelSource()));
        assertTrue(changed.success(), changed.diagnostics());
        assertNotEquals(source.generated("example/BrokenModelVev.java"), changed.generated("example/BrokenModelVev.java"));
        var defaults = positiveSources();
        Compilation implicit = compile(defaults);
        defaults.computeIfPresent("example/Account.java", (path, text) -> text.replace("@Entity", "@Entity @no.beint.vev.VevRows(1000)"));
        Compilation explicit = compile(defaults);
        assertTrue(explicit.success(), explicit.diagnostics());
        assertEquals(implicit.manifest("example.BillingModel"), explicit.manifest("example.BillingModel"));
    }

    @Test
    void rejectsInvalidRowLimitsAndCountsTheExtraPagingSentinelInTheMemoryBudget() throws IOException {
        for (int limit : List.of(Integer.MIN_VALUE, -1, 0, 1001, Integer.MAX_VALUE)) {
            String entity = recordSource(validTable(), validComponents(), "").replace("@Entity", "@Entity @no.beint.vev.VevRows(" + limit + ")");
            Compilation result = compile(Map.of("example/Broken.java", entity, "example/BrokenModel.java", brokenModelSource()));
            assertFalse(result.success());
            assertTrue(result.diagnostics().contains("@VevRows must be between 1 and 1000"), result.diagnostics());
            assertFalse(Files.exists(result.generatedDirectory().resolve("example/BrokenVev.java")));
        }
        // This four-column shape estimates 262,588 retained bytes per snapshot: 255 fit, 256 exceed 64 MiB.
        for (int limit : List.of(254, 255)) {
            String entity = recordSource(validTable(), validComponents().replace("length = 255", "length = 65535"), "")
                    .replace("@Entity", "@Entity @no.beint.vev.VevRows(" + limit + ")");
            Compilation result = compile(Map.of("example/Broken.java", entity, "example/BrokenModel.java", brokenModelSource()));
            assertEquals(limit == 254, result.success(), result.diagnostics());
            if (limit == 255) assertTrue(result.diagnostics().contains("64 MiB materialized-result"), result.diagnostics());
        }
    }

    @Test
    void rejectsAggregateCheckMetadataBeforeGeneratingAnyArtifact() throws IOException {
        var sources = new LinkedHashMap<String, String>();
        sources.put("example/CheckText.java", "package example; public final class CheckText { public static final String VALUE = \""
                + "x".repeat(no.beint.vev.pg.PgCheck.MAXIMUM_EXPRESSION_LENGTH) + "\"; }");
        String checks = java.util.stream.IntStream.range(0, no.beint.vev.pg.PgCheck.MAXIMUM_PER_ENTITY)
                .mapToObj(index -> "@jakarta.persistence.CheckConstraint(name = \"check_" + index + "\", constraint = CheckText.VALUE)")
                .collect(java.util.stream.Collectors.joining(", "));
        var entities = new ArrayList<String>();
        int count = no.beint.vev.pg.PgCheck.MAXIMUM_MODEL_CHARACTERS
                / (no.beint.vev.pg.PgCheck.MAXIMUM_PER_ENTITY * no.beint.vev.pg.PgCheck.MAXIMUM_EXPRESSION_LENGTH) + 1;
        for (int index = 0; index < count; index++) {
            String name = "CheckEntity" + index;
            sources.put("example/" + name + ".java", appendOnlyEntitySource(name, "check_entity_" + index)
                    .replace("schema = \"ledger\")", "schema = \"ledger\", check = {" + checks + "})"));
            entities.add(name + ".class");
        }
        sources.put("example/CheckModel.java", "package example; @no.beint.vev.VevModel(entities = {"
                + String.join(", ", entities) + "}) public final class CheckModel {}");
        Compilation result = compile(sources);
        assertFalse(result.success());
        assertTrue(result.diagnostics().contains("retained check-expression budget"), result.diagnostics());
        assertFalse(Files.exists(result.classesDirectory().resolve("META-INF/vev/example.CheckModel.schema.json")));
        assertFalse(Files.exists(result.generatedDirectory().resolve("example/CheckEntity0Vev.java")));
    }

    @Test
    void compilesAndInitializesTheMaximumClosedModelFromSourceAndCompiledDependencies() throws Exception {
        int count = no.beint.vev.VevModel.MAXIMUM_ENTITIES;
        var sources = new LinkedHashMap<String, String>();
        var entities = new ArrayList<String>();
        for (int index = 0; index < count; index++) {
            String name = "ScaleEntity" + index;
            sources.put("example/" + name + ".java", appendOnlyEntitySource(name, "scale_entity_" + index));
            entities.add(name + ".class");
        }
        String modelSource = "package example; @no.beint.vev.VevModel(entities = {"
                + String.join(", ", entities) + "}) public final class ScaleModel {}";
        sources.put("example/ScaleModel.java", modelSource);
        Compilation source = compile(sources);
        assertTrue(source.success(), source.diagnostics());
        sources.remove("example/ScaleModel.java");
        Compilation dependency = compile(sources, "", false);
        assertTrue(dependency.success(), dependency.diagnostics());
        Compilation binary = compile(Map.of("example/ScaleModel.java", modelSource), dependency.classesDirectory().toString(), true);
        assertTrue(binary.success(), binary.diagnostics());
        assertEquals(source.manifest("example.ScaleModel"), binary.manifest("example.ScaleModel"));
        assertEquals(source.generated("example/ScaleModelVev.java"), binary.generated("example/ScaleModelVev.java"));
        for (Compilation compilation : List.of(source, binary)) {
            try (var loader = new java.net.URLClassLoader(new java.net.URL[]{
                    compilation.classesDirectory().toUri().toURL(), dependency.classesDirectory().toUri().toURL()},
                    getClass().getClassLoader())) {
                Class<?> registry = Class.forName("example.ScaleModelVev", true, loader);
                var model = (no.beint.vev.pg.PgModel<?, ?>) registry.getField("POSTGRES").get(null);
                assertEquals(count, model.plans().size());
                assertEquals(java.util.UUID.class, model.tenantType());
            }
        }
    }

    @Test
    void rejectsOversizedClosedModelsBeforeGeneratingEntityPlans() throws IOException {
        String entities = String.join(", ", java.util.Collections.nCopies(no.beint.vev.VevModel.MAXIMUM_ENTITIES + 1, "Broken.class"));
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
        assertTrue(compilation.diagnostics().contains("@VevModel must not exceed " + no.beint.vev.VevModel.MAXIMUM_ENTITIES + " entities"),
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
