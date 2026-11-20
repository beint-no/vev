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
        for (String program : List.of("postgres", "initdb", "pg_ctl")) {
            writeVersion(bin, program, version);
        }
        return bin.toString();
    }

    private void writeVersion(Path bin, String program, String version) throws Exception {
        Path tool = bin.resolve(program);
        Files.writeString(tool, "#!/bin/sh\nprintf '" + program + " (PostgreSQL) " + version + "\\n'\n");
        assertTrue(tool.toFile().setExecutable(true));
    }

    @Test void fallsBackFromIncompleteOrMixedInstallations() throws Exception {
        String incomplete = fake("incomplete", "19.0");
        Files.delete(Path.of(incomplete, "initdb"));
        String mixed = fake("mixed", "19.0");
        writeVersion(Path.of(mixed), "pg_ctl", "18.7");
        String complete = fake("complete", "19.0");
        assertEquals(complete, PostgresBin.find(List.of(incomplete, mixed, complete)));
        assertThrows(IllegalStateException.class, () -> PostgresBin.find(List.of(mixed)));
    }

    @Test void skipsMissingAndOtherMajorVersions() throws Exception {
        String old = fake("18", "18.7"), current = fake("19", "19.0"), later = fake("20", "20.0");
        assertEquals(current, PostgresBin.find(List.of(directory.resolve("missing").toString(), old, later, current)));
    }

    @Test void explainsWhereItSearched() throws Exception {
        String old = fake("18", "18.7");
        var error = assertThrows(IllegalStateException.class, () -> PostgresBin.find(List.of(old)));
        assertTrue(error.getMessage().contains(old) && error.getMessage().contains("postgresBin"), error.getMessage());
    }
}
