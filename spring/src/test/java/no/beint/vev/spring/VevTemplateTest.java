package no.beint.vev.spring;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import no.beint.vev.cluster.Cluster;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VevTemplateTest {
    private Cluster cluster;
    private PGSimpleDataSource dataSource;
    private TransactionTemplate transactions;
    private JdbcTemplate jdbc;

    @BeforeAll void start() throws Exception {
        cluster = new Cluster("");
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(cluster.url());
        dataSource.setUser(cluster.user());
        dataSource.setPassword(cluster.password());
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE item(id integer PRIMARY KEY, name text NOT NULL)");
    }

    @AfterAll void stop() throws Exception { if (cluster != null) cluster.close(); }

    private List<String> names(no.beint.vev.Session session) throws java.sql.SQLException {
        return session.list("SELECT name FROM item ORDER BY id", 100, bindings -> {}, result -> result.getString(1));
    }

    @Test void queriesJoinTheSpringTransactionAndSeeItsUncommittedWrites() {
        var template = new VevTemplate(dataSource);
        transactions.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO item VALUES (1,'pending')");
            try { assertEquals(List.of("pending"), template.query(this::names)); }
            catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
            status.setRollbackOnly();
        });
        // Vev must not have committed or closed the connection Spring owns.
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM item", Integer.class));
    }

    @Test void queriesOutsideATransactionFailInsteadOfOpeningTheirOwn() {
        var template = new VevTemplate(dataSource);
        var failure = assertThrows(IllegalStateException.class, () -> template.query(this::names));
        assertTrue(failure.getMessage().contains("@Transactional"), failure.getMessage());
    }

    @Test void theBeforeQueryHookRunsOncePerQuery() {
        var flushes = new AtomicInteger();
        var template = new VevTemplate(dataSource, flushes::incrementAndGet);
        transactions.executeWithoutResult(status -> {
            try { template.query(this::names); template.query(this::names); }
            catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
            status.setRollbackOnly();
        });
        assertEquals(2, flushes.get());
    }

    @Test void tenantScopedQueriesRequireAPositiveTenant() {
        var template = new VevTemplate(dataSource);
        transactions.executeWithoutResult(status -> {
            assertThrows(IllegalArgumentException.class, () -> template.query(0, tenant -> names(tenant.session())));
            try { assertEquals(7, (int) template.query(7, tenant -> Integer.valueOf(tenant.tenantId()))); }
            catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
            status.setRollbackOnly();
        });
    }
}
