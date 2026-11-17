package no.beint.vev.compiler;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class QueryFileTest {
    private static final Path FILE = Path.of("customers.sql");
    @Test void markersInsideQuotedTextAreOrdinarySQL() {
        var query = SqlSource.parse(FILE, "-- vev.name: text :one\n-- vev.scope: global\n"
                + "SELECT $body$\n-- vev.name: hidden :exec\n;$body$ AS text;\n"
                + "/* outer /* nested */\n-- vev.name: hidden :exec\n*/");
        assertEquals("text", query.name());
        assertTrue(query.sql().contains("hidden"));
    }

    @ParameterizedTest @ValueSource(strings = {
        "-- vev.scpoe: global\nSELECT :tenantId::int AS id;",
        "-- vev.rows: 5\n-- rows: 10\nSELECT :tenantId::int AS id;",
        "SELECT :tenantId::int AS id\n-- vev.name: next :one\nSELECT 1;",
        "SELECT :tenantId::int AS id;\n-- vev.notnull: id",
        "SELECT :tenantId::int AS id;\nDELETE FROM customer;"
    }) void invalidContractCannotBeSilentlyIgnored(String body) {
        assertThrows(IllegalArgumentException.class,
                () -> SqlSource.parse(FILE, "-- vev.name: first :one\n" + body));
    }

    @Test void singleQueryApiRejectsMultipleQueries() {
        assertThrows(IllegalArgumentException.class, () -> SqlSource.parse(FILE,
                "-- name: first :one\nSELECT :tenantId::int AS id;\n-- name: next :one\nSELECT :tenantId::int AS id;"));
    }
}
