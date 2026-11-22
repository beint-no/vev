package no.beint.vev.benchmark;

import no.beint.vev.Session;
import no.beint.vev.compiler.Postgres;
import no.beint.vev.compiler.SqlSource;
import no.beint.vev.reai.fixture.LedgerRow;
import no.beint.vev.reai.fixture.Queries;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Same real schema, synthetic 3-tenant dataset, SQL, rows, fetch size, and transaction boundary. */
@State(Scope.Thread) @BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.MICROSECONDS)
public class ReaiBenchmark {
    private Postgres postgres;
    private Connection connection;
    private String ledger;
    @Setup(Level.Trial) public void setup() throws Exception {
        postgres=new Postgres("");
        postgres.migrate(Path.of("../test-fixtures/reai/migration"));
        connection=postgres.connect(); connection.setAutoCommit(false);
        try(var statement=connection.createStatement()) { statement.execute(Files.readString(Path.of("../test-fixtures/reai/seed.sql"))); }
        connection.commit();
        ledger=SqlSource.parse(Path.of("ledger.sql"),Files.readString(Path.of("../reai-verification/src/main/sql/ledger.sql"))).sql();
        if(!generatedLedger().equals(jdbcLedger())) throw new IllegalStateException("Result parity failed");
    }
    @TearDown(Level.Trial) public void close() throws Exception {
        try { if(connection!=null) connection.close(); } finally { if(postgres!=null) postgres.close(); }
    }
    @Benchmark public List<LedgerRow> generatedLedger() throws Exception {
        var result=Session.borrowTenant(connection, 1,s->Queries.ledger()); connection.commit(); return result;
    }
    @Benchmark public List<LedgerRow> jdbcLedger() throws Exception {
        var rows=new ArrayList<LedgerRow>();
        try(var statement=connection.prepareStatement(ledger)) {
            statement.setInt(1,1);
            try(var result=statement.executeQuery()) {
                while(result.next()) {
                    if(rows.size()==1000) throw new IllegalStateException("Row bound");
                    rows.add(new LedgerRow(
                        java.util.Objects.requireNonNull(result.getObject(1,Long.class)),
                        java.util.Objects.requireNonNull(result.getObject(2,Long.class)),
                        java.util.Objects.requireNonNull(result.getObject(3,java.time.LocalDate.class)),
                        java.util.Objects.requireNonNull(result.getString(4)),
                        java.util.Objects.requireNonNull(result.getObject(5,java.math.BigDecimal.class)),
                        java.util.Objects.requireNonNull(result.getString(6)),result.getString(7),
                        java.util.Objects.requireNonNull(result.getString(8))));
                }
            }
        }
        connection.commit();return List.copyOf(rows);
    }
}
