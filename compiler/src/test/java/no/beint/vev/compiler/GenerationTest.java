package no.beint.vev.compiler;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GenerationTest {
    @TempDir Path directory;
    @Test void groupedEditsRetainStableSignaturesAndFailurePreservesPreviousOutputs() throws Exception {
        try (var postgres = new Postgres("")) {
            Path queries = Files.createDirectory(directory.resolve("grouped"));
            Path output = directory.resolve("output");
            Path file = queries.resolve("numbers.sql");
            String first = "-- vev.name: first :one\n-- vev.scope: global\nSELECT 1::int AS value;\n";
            String second = "-- vev.name: second :one\n-- vev.scope: global\nSELECT 2::int AS value;\n";
            Files.writeString(file, first + second);
            try (var connection = postgres.connect()) {
                QueryCompiler.compile(connection, queries, output, "sample", "Queries");
                Path facade = output.resolve("Queries.kt"), unchanged = output.resolve("Queries_second_Sql.kt");
                var facadeTime = Files.getLastModifiedTime(facade);
                var unchangedTime = Files.getLastModifiedTime(unchanged);
                Files.writeString(file, first.replace("SELECT 1", "SELECT 3") + second);
                QueryCompiler.compile(connection, queries, output, "sample", "Queries");
                assertEquals(facadeTime, Files.getLastModifiedTime(facade));
                assertEquals(unchangedTime, Files.getLastModifiedTime(unchanged));
                String previous = Files.readString(output.resolve("Queries_first_Sql.kt"));
                Files.writeString(file, first + second.replace("2::int", "missing_column"));
                assertThrows(java.sql.SQLException.class,
                        () -> QueryCompiler.compile(connection, queries, output, "sample", "Queries"));
                assertEquals(previous, Files.readString(output.resolve("Queries_first_Sql.kt")));
                Files.writeString(file, first + first);
                assertThrows(IllegalArgumentException.class,
                        () -> QueryCompiler.compile(connection, queries, output, "sample", "Queries"));
                Files.writeString(file, first);
                QueryCompiler.compile(connection, queries, output, "sample", "Queries");
                assertFalse(Files.exists(unchanged));
            }
        }
    }

    @Test void sourceEditsPreserveOtherFilesAndRemovedQueriesCannotLeaveStaleImplementations() throws Exception {
        try(var postgres=new Postgres("")) {
            Path queries=Files.createDirectory(directory.resolve("queries"));
            Path output=directory.resolve("generated");
            Path first=queries.resolve("first.sql"),second=queries.resolve("second.sql");
            Files.writeString(first,"-- name: first :one\n-- scope: global\nSELECT 1::int AS value");
            Files.writeString(second,"-- name: second :one\n-- scope: global\nSELECT 2::int AS value");
            try(var connection=postgres.connect()) {
                QueryCompiler.compile(connection,queries,output,"sample","Queries");
                var unchanged=Files.getLastModifiedTime(output.resolve("Queries_second_Sql.kt"));
                var facade=Files.getLastModifiedTime(output.resolve("Queries.kt"));
                Files.writeString(first,"-- name: first :one\n-- scope: global\nSELECT 3::int AS value");
                QueryCompiler.compile(connection,queries,output,"sample","Queries");
                assertEquals(unchanged,Files.getLastModifiedTime(output.resolve("Queries_second_Sql.kt")));
                assertEquals(facade,Files.getLastModifiedTime(output.resolve("Queries.kt")));
                Files.delete(second);
                QueryCompiler.compile(connection,queries,output,"sample","Queries");
                assertFalse(Files.exists(output.resolve("Queries_second_Sql.kt")));
                assertFalse(Files.readString(output.resolve("Queries.kt")).contains("second"));
                Files.writeString(output.resolve(".vev-output"),"../outside.kt\n");
                assertThrows(java.io.IOException.class,()->QueryCompiler.compile(connection,queries,output,"sample","Queries"));
            }
        }
    }
}
