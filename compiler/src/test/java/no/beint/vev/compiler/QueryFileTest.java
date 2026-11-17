package no.beint.vev.compiler;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class QueryFileTest {
    private static final Path FILE = Path.of("customers.sql");
    @Test void relatedQueriesHaveIndependentContracts() {
        var queries = SqlSource.parseAll(FILE, """
                -- vev.name: first :one
                -- vev.scope: global
                -- vev.rows: 5
                -- vev.nullable: id
                SELECT :id::int AS id;

                -- vev.name: second :many
                SELECT :tenantId::int AS id;
                """);
        assertEquals(2, queries.size());
        assertNull(queries.getFirst().tenant());
        assertEquals(5, queries.getFirst().maxRows());
        assertTrue(queries.getFirst().nullable().contains("id"));
        assertEquals("tenantId", queries.getLast().tenant());
        assertEquals(1000, queries.getLast().maxRows());
        assertTrue(queries.getLast().nullable().isEmpty());
        assertEquals("second", queries.getLast().name());
    }

    @Test void queryMarkersInsideQuotedTextAndNestedCommentsAreNotBoundaries() {
        var queries = SqlSource.parseAll(FILE, """
                -- vev.name: text :one
                -- vev.scope: global
                SELECT $body$
                -- vev.name: hidden :exec
                ;
                $body$ AS text, E'escaped\\\'\n-- vev.name: hidden :exec' AS other;
                /* outer /* nested */
                -- vev.name: hidden :exec
                */
                -- vev.name: next :one
                -- vev.scope: global
                SELECT 1 AS value;
                -- trailing explanation
                """);
        assertEquals(2, queries.size());
        assertEquals("next", queries.getLast().name());
    }

    @ParameterizedTest @ValueSource(strings = {
        "-- vev.scpoe: global\nSELECT :tenantId::int AS id;",
        "-- vev.rows: 5\n-- rows: 10\nSELECT :tenantId::int AS id;",
        "SELECT :tenantId::int AS id\n-- vev.name: next :one\nSELECT 1;",
        "SELECT :tenantId::int AS id;\n-- vev.notnull: id",
        "SELECT :tenantId::int AS id;\nDELETE FROM customer;"
    }) void invalidContractCannotBeSilentlyIgnored(String body) {
        assertThrows(IllegalArgumentException.class,
                () -> SqlSource.parseAll(FILE, "-- vev.name: first :one\n" + body));
    }

    @Test void largeGroupedFilesDoNotRequireRecursionOrShareNames() {
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 1000; i++) source.append("-- vev.name: query").append(i)
                .append(" :one\nSELECT :tenantId::int AS id;\n");
        var queries = SqlSource.parseAll(FILE, source.toString());
        assertEquals(1000, queries.size());
        assertEquals("query999", queries.getLast().name());
    }

    @Test void singleQueryApiRejectsMultipleQueries() {
        assertThrows(IllegalArgumentException.class, () -> SqlSource.parse(FILE,
                "-- name: first :one\nSELECT :tenantId::int AS id;\n-- name: next :one\nSELECT :tenantId::int AS id;"));
    }
}
