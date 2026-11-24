package no.beint.vev.benchmark;

import no.beint.vev.Session;
import no.beint.vev.compiler.Postgres;
import no.beint.vev.fixture.CustomersRow;
import no.beint.vev.fixture.Queries;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.openjdk.jmh.annotations.*;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Warm native DTO projection, no entities, proxies, Spring repository, or deliberately expensive mapping. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class HibernateBenchmark {
    private Postgres postgres;
    private Connection connection;
    private StandardServiceRegistry registry;
    private SessionFactory factory;
    private static final String SQL = "SELECT id, name FROM customer WHERE tenant_id = :tenantId "
            + "AND (CAST(:name AS text) IS NULL OR name ILIKE :name) ORDER BY id";

    @Setup(Level.Trial) public void setup() throws Exception {
        postgres = new Postgres("");
        connection = postgres.connect();
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE customer(id int PRIMARY KEY, tenant_id int NOT NULL, name text NOT NULL)");
            statement.execute("INSERT INTO customer SELECT n, 7, 'Customer ' || n FROM generate_series(1,5) n");
        }
        registry = new StandardServiceRegistryBuilder()
                .applySetting("hibernate.dialect", new org.hibernate.dialect.PostgreSQLDialect(
                        org.hibernate.dialect.DatabaseVersion.make(19, 0)))
                .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
                .applySetting("hibernate.connection.provider_disables_autocommit", true)
                .build();
        factory = new MetadataSources(registry).buildMetadata().buildSessionFactory();
        connection.setAutoCommit(false);
        if (!vevNative().equals(hibernateNative())) throw new IllegalStateException("Hibernate/Vev result parity failed");
    }

    @Benchmark public List<CustomersRow> vevNative() throws Exception {
        var rows = Session.borrowTenantRead(connection, 7, tx -> Queries.customers(tx, null));
        connection.commit();
        return rows;
    }

    @Benchmark public List<CustomersRow> hibernateNative() {
        try (var session = factory.withOptions().connection(connection).openSession()) {
            var transaction = session.beginTransaction();
            try {
                var rows = session.createNativeQuery(SQL, CustomersRow.class)
                        .setParameter("tenantId", 7, Integer.class)
                        .setParameter("name", null, String.class)
                        .setReadOnly(true).getResultList();
                transaction.commit();
                return List.copyOf(rows);
            } catch (RuntimeException | Error failure) {
                if (transaction.isActive()) transaction.rollback();
                throw failure;
            }
        }
    }

    @TearDown(Level.Trial) public void close() throws Exception {
        try { if (factory != null) factory.close(); }
        finally {
            try { if (registry != null) StandardServiceRegistryBuilder.destroy(registry); }
            finally {
                try { if (connection != null) connection.close(); }
                finally { if (postgres != null) postgres.close(); }
            }
        }
    }
}
