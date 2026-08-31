package no.beint.vev.benchmark.hibernate;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityTransaction;
import org.hibernate.SessionFactory;
import org.hibernate.StatelessSession;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.sql.Connection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.ToLongFunction;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 1_024, batchSize = 1)
@Measurement(iterations = 2_048, batchSize = 1)
@Fork(3)
@Threads(1)
public class HibernateBatchUpdateBenchmark {
    private static final int CONNECTION_POOL_SIZE = 8;

    private BenchmarkDatabaseConfiguration databaseConfiguration;
    private HikariDataSource dataSource;
    private StandardServiceRegistry serviceRegistry;
    private SessionFactory sessionFactory;
    private BenchmarkDataset.DatabaseIdentity databaseIdentity;
    private long expectedVersion;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        databaseConfiguration = BenchmarkDatabaseConfiguration.fromEnvironment();
        BenchmarkDataset.prepare(BenchmarkAdminConfiguration.fromEnvironment(), databaseConfiguration);
        databaseIdentity = BenchmarkDataset.verifyDatabaseIdentity(databaseConfiguration);
        dataSource = databaseConfiguration.openPool(CONNECTION_POOL_SIZE);
        serviceRegistry = buildServiceRegistry(dataSource);
        try {
            sessionFactory = new MetadataSources(serviceRegistry)
                    .addAnnotatedClass(BenchmarkUpdateAccount.class)
                    .buildMetadata()
                    .buildSessionFactory();
            verifyHibernateConfiguration();
            verifyBatchUpdateResults();
            BenchmarkDataset.verifyUpdateRows(databaseConfiguration, 0L);
            expectedVersion = 0L;
        } catch (RuntimeException | Error failure) {
            closeAfterSetupFailure(failure);
            throw failure;
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        try {
            if (databaseConfiguration != null) {
                BenchmarkDataset.verifyUpdateRows(databaseConfiguration, expectedVersion);
            }
        } finally {
            try {
                closeRuntime();
            } finally {
                if (databaseConfiguration != null) {
                    BenchmarkDataset.resetUpdateRows(databaseConfiguration);
                }
            }
        }
    }

    @Benchmark
    public long updateMultiple32() {
        long currentVersion = expectedVersion;
        List<BenchmarkUpdateAccount> requested = BatchUpdateWorkload.request(currentVersion);
        long checksum = executeWriteTransaction(entityAgent -> {
            entityAgent.updateMultiple(requested);
            return BatchUpdateWorkload.checksum(currentVersion, requested);
        });
        expectedVersion = Math.addExact(currentVersion, 1L);
        return checksum;
    }

    private long executeWriteTransaction(ToLongFunction<StatelessSession> operation) {
        try (var entityAgent = sessionFactory.createEntityAgent()) {
            EntityTransaction transaction = entityAgent.getTransaction();
            try {
                prepareWriteConnection(entityAgent);
                transaction.begin();
                applyWriteTransactionContext(entityAgent);
                long checksum = operation.applyAsLong(entityAgent);
                verifyWriteTransactionContext(entityAgent);
                transaction.commit();
                return checksum;
            } catch (RuntimeException | Error failure) {
                rollbackAfterFailure(transaction, failure);
                throw failure;
            }
        }
    }

    private void verifyBatchUpdateResults() {
        List<BenchmarkUpdateAccount> requested = BatchUpdateWorkload.request(0L);
        try (var entityAgent = sessionFactory.createEntityAgent()) {
            EntityTransaction transaction = entityAgent.getTransaction();
            try {
                prepareWriteConnection(entityAgent);
                transaction.begin();
                applyWriteTransactionContext(entityAgent);
                verifyConnectionSettings(entityAgent);
                entityAgent.updateMultiple(requested);
                long providerChecksum = BatchUpdateWorkload.checksum(0L, requested);
                List<BenchmarkUpdateAccount> stored = entityAgent.createSelectionQuery("""
                        select account
                        from BenchmarkUpdateAccount account
                        where account.tenantId = :tenantId
                        order by account.id
                        """, BenchmarkUpdateAccount.class)
                        .setParameter("tenantId", BenchmarkDataset.TENANT_ID)
                        .setFetchSize(BatchUpdateWorkload.SIZE)
                        .setReadOnly(true)
                        .setCacheable(false)
                        .getResultList();
                long storedChecksum = BatchUpdateWorkload.checksum(0L, stored);
                if (providerChecksum != storedChecksum) {
                    throw new IllegalStateException("Hibernate batch-update entity state differs from stored state");
                }
                verifyWriteTransactionContext(entityAgent);
                transaction.rollback();
            } catch (RuntimeException | Error failure) {
                rollbackAfterFailure(transaction, failure);
                throw failure;
            }
        }
    }

    private static StandardServiceRegistry buildServiceRegistry(HikariDataSource dataSource) {
        return new StandardServiceRegistryBuilder()
                .applySetting("hibernate.connection.datasource", dataSource)
                .applySetting("hibernate.hbm2ddl.auto", "none")
                .applySetting("hibernate.connection.provider_disables_autocommit", "true")
                .applySetting("hibernate.connection.handling_mode", "IMMEDIATE_ACQUISITION_AND_HOLD")
                .applySetting("hibernate.jdbc.fetch_size", Integer.toString(BenchmarkDataset.FETCH_SIZE))
                .applySetting("hibernate.jdbc.batch_size", "0")
                .applySetting("hibernate.cache.use_second_level_cache", "false")
                .applySetting("hibernate.cache.use_query_cache", "false")
                .applySetting("hibernate.generate_statistics", "false")
                .applySetting("hibernate.show_sql", "false")
                .applySetting("hibernate.format_sql", "false")
                .applySetting("hibernate.highlight_sql", "false")
                .applySetting("hibernate.use_sql_comments", "false")
                .build();
    }

    private void verifyHibernateConfiguration() {
        if (dataSource.getMinimumIdle() != CONNECTION_POOL_SIZE
                || dataSource.getMaximumPoolSize() != CONNECTION_POOL_SIZE) {
            throw new IllegalStateException("HikariCP must use a fixed eight-connection pool");
        }
    }

    private static void verifyConnectionSettings(StatelessSession entityAgent) {
        var settings = entityAgent.doReturningWork(connection -> new ConnectionSettings(
                connection.isReadOnly(),
                connection.getTransactionIsolation(),
                connection.getNetworkTimeout()));
        if (settings.readOnly()) {
            throw new IllegalStateException("Hibernate batch-update connection is read-only");
        }
        if (settings.isolation() != Connection.TRANSACTION_SERIALIZABLE) {
            throw new IllegalStateException(
                    "Hibernate batch-update isolation is " + settings.isolation() + ", expected SERIALIZABLE");
        }
        if (settings.networkTimeout() != BenchmarkDataset.NETWORK_TIMEOUT_MILLISECONDS) {
            throw new IllegalStateException("Hibernate batch-update network deadline changed");
        }
    }

    private static void prepareWriteConnection(StatelessSession entityAgent) {
        entityAgent.doWork(BenchmarkDataset::prepareWriteConnection);
    }

    private void applyWriteTransactionContext(StatelessSession entityAgent) {
        entityAgent.doWork(connection -> {
            BenchmarkDataset.requireTrustedSessionBaseline(connection);
            BenchmarkDataset.verifyNoRetainedTempSchema(connection);
            BenchmarkDataset.applyTransactionContext(connection, databaseIdentity, false);
        });
    }

    private void verifyWriteTransactionContext(StatelessSession entityAgent) {
        entityAgent.doWork(connection -> BenchmarkDataset.verifyTransactionContext(
                connection,
                databaseIdentity,
                false));
    }

    private void closeAfterSetupFailure(Throwable failure) {
        try {
            closeRuntime();
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private void closeRuntime() {
        try {
            if (sessionFactory != null) {
                sessionFactory.close();
            }
        } finally {
            try {
                if (serviceRegistry != null) {
                    StandardServiceRegistryBuilder.destroy(serviceRegistry);
                }
            } finally {
                if (dataSource != null) {
                    dataSource.close();
                }
            }
        }
    }

    private static void rollbackAfterFailure(EntityTransaction transaction, Throwable failure) {
        if (!transaction.isActive()) {
            return;
        }
        try {
            transaction.rollback();
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private record ConnectionSettings(boolean readOnly, int isolation, int networkTimeout) {
    }
}
