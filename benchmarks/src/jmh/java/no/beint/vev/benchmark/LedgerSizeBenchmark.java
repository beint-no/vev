package no.beint.vev.benchmark;

import no.beint.vev.Session;
import no.beint.vev.reai.fixture.PagedLedgerRow;
import no.beint.vev.reai.fixture.Queries;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Thread) @BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.MICROSECONDS)
public class LedgerSizeBenchmark {
    @Param({"1","100","600"}) public int rows;
    private ReaiFixture fixture;
    private String sql;
    @Setup(Level.Trial) public void setup() throws Exception {
        fixture=new ReaiFixture();sql=ReaiFixture.sql("ledger-page");
        var generated=generatedPage();
        if(generated.size()!=rows || !generated.equals(jdbcPage())) throw new IllegalStateException("Paged ledger result parity failed");
    }
    @TearDown(Level.Trial) public void close() throws Exception { if(fixture!=null) fixture.close(); }
    @Benchmark public List<PagedLedgerRow> generatedPage() throws Exception {
        var result=Session.borrow(fixture.connection,1,s->Queries.pagedLedger(rows));
        fixture.connection.commit();return result;
    }
    @Benchmark public List<PagedLedgerRow> jdbcPage() throws Exception {
        var result=new ArrayList<PagedLedgerRow>();
        try(var statement=fixture.connection.prepareStatement(sql)) {
            statement.setInt(1,1);statement.setInt(2,rows);
            try(var r=statement.executeQuery()) {
                while(r.next()) {
                    if(result.size()==1000) throw new IllegalStateException("Row bound");
                    result.add(new PagedLedgerRow(java.util.Objects.requireNonNull(r.getObject(1,Long.class)),
                        java.util.Objects.requireNonNull(r.getObject(2,Long.class)),java.util.Objects.requireNonNull(r.getObject(3,java.time.LocalDate.class)),
                        java.util.Objects.requireNonNull(r.getString(4)),java.util.Objects.requireNonNull(r.getObject(5,java.math.BigDecimal.class)),
                        java.util.Objects.requireNonNull(r.getString(6)),r.getString(7),java.util.Objects.requireNonNull(r.getString(8))));
                }
            }
        }
        fixture.connection.commit();return List.copyOf(result);
    }
}
