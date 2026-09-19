package no.beint.vev.compiler;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MigrationTest {
    @TempDir Path directory;

    @Test void concurrentIndexesAndBuildOnlyCallbacksRunInDisposableDatabase() throws Exception {
        Path migrations = Files.createDirectory(directory.resolve("migrations"));
        Path fixtures = Files.createDirectory(directory.resolve("fixtures"));
        Files.writeString(migrations.resolve("V1__item.sql"), "CREATE TABLE item(id integer PRIMARY KEY);");
        Files.writeString(migrations.resolve("V2__index.sql"), "CREATE INDEX CONCURRENTLY item_index ON item(id);");
        Files.writeString(migrations.resolve("V2__index.sql.conf"), "executeInTransaction=false");
        Files.writeString(fixtures.resolve("afterEachMigrate__seed.sql"), "INSERT INTO item VALUES (1) ON CONFLICT DO NOTHING;");
        try (var postgres = new Postgres("")) {
            postgres.migrate(migrations, fixtures);
            try (var c = postgres.connect(); var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM item")) {
                assertTrue(r.next()); assertEquals(1, r.getInt(1));
            }
        }
    }

    @Test void failedMigrationStillAllowsClusterCleanup() throws Exception {
        Path migrations = Files.createDirectory(directory.resolve("broken"));
        Files.writeString(migrations.resolve("V1__broken.sql"), "CREATE TABLE broken;");
        Postgres postgres = new Postgres("");
        try {
            assertThrows(RuntimeException.class, () -> postgres.migrate(migrations));
        } finally { postgres.close(); }
        assertThrows(java.sql.SQLException.class, postgres::connect);
        postgres.close();
    }
}
