package no.beint.vev.benchmark;

import no.beint.vev.Session;
import no.beint.vev.compiler.Postgres;
import org.openjdk.jmh.annotations.*;
import java.sql.Connection;
import java.util.concurrent.TimeUnit;

/** Isolates the 2.1 single-row path from the previous list-based implementation. */
@State(Scope.Thread) @BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.MICROSECONDS)
public class SingleRowBenchmark {
    @Param({"0", "1"}) public int rows;
    private Postgres postgres;
    private Connection connection;
    private static final String SQL = "SELECT n FROM generate_series(1, ?::int) n";
    private final Session.Bind bind = b -> b.statement().setInt(1, rows);
    private final Session.Row<Integer> row = r -> r.getObject(1, Integer.class);

    @Setup(Level.Trial) public void setup() throws Exception {
        postgres = new Postgres("");
        connection = postgres.connect();
        connection.setAutoCommit(false);
        if (!java.util.Objects.equals(singleRow(), legacyListSingleRow())) throw new IllegalStateException("Single-row parity failed");
    }
    @Benchmark public Integer singleRow() throws Exception {
        var value = Session.borrow(connection, s -> s.optional(SQL, bind, row));
        connection.commit();
        return value;
    }
    @Benchmark public Integer legacyListSingleRow() throws Exception {
        var values = Session.borrow(connection, s -> s.list(SQL, 1, bind, row));
        connection.commit();
        return values.isEmpty() ? null : values.getFirst();
    }
    @TearDown(Level.Trial) public void close() throws Exception {
        try { if (connection != null) connection.close(); }
        finally { if (postgres != null) postgres.close(); }
    }
}
