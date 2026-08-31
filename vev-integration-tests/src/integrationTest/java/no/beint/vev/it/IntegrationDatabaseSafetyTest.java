package no.beint.vev.it;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class IntegrationDatabaseSafetyTest {
    @Test
    void acceptsOnlyLiteralLoopbackOrExactRemoteOptIn() {
        assertDoesNotThrow(() -> IntegrationDatabase.requireSafeAdminUrl(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ""));
        assertDoesNotThrow(() -> IntegrationDatabase.requireSafeAdminUrl(
                "jdbc:postgresql://[::1]:5432/postgres", ""));
        assertThrows(IllegalStateException.class, () -> IntegrationDatabase.requireSafeAdminUrl(
                "jdbc:postgresql://localhost:5432/postgres", ""));
        assertThrows(IllegalStateException.class, () -> IntegrationDatabase.requireSafeAdminUrl(
                "jdbc:postgresql://127.0.0.1:5432/postgres?sslmode=disable", ""));
        assertThrows(IllegalStateException.class, () -> IntegrationDatabase.requireSafeAdminUrl(
                "jdbc:postgresql://localhost,remote.example/postgres", "vev_it"));
        assertThrows(IllegalStateException.class, () -> IntegrationDatabase.requireSafeAdminUrl(
                "jdbc:postgresql://database.example:5432/postgres", ""));
        assertThrows(IllegalStateException.class, () -> IntegrationDatabase.requireSafeAdminUrl(
                "jdbc:postgresql://database.example:5432/postgres", "yes"));
        assertDoesNotThrow(() -> IntegrationDatabase.requireSafeAdminUrl(
                "jdbc:postgresql://database.example:5432/postgres", "vev_it"));
        assertThrows(IllegalStateException.class, () -> IntegrationDatabase.requireSafeAdminUrl(
                "jdbc:postgresql://database.example:5432/postgres?sslmode=require", "vev_it"));
        assertThrows(IllegalStateException.class, () -> IntegrationDatabase.requireSafeAdminUrl(
                "jdbc:postgresql://user@database.example:5432/postgres", "vev_it"));
    }
}
