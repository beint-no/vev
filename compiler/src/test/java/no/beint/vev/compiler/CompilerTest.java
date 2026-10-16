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
        }
    }
    @AfterAll void stop() throws Exception { if (postgres != null) postgres.close(); }

    private String compile(String sql) throws Exception {
        Path queries = Files.createTempDirectory(directory, "queries");
        Path output = Files.createTempDirectory(directory, "output");
        Files.writeString(queries.resolve("query.sql"), sql);
        try (Connection connection = postgres.connect()) { QueryCompiler.compile(connection, queries, output, "example", "Queries"); }
        return Files.readString(output.resolve("Queries.kt"));
    }

    @ParameterizedTest @ValueSource(strings = {
        "SELECT missing FROM item", "SELECT id FROM absent", "SELECT id FROM item WHERE id = :id::uuid",
        "SELECT id, id FROM item", "SELECT id AS \"bad label\" FROM item", "SELECT point(1,2) AS p",
        "SELECT :same::int, :same::uuid", "SELECT id FROM item WHERE :unknown IS NULL"
    }) void postgresRejectsInvalidContracts(String sql) { assertThrows(Exception.class, () -> compile("-- name: query :many\n" + sql)); }

    @Test void resultNullabilityComesFromQuery() throws Exception {
        String plain = compile("-- name: query :many\nSELECT id,name,note FROM item");
        assertTrue(plain.contains("val `id`: Int,"));
        assertTrue(plain.contains("val `name`: String,"));
        assertTrue(plain.contains("val `note`: String?,"));
        String outer = compile("-- name: query :many\nSELECT b.id FROM item a LEFT JOIN item b ON false");
        assertTrue(outer.contains("val `id`: Int?,"));
        String union = compile("-- name: query :many\nSELECT id FROM item UNION ALL SELECT NULL");
        assertTrue(union.contains("val `id`: Int?,"));
    }
    @Test void describesWritesWithoutExecutingThem() throws Exception {
        compile("-- name: insert :exec\nINSERT INTO item VALUES (1,'not executed',null)");
        try (var c = postgres.connect(); var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM item")) {
            assertTrue(r.next()); assertEquals(0, r.getInt(1));
        }
    }
    @Test void requiredExpressionsDoNotMakeNullableExpressionsRequired() throws Exception {
        String result = compile("-- name: query :one\nSELECT EXISTS(SELECT 1 FROM item) AS present, count(*) AS total FROM item");
        assertTrue(result.contains("val `present`: Boolean,"));
        assertTrue(result.contains("val `total`: Long,"));
        String nullable = compile("-- name: query :one\nSELECT EXISTS(SELECT 1) AND NULL AS present, count(*) + NULL::bigint AS total FROM item");
        assertTrue(nullable.contains("val `present`: Boolean?,"));
        assertTrue(nullable.contains("val `total`: Long?,"));
        String union = compile("-- name: query :many\nSELECT EXISTS(SELECT 1) AS present UNION ALL SELECT NULL");
        assertTrue(union.contains("val `present`: Boolean?,"));
    }

    @Test void renamedOutputRemovesOnlyItsPreviouslyOwnedSource() throws Exception {
        Path queries = Files.createTempDirectory(directory, "rename-queries");
        Path output = Files.createTempDirectory(directory, "rename-output");
        Files.writeString(queries.resolve("query.sql"), "-- name: query :many\nSELECT id FROM item");
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
        String result = compile("-- name: query :many\n-- nullable: name\n-- type: id ItemId\nSELECT id FROM item WHERE id=:id AND (:name::text IS NULL OR name=:name)");
        assertTrue(result.contains("`id`: ItemId"));
        assertTrue(result.contains("`name`: String?"));
        assertTrue(result.contains("setObject(3, `name`"));
    }
    @Test void generationIsDeterministicAndDollarSafe() throws Exception {
        String source = "-- name: query :one\nSELECT '$oops'::text AS value";
        assertEquals(compile(source), compile(source));
        assertTrue(compile(source).contains("\\$oops"));
    }
    @Test void cardinalityAndDomainMistakesFailBuild() {
        assertThrows(Exception.class, () -> compile("-- name: query :exec\nSELECT id FROM item"));
        assertThrows(Exception.class, () -> compile("-- name: query :many\nDELETE FROM item"));
        assertThrows(Exception.class, () -> compile("-- name: query :many\n-- type: absent ItemId\nSELECT id FROM item"));
    }
    @Test void textualPostgresTypesReachKotlinAsStrings() throws Exception {
        String result = compile("-- name: query :many\nSELECT email, document, address FROM record");
        assertTrue(result.contains("val `email`: String,"), result);
        assertTrue(result.contains("val `document`: String?,"), result);
        assertTrue(result.contains("val `address`: String?,"), result);
    }

    @Test void enumColumnsAndParametersUseTheDeclaredKotlinEnum() throws Exception {
        String result = compile("-- name: query :many\n-- enum: status example.Status\nSELECT status FROM record WHERE status = :status");
        assertTrue(result.contains("val `status`: example.Status,"), result);
        assertTrue(result.contains("`status`: example.Status"), result);
        assertTrue(result.contains("example.Status.valueOf("), result);
        assertTrue(result.contains("`status`.name"), result);
    }

    @Test void nullableEnumsDecodeAndBindThroughLet() throws Exception {
        String result = compile("-- name: query :many\n-- enum: kind example.Kind\n-- nullable: kind\nSELECT kind FROM record WHERE (:kind::text IS NULL OR kind = :kind)");
        assertTrue(result.contains("val `kind`: example.Kind?,"), result);
        assertTrue(result.contains("?.let { example.Kind.valueOf(it) }"), result);
        assertTrue(result.contains("`kind`?.name"), result);
    }

    @Test void enumDeclarationsMustBeTextualAndKnown() {
        assertThrows(Exception.class, () -> compile("-- name: query :many\n-- enum: id example.Status\nSELECT id FROM record"));
        assertThrows(Exception.class, () -> compile("-- name: query :many\n-- enum: absent example.Status\nSELECT id FROM record"));
        assertThrows(Exception.class, () -> compile("-- name: query :many\n-- enum: status lowercase\nSELECT status FROM record"));
        assertThrows(Exception.class, () -> compile("-- name: query :many\n-- enum: status example.Status\n-- type: status Wrapped\nSELECT status FROM record"));
    }

    @Test void notnullOverridesConservativeNullabilityWithAGuardedRead() throws Exception {
        String conservative = compile("-- name: query :many\nWITH source AS (SELECT id, name FROM item) SELECT id, name FROM source");
        assertTrue(conservative.contains("val `id`: Int?,"), conservative);
        assertTrue(conservative.contains("val `name`: String?,"), conservative);
        String declared = compile("-- name: query :many\n-- notnull: id, name\nWITH source AS (SELECT id, name FROM item) SELECT id, name FROM source");
        assertTrue(declared.contains("val `id`: Int,"), declared);
        assertTrue(declared.contains("val `name`: String,"), declared);
        // A wrong declaration must fail loudly at the row, never decode as a wrong value.
        assertTrue(declared.contains("requireNotNull("), declared);
    }

    @Test void notnullMustNameAResultColumn() {
        assertThrows(Exception.class, () -> compile("-- name: query :many\n-- notnull: absent\nSELECT id FROM item"));
        assertThrows(Exception.class, () -> compile("-- name: query :many\n-- notnull: id\nSELECT id FROM item WHERE id = :id"));
        assertThrows(Exception.class, () -> compile("-- name: query :exec\n-- notnull: id\nDELETE FROM item"));
    }

    @Test void schemaChangesFailGeneration() throws Exception {
        compile("-- name: query :many\nSELECT name FROM item");
        try (var connection = postgres.connect(); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE item RENAME COLUMN name TO renamed");
            try { assertThrows(Exception.class, () -> compile("-- name: query :many\nSELECT name FROM item")); }
            finally { statement.execute("ALTER TABLE item RENAME COLUMN renamed TO name"); }
        }
    }
}
