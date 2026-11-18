package no.beint.vev.gradle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PostgresBinTest {
    @TempDir Path directory;

    private String fake(String name, String version) throws Exception {
        Path bin = Files.createDirectory(directory.resolve(name));
        Path postgres = bin.resolve("postgres");
        Files.writeString(postgres, "#!/bin/sh\nprintf 'postgres (PostgreSQL) " + version + "\\n'\n");
        assertTrue(postgres.toFile().setExecutable(true));
        return bin.toString();
    }

    @Test void skipsMissingAndOtherMajorVersions() throws Exception {
        String old = fake("17", "17.6"), current = fake("18", "18.1"), later = fake("19", "19.0");
        assertEquals(current, PostgresBin.find(List.of(directory.resolve("missing").toString(), old, later, current)));
    }

    @Test void explainsWhereItSearched() throws Exception {
        String old = fake("17", "17.6");
        var error = assertThrows(IllegalStateException.class, () -> PostgresBin.find(List.of(old)));
        assertTrue(error.getMessage().contains(old) && error.getMessage().contains("postgresBin"), error.getMessage());
    }
}
