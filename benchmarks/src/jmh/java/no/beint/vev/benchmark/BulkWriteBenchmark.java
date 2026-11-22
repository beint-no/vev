package no.beint.vev.benchmark;

import no.beint.vev.Session;
import no.beint.vev.reai.fixture.Queries;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.openjdk.jmh.annotations.*;

@State(Scope.Thread) @BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.MICROSECONDS)
public class BulkWriteBenchmark {
    @Param({"1","32","512"}) public int rows;
    private ReaiFixture fixture;
    private String sql;
    private List<Integer> ids;
    @Setup(Level.Trial) public void setup() throws Exception {
        fixture=new ReaiFixture();sql=ReaiFixture.sql("departments-bulk");
        ids=IntStream.range(900,900+rows).boxed().toList();
        if(generatedBulk()!=rows || jdbcBulk()!=rows) throw new IllegalStateException("Bulk affected-row parity failed");
    }
    @TearDown(Level.Trial) public void close() throws Exception { if(fixture!=null) fixture.close(); }
    @Benchmark public long generatedBulk() throws Exception {
        try { return Session.borrowTenant(fixture.connection, 1,s->Queries.bulkDepartments(ids)); }
        finally { fixture.connection.rollback(); }
    }
    @Benchmark public long jdbcBulk() throws Exception {
        var array=fixture.connection.createArrayOf("int4",ids.toArray(Integer[]::new));
        try(var statement=fixture.connection.prepareStatement(sql)) {
            statement.setObject(1,1,java.sql.Types.INTEGER);statement.setArray(2,array);
            return statement.executeLargeUpdate();
        } finally {
            try { array.free(); } finally { fixture.connection.rollback(); }
        }
    }
}
