package no.beint.vev.compiler;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class TenantContractTest {
    @ParameterizedTest @ValueSource(strings={
        "SELECT 1 AS value", "-- nullable: tenantId\nSELECT :tenantId::int AS tenant_id",
        "-- type: tenantId TenantId\nSELECT :tenantId::int AS tenant_id", "-- scope: tenant\nSELECT 1 AS value",
        "-- scope: global\n-- scope: global\nSELECT 1 AS value"
    }) void missingOrAmbiguousScopeFailsGeneration(String sql) {
        assertThrows(IllegalArgumentException.class, () -> SqlSource.parse(Path.of("tenant.sql"), "-- name: query :one\n"+sql));
    }
    @Test void defaultTenantParameterIsReservedAndGlobalOptOutIsExplicit() {
        var tenant=SqlSource.parse(Path.of("tenant.sql"), "-- name: query :one\nSELECT :tenantId::int AS tenant_id");
        assertEquals("tenantId",tenant.tenant());
        var global=SqlSource.parse(Path.of("global.sql"), "-- name: query :one\n-- scope: global\nSELECT 1 AS value");
        assertNull(global.tenant());
    }
}
