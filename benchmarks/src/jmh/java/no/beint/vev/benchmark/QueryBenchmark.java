package no.beint.vev.benchmark;

import no.beint.vev.Session;
import no.beint.vev.compiler.Postgres;
import no.beint.vev.compiler.SqlSource;
import no.beint.vev.fixture.CustomersRow;
import no.beint.vev.fixture.Queries;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class QueryBenchmark {
    private Postgres postgres;
    private Connection connection;
    private String sql;

    @Setup(Level.Trial) public void setup() throws Exception {
        postgres = new Postgres("");
        connection = postgres.connect();
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE customer(id integer PRIMARY KEY, tenant_id integer NOT NULL, name text NOT NULL)");
            statement.execute("INSERT INTO customer SELECT n, 7, 'Customer ' || n FROM generate_series(1,5) n");
        }
        try (var input = getClass().getResourceAsStream("/sql/customers.sql")) {
            if (input == null) throw new IllegalStateException("Missing benchmark query");
            sql = SqlSource.parse(Path.of("customers.sql"), new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).sql();
        }
        connection.setAutoCommit(false);
        if (!generated().equals(jdbc())) throw new IllegalStateException("Benchmark result parity failed");
    }

    @TearDown(Level.Trial) public void close() throws Exception {
        try { if (connection != null) connection.close(); }
        finally { if (postgres != null) postgres.close(); }
    }

    @Benchmark public List<CustomersRow> generated() throws Exception {
        var rows = Session.borrow(connection, session -> Queries.INSTANCE.customers(session, 7, null));
        connection.commit();
        return rows;
    }

    @Benchmark public List<CustomersRow> jdbc() throws Exception {
        List<CustomersRow> rows = new ArrayList<>();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setFetchSize(5);
            statement.setObject(1, 7, java.sql.Types.INTEGER);
            statement.setObject(2, null, java.sql.Types.VARCHAR);
            statement.setObject(3, null, java.sql.Types.VARCHAR);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    if (rows.size() == 5) throw new IllegalStateException("row bound");
                    rows.add(new CustomersRow(java.util.Objects.requireNonNull(result.getObject(1, Integer.class)), java.util.Objects.requireNonNull(result.getObject(2, String.class))));
                }
            }
        }
        connection.commit();
        return List.copyOf(rows);
    }
}
