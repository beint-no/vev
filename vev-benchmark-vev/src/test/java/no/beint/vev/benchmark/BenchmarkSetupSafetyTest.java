package no.beint.vev.benchmark;

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
        assertDoesNotThrow(() -> BenchmarkDatabase.requireDestructiveSetupUrlAllowed(jdbcUrl, null));
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
                () -> BenchmarkDatabase.requireDestructiveSetupUrlAllowed(jdbcUrl, null));
    }

    @Test
    void exactRemoteOptInAllowsAnotherTarget() {
        assertDoesNotThrow(() -> BenchmarkDatabase.requireDestructiveSetupUrlAllowed(
                "jdbc:postgresql://192.0.2.1:5432/postgres",
                "vev_bench"));
        assertDoesNotThrow(() -> BenchmarkDatabase.requireDestructiveSetupUrlAllowed(
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
                () -> BenchmarkDatabase.requireDestructiveSetupUrlAllowed(jdbcUrl, "vev_bench"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "vev_bench ", "VEV_BENCH", "true", "1"})
    void inexactRemoteOptInIsRejected(String optIn) {
        assertThrows(
                IllegalStateException.class,
                () -> BenchmarkDatabase.requireDestructiveSetupUrlAllowed(
                        "jdbc:postgresql://192.0.2.1:5432/postgres",
                        optIn));
    }

    @Test
    void exactFixtureMarkerIsRequired() {
        assertDoesNotThrow(() -> BenchmarkDatabase.requireExactOwnershipMarker(
                "database vev_bench",
                "vev-owned-fixture:vev_bench:v1"));
        assertThrows(
                IllegalStateException.class,
                () -> BenchmarkDatabase.requireExactOwnershipMarker("database vev_bench", null));
        assertThrows(
                IllegalStateException.class,
                () -> BenchmarkDatabase.requireExactOwnershipMarker(
                        "database vev_bench",
                        "vev-owned-fixture:vev_bench:v1 "));
        assertThrows(
                IllegalStateException.class,
                () -> BenchmarkDatabase.requireExactOwnershipMarker(
                        "database vev_bench",
                        "vev-owned-fixture:other:v1"));
    }

    @Test
    void trustedSearchPathRejectsRetainedTemporaryStateAndAmbiguousResults() {
        assertDoesNotThrow(() -> BenchmarkDatabase.requireTrustedSearchPathResult(
                "pg_catalog", "pg_catalog", 0, false, false));
        assertThrows(
                IllegalStateException.class,
                () -> BenchmarkDatabase.requireTrustedSearchPathResult(
                        "pg_catalog", "pg_catalog", 16_384, false, false));
        assertThrows(
                IllegalStateException.class,
                () -> BenchmarkDatabase.requireTrustedSearchPathResult(
                        "pg_catalog", "pg_catalog", 0, true, false));
        assertThrows(
                IllegalStateException.class,
                () -> BenchmarkDatabase.requireTrustedSearchPathResult(
                        "public, pg_catalog", "pg_catalog", 0, false, false));
        assertThrows(
                IllegalStateException.class,
                () -> BenchmarkDatabase.requireTrustedSearchPathResult(
                        "pg_catalog", "pg_catalog", 0, false, true));
    }
}
