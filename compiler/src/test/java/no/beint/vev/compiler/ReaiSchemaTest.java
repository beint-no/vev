package no.beint.vev.compiler;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real application DDL, synthetic execution fixtures, and mutations of every table contract. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReaiSchemaTest {
    private Postgres postgres;
    private final List<Column> columns = new ArrayList<>();
    private final List<String> tables = new ArrayList<>();
    record Column(String table, String name, String type, boolean nullable) {}

    @BeforeAll void start() throws Exception {
        postgres = new Postgres("");
        postgres.migrate(Path.of("../test-fixtures/reai/migration"));
        try (var connection = postgres.connect(); var statement = connection.createStatement(); var result = statement.executeQuery("""
                SELECT c.relname, a.attname, t.typname, NOT a.attnotnull
                FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                JOIN pg_attribute a ON a.attrelid=c.oid JOIN pg_type t ON t.oid=a.atttypid
                WHERE n.nspname='public' AND c.relkind='r' AND a.attnum>0 AND NOT a.attisdropped
                    AND c.relname <> 'flyway_schema_history' ORDER BY c.relname,a.attnum
                """)) {
            while (result.next()) columns.add(new Column(result.getString(1), result.getString(2), result.getString(3), result.getBoolean(4)));
        }
        columns.stream().map(Column::table).distinct().forEach(tables::add);
        assertEquals(432, tables.size(), "Changing the corpus requires updating its provenance and coverage expectation");
        assertTrue(columns.size() > 4000, "Do not silently substitute a toy schema");
    }
    @AfterAll void stop() throws Exception { if (postgres != null) postgres.close(); }

    private QueryCompiler.Query describe(String sql, String cardinality) throws Exception {
        var source = SqlSource.parse(Path.of("reai.sql"), "-- vev.name: query :" + cardinality + "\n-- vev.scope: global\n" + sql);
        try (var connection = postgres.connect()) { return QueryCompiler.describe(connection, source); }
    }
    private static String quoted(String name) { return "\"" + name.replace("\"", "\"\"") + "\""; }

    @TestFactory Stream<DynamicTest> everyColumnHasAnExplicitVerifiedTypeContract() {
        return columns.stream().map(column -> DynamicTest.dynamicTest(column.table + "." + column.name + " [" + column.type + "]", () -> {
            String sql = "SELECT " + quoted(column.name) + " FROM " + quoted(column.table);
            PgType expected;
            try { expected = PgType.of(Path.of("reai.sql"), column.type); }
            catch (IllegalArgumentException unsupported) {
                var error = assertThrows(IllegalArgumentException.class, () -> describe(sql, "many"));
                assertTrue(error.getMessage().contains("Unsupported"), error.getMessage());
                return;
            }
            var actual = describe(sql, "optional").columns().getFirst();
            assertEquals(expected, actual.type());
            assertEquals(column.nullable, actual.nullable(), "Origin-column nullability must match the real catalog");
        }));
    }

    @TestFactory Stream<DynamicTest> everyTableRejectsMissingColumnsAndInvalidOperators() {
        return tables.stream().flatMap(table -> Stream.of(
            DynamicTest.dynamicTest(table + " missing column", () -> {
                var error = assertThrows(SQLException.class, () -> describe("SELECT vev_missing_column FROM " + quoted(table), "many"));
                assertEquals("42703", error.getSQLState());
            }),
            DynamicTest.dynamicTest(table + " invalid operator", () -> {
                var error = assertThrows(SQLException.class, () -> describe("SELECT count(*) + true AS bad FROM " + quoted(table), "one"));
                assertEquals("42883", error.getSQLState());
            }),
            DynamicTest.dynamicTest(table + " aggregate contract", () -> {
                var query = describe("SELECT count(*) AS total FROM " + quoted(table), "one");
                assertEquals("Long", query.columns().getFirst().type().kotlin());
                assertFalse(query.columns().getFirst().nullable());
            })
        ));
    }

    @TestFactory Stream<DynamicTest> tenantQueriesBindInferredIntegerParametersAcrossRealTables() {
        return columns.stream().filter(c -> c.name.equals("tenant_id") && c.type.equals("int4")).map(column ->
            DynamicTest.dynamicTest(column.table + " tenant", () -> {
                var source = SqlSource.parse(Path.of("tenant.sql"), "-- vev.name: scoped :many\nSELECT tenant_id FROM " + quoted(column.table) + " WHERE tenant_id=:tenantId");
                try (var connection = postgres.connect()) {
                    var query = QueryCompiler.describe(connection, source);
                    assertEquals("Int", query.parameters().getFirst().type().kotlin());
                    assertEquals("tenantId", source.tenant());
                }
            }));
    }

    @Test void applicationQueriesAreDescribedButNeverExecuted() throws Exception {
        try (var connection = postgres.connect(); var statement = connection.createStatement()) {
            statement.execute("CREATE SEQUENCE vev_execution_probe");
        }
        describe("SELECT nextval('vev_execution_probe') AS value", "one");
        try (var connection = postgres.connect(); var statement = connection.createStatement(); var result = statement.executeQuery("SELECT is_called FROM vev_execution_probe")) {
            assertTrue(result.next()); assertFalse(result.getBoolean(1));
        }
        // DML validation does not execute triggers, constraints, or mutations either.
        describe("DELETE FROM departments WHERE tenant_id=:id", "exec");
    }

    @Test void outerJoinAndReturningContractsStayConservativeOnRealTables() throws Exception {
        var outer = describe("SELECT b.id FROM departments a LEFT JOIN departments b ON a.id=b.id AND a.tenant_id=b.tenant_id", "many");
        assertTrue(outer.columns().getFirst().nullable());
        var returned = describe("INSERT INTO departments(tenant_id,name) VALUES (:tenant,'synthetic') RETURNING id", "one");
        assertTrue(returned.columns().getFirst().nullable());
    }
}
