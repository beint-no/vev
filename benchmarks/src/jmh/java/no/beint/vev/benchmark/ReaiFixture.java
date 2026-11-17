package no.beint.vev.benchmark;

import no.beint.vev.compiler.Postgres;
import no.beint.vev.compiler.SqlSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;

/** Each JMH state owns an independent real-schema database; never share mutable benchmark data. */
final class ReaiFixture implements AutoCloseable {
    private final Postgres postgres;
    final Connection connection;
    ReaiFixture() throws Exception {
        postgres=new Postgres("");
        postgres.migrate(Path.of("../test-fixtures/reai/migration"));
        connection=postgres.connect();connection.setAutoCommit(false);
        try(var statement=connection.createStatement()) { statement.execute(Files.readString(Path.of("../test-fixtures/reai/seed.sql"))); }
        connection.commit();
    }
    static String sql(String name) throws java.io.IOException {
        Path path=Path.of("../reai-verification/src/main/sql/"+name+".sql");
        return SqlSource.parse(path,Files.readString(path)).sql();
    }
    @Override public void close() throws java.io.IOException,java.sql.SQLException {
        try { connection.close(); } finally { postgres.close(); }
    }
}
