package no.beint.vev.pg;

import no.beint.vev.ReadTx;
import no.beint.vev.ReadWork;
import no.beint.vev.TenantAuthority;
import no.beint.vev.TenantScope;
import no.beint.vev.TransactionExecutor;
import no.beint.vev.WriteTx;
import no.beint.vev.WriteWork;
import no.beint.vev.spi.TransactionGuard;
import no.beint.vev.spi.TransactionScopes;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Fail-closed PostgreSQL 18 transaction executor for one verified model, database, role, and tenant authority.
 *
 * <p>Construction opens a connection and verifies the server version, database identity, model fingerprint, schema,
 * runtime role, and row-level tenant-isolation policy before claiming the authority. Each operation then uses a fresh
 * serializable lexical transaction and revalidates its security context before commit.</p>
 *
 * @param <M> closed-model marker type
 * @param <T> tenant-key type
 */
public final class PgVev<M, T> implements TransactionExecutor<M, T> {
    private static final int POSTGRESQL_18 = 180_000;
    private static final int POSTGRESQL_19 = 190_000;
    private static final Executor NETWORK_TIMEOUT_EXECUTOR = command ->
            Thread.ofVirtual().name("vev-postgresql-network-timeout").start(command);

    private final DataSource dataSource;
    private final PgModel<M, T> model;
    private final TenantAuthority<M, T> tenantAuthority;
    private final TenantAuthority.Claim<M> tenantClaim;
    private final PgSettings settings;
    private final DatabaseIdentity databaseIdentity;

    /**
     * Verifies and creates a runtime using {@link PgSettings#SAFE_DEFAULTS}.
     *
     * @param dataSource connection source for exactly one PostgreSQL database and runtime role
     * @param model validated generated model expected in that database
     * @param tenantAuthority unclaimed authority generated for the same model
     */
    public PgVev(DataSource dataSource, PgModel<M, T> model, TenantAuthority<M, T> tenantAuthority) {
        this(dataSource, model, tenantAuthority, PgSettings.SAFE_DEFAULTS);
    }

    /**
     * Verifies and creates a runtime with explicit bounded timeouts.
     *
     * @param dataSource connection source for exactly one PostgreSQL database and runtime role
     * @param model validated generated model expected in that database
     * @param tenantAuthority unclaimed authority generated for the same model
     * @param settings bounded statement, transaction, and network timeouts
     */
    public PgVev(
            DataSource dataSource,
            PgModel<M, T> model,
            TenantAuthority<M, T> tenantAuthority,
            PgSettings settings) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.model = Objects.requireNonNull(model, "model");
        this.tenantAuthority = Objects.requireNonNull(tenantAuthority, "tenantAuthority");
        if (tenantAuthority.tenantType() != model.tenantType()) {
            throw new IllegalArgumentException(
                    "Tenant authority for model " + model.identity().name() + " must govern "
                            + model.tenantType().getName());
        }
        this.settings = Objects.requireNonNull(settings, "settings");
        DatabaseIdentity verifiedDatabaseIdentity;
        TenantAuthority.Claim<M> verifiedTenantClaim;
        try (TenantAuthority.Reservation<M> reservation = tenantAuthority.reserve(model.identity())) {
            verifiedDatabaseIdentity = verifyDatabase();
            verifiedTenantClaim = reservation.claim();
        }
        this.databaseIdentity = verifiedDatabaseIdentity;
        this.tenantClaim = verifiedTenantClaim;
    }

    /**
     * Returns the immutable model verified when this runtime was created.
     *
     * @return this runtime's closed PostgreSQL model
     */
    public PgModel<M, T> model() {
        return model;
    }

    @Override
    public <R> R read(TenantScope<M, T> tenant, ReadWork<M, T, R> work) {
        Objects.requireNonNull(work, "work");
        return transact(requireTenant(tenant), true, (transaction, guard) -> work.run(transaction));
    }

    @Override
    public <R> R write(TenantScope<M, T> tenant, WriteWork<M, T, R> work) {
        Objects.requireNonNull(work, "work");
        return transact(requireTenant(tenant), false, (transaction, guard) -> work.run((WriteTx<M, T>) transaction));
    }

    private TenantScope<M, T> requireTenant(TenantScope<M, T> tenant) {
        tenantAuthority.requireScope(tenant, tenantClaim);
        if (tenant.javaType() != model.tenantType()) {
            throw new IllegalArgumentException(
                    "Tenant key for model " + model.identity().name() + " must be " + model.tenantType().getName());
        }
        return tenant;
    }

    private <R> R transact(TenantScope<M, T> tenant, boolean readOnly, TransactionWork<M, T, R> work) {
        return TransactionScopes.call(guard -> {
            Connection connection = null;
            Throwable failure = null;
            try {
                connection = acquireConnection();
                configure(connection, tenant, readOnly);
                PgEntities<M, T> entities = new PgEntities<>(connection, model, tenant, guard, settings, !readOnly);
                ReadTx<M, T> transaction = readOnly
                        ? new PgReadTransaction<>(tenant, entities, guard)
                        : new PgWriteTransaction<>(tenant, entities, guard);
                R result = work.run(transaction, guard);
                guard.checkUsable();
                verifyTransactionContext(connection, tenant, readOnly);
                guard.checkUsable();
                commit(connection, guard);
                return result;
            } catch (SQLException sqlFailure) {
                failure = databaseFailure(guard, sqlFailure);
                throw (IllegalStateException) failure;
            } catch (RuntimeException | Error runtimeFailure) {
                failure = runtimeFailure;
                throw runtimeFailure;
            } finally {
                finish(connection, failure);
            }
        });
    }

    private void configure(Connection connection, TenantScope<M, T> tenant, boolean readOnly) throws SQLException {
        try {
            configureUnchecked(connection, tenant, readOnly);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Vev rejected the PostgreSQL transaction context");
        }
    }

    private void configureUnchecked(Connection connection, TenantScope<M, T> tenant, boolean readOnly) throws SQLException {
        beginFreshTransaction(connection);
        connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        connection.setReadOnly(readOnly);
        installTrustedSearchPath(connection);
        installUtf8Transport(connection);
        String tenantValue = tenant.tenantId().toString();
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH configured AS MATERIALIZED (
                    SELECT pg_catalog.set_config('vev.tenant_id', ?, true) AS tenant,
                           pg_catalog.set_config('statement_timeout', ?, true) AS timeout,
                           pg_catalog.set_config('transaction_timeout', ?, true) AS transaction_timeout,
                           pg_catalog.set_config('lock_timeout', ?, true) AS lock_timeout,
                           pg_catalog.set_config('search_path', 'pg_catalog', true) AS search_path,
                           pg_catalog.set_config('row_security', 'on', true) AS row_security,
                           pg_catalog.set_config('synchronous_commit', 'on', true) AS synchronous_commit,
                           pg_catalog.set_config('TimeZone', 'UTC', true) AS time_zone
                )
                SELECT configured.tenant,
                       configured.timeout,
                       configured.transaction_timeout,
                       configured.lock_timeout,
                       configured.search_path,
                       configured.row_security,
                       configured.synchronous_commit,
                       configured.time_zone,
                       pg_catalog.current_setting('vev.tenant_id'),
                       pg_catalog.current_setting('statement_timeout'),
                       pg_catalog.current_setting('transaction_timeout'),
                       pg_catalog.current_setting('lock_timeout'),
                       pg_catalog.current_setting('client_encoding'),
                       pg_catalog.current_setting('server_encoding'),
                       pg_catalog.current_setting('search_path'),
                       pg_catalog.current_setting('row_security'),
                       pg_catalog.current_setting('synchronous_commit'),
                       pg_catalog.current_setting('TimeZone'),
                       pg_catalog.current_setting('transaction_read_only'),
                       pg_catalog.current_setting('transaction_isolation'),
                       pg_catalog.current_database(),
                       database_identity.oid,
                       (pg_catalog.pg_control_system()).system_identifier,
                       pg_catalog.inet_server_addr()::pg_catalog.text,
                       pg_catalog.inet_server_port(),
                       (extract(epoch FROM pg_catalog.pg_postmaster_start_time()) * 1000000)::pg_catalog.int8,
                       pg_catalog.pg_is_in_recovery(),
                       session_user,
                       current_user,
                       pg_catalog.pg_my_temp_schema() = 0,
                       (SELECT pg_catalog.count(*) = 1
                          FROM public.vev_schema_fingerprint fingerprint
                         WHERE fingerprint.model_name = ?
                           AND fingerprint.fingerprint = ?)
                  FROM configured
                  JOIN pg_catalog.pg_database database_identity
                    ON database_identity.datname = pg_catalog.current_database()
                """)) {
            statement.setString(1, tenantValue);
            statement.setString(2, settings.statementTimeout().toMillis() + "ms");
            statement.setString(3, settings.transactionTimeout().toMillis() + "ms");
            statement.setString(4, settings.statementTimeout().toMillis() + "ms");
            statement.setString(5, model.identity().name());
            statement.setString(6, model.identity().fingerprint());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()
                        || !tenantValue.equals(resultSet.getString(1))
                        || !resultSet.getString(2).equals(resultSet.getString(10))
                        || "0".equals(resultSet.getString(2))
                        || !resultSet.getString(3).equals(resultSet.getString(11))
                        || "0".equals(resultSet.getString(3))
                        || !resultSet.getString(4).equals(resultSet.getString(12))
                        || "0".equals(resultSet.getString(4))
                        || !"pg_catalog".equals(resultSet.getString(5))
                        || !"on".equals(resultSet.getString(6))
                        || !"on".equals(resultSet.getString(7))
                        || !"UTC".equals(resultSet.getString(8))
                        || !tenantValue.equals(resultSet.getString(9))
                        || !"UTF8".equals(resultSet.getString(13))
                        || !"UTF8".equals(resultSet.getString(14))
                        || !"pg_catalog".equals(resultSet.getString(15))
                        || !"on".equals(resultSet.getString(16))
                        || !"on".equals(resultSet.getString(17))
                        || !"UTC".equals(resultSet.getString(18))
                        || !(readOnly ? "on" : "off").equals(resultSet.getString(19))
                        || !"serializable".equals(resultSet.getString(20))
                        || !databaseIdentity.database().equals(resultSet.getString(21))
                        || databaseIdentity.databaseOid() != resultSet.getLong(22)
                        || databaseIdentity.systemIdentifier() != resultSet.getLong(23)
                        || !databaseIdentity.endpointAddress().equals(resultSet.getString(24))
                        || databaseIdentity.endpointPort() != resultSet.getInt(25)
                        || databaseIdentity.postmasterStartEpochMicros() != resultSet.getLong(26)
                        || resultSet.getBoolean(27)
                        || !databaseIdentity.role().equals(resultSet.getString(28))
                        || !databaseIdentity.role().equals(resultSet.getString(29))
                        || !resultSet.getBoolean(30)
                        || !resultSet.getBoolean(31)
                        || resultSet.next()) {
                    throw new IllegalStateException("Checked-out connection did not preserve Vev's transaction invariants");
                }
            }
        }
    }

    private DatabaseIdentity verifyDatabase() {
        BootstrapStage stage = BootstrapStage.CONNECTION_ACQUISITION;
        try (Connection connection = acquireConnection()) {
            Throwable verificationFailure = null;
            try {
                stage = BootstrapStage.VERIFIER_CONFIGURATION;
                configureVerifier(connection);
                stage = BootstrapStage.POSTGRESQL_VERSION;
                verifyPostgresVersion(connection);
                stage = BootstrapStage.RUNTIME_ROLE;
                DatabaseIdentity identity = verifyRuntimeRole(connection);
                stage = BootstrapStage.FINGERPRINT_RELATION;
                verifyFingerprintRelation(connection);
                stage = BootstrapStage.FINGERPRINT_PRIVILEGES;
                verifyFingerprintPrivileges(connection);
                stage = BootstrapStage.FINGERPRINT_VALUE;
                verifyFingerprintValue(connection);
                stage = BootstrapStage.TENANT_ISOLATION;
                verifyTenantIsolation(connection);
                stage = BootstrapStage.BOOTSTRAP_CONTEXT;
                verifyBootstrapContext(connection, identity);
                return identity;
            } catch (SQLException | RuntimeException | Error failure) {
                verificationFailure = failure;
                throw failure;
            } finally {
                rollbackVerifier(connection, verificationFailure);
            }
        } catch (SQLException failure) {
            throw sanitizedSqlFailure("Vev could not verify PostgreSQL during " + stage.label, failure);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Vev rejected PostgreSQL during " + stage.label);
        }
    }

    private enum BootstrapStage {
        CONNECTION_ACQUISITION("connection acquisition"),
        VERIFIER_CONFIGURATION("verifier configuration"),
        POSTGRESQL_VERSION("PostgreSQL version verification"),
        RUNTIME_ROLE("runtime role verification"),
        FINGERPRINT_RELATION("fingerprint relation verification"),
        FINGERPRINT_PRIVILEGES("fingerprint privilege verification"),
        FINGERPRINT_VALUE("fingerprint value verification"),
        TENANT_ISOLATION("tenant isolation verification"),
        BOOTSTRAP_CONTEXT("bootstrap context verification");

        private final String label;

        BootstrapStage(String label) {
            this.label = label;
        }
    }

    private Connection acquireConnection() throws SQLException {
        try {
            return dataSource.getConnection();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Vev could not acquire a PostgreSQL connection");
        }
    }

    private static void rollbackVerifier(Connection connection, Throwable verificationFailure) throws SQLException {
        try {
            connection.rollback();
        } catch (SQLException | RuntimeException | Error rollbackFailure) {
            if (verificationFailure != null) {
                verificationFailure.addSuppressed(rollbackFailure instanceof SQLException sqlFailure
                        ? sanitizedSqlFailure("PostgreSQL bootstrap rollback failed", sqlFailure)
                        : new IllegalStateException("PostgreSQL bootstrap rollback failed"));
            } else if (rollbackFailure instanceof SQLException sqlFailure) {
                throw sqlFailure;
            } else {
                throw rollbackFailure;
            }
        }
    }

    private void configureVerifier(Connection connection) throws SQLException {
        beginFreshTransaction(connection);
        connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        connection.setReadOnly(true);
        installTrustedSearchPath(connection);
        installUtf8Transport(connection);
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH configured AS MATERIALIZED (
                    SELECT pg_catalog.set_config('row_security', 'on', true) AS row_security,
                           pg_catalog.set_config('statement_timeout', ?, true) AS timeout,
                           pg_catalog.set_config('transaction_timeout', ?, true) AS transaction_timeout,
                           pg_catalog.set_config('lock_timeout', ?, true) AS lock_timeout,
                           pg_catalog.set_config('synchronous_commit', 'on', true) AS synchronous_commit,
                           pg_catalog.set_config('TimeZone', 'UTC', true) AS time_zone
                )
                SELECT configured.row_security,
                       configured.timeout,
                       configured.transaction_timeout,
                       configured.lock_timeout,
                       configured.synchronous_commit,
                       configured.time_zone,
                       pg_catalog.current_setting('search_path'),
                       pg_catalog.current_setting('row_security'),
                       pg_catalog.current_setting('statement_timeout'),
                       pg_catalog.current_setting('transaction_timeout'),
                       pg_catalog.current_setting('lock_timeout'),
                       pg_catalog.current_setting('client_encoding'),
                       pg_catalog.current_setting('server_encoding'),
                       pg_catalog.current_setting('transaction_read_only'),
                       pg_catalog.current_setting('transaction_isolation'),
                       pg_catalog.current_setting('synchronous_commit'),
                       pg_catalog.current_setting('TimeZone'),
                       pg_catalog.pg_my_temp_schema() = 0
                  FROM configured
                """)) {
            statement.setString(1, settings.statementTimeout().toMillis() + "ms");
            statement.setString(2, settings.transactionTimeout().toMillis() + "ms");
            statement.setString(3, settings.statementTimeout().toMillis() + "ms");
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()
                        || !"on".equals(resultSet.getString(1))
                        || !resultSet.getString(2).equals(resultSet.getString(9))
                        || "0".equals(resultSet.getString(2))
                        || !resultSet.getString(3).equals(resultSet.getString(10))
                        || "0".equals(resultSet.getString(3))
                        || !resultSet.getString(4).equals(resultSet.getString(11))
                        || "0".equals(resultSet.getString(4))
                        || !"on".equals(resultSet.getString(5))
                        || !"UTC".equals(resultSet.getString(6))
                        || !"pg_catalog".equals(resultSet.getString(7))
                        || !"on".equals(resultSet.getString(8))
                        || !"UTF8".equals(resultSet.getString(12))
                        || !"UTF8".equals(resultSet.getString(13))
                        || !"on".equals(resultSet.getString(14))
                        || !"serializable".equals(resultSet.getString(15))
                        || !"on".equals(resultSet.getString(16))
                        || !"UTC".equals(resultSet.getString(17))
                        || !resultSet.getBoolean(18)
                        || resultSet.next()) {
                    throw new IllegalStateException("Vev could not establish a read-only bootstrap boundary");
                }
            }
        }
    }

    private void beginFreshTransaction(Connection connection) throws SQLException {
        int networkTimeout = Math.toIntExact(settings.networkTimeout().toMillis());
        connection.setNetworkTimeout(NETWORK_TIMEOUT_EXECUTOR, networkTimeout);
        if (connection.getNetworkTimeout() != networkTimeout) {
            throw new IllegalStateException("PostgreSQL connection did not preserve Vev's network deadline");
        }
        connection.setAutoCommit(false);
        connection.rollback();
    }

    private static void installUtf8Transport(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH configured AS MATERIALIZED (
                    SELECT pg_catalog.set_config('client_encoding', 'UTF8', true) AS client_encoding
                )
                SELECT configured.client_encoding,
                       pg_catalog.current_setting('client_encoding'),
                       pg_catalog.current_setting('server_encoding')
                  FROM configured
                """);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()
                    || !"UTF8".equals(resultSet.getString(1))
                    || !"UTF8".equals(resultSet.getString(2))
                    || !"UTF8".equals(resultSet.getString(3))
                    || resultSet.next()) {
                throw new IllegalStateException("Vev requires UTF-8 on every checked-out PostgreSQL connection");
            }
        }
    }

    private static void installTrustedSearchPath(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH configured AS MATERIALIZED (
                    SELECT pg_catalog.set_config('search_path', 'pg_catalog', true) AS search_path
                )
                SELECT configured.search_path,
                       pg_catalog.current_setting('search_path'),
                       pg_catalog.pg_my_temp_schema()
                  FROM configured
                """);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()
                    || !"pg_catalog".equals(resultSet.getString(1))
                    || !"pg_catalog".equals(resultSet.getString(2))
                    || resultSet.getLong(3) != 0
                    || resultSet.wasNull()
                    || resultSet.next()) {
                throw new IllegalStateException("Vev could not install a trusted bootstrap search path");
            }
        }
    }

    private void verifyTransactionContext(
            Connection connection,
            TenantScope<M, T> tenant,
            boolean readOnly) throws SQLException {
        try {
            verifyConnectionContext(connection, tenant.tenantId().toString(), readOnly, databaseIdentity);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Vev rejected the final PostgreSQL transaction context");
        }
    }

    private void verifyBootstrapContext(Connection connection, DatabaseIdentity identity) throws SQLException {
        verifyConnectionContext(connection, null, true, identity);
    }

    private void verifyConnectionContext(
            Connection connection,
            String expectedTenant,
            boolean readOnly,
            DatabaseIdentity identity) throws SQLException {
        if (connection.getNetworkTimeout() != Math.toIntExact(settings.networkTimeout().toMillis())) {
            throw new IllegalStateException("PostgreSQL connection escaped Vev's network deadline");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pg_catalog.current_setting('vev.tenant_id', true),
                       extract(epoch FROM pg_catalog.current_setting('statement_timeout')::pg_catalog.interval) * 1000,
                       extract(epoch FROM pg_catalog.current_setting('transaction_timeout')::pg_catalog.interval) * 1000,
                       extract(epoch FROM pg_catalog.current_setting('lock_timeout')::pg_catalog.interval) * 1000,
                       pg_catalog.current_setting('client_encoding'),
                       pg_catalog.current_setting('server_encoding'),
                       pg_catalog.current_setting('search_path'),
                       pg_catalog.current_setting('row_security'),
                       pg_catalog.current_setting('transaction_read_only'),
                       pg_catalog.current_setting('transaction_isolation'),
                       pg_catalog.current_setting('synchronous_commit'),
                       pg_catalog.current_setting('TimeZone'),
                       pg_catalog.current_database(),
                       database_identity.oid,
                       (pg_catalog.pg_control_system()).system_identifier,
                       pg_catalog.inet_server_addr()::pg_catalog.text,
                       pg_catalog.inet_server_port(),
                       (extract(epoch FROM pg_catalog.pg_postmaster_start_time()) * 1000000)::pg_catalog.int8,
                       pg_catalog.pg_is_in_recovery(),
                       session_user,
                       current_user,
                       pg_catalog.pg_my_temp_schema() = 0,
                       (SELECT pg_catalog.count(*) = 1
                          FROM public.vev_schema_fingerprint fingerprint
                         WHERE fingerprint.model_name = ?
                           AND fingerprint.fingerprint = ?)
                  FROM pg_catalog.pg_database database_identity
                 WHERE database_identity.datname = pg_catalog.current_database()
                """)) {
            statement.setString(1, model.identity().name());
            statement.setString(2, model.identity().fingerprint());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()
                        || expectedTenant != null && !expectedTenant.equals(resultSet.getString(1))
                        || resultSet.getLong(2) != settings.statementTimeout().toMillis()
                        || resultSet.getLong(3) != settings.transactionTimeout().toMillis()
                        || resultSet.getLong(4) != settings.statementTimeout().toMillis()
                        || !"UTF8".equals(resultSet.getString(5))
                        || !"UTF8".equals(resultSet.getString(6))
                        || !"pg_catalog".equals(resultSet.getString(7))
                        || !"on".equals(resultSet.getString(8))
                        || !(readOnly ? "on" : "off").equals(resultSet.getString(9))
                        || !"serializable".equals(resultSet.getString(10))
                        || !"on".equals(resultSet.getString(11))
                        || !"UTC".equals(resultSet.getString(12))
                        || !identity.database().equals(resultSet.getString(13))
                        || identity.databaseOid() != resultSet.getLong(14)
                        || identity.systemIdentifier() != resultSet.getLong(15)
                        || !identity.endpointAddress().equals(resultSet.getString(16))
                        || identity.endpointPort() != resultSet.getInt(17)
                        || identity.postmasterStartEpochMicros() != resultSet.getLong(18)
                        || resultSet.getBoolean(19)
                        || !identity.role().equals(resultSet.getString(20))
                        || !identity.role().equals(resultSet.getString(21))
                        || !resultSet.getBoolean(22)
                        || !resultSet.getBoolean(23)
                        || resultSet.next()) {
                    throw new IllegalStateException("PostgreSQL connection escaped Vev's verified execution context");
                }
            }
        }
    }

    private void verifyPostgresVersion(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pg_catalog.current_setting('server_version_num')::pg_catalog.int4,
                       pg_catalog.current_setting('server_encoding'),
                       pg_catalog.current_setting('client_encoding')
                """);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()
                    || resultSet.getInt(1) < POSTGRESQL_18
                    || resultSet.getInt(1) >= POSTGRESQL_19
                    || !"UTF8".equals(resultSet.getString(2))
                    || !"UTF8".equals(resultSet.getString(3))
                    || resultSet.next()) {
                throw new IllegalStateException(
                        "Vev requires an explicitly verified PostgreSQL 18.x server with UTF-8 transport and storage");
            }
        }
    }

    private static void verifyFingerprintPrivileges(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pg_catalog.has_table_privilege(current_user, 'public.vev_schema_fingerprint', 'SELECT'),
                       pg_catalog.has_table_privilege(
                           current_user,
                           'public.vev_schema_fingerprint',
                           'INSERT,UPDATE,DELETE,TRUNCATE,TRIGGER,MAINTAIN'),
                       pg_catalog.has_any_column_privilege(current_user, 'public.vev_schema_fingerprint', 'INSERT'),
                       pg_catalog.has_any_column_privilege(current_user, 'public.vev_schema_fingerprint', 'UPDATE'),
                       pg_catalog.has_any_column_privilege(current_user, 'public.vev_schema_fingerprint', 'REFERENCES'),
                       pg_catalog.has_schema_privilege(current_user, 'public', 'USAGE'),
                       pg_catalog.has_schema_privilege(current_user, 'public', 'CREATE'),
                       pg_catalog.has_table_privilege(
                           current_user, 'public.vev_schema_fingerprint', 'SELECT WITH GRANT OPTION'),
                       pg_catalog.has_schema_privilege(current_user, 'public', 'USAGE WITH GRANT OPTION')
                """);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()
                    || !resultSet.getBoolean(1)
                    || resultSet.getBoolean(2)
                    || resultSet.getBoolean(3)
                    || resultSet.getBoolean(4)
                    || resultSet.getBoolean(5)
                    || !resultSet.getBoolean(6)
                    || resultSet.getBoolean(7)
                    || resultSet.getBoolean(8)
                    || resultSet.getBoolean(9)
                    || resultSet.next()) {
                throw new IllegalStateException("Application role must have read-only access to the Vev schema fingerprint");
            }
        }
    }

    private void verifyFingerprintValue(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT model_name, fingerprint FROM public.vev_schema_fingerprint WHERE model_name = ?")) {
            statement.setString(1, model.identity().name());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("Database has no schema fingerprint for model " + model.identity().name());
                }
                if (!model.identity().name().equals(resultSet.getString(1))) {
                    throw new IllegalStateException("Database returned a non-canonical Vev schema model identity");
                }
                String actual = resultSet.getString(2);
                if (!model.identity().fingerprint().equals(actual)) {
                    throw new IllegalStateException("Database schema fingerprint does not match generated model " + model.identity().name());
                }
                if (resultSet.next()) {
                    throw new IllegalStateException("Database contains duplicate schema fingerprints for model " + model.identity().name());
                }
            }
        }
    }

    private void verifyFingerprintRelation(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT relation.relkind,
                       relation.relpersistence,
                       relation.relispartition,
                       relation.relrowsecurity,
                       relation.relforcerowsecurity,
                       pg_catalog.pg_has_role(current_user, relation.relowner, 'MEMBER'),
                       access_method.amname,
                       EXISTS (
                           SELECT 1 FROM pg_catalog.pg_trigger mapped_trigger
                            WHERE mapped_trigger.tgrelid = relation.oid
                              AND NOT mapped_trigger.tgisinternal
                       ),
                       EXISTS (
                           SELECT 1 FROM pg_catalog.pg_rewrite rewrite
                            WHERE rewrite.ev_class = relation.oid
                       ),
                       EXISTS (
                           SELECT 1 FROM pg_catalog.pg_inherits inheritance
                            WHERE inheritance.inhparent = relation.oid
                               OR inheritance.inhrelid = relation.oid
                       ),
                       EXISTS (
                           SELECT 1 FROM pg_catalog.pg_constraint incoming_foreign_key
                            WHERE incoming_foreign_key.contype = 'f'
                              AND incoming_foreign_key.confrelid = relation.oid
                       )
                  FROM pg_catalog.pg_class relation
                  JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
                  JOIN pg_catalog.pg_am access_method ON access_method.oid = relation.relam
                 WHERE namespace.nspname = 'public'
                   AND relation.relname = 'vev_schema_fingerprint'
                """);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()
                    || !"r".equals(resultSet.getString(1))
                    || !"p".equals(resultSet.getString(2))
                    || resultSet.getBoolean(3)
                    || resultSet.getBoolean(4)
                    || resultSet.getBoolean(5)
                    || resultSet.getBoolean(6)
                    || !"heap".equals(resultSet.getString(7))
                    || resultSet.getBoolean(8)
                    || resultSet.getBoolean(9)
                    || resultSet.getBoolean(10)
                    || resultSet.getBoolean(11)
                    || resultSet.next()) {
                throw new IllegalStateException("Vev schema fingerprint must be one owner-separated permanent built-in heap table");
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pg_catalog.count(*),
                       COALESCE(pg_catalog.bool_and(
                           (attribute.attnum = 1
                            AND attribute.attname = 'model_name'
                            AND attribute.atttypid = 'pg_catalog.varchar'::pg_catalog.regtype
                            AND attribute.atttypmod = 132)
                           OR
                           (attribute.attnum = 2
                            AND attribute.attname = 'fingerprint'
                            AND attribute.atttypid = 'pg_catalog.varchar'::pg_catalog.regtype
                            AND attribute.atttypmod = 75)
                       ), false),
                       COALESCE(pg_catalog.bool_and(
                           attribute.attnotnull
                           AND NOT attribute.atthasdef
                           AND NOT attribute.atthasmissing
                           AND attribute.attidentity = ''
                           AND attribute.attgenerated = ''
                           AND type_namespace.nspname = 'pg_catalog'
                           AND mapped_type.typtype = 'b'
                           AND COALESCE(mapped_collation.collisdeterministic, true)
                           AND (mapped_collation.oid IS NULL
                                OR mapped_collation.collversion IS NOT DISTINCT FROM
                                   pg_catalog.pg_collation_actual_version(mapped_collation.oid))
                       ), false)
                  FROM pg_catalog.pg_attribute attribute
                  JOIN pg_catalog.pg_class relation ON relation.oid = attribute.attrelid
                  JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
                  JOIN pg_catalog.pg_type mapped_type ON mapped_type.oid = attribute.atttypid
                  JOIN pg_catalog.pg_namespace type_namespace ON type_namespace.oid = mapped_type.typnamespace
                  LEFT JOIN pg_catalog.pg_collation mapped_collation
                    ON mapped_collation.oid = attribute.attcollation
                 WHERE namespace.nspname = 'public'
                   AND relation.relname = 'vev_schema_fingerprint'
                   AND attribute.attnum > 0
                   AND NOT attribute.attisdropped
                """);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()
                    || resultSet.getInt(1) != 2
                    || !resultSet.getBoolean(2)
                    || !resultSet.getBoolean(3)
                    || resultSet.next()) {
                throw new IllegalStateException("Vev schema fingerprint table has an unsafe column shape");
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pg_catalog.count(*),
                       COALESCE(pg_catalog.bool_and(
                           mapped_index.indisprimary
                           AND mapped_index.indisunique
                           AND mapped_index.indisvalid
                           AND mapped_index.indisready
                           AND mapped_index.indislive
                           AND mapped_index.indexprs IS NULL
                           AND mapped_index.indpred IS NULL
                           AND mapped_index.indnkeyatts = 1
                           AND mapped_index.indnatts = 1
                           AND access_method.amname = 'btree'
                           AND key_attribute.attname = 'model_name'
                           AND operator_namespace.nspname = 'pg_catalog'
                           AND operator_class.opcdefault
                           AND (mapped_index.indcollation::pg_catalog.oid[])[0] = key_attribute.attcollation
                       ), false)
                  FROM pg_catalog.pg_index mapped_index
                  JOIN pg_catalog.pg_class relation ON relation.oid = mapped_index.indrelid
                  JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
                  JOIN pg_catalog.pg_class index_relation ON index_relation.oid = mapped_index.indexrelid
                  JOIN pg_catalog.pg_am access_method ON access_method.oid = index_relation.relam
                  JOIN pg_catalog.pg_attribute key_attribute
                    ON key_attribute.attrelid = relation.oid
                   AND key_attribute.attnum = (mapped_index.indkey::pg_catalog.int2[])[0]
                  JOIN pg_catalog.pg_opclass operator_class
                    ON operator_class.oid = (mapped_index.indclass::pg_catalog.oid[])[0]
                  JOIN pg_catalog.pg_namespace operator_namespace
                    ON operator_namespace.oid = operator_class.opcnamespace
                 WHERE namespace.nspname = 'public'
                   AND relation.relname = 'vev_schema_fingerprint'
                """);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()
                    || resultSet.getInt(1) != 1
                    || !resultSet.getBoolean(2)
                    || resultSet.next()) {
                throw new IllegalStateException("Vev schema fingerprint table requires one exact built-in primary key");
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pg_catalog.count(*),
                       pg_catalog.count(*) FILTER (WHERE constraint_definition.contype = 'p'),
                       pg_catalog.count(*) FILTER (WHERE constraint_definition.contype = 'n'),
                       COALESCE(pg_catalog.bool_and(
                           constraint_definition.contype IN ('p', 'n')
                           AND NOT constraint_definition.condeferrable
                           AND constraint_definition.convalidated
                           AND constraint_definition.conenforced
                       ), false)
                  FROM pg_catalog.pg_constraint constraint_definition
                  JOIN pg_catalog.pg_class relation ON relation.oid = constraint_definition.conrelid
                  JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
                 WHERE namespace.nspname = 'public'
                   AND relation.relname = 'vev_schema_fingerprint'
                """);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()
                    || resultSet.getInt(1) != 3
                    || resultSet.getInt(2) != 1
                    || resultSet.getInt(3) != 2
                    || !resultSet.getBoolean(4)
                    || resultSet.next()) {
                throw new IllegalStateException("Vev schema fingerprint table has unexpected executable constraints");
            }
        }
    }

    private DatabaseIdentity verifyRuntimeRole(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pg_catalog.current_database(), database_owner.oid,
                       (pg_catalog.pg_control_system()).system_identifier,
                       pg_catalog.inet_server_addr()::pg_catalog.text,
                       pg_catalog.inet_server_port(),
                       (extract(epoch FROM pg_catalog.pg_postmaster_start_time()) * 1000000)::pg_catalog.int8,
                       pg_catalog.pg_is_in_recovery(),
                       session_user, current_user, runtime_role.rolsuper,
                       runtime_role.rolbypassrls,
                       runtime_role.rolreplication,
                       runtime_role.rolcreaterole,
                       runtime_role.rolcreatedb,
                       runtime_role.rolcanlogin,
                       EXISTS (
                           SELECT 1
                             FROM pg_catalog.pg_roles inherited_role
                            WHERE inherited_role.oid <> runtime_role.oid
                              AND pg_catalog.pg_has_role(current_user, inherited_role.oid, 'MEMBER')
                       ),
                       pg_catalog.pg_has_role(current_user, database_owner.datdba, 'MEMBER'),
                       pg_catalog.has_database_privilege(current_user, database_owner.oid, 'CREATE'),
                       pg_catalog.has_database_privilege(current_user, database_owner.oid, 'TEMP'),
                       pg_catalog.has_schema_privilege(current_user, 'pg_catalog', 'CREATE'),
                       EXISTS (
                           SELECT 1
                             FROM (
                                 SELECT namespace.nspowner AS owner
                                   FROM pg_catalog.pg_namespace namespace
                                  WHERE namespace.nspname = 'pg_catalog'
                                 UNION ALL
                                 SELECT relation.relowner
                                   FROM pg_catalog.pg_class relation
                                   JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
                                  WHERE namespace.nspname = 'pg_catalog'
                                 UNION ALL
                                 SELECT routine.proowner
                                   FROM pg_catalog.pg_proc routine
                                   JOIN pg_catalog.pg_namespace namespace ON namespace.oid = routine.pronamespace
                                  WHERE namespace.nspname = 'pg_catalog'
                                 UNION ALL
                                 SELECT mapped_type.typowner
                                   FROM pg_catalog.pg_type mapped_type
                                   JOIN pg_catalog.pg_namespace namespace ON namespace.oid = mapped_type.typnamespace
                                  WHERE namespace.nspname = 'pg_catalog'
                                 UNION ALL
                                 SELECT mapped_operator.oprowner
                                   FROM pg_catalog.pg_operator mapped_operator
                                   JOIN pg_catalog.pg_namespace namespace
                                     ON namespace.oid = mapped_operator.oprnamespace
                                  WHERE namespace.nspname = 'pg_catalog'
                                 UNION ALL
                                 SELECT mapped_collation.collowner
                                   FROM pg_catalog.pg_collation mapped_collation
                                   JOIN pg_catalog.pg_namespace namespace
                                     ON namespace.oid = mapped_collation.collnamespace
                                  WHERE namespace.nspname = 'pg_catalog'
                           ) pg_catalog_owner
                            WHERE pg_catalog_owner.owner = runtime_role.oid
                       ),
                       pg_catalog.has_database_privilege(
                           current_user, database_owner.oid, 'CONNECT WITH GRANT OPTION')
                  FROM pg_catalog.pg_roles runtime_role
                  JOIN pg_catalog.pg_database database_owner
                    ON database_owner.datname = pg_catalog.current_database()
                 WHERE runtime_role.rolname = current_user
                """);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                throw new IllegalStateException("Vev could not resolve the PostgreSQL application role");
            }
            String database = resultSet.getString(1);
            long databaseOid = resultSet.getLong(2);
            long systemIdentifier = resultSet.getLong(3);
            String endpointAddress = resultSet.getString(4);
            int endpointPort = resultSet.getInt(5);
            long postmasterStartEpochMicros = resultSet.getLong(6);
            String sessionUser = resultSet.getString(8);
            String currentUser = resultSet.getString(9);
            if (!sessionUser.equals(currentUser)
                    || endpointAddress == null
                    || endpointAddress.isBlank()
                    || endpointPort < 1
                    || endpointPort > 65_535
                    || postmasterStartEpochMicros <= 0
                    || resultSet.getBoolean(7)
                    || resultSet.getBoolean(10)
                    || resultSet.getBoolean(11)
                    || resultSet.getBoolean(12)
                    || resultSet.getBoolean(13)
                    || resultSet.getBoolean(14)
                    || !resultSet.getBoolean(15)
                    || resultSet.getBoolean(16)
                    || resultSet.getBoolean(17)
                    || resultSet.getBoolean(18)
                    || resultSet.getBoolean(19)
                    || resultSet.getBoolean(20)
                    || resultSet.getBoolean(21)
                    || resultSet.getBoolean(22)
                    || resultSet.next()) {
                throw new IllegalStateException(
                        "Vev requires one primary TCP PostgreSQL endpoint and a login-only application role with no elevated attributes, memberships, ownership, CREATE, or TEMP privilege");
            }
            return new DatabaseIdentity(
                    database,
                    databaseOid,
                    systemIdentifier,
                    endpointAddress,
                    endpointPort,
                    postmasterStartEpochMicros,
                    currentUser);
        }
    }

    private void verifyTenantIsolation(Connection connection) throws SQLException {
        String sql = """
                SELECT c.relkind,
                       c.relpersistence,
                       c.relispartition,
                       c.relrowsecurity,
                       c.relforcerowsecurity,
                       pg_catalog.pg_has_role(
                           current_user,
                           pg_catalog.pg_get_userbyid(c.relowner),
                           'MEMBER'),
                       pg_catalog.has_schema_privilege(current_user, n.oid, 'USAGE'),
                       pg_catalog.has_schema_privilege(current_user, n.oid, 'CREATE'),
                       pg_catalog.has_table_privilege(current_user, c.oid, 'SELECT'),
                       pg_catalog.has_table_privilege(current_user, c.oid, 'INSERT'),
                       pg_catalog.has_table_privilege(current_user, c.oid, 'UPDATE'),
                       pg_catalog.has_table_privilege(current_user, c.oid, 'DELETE'),
                       pg_catalog.has_table_privilege(current_user, c.oid, 'TRUNCATE'),
                       pg_catalog.has_table_privilege(current_user, c.oid, 'REFERENCES'),
                       pg_catalog.has_table_privilege(current_user, c.oid, 'TRIGGER'),
                       EXISTS (
                           SELECT 1
                             FROM pg_catalog.pg_trigger trigger
                            WHERE trigger.tgrelid = c.oid
                              AND NOT trigger.tgisinternal
                              AND trigger.tgenabled <> 'D'
                       ),
                       pg_catalog.has_any_column_privilege(current_user, c.oid, 'INSERT'),
                       pg_catalog.has_any_column_privilege(current_user, c.oid, 'UPDATE'),
                       pg_catalog.has_any_column_privilege(current_user, c.oid, 'REFERENCES'),
                       EXISTS (
                           SELECT 1
                             FROM pg_catalog.pg_rewrite rewrite
                            WHERE rewrite.ev_class = c.oid
                       ),
                       EXISTS (
                           SELECT 1
                             FROM pg_catalog.pg_inherits inheritance
                            WHERE inheritance.inhparent = c.oid
                               OR inheritance.inhrelid = c.oid
                       ),
                       pg_catalog.has_table_privilege(current_user, c.oid, 'MAINTAIN'),
                       table_access_method.amname,
                       pg_catalog.has_table_privilege(current_user, c.oid, 'SELECT WITH GRANT OPTION'),
                       pg_catalog.has_table_privilege(current_user, c.oid, 'DELETE WITH GRANT OPTION'),
                       pg_catalog.has_any_column_privilege(current_user, c.oid, 'INSERT WITH GRANT OPTION'),
                       pg_catalog.has_any_column_privilege(current_user, c.oid, 'UPDATE WITH GRANT OPTION'),
                       pg_catalog.has_schema_privilege(current_user, n.oid, 'USAGE WITH GRANT OPTION')
                  FROM pg_catalog.pg_class c
                  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                  JOIN pg_catalog.pg_am table_access_method ON table_access_method.oid = c.relam
                 WHERE n.nspname = ?
                   AND c.relname = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (PgPlan<M, ?, ?, T> plan : model.frozenPlans()) {
                statement.clearParameters();
                statement.setString(1, plan.schemaName());
                statement.setString(2, plan.tableName());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new IllegalStateException("Mapped PostgreSQL table does not exist: "
                                + plan.schemaName() + '.' + plan.tableName());
                    }
                    if (!"r".equals(resultSet.getString(1))
                            || !"p".equals(resultSet.getString(2))
                            || resultSet.getBoolean(3)
                            || !resultSet.getBoolean(4)
                            || !resultSet.getBoolean(5)
                            || resultSet.getBoolean(6)
                            || !resultSet.getBoolean(7)
                            || resultSet.getBoolean(8)
                            || !resultSet.getBoolean(9)
                            || resultSet.getBoolean(10)
                            || resultSet.getBoolean(11)
                            || resultSet.getBoolean(13)
                            || resultSet.getBoolean(14)
                            || resultSet.getBoolean(15)
                            || resultSet.getBoolean(16)
                            || !resultSet.getBoolean(17)
                            || resultSet.getBoolean(19)
                            || resultSet.getBoolean(20)
                            || resultSet.getBoolean(21)
                            || resultSet.getBoolean(22)
                            || !"heap".equals(resultSet.getString(23))
                            || resultSet.getBoolean(24)
                            || resultSet.getBoolean(25)
                            || resultSet.getBoolean(26)
                            || resultSet.getBoolean(27)
                            || resultSet.getBoolean(28)) {
                        throw new IllegalStateException("Mapped table must use a least-privilege role and forced row security: "
                                + plan.schemaName() + '.' + plan.tableName());
                    }
                    boolean versioned = plan instanceof PgVersionPlan<?, ?, ?, ?, ?>;
                    if (resultSet.getBoolean(12) != versioned
                            || resultSet.getBoolean(18) != versioned) {
                        throw new IllegalStateException("Mapped table write privileges do not match its generated mutation profile: "
                                + plan.schemaName() + '.' + plan.tableName());
                    }
                    if (resultSet.next()) {
                        throw new IllegalStateException("Mapped PostgreSQL table identity is ambiguous: "
                                + plan.schemaName() + '.' + plan.tableName());
                    }
                }
                verifyColumns(connection, plan);
                verifyColumnPrivileges(connection, plan);
                verifyPrimaryKey(connection, plan);
                verifyStructuralConstraints(connection, plan);
                verifyPolicy(connection, plan);
            }
        }
    }

    private void verifyColumnPrivileges(Connection connection, PgPlan<M, ?, ?, T> plan) throws SQLException {
        String sql = """
                SELECT attribute.attname,
                       pg_catalog.has_column_privilege(current_user, relation.oid, attribute.attname, 'INSERT'),
                       pg_catalog.has_column_privilege(current_user, relation.oid, attribute.attname, 'UPDATE'),
                       pg_catalog.has_column_privilege(current_user, relation.oid, attribute.attname, 'REFERENCES'),
                       pg_catalog.has_column_privilege(
                           current_user, relation.oid, attribute.attname, 'INSERT WITH GRANT OPTION'),
                       pg_catalog.has_column_privilege(
                           current_user, relation.oid, attribute.attname, 'UPDATE WITH GRANT OPTION')
                  FROM pg_catalog.pg_attribute attribute
                  JOIN pg_catalog.pg_class relation ON relation.oid = attribute.attrelid
                  JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
                 WHERE namespace.nspname = ?
                   AND relation.relname = ?
                   AND attribute.attname = ?
                   AND attribute.attnum > 0
                   AND NOT attribute.attisdropped
                """;
        List<PgColumn> columns = plan.columns();
        boolean versioned = plan instanceof PgVersionPlan<?, ?, ?, ?, ?>;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (PgColumn column : columns) {
                statement.clearParameters();
                statement.setString(1, plan.schemaName());
                statement.setString(2, plan.tableName());
                statement.setString(3, column.name());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new IllegalStateException("Mapped column privileges are incomplete for "
                                + plan.schemaName() + '.' + plan.tableName() + '.' + column.name());
                    }
                    boolean updateAllowed = versioned
                            && (column.role() == PgColumn.Role.VALUE || column.role() == PgColumn.Role.VERSION);
                    if (!column.name().equals(resultSet.getString(1))
                            || !resultSet.getBoolean(2)
                            || resultSet.getBoolean(3) != updateAllowed
                            || resultSet.getBoolean(4)
                            || resultSet.getBoolean(5)
                            || resultSet.getBoolean(6)) {
                        throw new IllegalStateException("Mapped column privileges do not match generated SQL for "
                                + plan.schemaName() + '.' + plan.tableName() + '.' + column.name());
                    }
                    if (resultSet.next()) {
                        throw new IllegalStateException("Mapped column privilege identity is ambiguous for "
                                + plan.schemaName() + '.' + plan.tableName() + '.' + column.name());
                    }
                }
            }
        }
    }

    private void verifyColumns(Connection connection, PgPlan<M, ?, ?, T> plan) throws SQLException {
        List<PgColumn> columns = plan.columns();
        String countSql = """
                SELECT pg_catalog.count(*)
                  FROM pg_catalog.pg_attribute attribute
                  JOIN pg_catalog.pg_class relation ON relation.oid = attribute.attrelid
                  JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
                 WHERE namespace.nspname = ?
                   AND relation.relname = ?
                   AND attribute.attnum > 0
                   AND NOT attribute.attisdropped
                """;
        try (PreparedStatement statement = connection.prepareStatement(countSql)) {
            statement.setString(1, plan.schemaName());
            statement.setString(2, plan.tableName());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getInt(1) != columns.size() || resultSet.next()) {
                    throw new IllegalStateException("Mapped column set does not exactly match generated model: "
                            + plan.schemaName() + '.' + plan.tableName());
                }
            }
        }

        String columnSql = """
                SELECT attribute.attnotnull,
                       attribute.atttypid = pg_catalog.to_regtype(?),
                       attribute.attidentity,
                       attribute.attgenerated,
                       attribute.atthasdef,
                       NOT attribute.atthasmissing,
                       attribute.atttypmod = ?,
                       type_namespace.nspname = 'pg_catalog',
                       type.typtype = 'b',
                       COALESCE(mapped_collation.collisdeterministic, true),
                       mapped_collation.oid IS NULL
                           OR mapped_collation.collversion IS NOT DISTINCT FROM
                              pg_catalog.pg_collation_actual_version(mapped_collation.oid)
                  FROM pg_catalog.pg_attribute attribute
                  JOIN pg_catalog.pg_class relation ON relation.oid = attribute.attrelid
                  JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
                  JOIN pg_catalog.pg_type type ON type.oid = attribute.atttypid
                  JOIN pg_catalog.pg_namespace type_namespace ON type_namespace.oid = type.typnamespace
                  LEFT JOIN pg_catalog.pg_collation mapped_collation
                    ON mapped_collation.oid = attribute.attcollation
                 WHERE namespace.nspname = ?
                   AND relation.relname = ?
                   AND attribute.attname = ?
                   AND attribute.attnum > 0
                   AND NOT attribute.attisdropped
                """;
        try (PreparedStatement statement = connection.prepareStatement(columnSql)) {
            for (PgColumn column : columns) {
                statement.clearParameters();
                statement.setString(1, column.codec().databaseType());
                statement.setInt(2, column.expectedTypeModifier());
                statement.setString(3, plan.schemaName());
                statement.setString(4, plan.tableName());
                statement.setString(5, column.name());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new IllegalStateException("Mapped PostgreSQL column does not exist: "
                                + plan.schemaName() + '.' + plan.tableName() + '.' + column.name());
                    }
                    String identity = resultSet.getString(3);
                    String generated = resultSet.getString(4);
                    if (resultSet.getBoolean(1) != !column.nullable()
                            || !resultSet.getBoolean(2)
                            || !identity.isEmpty()
                            || !generated.isEmpty()
                            || resultSet.getBoolean(5)
                            || !resultSet.getBoolean(6)
                            || !resultSet.getBoolean(7)
                            || !resultSet.getBoolean(8)
                            || !resultSet.getBoolean(9)
                            || !resultSet.getBoolean(10)
                            || !resultSet.getBoolean(11)
                            || resultSet.next()) {
                        throw new IllegalStateException("Mapped PostgreSQL column shape does not match generated model: "
                                + plan.schemaName() + '.' + plan.tableName() + '.' + column.name());
                    }
                }
            }
        }
    }

    private void verifyPrimaryKey(Connection connection, PgPlan<M, ?, ?, T> plan) throws SQLException {
        String indexShapeSql = """
                SELECT pg_catalog.count(*),
                       COALESCE(pg_catalog.bool_and(
                           access_method.amname = 'btree'
                           AND mapped_index.indisunique
                           AND mapped_index.indisvalid
                           AND mapped_index.indisready
                           AND mapped_index.indislive
                           AND mapped_index.indexprs IS NULL
                           AND mapped_index.indpred IS NULL
                           AND mapped_index.indnkeyatts = 2
                           AND mapped_index.indnatts = 2
                           AND primary_constraint.oid IS NOT NULL
                           AND NOT primary_constraint.condeferrable
                           AND primary_constraint.convalidated
                           AND primary_constraint.conenforced
                       ), false)
                  FROM pg_catalog.pg_index mapped_index
                  JOIN pg_catalog.pg_class relation ON relation.oid = mapped_index.indrelid
                  JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
                  JOIN pg_catalog.pg_class index_relation ON index_relation.oid = mapped_index.indexrelid
                  JOIN pg_catalog.pg_am access_method ON access_method.oid = index_relation.relam
                  LEFT JOIN pg_catalog.pg_constraint primary_constraint
                    ON primary_constraint.conindid = mapped_index.indexrelid
                   AND primary_constraint.contype = 'p'
                 WHERE namespace.nspname = ?
                   AND relation.relname = ?
                   AND mapped_index.indisprimary
                """;
        try (PreparedStatement statement = connection.prepareStatement(indexShapeSql)) {
            statement.setString(1, plan.schemaName());
            statement.setString(2, plan.tableName());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()
                        || resultSet.getInt(1) != 1
                        || !resultSet.getBoolean(2)
                        || resultSet.next()) {
                    throw new IllegalStateException("Mapped table requires one immediate built-in B-tree primary key: "
                            + plan.schemaName() + '.' + plan.tableName());
                }
            }
        }

        String sql = """
                SELECT attribute.attname
                  FROM pg_catalog.pg_index index
                  JOIN pg_catalog.pg_class relation ON relation.oid = index.indrelid
                  JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
                  CROSS JOIN LATERAL pg_catalog.unnest(index.indkey::pg_catalog.int2[])
                                      WITH ORDINALITY AS key(attnum, position)
                  JOIN pg_catalog.pg_attribute attribute
                    ON attribute.attrelid = relation.oid AND attribute.attnum = key.attnum
                 WHERE namespace.nspname = ?
                   AND relation.relname = ?
                   AND index.indisprimary
                 ORDER BY key.position
                """;
        List<String> actual = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, plan.schemaName());
            statement.setString(2, plan.tableName());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    actual.add(resultSet.getString(1));
                }
            }
        }
        String idColumn = plan.columns().stream()
                .filter(column -> column.role() == PgColumn.Role.ID)
                .map(PgColumn::name)
                .findFirst()
                .orElseThrow();
        if (!actual.equals(List.of(plan.tenantColumn(), idColumn))) {
            throw new IllegalStateException("Mapped table primary key must be exactly (tenant, id): "
                    + plan.schemaName() + '.' + plan.tableName());
        }

        String operatorClassSql = """
                SELECT pg_catalog.count(*)
                  FROM pg_catalog.pg_index mapped_index
                  JOIN pg_catalog.pg_class relation ON relation.oid = mapped_index.indrelid
                  JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
                  CROSS JOIN LATERAL pg_catalog.generate_subscripts(
                      mapped_index.indkey::pg_catalog.int2[], 1) AS key_position(position)
                  JOIN pg_catalog.pg_attribute attribute
                    ON attribute.attrelid = relation.oid
                   AND attribute.attnum = (mapped_index.indkey::pg_catalog.int2[])[key_position.position]
                  JOIN pg_catalog.pg_opclass operator_class
                    ON operator_class.oid = (mapped_index.indclass::pg_catalog.oid[])[key_position.position]
                  JOIN pg_catalog.pg_namespace operator_namespace
                    ON operator_namespace.oid = operator_class.opcnamespace
                 WHERE namespace.nspname = ?
                   AND relation.relname = ?
                   AND mapped_index.indisprimary
                   AND (operator_namespace.nspname <> 'pg_catalog'
                     OR NOT operator_class.opcdefault
                     OR (mapped_index.indcollation::pg_catalog.oid[])[key_position.position] <> attribute.attcollation)
                """;
        try (PreparedStatement statement = connection.prepareStatement(operatorClassSql)) {
            statement.setString(1, plan.schemaName());
            statement.setString(2, plan.tableName());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getInt(1) != 0 || resultSet.next()) {
                    throw new IllegalStateException(
                            "Mapped primary key must use default pg_catalog operator classes and column collations: "
                                    + plan.schemaName() + '.' + plan.tableName());
                }
            }
        }
    }

    private void verifyStructuralConstraints(Connection connection, PgPlan<M, ?, ?, T> plan) throws SQLException {
        String secondaryIndexSql = """
                SELECT pg_catalog.count(*)
                  FROM pg_catalog.pg_index mapped_index
                  JOIN pg_catalog.pg_class relation ON relation.oid = mapped_index.indrelid
                  JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
                 WHERE namespace.nspname = ?
                   AND relation.relname = ?
                   AND NOT mapped_index.indisprimary
                """;
        try (PreparedStatement statement = connection.prepareStatement(secondaryIndexSql)) {
            statement.setString(1, plan.schemaName());
            statement.setString(2, plan.tableName());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getInt(1) != 0 || resultSet.next()) {
                    throw new IllegalStateException(
                            "Secondary indexes are outside Vev's closed schema profile: "
                                    + plan.schemaName() + '.' + plan.tableName());
                }
            }
        }

        String foreignKeySql = """
                SELECT pg_catalog.count(*)
                  FROM pg_catalog.pg_constraint constraint_definition
                  JOIN pg_catalog.pg_class source_relation
                    ON source_relation.oid = constraint_definition.conrelid
                  JOIN pg_catalog.pg_namespace source_namespace
                    ON source_namespace.oid = source_relation.relnamespace
                  JOIN pg_catalog.pg_class target_relation
                    ON target_relation.oid = constraint_definition.confrelid
                  JOIN pg_catalog.pg_namespace target_namespace
                    ON target_namespace.oid = target_relation.relnamespace
                 WHERE constraint_definition.contype = 'f'
                   AND ((source_namespace.nspname = ? AND source_relation.relname = ?)
                     OR (target_namespace.nspname = ? AND target_relation.relname = ?))
                """;
        try (PreparedStatement statement = connection.prepareStatement(foreignKeySql)) {
            statement.setString(1, plan.schemaName());
            statement.setString(2, plan.tableName());
            statement.setString(3, plan.schemaName());
            statement.setString(4, plan.tableName());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getInt(1) != 0 || resultSet.next()) {
                    throw new IllegalStateException(
                            "Foreign keys touching mapped tables are outside Vev's closed schema profile: "
                                    + plan.schemaName() + '.' + plan.tableName());
                }
            }
        }

        String executableConstraintSql = """
                SELECT pg_catalog.count(*) FILTER (
                           WHERE constraint_definition.contype NOT IN ('p', 'n')),
                       pg_catalog.count(*) FILTER (
                           WHERE constraint_definition.contype = 'n'),
                       pg_catalog.count(*) FILTER (
                           WHERE constraint_definition.contype = 'p'),
                       COALESCE(pg_catalog.bool_and(
                           NOT constraint_definition.condeferrable
                           AND constraint_definition.convalidated
                           AND constraint_definition.conenforced
                       ), false)
                  FROM pg_catalog.pg_constraint constraint_definition
                  JOIN pg_catalog.pg_class relation ON relation.oid = constraint_definition.conrelid
                  JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
                 WHERE namespace.nspname = ?
                   AND relation.relname = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(executableConstraintSql)) {
            statement.setString(1, plan.schemaName());
            statement.setString(2, plan.tableName());
            try (ResultSet resultSet = statement.executeQuery()) {
                long requiredNotNullConstraints = plan.columns().stream().filter(column -> !column.nullable()).count();
                if (!resultSet.next()
                        || resultSet.getInt(1) != 0
                        || resultSet.getLong(2) != requiredNotNullConstraints
                        || resultSet.getInt(3) != 1
                        || !resultSet.getBoolean(4)
                        || resultSet.next()) {
                    throw new IllegalStateException(
                            "Executable or additional constraints are outside Vev's closed schema profile: "
                                    + plan.schemaName() + '.' + plan.tableName());
                }
            }
        }
    }

    private void verifyPolicy(Connection connection, PgPlan<M, ?, ?, T> plan) throws SQLException {
        String sql = """
                SELECT pg_catalog.count(*),
                       COALESCE(pg_catalog.bool_and(
                           policy.polpermissive
                           AND policy.polcmd = '*'
                           AND policy.polroles = ARRAY[runtime_role.oid]::pg_catalog.oid[]
                           AND pg_catalog.pg_get_expr(policy.polqual, policy.polrelid) = ?
                           AND pg_catalog.pg_get_expr(policy.polwithcheck, policy.polrelid) = ?
                       ), false)
                  FROM pg_catalog.pg_policy policy
                  JOIN pg_catalog.pg_class relation ON relation.oid = policy.polrelid
                  JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
                  JOIN pg_catalog.pg_roles runtime_role ON runtime_role.rolname = current_user
                 WHERE namespace.nspname = ?
                   AND relation.relname = ?
                """;
        String expectedExpression = expectedTenantPolicy(connection, plan);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, expectedExpression);
            statement.setString(2, expectedExpression);
            statement.setString(3, plan.schemaName());
            statement.setString(4, plan.tableName());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()
                        || resultSet.getInt(1) != 1
                        || !resultSet.getBoolean(2)
                        || resultSet.next()) {
                    throw new IllegalStateException("Mapped table must have exactly one effective Vev tenant policy: "
                            + plan.schemaName() + '.' + plan.tableName());
                }
            }
        }
    }

    private String expectedTenantPolicy(Connection connection, PgPlan<M, ?, ?, T> plan) throws SQLException {
        String tenantIdentifier;
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_catalog.quote_ident(?)")) {
            statement.setString(1, plan.tenantColumn());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || (tenantIdentifier = resultSet.getString(1)) == null || resultSet.next()) {
                    throw new IllegalStateException("PostgreSQL could not canonicalize the tenant policy identifier");
                }
            }
        }
        String setting = "current_setting('vev.tenant_id'::text, true)";
        String databaseType = plan.tenantCodec().databaseType();
        return databaseType.equals("character varying")
                ? "((" + tenantIdentifier + ")::text = " + setting + ')'
                : '(' + tenantIdentifier + " = (" + setting + ")::" + databaseType + ')';
    }

    static IllegalStateException databaseFailure(TransactionGuard guard, SQLException failure) {
        return databaseFailure(guard, failure, "PostgreSQL operation failed");
    }

    private static IllegalStateException databaseFailure(
            TransactionGuard guard,
            SQLException failure,
            String message) {
        IllegalStateException sanitized = sanitizedSqlFailure(message, failure);
        try {
            guard.poison(sanitized);
        } catch (IllegalStateException alreadyPoisoned) {
            sanitized.addSuppressed(new IllegalStateException("Vev transaction capability was already poisoned"));
        }
        return sanitized;
    }

    private static IllegalStateException sanitizedSqlFailure(String message, SQLException failure) {
        String sqlState = failure.getSQLState();
        String suffix = sqlState != null && sqlState.matches("[0-9A-Z]{5}")
                ? " [SQLSTATE " + sqlState + ']'
                : "";
        return new IllegalStateException(message + suffix);
    }

    private static void commit(Connection connection, TransactionGuard guard) {
        try {
            connection.commit();
        } catch (SQLException failure) {
            throw databaseFailure(
                    guard,
                    failure,
                    "PostgreSQL commit outcome is indeterminate; the operation must not be retried automatically");
        } catch (RuntimeException failure) {
            IllegalStateException sanitized = new IllegalStateException(
                    "PostgreSQL commit outcome is indeterminate; the operation must not be retried automatically");
            guard.poison(sanitized);
            throw sanitized;
        }
    }

    private static void finish(Connection connection, Throwable failure) {
        if (connection == null) {
            return;
        }
        if (failure != null) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(sanitizedSqlFailure("PostgreSQL rollback failed", rollbackFailure));
            } catch (RuntimeException | Error rollbackFailure) {
                failure.addSuppressed(new IllegalStateException("PostgreSQL rollback failed"));
            }
        }
        try {
            connection.close();
        } catch (SQLException closeFailure) {
            IllegalStateException sanitized = sanitizedSqlFailure("PostgreSQL connection close failed", closeFailure);
            if (failure != null) {
                failure.addSuppressed(sanitized);
            } else {
                PgConnectionCloseFailureEvent.emit(closeFailure);
            }
        } catch (RuntimeException | Error closeFailure) {
            if (failure != null) {
                failure.addSuppressed(new IllegalStateException("PostgreSQL connection close failed"));
            } else {
                PgConnectionCloseFailureEvent.emit(closeFailure);
            }
        }
    }

    @FunctionalInterface
    private interface TransactionWork<M, T, R> {
        R run(ReadTx<M, T> transaction, TransactionGuard guard);
    }

    private record DatabaseIdentity(
            String database,
            long databaseOid,
            long systemIdentifier,
            String endpointAddress,
            int endpointPort,
            long postmasterStartEpochMicros,
            String role) {
        private DatabaseIdentity {
            Objects.requireNonNull(database, "database");
            Objects.requireNonNull(endpointAddress, "endpointAddress");
            Objects.requireNonNull(role, "role");
        }
    }
}
