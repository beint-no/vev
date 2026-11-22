package no.beint.vev.compiler;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GenerationTest {
    @TempDir Path directory;
    @Test void sourceEditsPreserveOtherFilesAndRemovedQueriesCannotLeaveStaleImplementations() throws Exception {
        try(var postgres=new Postgres("")) {
            Path queries=Files.createDirectory(directory.resolve("queries"));
            Path output=directory.resolve("generated");
            Path first=queries.resolve("first.sql"),second=queries.resolve("second.sql");
            Files.writeString(first,"-- vev.name: first :one\n-- vev.scope: global\nSELECT 1::int AS value");
            Files.writeString(second,"-- vev.name: second :one\n-- vev.scope: global\nSELECT 2::int AS value");
            try(var connection=postgres.connect()) {
                QueryCompiler.compile(connection,queries,output,"sample","Queries");
                var unchanged=Files.getLastModifiedTime(output.resolve("Queries_second_Sql.kt"));
                var facade=Files.getLastModifiedTime(output.resolve("Queries.kt"));
                Files.writeString(first,"-- vev.name: first :one\n-- vev.scope: global\nSELECT 3::int AS value");
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
