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
        "SELECT :tenantId::int AS id;\nDELETE FROM customer;",
        "-- vev.plan: generic\nSELECT :tenantId::int AS id;",
        "-- vev.plan: custom\n-- vev.plan: custom\nSELECT :tenantId::int AS id;"
    }) void invalidContractCannotBeSilentlyIgnored(String body) {
        assertThrows(IllegalArgumentException.class,
                () -> SqlSource.parse(FILE, "-- vev.name: first :one\n" + body));
    }

    @Test void ordinaryCommentsBetweenDirectivesPreserveTheRowBound() {
        var query = SqlSource.parse(FILE, "-- vev.name: customers :many\n/* outer /* nested */ */\n"
                + "-- vev.rows: 7\nSELECT :tenantId::int AS id");
        assertEquals(7, query.maxRows());
    }

    @ParameterizedTest @ValueSource(strings = {":one", ":optional", ":exec", ":void"})
    void rowBoundRequiresAListResult(String cardinality) {
        var error = assertThrows(IllegalArgumentException.class, () -> SqlSource.parse(FILE,
                "-- vev.name: customers " + cardinality + "\n-- vev.rows: 7\nSELECT :tenantId::int AS id"));
        assertTrue(error.getMessage().contains("rows requires :many"));
    }

    @Test void listsAreUnboundedUnlessARowBoundIsDeclared() {
        var query = SqlSource.parse(FILE, "-- vev.name: customers :many\nSELECT :tenantId::int AS id");
        assertEquals(0, query.maxRows());
        assertFalse(query.customPlan());
    }

    @Test void customPlanDirectiveIsRecorded() {
        var query = SqlSource.parse(FILE, "-- vev.name: customers :many\n-- vev.plan: custom\nSELECT :tenantId::int AS id");
        assertTrue(query.customPlan());
    }

    @Test void carriageReturnsEndDirectivesAndSqlComments() {
        var query = SqlSource.parse(FILE, "-- vev.name: customers :many\r-- vev.rows: 7\r"
                + "SELECT -- an ordinary comment\r:tenantId::int AS id");
        assertEquals(7, query.maxRows());
        assertEquals(java.util.List.of("tenantId"), query.parameters());
    }

    @Test void singleQueryApiRejectsMultipleQueries() {
        assertThrows(IllegalArgumentException.class, () -> SqlSource.parse(FILE,
                "-- name: first :one\nSELECT :tenantId::int AS id;\n-- name: next :one\nSELECT :tenantId::int AS id;"));
    }
}
