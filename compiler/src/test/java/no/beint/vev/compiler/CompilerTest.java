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
            statement.execute("CREATE TABLE item(id integer PRIMARY KEY, name text NOT NULL, note text)");
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
    @Test void schemaChangesFailGeneration() throws Exception {
        compile("-- name: query :many\nSELECT name FROM item");
        try (var connection = postgres.connect(); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE item RENAME COLUMN name TO renamed");
            try { assertThrows(Exception.class, () -> compile("-- name: query :many\nSELECT name FROM item")); }
            finally { statement.execute("ALTER TABLE item RENAME COLUMN renamed TO name"); }
        }
    }
}
