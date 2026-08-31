package no.beint.vev.benchmark.hibernate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class BenchmarkSetupSafetyTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://127.0.0.1/postgres",
            "jdbc:postgresql://127.0.0.1:5432/postgres",
            "jdbc:postgresql://[::1]/postgres",
            "jdbc:postgresql://[::1]:5432/postgres"
    })
    void literalLoopbackAdminUrlsAreAllowed(String jdbcUrl) {
        assertDoesNotThrow(() -> BenchmarkAdminConfiguration.requireDestructiveSetupUrlAllowed(jdbcUrl, null));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "postgresql://127.0.0.1:5432/postgres",
            "jdbc:postgresql://localhost:5432/postgres",
            "jdbc:postgresql://127.0.0.2:5432/postgres",
            "jdbc:postgresql://127.0.0.1.example.test:5432/postgres",
            "jdbc:postgresql://127.0.0.1,192.0.2.1:5432/postgres",
            "jdbc:postgresql://user@127.0.0.1:5432/postgres",
            "jdbc:postgresql://127.0.0.1:0/postgres",
            "jdbc:postgresql://127.0.0.1:65536/postgres",
            "jdbc:postgresql://127.0.0.1:5432/",
            "jdbc:postgresql://127.0.0.1:5432/postgres?sslmode=disable",
            "jdbc:postgresql://127.0.0.1:5432/postgres/other",
            "jdbc:postgresql://[::1%25lo0]:5432/postgres",
            "jdbc:postgresql://192.0.2.1:5432/postgres"
    })
    void nonLiteralOrParameterizedAdminUrlsAreRejected(String jdbcUrl) {
        assertThrows(
                IllegalStateException.class,
                () -> BenchmarkAdminConfiguration.requireDestructiveSetupUrlAllowed(jdbcUrl, null));
    }

    @Test
    void exactRemoteOptInAllowsAnotherTarget() {
        assertDoesNotThrow(() -> BenchmarkAdminConfiguration.requireDestructiveSetupUrlAllowed(
                "jdbc:postgresql://192.0.2.1:5432/postgres",
                "vev_bench"));
        assertDoesNotThrow(() -> BenchmarkAdminConfiguration.requireDestructiveSetupUrlAllowed(
                "jdbc:postgresql://database.example.test/vev-bench",
                "vev_bench"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "postgresql://database.example.test:5432/postgres",
            "jdbc:mysql://database.example.test:3306/postgres",
            "jdbc:postgresql://database.example.test,standby.example.test:5432/postgres",
            "jdbc:postgresql://user@database.example.test:5432/postgres",
            "jdbc:postgresql://database.example.test:0/postgres",
            "jdbc:postgresql://database.example.test:65536/postgres",
            "jdbc:postgresql://database.example.test:notaport/postgres",
            "jdbc:postgresql://database.example.test:5432/",
            "jdbc:postgresql://database.example.test:5432/postgres/other",
            "jdbc:postgresql://database.example.test:5432/postgres?sslmode=require",
            "jdbc:postgresql://database.example.test:5432/postgres#fragment",
            "jdbc:postgresql://[2001:db8::1/postgres"
    })
    void remoteOptInDoesNotBypassTargetValidation(String jdbcUrl) {
        assertThrows(
                IllegalStateException.class,
                () -> BenchmarkAdminConfiguration.requireDestructiveSetupUrlAllowed(jdbcUrl, "vev_bench"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "vev_bench ", "VEV_BENCH", "true", "1"})
    void inexactRemoteOptInIsRejected(String optIn) {
        assertThrows(
                IllegalStateException.class,
                () -> BenchmarkAdminConfiguration.requireDestructiveSetupUrlAllowed(
                        "jdbc:postgresql://192.0.2.1:5432/postgres",
                        optIn));
    }

    @Test
    void runtimeTargetMustExactlyMatchPreparedFixture() {
        var prepared = "jdbc:postgresql://127.0.0.1:5432/vev_bench";
        assertDoesNotThrow(() -> BenchmarkDatabaseConfiguration.requirePreparedFixtureTarget(prepared, prepared));
        assertThrows(
                IllegalStateException.class,
                () -> BenchmarkDatabaseConfiguration.requirePreparedFixtureTarget(
                        prepared,
                        "jdbc:postgresql://127.0.0.1:5433/vev_bench"));
        assertThrows(
                IllegalStateException.class,
                () -> BenchmarkDatabaseConfiguration.requirePreparedFixtureTarget(
                        prepared,
                        "jdbc:postgresql://localhost:5432/vev_bench"));
    }

    @Test
    void exactFixtureMarkerIsRequired() {
        assertDoesNotThrow(() -> BenchmarkDataset.requireExactOwnershipMarker(
                "database vev_bench",
                "vev-owned-fixture:vev_bench:v1"));
        assertThrows(
                IllegalStateException.class,
                () -> BenchmarkDataset.requireExactOwnershipMarker("database vev_bench", null));
        assertThrows(
                IllegalStateException.class,
                () -> BenchmarkDataset.requireExactOwnershipMarker(
                        "database vev_bench",
                        "vev-owned-fixture:vev_bench:v1 "));
        assertThrows(
                IllegalStateException.class,
                () -> BenchmarkDataset.requireExactOwnershipMarker(
                        "database vev_bench",
                        "vev-owned-fixture:other:v1"));
    }

    @Test
    void trustedSearchPathRejectsRetainedTemporaryStateAndAmbiguousResults() {
        assertDoesNotThrow(() -> BenchmarkDataset.requireTrustedSearchPathResult(
                "pg_catalog", "pg_catalog", 0, false, false));
        assertThrows(
                IllegalStateException.class,
                () -> BenchmarkDataset.requireTrustedSearchPathResult(
                        "pg_catalog", "pg_catalog", 16_384, false, false));
        assertThrows(
                IllegalStateException.class,
                () -> BenchmarkDataset.requireTrustedSearchPathResult(
                        "pg_catalog", "pg_catalog", 0, true, false));
        assertThrows(
                IllegalStateException.class,
                () -> BenchmarkDataset.requireTrustedSearchPathResult(
                        "public, pg_catalog", "pg_catalog", 0, false, false));
        assertThrows(
                IllegalStateException.class,
                () -> BenchmarkDataset.requireTrustedSearchPathResult(
                        "pg_catalog", "pg_catalog", 0, false, true));
    }

    @Test
    void immutablePgjdbcSessionBaselineIsExact() {
        assertDoesNotThrow(() -> BenchmarkDataset.requireTrustedSessionBaselineValues(
                "pg_catalog", "UTF8", "UTF8", "on", "on"));
        assertThrows(IllegalStateException.class, () -> BenchmarkDataset.requireTrustedSessionBaselineValues(
                "public, pg_catalog", "UTF8", "UTF8", "on", "on"));
        assertThrows(IllegalStateException.class, () -> BenchmarkDataset.requireTrustedSessionBaselineValues(
                "pg_catalog", "LATIN1", "UTF8", "on", "on"));
        assertThrows(IllegalStateException.class, () -> BenchmarkDataset.requireTrustedSessionBaselineValues(
                "pg_catalog", "UTF8", "LATIN1", "on", "on"));
        assertThrows(IllegalStateException.class, () -> BenchmarkDataset.requireTrustedSessionBaselineValues(
                "pg_catalog", "UTF8", "UTF8", "off", "on"));
        assertThrows(IllegalStateException.class, () -> BenchmarkDataset.requireTrustedSessionBaselineValues(
                "pg_catalog", "UTF8", "UTF8", "on", "off"));
    }
}
