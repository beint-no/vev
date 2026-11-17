package no.beint.vev.benchmark;

import no.beint.vev.Session;
import no.beint.vev.reai.fixture.CreateDepartmentRow;
import no.beint.vev.reai.fixture.Queries;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Thread) @BenchmarkMode(Mode.AverageTime) @OutputTimeUnit(TimeUnit.MICROSECONDS)
public class InsertBenchmark {
    private ReaiFixture fixture;
    private String sql;
    @Setup(Level.Trial) public void setup() throws Exception {
        fixture=new ReaiFixture();sql=ReaiFixture.sql("department-insert");
        if(!generatedInsert().equals(jdbcInsert())) throw new IllegalStateException("INSERT RETURNING result parity failed");
    }
    @TearDown(Level.Trial) public void close() throws Exception { if(fixture!=null) fixture.close(); }
    @Benchmark public CreateDepartmentRow generatedInsert() throws Exception {
        try { return Session.borrow(fixture.connection,1,s->Queries.createDepartment(900,"Synthetic Insert")); }
        finally { fixture.connection.rollback(); }
    }
    @Benchmark public CreateDepartmentRow jdbcInsert() throws Exception {
        try(var statement=fixture.connection.prepareStatement(sql)) {
            statement.setFetchSize(2);
            statement.setObject(1,900,java.sql.Types.INTEGER);statement.setObject(2,1,java.sql.Types.INTEGER);statement.setObject(3,"Synthetic Insert",java.sql.Types.VARCHAR);
            try(var result=statement.executeQuery()) {
                if(!result.next()) throw new IllegalStateException("Expected one returned row");
                var row=new CreateDepartmentRow(java.util.Objects.requireNonNull(result.getObject(1,Integer.class)),java.util.Objects.requireNonNull(result.getString(2)));
                if(result.next()) throw new IllegalStateException("Expected only one returned row");
                return row;
            }
        } finally { fixture.connection.rollback(); }
    }
}
