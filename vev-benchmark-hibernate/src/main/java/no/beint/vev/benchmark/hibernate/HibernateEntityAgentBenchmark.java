package no.beint.vev.benchmark.hibernate;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityTransaction;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.ToLongFunction;
import org.hibernate.SessionFactory;
import org.hibernate.StatelessSession;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.query.SelectionQuery;
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

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 8, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
@Threads(1)
@State(Scope.Benchmark)
public class HibernateEntityAgentBenchmark {
    private static final int CONNECTION_POOL_SIZE = 8;

    private HikariDataSource dataSource;
    private StandardServiceRegistry serviceRegistry;
    private SessionFactory sessionFactory;
    private BenchmarkDataset.DatabaseIdentity databaseIdentity;
    private List<BenchmarkAccountId> identifiers32;
    private List<BenchmarkAccountId> identifiers256;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        var databaseConfiguration = BenchmarkDatabaseConfiguration.fromEnvironment();
        BenchmarkDataset.resetUpdateRows(databaseConfiguration);
        BenchmarkDataset.verify(databaseConfiguration);
        databaseIdentity = BenchmarkDataset.verifyDatabaseIdentity(databaseConfiguration);
        identifiers32 = BenchmarkDataset.findMultipleIdentifiers(BenchmarkDataset.FIND_MULTIPLE_32_PRESENT_COUNT);
        identifiers256 = BenchmarkDataset.findMultipleIdentifiers(BenchmarkDataset.FIND_MULTIPLE_256_PRESENT_COUNT);
        dataSource = databaseConfiguration.openPool(CONNECTION_POOL_SIZE);
        serviceRegistry = buildServiceRegistry(dataSource);
        try {
            sessionFactory = new MetadataSources(serviceRegistry)
                    .addAnnotatedClass(BenchmarkAccount.class)
                    .buildMetadata()
                    .buildSessionFactory();
            verifyHibernateConfiguration();
            verifyHibernateResults();
        } catch (RuntimeException | Error failure) {
            closeAfterSetupFailure(failure);
            throw failure;
        }
    }

    @TearDown(Level.Trial)
    public void tearDown() {
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

    @Benchmark
    public long transactionOnly() {
        return executeReadTransaction(ignored -> BenchmarkDataset.TENANT_ID);
    }

    @Benchmark
    public long findOne() {
        return executeReadTransaction(entityAgent -> BenchmarkDataset.benchmarkChecksum(
                entityAgent.find(BenchmarkAccount.class, BenchmarkDataset.identifier(BenchmarkDataset.FIND_ONE_ID))));
    }

    @Benchmark
    public long findMultiple32() {
        return executeReadTransaction(entityAgent -> BenchmarkDataset.findMultipleChecksum(
                entityAgent.findMultiple(BenchmarkAccount.class, identifiers32),
                identifiers32));
    }

    @Benchmark
    public long findMultiple256() {
        return executeReadTransaction(entityAgent -> BenchmarkDataset.findMultipleChecksum(
                entityAgent.findMultiple(BenchmarkAccount.class, identifiers256),
                identifiers256));
    }

    @Benchmark
    public long boundedScan() {
        return executeReadTransaction(entityAgent -> BenchmarkDataset.boundedScanChecksum(
                createBoundedScan(entityAgent).getResultList()));
    }

    @Benchmark
    public long indexedEmail() {
        return executeReadTransaction(entityAgent -> BenchmarkDataset.indexedPageChecksum(
                createIndexedEmail(entityAgent).getResultList(), BenchmarkDataset.INDEXED_EMAIL_LIMIT));
    }

    @Benchmark
    public long indexedActive32() {
        return executeReadTransaction(entityAgent -> BenchmarkDataset.indexedPageChecksum(
                createIndexedActive(entityAgent).getResultList(), BenchmarkDataset.INDEXED_ACTIVE_LIMIT));
    }

    private long executeReadTransaction(ToLongFunction<StatelessSession> operation) {
        try (var entityAgent = openEntityAgent()) {
            EntityTransaction transaction = entityAgent.getTransaction();
            try {
                prepareTransactionConnection(entityAgent);
                transaction.begin();
                applyTransactionContext(entityAgent);
                var checksum = operation.applyAsLong(entityAgent);
                verifyTransactionContext(entityAgent);
                transaction.commit();
                return checksum;
            } catch (RuntimeException | Error failure) {
                rollbackAfterFailure(transaction, failure);
                throw failure;
            }
        }
    }

    private StatelessSession openEntityAgent() {
        return sessionFactory.createEntityAgent();
    }

    private static SelectionQuery<BenchmarkAccount> createBoundedScan(StatelessSession entityAgent) {
        return entityAgent.createSelectionQuery("""
                select account
                from BenchmarkAccount account
                where account.tenantId = :tenantId
                order by account.id
                """, BenchmarkAccount.class)
                .setParameter("tenantId", BenchmarkDataset.TENANT_ID)
                .setMaxResults(BenchmarkDataset.SCAN_SIZE + 1)
                .setFetchSize(BenchmarkDataset.SCAN_SIZE + 1)
                .setReadOnly(true)
                .setCacheable(false);
    }

    private static SelectionQuery<BenchmarkAccount> createIndexedEmail(StatelessSession entityAgent) {
        return entityAgent.createSelectionQuery("""
                select account
                from BenchmarkAccount account
                where account.tenantId = :tenantId
                  and account.email = :email
                order by account.id
                """, BenchmarkAccount.class)
                .setParameter("tenantId", BenchmarkDataset.TENANT_ID)
                .setParameter("email", BenchmarkDataset.INDEXED_EMAIL_VALUE)
                .setMaxResults(BenchmarkDataset.INDEXED_EMAIL_LIMIT + 1)
                .setFetchSize(BenchmarkDataset.INDEXED_EMAIL_LIMIT + 1)
                .setReadOnly(true)
                .setCacheable(false);
    }

    private static SelectionQuery<BenchmarkAccount> createIndexedActive(StatelessSession entityAgent) {
        return entityAgent.createSelectionQuery("""
                select account
                from BenchmarkAccount account
                where account.tenantId = :tenantId
                  and account.active = :active
                order by account.id
                """, BenchmarkAccount.class)
                .setParameter("tenantId", BenchmarkDataset.TENANT_ID)
                .setParameter("active", true)
                .setMaxResults(BenchmarkDataset.INDEXED_ACTIVE_LIMIT + 1)
                .setFetchSize(BenchmarkDataset.INDEXED_ACTIVE_LIMIT + 1)
                .setReadOnly(true)
                .setCacheable(false);
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

    private void verifyHibernateResults() {
        try (var entityAgent = openEntityAgent()) {
            EntityTransaction transaction = entityAgent.getTransaction();
            try {
                prepareTransactionConnection(entityAgent);
                transaction.begin();
                applyTransactionContext(entityAgent);
                verifyConnectionSettings(entityAgent);
                var one = entityAgent.find(
                        BenchmarkAccount.class,
                        BenchmarkDataset.identifier(BenchmarkDataset.FIND_ONE_ID));
                var expectedOne = BenchmarkDataset.expectedAccount(BenchmarkDataset.FIND_ONE_ID);
                if (BenchmarkDataset.benchmarkChecksum(one) != BenchmarkDataset.benchmarkChecksum(expectedOne)) {
                    throw new IllegalStateException("Hibernate find-one checksum mismatch");
                }

                var actual32 = BenchmarkDataset.findMultipleChecksum(
                        entityAgent.findMultiple(BenchmarkAccount.class, identifiers32),
                        identifiers32);
                var expected32 = BenchmarkDataset.findMultipleChecksum(
                        BenchmarkDataset.expectedFindMultipleAccounts(BenchmarkDataset.FIND_MULTIPLE_32_PRESENT_COUNT),
                        identifiers32);
                if (actual32 != expected32) {
                    throw new IllegalStateException("Hibernate findMultiple 32 checksum mismatch");
                }

                var actual256 = BenchmarkDataset.findMultipleChecksum(
                        entityAgent.findMultiple(BenchmarkAccount.class, identifiers256),
                        identifiers256);
                var expected256 = BenchmarkDataset.findMultipleChecksum(
                        BenchmarkDataset.expectedFindMultipleAccounts(BenchmarkDataset.FIND_MULTIPLE_256_PRESENT_COUNT),
                        identifiers256);
                if (actual256 != expected256) {
                    throw new IllegalStateException("Hibernate findMultiple 256 checksum mismatch");
                }

                var actualScan = BenchmarkDataset.boundedScanChecksum(createBoundedScan(entityAgent).getResultList());
                var expectedScan = BenchmarkDataset.boundedScanChecksum(
                        BenchmarkDataset.expectedAccounts(1, BenchmarkDataset.SCAN_SIZE + 1));
                if (actualScan != expectedScan) {
                    throw new IllegalStateException("Hibernate bounded scan checksum mismatch");
                }

                var actualEmail = BenchmarkDataset.indexedPageChecksum(
                        createIndexedEmail(entityAgent).getResultList(), BenchmarkDataset.INDEXED_EMAIL_LIMIT);
                var expectedEmail = BenchmarkDataset.indexedPageChecksum(
                        List.of(BenchmarkDataset.expectedAccount(BenchmarkDataset.FIND_ONE_ID)),
                        BenchmarkDataset.INDEXED_EMAIL_LIMIT);
                if (actualEmail != expectedEmail) {
                    throw new IllegalStateException("Hibernate indexed-email checksum mismatch");
                }

                var actualActive = BenchmarkDataset.indexedPageChecksum(
                        createIndexedActive(entityAgent).getResultList(), BenchmarkDataset.INDEXED_ACTIVE_LIMIT);
                var expectedActive = BenchmarkDataset.indexedPageChecksum(
                        BenchmarkDataset.expectedActiveAccounts(BenchmarkDataset.INDEXED_ACTIVE_LIMIT + 1),
                        BenchmarkDataset.INDEXED_ACTIVE_LIMIT);
                if (actualActive != expectedActive) {
                    throw new IllegalStateException("Hibernate indexed-active checksum mismatch");
                }
                verifyTransactionContext(entityAgent);
                transaction.commit();
            } catch (RuntimeException | Error failure) {
                rollbackAfterFailure(transaction, failure);
                throw failure;
            }
        }
    }

    private static void verifyConnectionSettings(StatelessSession entityAgent) {
        var settings = entityAgent.doReturningWork(connection -> new ConnectionSettings(
                connection.isReadOnly(),
                connection.getTransactionIsolation(),
                connection.getNetworkTimeout()));
        if (!settings.readOnly()) {
            throw new IllegalStateException("Hibernate benchmark connection is not read-only");
        }
        if (settings.isolation() != Connection.TRANSACTION_SERIALIZABLE) {
            throw new IllegalStateException(
                    "Hibernate benchmark connection isolation is " + settings.isolation() + ", expected SERIALIZABLE");
        }
        if (settings.networkTimeout() != BenchmarkDataset.NETWORK_TIMEOUT_MILLISECONDS) {
            throw new IllegalStateException("Hibernate benchmark connection network deadline changed");
        }
    }

    private static void prepareTransactionConnection(StatelessSession entityAgent) {
        entityAgent.doWork(BenchmarkDataset::prepareReadConnection);
    }

    private void applyTransactionContext(StatelessSession entityAgent) {
        entityAgent.doWork(connection -> {
            BenchmarkDataset.requireTrustedSessionBaseline(connection);
            BenchmarkDataset.verifyNoRetainedTempSchema(connection);
            BenchmarkDataset.applyTransactionContext(connection, databaseIdentity, true);
        });
    }

    private void verifyTransactionContext(StatelessSession entityAgent) {
        entityAgent.doWork(connection -> BenchmarkDataset.verifyTransactionContext(connection, databaseIdentity, true));
    }

    private void closeAfterSetupFailure(Throwable failure) {
        if (sessionFactory != null) {
            try {
                sessionFactory.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
        if (serviceRegistry != null) {
            try {
                StandardServiceRegistryBuilder.destroy(serviceRegistry);
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
        }
        if (dataSource != null) {
            try {
                dataSource.close();
            } catch (RuntimeException closeFailure) {
                failure.addSuppressed(closeFailure);
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
