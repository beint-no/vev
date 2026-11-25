package no.beint.vev.compiler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompilerTest {
    private Postgres postgres;
    @TempDir Path directory;
    @BeforeAll void start() throws Exception {
        postgres = new Postgres("");
        try (Connection connection = postgres.connect(); var statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS citext");
            statement.execute("CREATE TABLE item(id integer PRIMARY KEY, name text NOT NULL, note text)");
            statement.execute("CREATE TABLE record(id integer PRIMARY KEY, status varchar(16) NOT NULL,"
                    + " email citext NOT NULL, document xml, address inet, kind text)");
            statement.execute("CREATE TABLE document_blob(id integer PRIMARY KEY, name text NOT NULL,"
                    + " c1 text, c2 text, c3 text, c4 text, c5 text, c6 text, c7 text,"
                    + " file_data bytea NOT NULL,"
                    + " token_hash bytea NOT NULL CHECK (octet_length(token_hash) = 32),"
                    + " opt_digest bytea CHECK (opt_digest IS NULL OR octet_length(opt_digest) <= 64))");
        }
    }
    @AfterAll void stop() throws Exception { if (postgres != null) postgres.close(); }

    private String compile(String sql) throws Exception {
        Path queries = Files.createTempDirectory(directory, "queries");
        Path output = Files.createTempDirectory(directory, "output");
        Files.writeString(queries.resolve("query.sql"), sql);
        try (Connection connection = postgres.connect()) { QueryCompiler.compile(connection, queries, output, "example", "Queries"); }
        try (var files = Files.list(output)) {
            var result = new StringBuilder();
            for (Path file : files.filter(p -> p.toString().endsWith(".kt")).sorted().toList()) result.append(Files.readString(file));
            return result.toString();
        }
    }

    private static String wideProjection(int columns, boolean requiredLong) {
        String labels=java.util.stream.IntStream.rangeClosed(1,columns).mapToObj(i -> "c"+i).collect(java.util.stream.Collectors.joining(","));
        String sql=java.util.stream.IntStream.rangeClosed(1,columns).mapToObj(i -> "1::bigint AS c"+i).collect(java.util.stream.Collectors.joining(","));
        return "-- vev.name: wide :one\n-- vev.scope: global\n"+(requiredLong?"-- vev.notnull: "+labels+"\n":"")+"SELECT "+sql;
    }
    @Test void everyFailingQueryIsReportedInOneRunWithoutGeneratingOutput() throws Exception {
        Path queries = Files.createTempDirectory(directory, "queries");
        Path output = Files.createTempDirectory(directory, "output");
        Files.writeString(queries.resolve("a.sql"), "-- vev.name: a :one\n-- vev.scope: global\nSELECT missing FROM item");
        Files.writeString(queries.resolve("b.sql"), "-- vev.name: b :one\n-- vev.scope: global\nSELECT id FROM item WHERE id = 1");
        Files.writeString(queries.resolve("c.sql"), "-- vev.name: c :one\n-- vev.scope: global\nSELECT id FROM absent_table");
        Files.writeString(queries.resolve("d.sql"), "-- vev.name: d :many\n-- vev.unknown: x\nSELECT id FROM item");
        try (Connection connection = postgres.connect()) {
            connection.setAutoCommit(false);
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> QueryCompiler.compile(connection, queries, output, "example", "Queries"));
            assertTrue(failure.getMessage().startsWith("3 Vev queries failed:"), failure.getMessage());
            assertTrue(failure.getMessage().contains("a.sql"));
            assertTrue(failure.getMessage().contains("c.sql"));
            assertTrue(failure.getMessage().contains("d.sql"));
            assertFalse(failure.getMessage().contains("b.sql"));
            assertEquals(3, failure.getSuppressed().length);
        }
        try (var files = Files.list(output)) { assertEquals(0, files.count()); }
    }
    @Test void jsonOperatorParametersMustBeCastBecausePostgresqlPicksTheTextKeyOperator() throws Exception {
        for (String operator : java.util.List.of("->", "->>")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> compile(
                    "-- vev.name: element :optional\n-- vev.scope: global\nSELECT ('[1,2]'::jsonb " + operator + " :index) AS element"));
            assertTrue(failure.getMessage().contains("Cast :index after -> or ->>"), failure.getMessage());
            assertTrue(compile("-- vev.name: element :optional\n-- vev.scope: global\nSELECT ('[1,2]'::jsonb " + operator
                    + " :index::int) AS element").contains("`index`: Int"));
        }
        assertTrue(compile("-- vev.name: nested :optional\n-- vev.scope: global\nSELECT ('{\"a\":1}'::jsonb -> CAST(:key AS text)) AS value").contains("`key`: String"));
        assertTrue(compile("-- vev.name: compare :many\n-- vev.scope: global\nSELECT id FROM item WHERE id - 1 > :minimum").contains("`minimum`: Int"));
    }
    @Test void textAndVarcharOccurrencesOfOneParameterShareAStringBinding() throws Exception {
        String result = compile("-- vev.name: tagged :many\n-- vev.scope: global\n"
                + "SELECT id, :status AS label FROM record WHERE status = :status");
        assertTrue(result.contains("fun `tagged`(tx: no.beint.vev.ReadScope, `status`: String)"), result);
        var failure = assertThrows(IllegalArgumentException.class, () -> compile("-- vev.name: mixed :many\n-- vev.scope: global\n"
                + "SELECT id FROM item WHERE id = :value OR name = :value"));
        assertTrue(failure.getMessage().contains("incompatible types across occurrences"), failure.getMessage());
    }
    @Test void wideRowsFailWithAnActionableJvmLimitInsteadOfInvalidGeneratedBytecode() throws Exception {
        assertTrue(compile(wideProjection(245,false)).contains("data class"));
        assertTrue(compile(wideProjection(124,true)).contains("data class"));
        for (String sql : java.util.List.of(wideProjection(246,false),wideProjection(125,true))) {
            var failure=assertThrows(IllegalArgumentException.class, () -> compile(sql));
            assertTrue(failure.getMessage().contains("JVM parameter slots"));
            assertTrue(failure.getMessage().contains("narrower projection"));
        }
    }
    @Test void nestedWildcardQueryRetainsOnlyItsExplicitResultColumns() throws Exception {
        String result=compile("-- vev.name: nested :many\n-- vev.scope: global\nWITH x AS (SELECT * FROM item) SELECT id FROM x");
        assertTrue(result.contains("val `id`: Int?"));
        assertFalse(result.contains("val `name`:"));
    }

    private static String inputProjection(int count, boolean nullable, boolean tenant) {
        String names=java.util.stream.IntStream.rangeClosed(1,count).mapToObj(n -> "p"+n).collect(java.util.stream.Collectors.joining(","));
        String values=java.util.stream.IntStream.rangeClosed(1,count).mapToObj(n -> "(:p"+n+"::bigint)").collect(java.util.stream.Collectors.joining(","));
        return "-- vev.name: inputs :one\n"+(tenant?"":"-- vev.scope: global\n")
                +(nullable?"-- vev.nullable: "+names+"\n":"")
                +"SELECT sum(v) AS total FROM (VALUES "+values+") q(v)"+(tenant?" WHERE :tenantId::int > 0":"");
    }
    @Test void methodParameterLimitsFailDuringGeneration() throws Exception {
        assertTrue(compile(inputProjection(253,true,true)).contains("fun `inputs`"));
        assertTrue(compile(inputProjection(253,true,false)).contains("fun `inputs`"));
        assertTrue(compile(inputProjection(126,false,true)).contains("fun `inputs`"));
        for (String sql : java.util.List.of(inputProjection(254,true,true),inputProjection(254,true,false),inputProjection(127,false,true))) {
            var failure=assertThrows(IllegalArgumentException.class, () -> compile(sql));
            assertTrue(failure.getMessage().contains("Query parameters require"));
            assertTrue(failure.getMessage().contains("PostgreSQL array"));
        }
    }
    @Test void repeatedBindsCaptureOneApiValueRatherThanOneValuePerPlaceholder() throws Exception {
        String values=java.util.stream.IntStream.range(0,500).mapToObj(n -> "(:value::bigint)").collect(java.util.stream.Collectors.joining(","));
        assertTrue(compile("-- vev.name: repeated :one\n-- vev.scope: global\nSELECT sum(v) AS total FROM (VALUES "+values+") q(v)").contains("fun `repeated`(tx: no.beint.vev.ReadScope, `value`: Long)"));
    }

    @Test void postgres18ReturningAliasesDescribeOldAndNewValuesWithoutExecutingWrites() throws Exception {
        String result=compile("-- vev.name: rename :optional\n-- vev.scope: global\nUPDATE item SET name=:name WHERE id=:id RETURNING WITH (OLD AS previous, NEW AS current) previous.name AS old_name,current.name AS name");
        assertTrue(result.contains("val `oldName`: String?"));
        assertTrue(result.contains("val `name`: String?"));
        try(var connection=postgres.connect();var statement=connection.createStatement();var rows=statement.executeQuery("SELECT count(*) FROM item")) {
            assertTrue(rows.next());assertEquals(0,rows.getInt(1));
        }
    }

    @Test void databaseDiagnosticsCarryStableSqlStateAndTheQueryLocation() {
        var error = assertThrows(java.sql.SQLException.class,
                () -> compile("-- vev.name: broken :many\n-- vev.scope: global\nSELECT missing FROM item"));
        assertEquals("42703", error.getSQLState());
        assertTrue(error.getMessage().contains("query.sql"));
        assertTrue(error.getMessage().contains("broken [SQLSTATE 42703]"));
        assertInstanceOf(org.postgresql.util.PSQLException.class, error.getCause());
    }

    @ParameterizedTest @ValueSource(strings = {
        "SELECT missing FROM item", "SELECT id FROM absent", "SELECT id FROM item WHERE id = :id::uuid",
        "SELECT id, id FROM item", "SELECT id AS \"bad label\" FROM item", "SELECT point(1,2) AS p",
        "SELECT :same::int, :same::uuid", "SELECT id FROM item WHERE :unknown IS NULL"
    }) void postgresRejectsInvalidContracts(String sql) { assertThrows(Exception.class, () -> compile("-- vev.name: query :many\n-- vev.scope: global\n" + sql)); }

    @Test void resultNullabilityComesFromQuery() throws Exception {
        String plain = compile("-- vev.name: query :many\n-- vev.scope: global\nSELECT id,name,note FROM item");
        assertTrue(plain.contains("val `id`: Int,"));
        assertTrue(plain.contains("val `name`: String,"));
        assertTrue(plain.contains("val `note`: String?,"));
        String outer = compile("-- vev.name: query :many\n-- vev.scope: global\nSELECT b.id FROM item a LEFT JOIN item b ON false");
        assertTrue(outer.contains("val `id`: Int?,"));
        String union = compile("-- vev.name: query :many\n-- vev.scope: global\nSELECT id FROM item UNION ALL SELECT NULL");
        assertTrue(union.contains("val `id`: Int?,"));
    }
    @Test void describesWritesWithoutExecutingThem() throws Exception {
        compile("-- vev.name: insert :exec\n-- vev.scope: global\nINSERT INTO item VALUES (1,'not executed',null)");
        try (var c = postgres.connect(); var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM item")) {
            assertTrue(r.next()); assertEquals(0, r.getInt(1));
        }
    }
    @Test void requiredExpressionsDoNotMakeNullableExpressionsRequired() throws Exception {
        String result = compile("-- vev.name: query :one\n-- vev.scope: global\nSELECT EXISTS(SELECT 1 FROM item) AS present, count(*) AS total FROM item");
        assertTrue(result.contains("val `present`: Boolean,"));
        assertTrue(result.contains("val `total`: Long,"));
        String nullable = compile("-- vev.name: query :one\n-- vev.scope: global\nSELECT EXISTS(SELECT 1) AND NULL AS present, count(*) + NULL::bigint AS total FROM item");
        assertTrue(nullable.contains("val `present`: Boolean?,"));
        assertTrue(nullable.contains("val `total`: Long?,"));
        String union = compile("-- vev.name: query :many\n-- vev.scope: global\nSELECT EXISTS(SELECT 1) AS present UNION ALL SELECT NULL");
        assertTrue(union.contains("val `present`: Boolean?,"));
    }

    @Test void renamedOutputRemovesOnlyItsPreviouslyOwnedSource() throws Exception {
        Path queries = Files.createTempDirectory(directory, "rename-queries");
        Path output = Files.createTempDirectory(directory, "rename-output");
        Files.writeString(queries.resolve("query.sql"), "-- vev.name: query :many\n-- vev.scope: global\nSELECT id FROM item");
        Files.writeString(output.resolve("Handwritten.kt"), "// retained");
        try (var connection = postgres.connect()) {
            QueryCompiler.compile(connection, queries, output, "example", "Before");
            QueryCompiler.compile(connection, queries, output, "example", "After");
        }
        assertFalse(Files.exists(output.resolve("Before.kt")));
        assertTrue(Files.exists(output.resolve("After.kt")));
        assertEquals("// retained", Files.readString(output.resolve("Handwritten.kt")));
    }
    @Test void namedRepeatedNullableAndDomainParameters() throws Exception {
        String result = compile("-- vev.name: query :many\n-- vev.scope: global\n-- vev.nullable: name\n-- vev.type: id ItemId\nSELECT id FROM item WHERE id=:id AND (:name::text IS NULL OR name=:name)");
        assertTrue(result.contains("`id`: ItemId"));
        assertTrue(result.contains("`name`: String?"));
        assertTrue(result.contains("setObject(3, `name`"));
    }
    @Test void generationIsDeterministicAndDollarSafe() throws Exception {
        String source = "-- vev.name: query :one\n-- vev.scope: global\nSELECT '$oops'::text AS value";
        assertEquals(compile(source), compile(source));
        assertTrue(compile(source).contains("\\$oops"));
    }
    @Test void cardinalityAndDomainMistakesFailBuild() {
        assertThrows(Exception.class, () -> compile("-- vev.name: query :exec\n-- vev.scope: global\nSELECT id FROM item"));
        assertThrows(Exception.class, () -> compile("-- vev.name: query :many\n-- vev.scope: global\nDELETE FROM item"));
        assertThrows(Exception.class, () -> compile("-- vev.name: query :many\n-- vev.scope: global\n-- vev.type: absent ItemId\nSELECT id FROM item"));
    }
    @Test void textualPostgresTypesReachKotlinAsStrings() throws Exception {
        String result = compile("-- vev.name: query :many\n-- vev.scope: global\nSELECT email, document, address FROM record");
        assertTrue(result.contains("val `email`: String,"), result);
        assertTrue(result.contains("val `document`: String?,"), result);
        assertTrue(result.contains("val `address`: String?,"), result);
    }

    @Test void enumColumnsAndParametersUseTheDeclaredKotlinEnum() throws Exception {
        String result = compile("-- vev.name: query :many\n-- vev.scope: global\n-- vev.enum: status example.Status\nSELECT status FROM record WHERE status = :status");
        assertTrue(result.contains("val `status`: example.Status,"), result);
        assertTrue(result.contains("`status`: example.Status"), result);
        assertTrue(result.contains("example.Status.valueOf("), result);
        assertTrue(result.contains("`status`.name"), result);
    }

    @Test void nullableEnumsDecodeAndBindThroughLet() throws Exception {
        String result = compile("-- vev.name: query :many\n-- vev.scope: global\n-- vev.enum: kind example.Kind\n-- vev.nullable: kind\nSELECT kind FROM record WHERE (:kind::text IS NULL OR kind = :kind)");
        assertTrue(result.contains("val `kind`: example.Kind?,"), result);
        assertTrue(result.contains("?.let { example.Kind.valueOf(it) }"), result);
        assertTrue(result.contains("`kind`?.name"), result);
    }

    @Test void enumDeclarationsMustBeTextualAndKnown() {
        assertThrows(Exception.class, () -> compile("-- vev.name: query :many\n-- vev.scope: global\n-- vev.enum: id example.Status\nSELECT id FROM record"));
        assertThrows(Exception.class, () -> compile("-- vev.name: query :many\n-- vev.scope: global\n-- vev.enum: absent example.Status\nSELECT id FROM record"));
        assertThrows(Exception.class, () -> compile("-- vev.name: query :many\n-- vev.scope: global\n-- vev.enum: status lowercase\nSELECT status FROM record"));
        assertThrows(Exception.class, () -> compile("-- vev.name: query :many\n-- vev.scope: global\n-- vev.enum: status example.Status\n-- vev.type: status Wrapped\nSELECT status FROM record"));
    }

    @Test void notnullOverridesConservativeNullabilityWithAGuardedRead() throws Exception {
        String conservative = compile("-- vev.name: query :many\n-- vev.scope: global\nWITH source AS (SELECT id, name FROM item) SELECT id, name FROM source");
        assertTrue(conservative.contains("val `id`: Int?,"), conservative);
        assertTrue(conservative.contains("val `name`: String?,"), conservative);
        String declared = compile("-- vev.name: query :many\n-- vev.scope: global\n-- vev.notnull: id, name\nWITH source AS (SELECT id, name FROM item) SELECT id, name FROM source");
        assertTrue(declared.contains("val `id`: Int,"), declared);
        assertTrue(declared.contains("val `name`: String,"), declared);
        // A wrong declaration must fail loudly at the row, never decode as a wrong value.
        assertTrue(declared.contains("no.beint.vev.Values.requireNonNull("), declared);
    }

    @Test void notnullMustNameAResultColumn() {
        assertThrows(Exception.class, () -> compile("-- vev.name: query :many\n-- vev.scope: global\n-- vev.notnull: absent\nSELECT id FROM item"));
        assertThrows(Exception.class, () -> compile("-- vev.name: query :many\n-- vev.scope: global\n-- vev.notnull: missing\nSELECT id FROM item WHERE id = :id"));
        assertThrows(Exception.class, () -> compile("-- vev.name: query :exec\n-- vev.scope: global\n-- vev.notnull: id\nDELETE FROM item"));
    }

    @Test void voidFunctionsAreCalledForTheirEffect() throws Exception {
        String result = compile("-- vev.name: lock :void\n-- vev.scope: global\nSELECT pg_advisory_xact_lock(hashtextextended(:key, 0))");
        assertTrue(result.contains("fun `lock`(tx: no.beint.vev.WriteScope, `key`: String): Unit ="), result);
        assertTrue(result.contains("_vevStmt.execute()"), result);
        assertFalse(result.contains("LockRow"), result);
    }

    @Test void streamQueriesGenerateConsumerCallbacks() throws Exception {
        String result = compile("-- vev.name: exportItems :stream\n-- vev.scope: global\nSELECT id, name FROM item");
        assertTrue(result.contains("fun `exportItems`(tx: no.beint.vev.ReadScope, onRow: java.util.function.Consumer<`ExportItemsRow`>): Unit"), result);
        assertTrue(result.contains("_vevStmt.setFetchSize(no.beint.vev.Session.DEFAULT_STREAM_FETCH_SIZE)"), result);
        assertTrue(result.contains("onRow.accept(readRow(_vevResult))"), result);
        assertThrows(IllegalArgumentException.class, () -> compile("-- vev.name: bad :stream\n-- vev.scope: global\nSELECT id FROM item WHERE name = :onRow"));
    }

    @Test void capabilityScopesDistinguishReadWriteLockingAndTenantQueries() throws Exception {
        assertTrue(compile("-- vev.name: readGlobal :many\n-- vev.scope: global\nSELECT id FROM item")
                .contains("fun `readGlobal`(tx: no.beint.vev.ReadScope)"));
        assertTrue(compile("-- vev.name: lockGlobal :many\n-- vev.scope: global\nSELECT id FROM item FOR UPDATE")
                .contains("fun `lockGlobal`(tx: no.beint.vev.WriteScope)"));
        assertTrue(compile("-- vev.name: lockShare :many\n-- vev.scope: global\nSELECT id FROM item FOR NO KEY UPDATE")
                .contains("fun `lockShare`(tx: no.beint.vev.WriteScope)"));
        assertTrue(compile("-- vev.name: cteWrite :many\n-- vev.scope: global\nWITH updated AS (UPDATE item SET name = 'x' RETURNING id) SELECT id FROM updated")
                .contains("fun `cteWrite`(tx: no.beint.vev.WriteScope)"));
        assertTrue(compile("-- vev.name: advisory :one\n-- vev.scope: global\nSELECT 1 AS ok FROM pg_advisory_xact_lock(1)")
                .contains("fun `advisory`(tx: no.beint.vev.WriteScope)"));
        assertTrue(compile("-- vev.name: explicitWrite :one\n-- vev.scope: global\n-- vev.access: write\nSELECT 1 AS ok")
                .contains("fun `explicitWrite`(tx: no.beint.vev.WriteScope)"));
        assertTrue(compile("-- vev.name: readTenant :one\nSELECT :tenantId::int AS tenant_id")
                .contains("fun `readTenant`(tx: no.beint.vev.TenantReadScope)"));
        assertTrue(compile("-- vev.name: writeTenant :one\n-- vev.access: write\nSELECT :tenantId::int AS tenant_id")
                .contains("fun `writeTenant`(tx: no.beint.vev.TenantWriteScope)"));
    }

    @Test void stringFunctionsNamedLeftOrRightPreserveDirectNullability() throws Exception {
        String result = compile("-- vev.name: prefixes :many\n-- vev.scope: global\nSELECT id, left(name, 3) AS prefix, right(name, 2) AS suffix FROM item");
        assertTrue(result.contains("val `id`: Int,"), result);
        assertTrue(result.contains("val `prefix`: String?,"), result);
        assertTrue(result.contains("val `suffix`: String?,"), result);
    }

    @Test void arraySliceColonIsNotTreatedAsABindParameter() throws Exception {
        String result = compile("-- vev.name: slice :one\n-- vev.scope: global\nSELECT (ARRAY[10,20,30])[1:id] AS part FROM item");
        assertTrue(result.contains("fun `slice`(tx: no.beint.vev.ReadScope): `SliceRow`"), result);
    }

    @Test void timestamptzMapsToInstantAndSupportsTemporalArrays() throws Exception {
        String result = compile("-- vev.name: times :one\n-- vev.scope: global\nSELECT :at::timestamptz AS at, :dates::date[] AS dates, :instants::timestamptz[] AS instants");
        assertTrue(result.contains("`at`: java.time.Instant"), result);
        assertTrue(result.contains("`dates`: List<java.time.LocalDate?>"), result);
        assertTrue(result.contains("`instants`: List<java.time.Instant?>"), result);
        assertTrue(result.contains(".atOffset(java.time.ZoneOffset.UTC)"), result);
        assertTrue(result.contains("no.beint.vev.Values.instantOrNull(_vevResult, 1)"), result);
    }

    @Test void voidResultsMustBeDeclaredVoidAndVoidQueriesMustBeValueless() {
        assertThrows(Exception.class, () -> compile("-- vev.name: lock :one\n-- vev.scope: global\nSELECT pg_advisory_xact_lock(hashtextextended(:key, 0)) AS locked"));
        assertThrows(Exception.class, () -> compile("-- vev.name: lock :void\n-- vev.scope: global\nSELECT id FROM item"));
        assertThrows(Exception.class, () -> compile("-- vev.name: lock :void\n-- vev.scope: global\nDELETE FROM item"));
    }

    @Test void unnamedResultColumnsSayToAddAnAlias() {
        var failure = assertThrows(Exception.class, () -> compile("-- vev.name: query :one\n-- vev.scope: global\nSELECT count(*) > 0 FROM item"));
        assertTrue(failure.getMessage().contains("has no name"), failure.getMessage());
        assertTrue(failure.getMessage().contains("alias"), failure.getMessage());
    }

    @Test void schemaChangesFailGeneration() throws Exception {
        compile("-- vev.name: query :many\n-- vev.scope: global\nSELECT name FROM item");
        try (var connection = postgres.connect(); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE item RENAME COLUMN name TO renamed");
            try { assertThrows(Exception.class, () -> compile("-- vev.name: query :many\n-- vev.scope: global\nSELECT name FROM item")); }
            finally { statement.execute("ALTER TABLE item RENAME COLUMN renamed TO name"); }
        }
    }
    @ParameterizedTest @ValueSource(strings={"bigint","text","uuid"})
    void automaticTenantBindingsRejectNonIntegerPostgresTypes(String type) throws Exception {
        var source=SqlSource.parse(Path.of("tenant.sql"),"-- vev.name: tenant :one\nSELECT :tenantId::"+type+" AS tenant_id");
        try(var connection=postgres.connect()) {
            var error=assertThrows(IllegalArgumentException.class,()->QueryCompiler.describe(connection,source));
            assertTrue(error.getMessage().contains("Tenant parameter must be PostgreSQL integer"));
        }
    }

    @Test void duplicateSqlQueriesWithinAModuleFailCompilation() throws Exception {
        Path queries = Files.createTempDirectory(directory, "dup-queries");
        Path output = Files.createTempDirectory(directory, "dup-output");
        Files.writeString(queries.resolve("first.sql"),
                "-- vev.name: first :optional\n-- vev.scope: global\nSELECT id, name FROM item WHERE id = :id;");
        Files.writeString(queries.resolve("second.sql"),
                "-- vev.name: second :optional\n-- vev.scope: global\nselect  id,  name\nfrom item\nwhere id = :otherId;");
        try (var connection = postgres.connect()) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> QueryCompiler.compile(connection, queries, output, "example", "Queries"));
            assertTrue(error.getMessage().contains("Duplicate SQL query"), error.getMessage());
            assertTrue(error.getMessage().contains("first"), error.getMessage());
        }
        // Distinct string literals or distinct cardinalities are not duplicates.
        Files.writeString(queries.resolve("second.sql"),
                "-- vev.name: second :optional\n-- vev.scope: global\nSELECT id, name FROM item WHERE name = 'Open';");
        Files.writeString(queries.resolve("third.sql"),
                "-- vev.name: third :optional\n-- vev.scope: global\nSELECT id, name FROM item WHERE name = 'open';");
        Files.writeString(queries.resolve("fourth.sql"),
                "-- vev.name: fourth :many\n-- vev.scope: global\nSELECT id, name FROM item WHERE id = :id;");
        try (var connection = postgres.connect()) {
            QueryCompiler.compile(connection, queries, output, "example", "Queries");
        }
    }

    @Test void unboundedByteaGuardAllowsDedicatedDownloadsBoundedHashesAndExplicitOptIn() throws Exception {
        // Dedicated single-file download (<= 8 columns, :optional) is allowed without opt-in.
        assertTrue(compile("-- vev.name: download :optional\n-- vev.scope: global\n"
                + "SELECT id, name, file_data FROM document_blob WHERE id = :id").contains("`fileData`: ByteArray"));

        // Small CHECK-bounded bytea columns (hashes/digests) are allowed even in :many and wide (>8 cols) queries.
        assertTrue(compile("-- vev.name: hashes :many\n-- vev.scope: global\n"
                + "SELECT id, name, c1, c2, c3, c4, c5, c6, c7, token_hash, opt_digest FROM document_blob")
                .contains("`tokenHash`: ByteArray"));

        // Unbounded bytea in :many fails unless opted in.
        var manyErr = assertThrows(IllegalArgumentException.class, () -> compile(
                "-- vev.name: allBlobs :many\n-- vev.scope: global\nSELECT id, file_data FROM document_blob"));
        assertTrue(manyErr.getMessage().contains("unbounded bytea column 'file_data'"), manyErr.getMessage());
        assertTrue(manyErr.getMessage().contains("-- vev.allow: blob"), manyErr.getMessage());

        // Unbounded bytea in wide (>8 cols) :optional fails unless opted in.
        var wideErr = assertThrows(IllegalArgumentException.class, () -> compile(
                "-- vev.name: wideBlob :optional\n-- vev.scope: global\n"
                + "SELECT id, name, c1, c2, c3, c4, c5, c6, c7, file_data FROM document_blob WHERE id = :id"));
        assertTrue(wideErr.getMessage().contains("unbounded bytea column 'file_data'"), wideErr.getMessage());
        assertTrue(wideErr.getMessage().contains("wide (10 columns)"), wideErr.getMessage());

        // Explicit opt-in allows intentional batch/wide blob queries.
        assertTrue(compile("-- vev.name: exportBlobs :many\n-- vev.scope: global\n-- vev.allow: blob\n"
                + "SELECT id, file_data FROM document_blob").contains("`fileData`: ByteArray"));

        // Derived scalar like octet_length(file_data) is not bytea and passes.
        assertTrue(compile("-- vev.name: metaList :many\n-- vev.scope: global\n"
                + "SELECT id, name, octet_length(file_data)::bigint AS size FROM document_blob")
                .contains("`size`: Long?"));
    }
}
