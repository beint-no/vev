package no.beint.vev.it;

import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

final class IntegrationDatabase {
    static final String APPLICATION_USER = "vev_it_app";
    static final String APPLICATION_PASSWORD = "vev_it_password";
    private static final String DATABASE = "vev_it";
    private static final String OWNER_ROLE = "vev_it_owner";
    private static final String FIXTURE_MARKER = "vev-owned-fixture:vev_it:v1";
    private static final String POSTGRESQL_JDBC_PREFIX = "jdbc:postgresql://";
    private static final String EXPECTED_ACCOUNT_EMAIL_INDEX =
            "CREATE INDEX account_email_vev_idx ON vev_it.account USING btree (tenant_id, email, id)";

    private final String adminUrl;
    private final String adminUser;
    private final String adminPassword;
    private final String databaseUrl;

    private IntegrationDatabase(String adminUrl, String adminUser, String adminPassword) {
        this.adminUrl = adminUrl;
        this.adminUser = adminUser;
        this.adminPassword = adminPassword;
        this.databaseUrl = databaseUrl(adminUrl, DATABASE);
    }

    static IntegrationDatabase connect() {
        String adminUrl = environment("VEV_TEST_ADMIN_JDBC_URL", "jdbc:postgresql://127.0.0.1:5432/postgres");
        requireSafeAdminUrl(adminUrl, environment("VEV_TEST_ALLOW_REMOTE_DESTRUCTIVE_SETUP", ""));
        return new IntegrationDatabase(
                adminUrl,
                environment("VEV_TEST_ADMIN_USER", "postgres"),
                environment("VEV_TEST_ADMIN_PASSWORD", ""));
    }

    void initialize(String modelName, String fingerprint) throws SQLException {
        createDatabase();
        createRoles();
        try (Connection connection = adminConnection()) {
            for (String sql : schemaStatements()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO public.vev_schema_fingerprint(model_name, fingerprint) VALUES (?, ?)")) {
                statement.setString(1, modelName);
                statement.setString(2, fingerprint);
                statement.executeUpdate();
            }
        }
    }

    void truncateAccounts() throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE vev_it.account, vev_it.audit_event");
        }
    }

    void setAuditColumnUpdatePrivilege(boolean enabled) throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute((enabled ? "GRANT" : "REVOKE")
                    + " UPDATE(event_type) ON TABLE vev_it.audit_event "
                    + (enabled ? "TO " : "FROM ") + APPLICATION_USER);
        }
    }

    void setSchemaCreatePrivilege(boolean enabled) throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute((enabled ? "GRANT CREATE ON SCHEMA vev_it TO "
                    : "REVOKE CREATE ON SCHEMA vev_it FROM ") + APPLICATION_USER);
        }
    }

    void setFingerprintOperationalPrivileges(boolean enabled) throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute((enabled ? "GRANT" : "REVOKE")
                    + " TRIGGER, MAINTAIN ON TABLE public.vev_schema_fingerprint "
                    + (enabled ? "TO " : "FROM ") + APPLICATION_USER);
        }
    }

    void setIncomingFingerprintForeignKey(boolean enabled) throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            if (enabled) {
                statement.execute("""
                        CREATE TABLE public.vev_fingerprint_reference (
                            model_name varchar(128) NOT NULL REFERENCES public.vev_schema_fingerprint(model_name)
                        )
                        """);
            } else {
                statement.execute("DROP TABLE IF EXISTS public.vev_fingerprint_reference");
            }
        }
    }

    void setNondeterministicEmailCollation(boolean enabled) throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            if (enabled) {
                statement.execute("CREATE COLLATION IF NOT EXISTS vev_it.nondeterministic "
                        + "(provider = icu, locale = 'und-u-ks-level2', deterministic = false)");
                statement.execute("ALTER TABLE vev_it.account ALTER COLUMN email TYPE varchar(255) "
                        + "COLLATE vev_it.nondeterministic");
            } else {
                statement.execute("ALTER TABLE vev_it.account ALTER COLUMN email TYPE varchar(255) COLLATE pg_catalog.default");
                statement.execute("DROP COLLATION IF EXISTS vev_it.nondeterministic");
            }
        }
    }

    void setAccountUnlogged(boolean enabled) throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE vev_it.account SET " + (enabled ? "UNLOGGED" : "LOGGED"));
        }
    }

    void setAccountEmailIndexPresent(boolean present) throws SQLException {
        try (Connection connection = adminConnection();
            Statement statement = connection.createStatement()) {
            if (present) {
                statement.execute("CREATE INDEX account_email_vev_idx ON vev_it.account "
                        + "USING btree (tenant_id, email, id)");
            } else {
                statement.execute("DROP INDEX IF EXISTS vev_it.account_email_vev_idx");
            }
        }
    }

    void setExtraAccountIndex(boolean present) throws SQLException {
        try (Connection connection = adminConnection();
            Statement statement = connection.createStatement()) {
            if (present) {
                statement.execute("CREATE INDEX account_balance_extra_idx ON vev_it.account "
                        + "USING btree (tenant_id, balance, id)");
            } else {
                statement.execute("DROP INDEX IF EXISTS vev_it.account_balance_extra_idx");
            }
        }
    }

    void setAccountEmailIndexWrongShape(boolean wrongShape) throws SQLException {
        try (Connection connection = adminConnection();
            Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX IF EXISTS vev_it.account_email_vev_idx");
            if (wrongShape) {
                statement.execute("CREATE INDEX account_email_vev_idx ON vev_it.account "
                        + "USING btree (tenant_id, id, email)");
            } else {
                statement.execute("CREATE INDEX account_email_vev_idx ON vev_it.account "
                        + "USING btree (tenant_id, email, id)");
            }
        }
    }

    void setAccountEmailIndexUnique(boolean unique) throws SQLException {
        replaceAccountEmailIndex(unique
                ? "CREATE UNIQUE INDEX account_email_vev_idx ON vev_it.account "
                        + "USING btree (tenant_id, email, id)"
                : EXPECTED_ACCOUNT_EMAIL_INDEX);
    }

    void setAccountEmailIndexPartial(boolean partial) throws SQLException {
        replaceAccountEmailIndex(partial
                ? "CREATE INDEX account_email_vev_idx ON vev_it.account "
                        + "USING btree (tenant_id, email, id) WHERE email IS NOT NULL"
                : EXPECTED_ACCOUNT_EMAIL_INDEX);
    }

    void setAccountEmailIndexExpression(boolean expression) throws SQLException {
        replaceAccountEmailIndex(expression
                ? "CREATE INDEX account_email_vev_idx ON vev_it.account "
                        + "USING btree (tenant_id, pg_catalog.lower(email), id)"
                : EXPECTED_ACCOUNT_EMAIL_INDEX);
    }

    void setAccountEmailIndexIncludingBalance(boolean includingBalance) throws SQLException {
        replaceAccountEmailIndex(includingBalance
                ? "CREATE INDEX account_email_vev_idx ON vev_it.account "
                        + "USING btree (tenant_id, email, id) INCLUDE (balance)"
                : EXPECTED_ACCOUNT_EMAIL_INDEX);
    }

    void setAccountEmailIndexDescending(boolean descending) throws SQLException {
        replaceAccountEmailIndex(descending
                ? "CREATE INDEX account_email_vev_idx ON vev_it.account "
                        + "USING btree (tenant_id, email DESC, id)"
                : EXPECTED_ACCOUNT_EMAIL_INDEX);
    }

    void setAccountEmailIndexNullsFirst(boolean nullsFirst) throws SQLException {
        replaceAccountEmailIndex(nullsFirst
                ? "CREATE INDEX account_email_vev_idx ON vev_it.account "
                        + "USING btree (tenant_id, email NULLS FIRST, id)"
                : EXPECTED_ACCOUNT_EMAIL_INDEX);
    }

    void setAccountEmailIndexNondefaultCollation(boolean nondefaultCollation) throws SQLException {
        replaceAccountEmailIndex(nondefaultCollation
                ? "CREATE INDEX account_email_vev_idx ON vev_it.account "
                        + "USING btree (tenant_id, email COLLATE pg_catalog.\"C\", id)"
                : EXPECTED_ACCOUNT_EMAIL_INDEX);
    }

    void setAccountEmailIndexReloptions(boolean reloptions) throws SQLException {
        replaceAccountEmailIndex(reloptions
                ? "CREATE INDEX account_email_vev_idx ON vev_it.account "
                        + "USING btree (tenant_id, email, id) WITH (fillfactor = 90)"
                : EXPECTED_ACCOUNT_EMAIL_INDEX);
    }

    private void replaceAccountEmailIndex(String createSql) throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX IF EXISTS vev_it.account_email_vev_idx");
            try {
                statement.execute(createSql);
            } catch (SQLException failure) {
                try {
                    statement.execute(EXPECTED_ACCOUNT_EMAIL_INDEX);
                } catch (SQLException restoreFailure) {
                    failure.addSuppressed(restoreFailure);
                }
                throw failure;
            }
        }
    }

    void setForeignKeyTouchingAccount(boolean enabled) throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            if (enabled) {
                statement.execute("""
                        CREATE TABLE vev_it.account_reference (
                            id integer PRIMARY KEY,
                            account_id uuid NOT NULL,
                            tenant_id integer NOT NULL,
                            FOREIGN KEY (tenant_id, account_id)
                                REFERENCES vev_it.account(tenant_id, id)
                        )
                        """);
            } else {
                statement.execute("DROP TABLE IF EXISTS vev_it.account_reference");
            }
        }
    }

    void setAccountInheritanceChild(boolean enabled) throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            if (enabled) {
                statement.execute("CREATE TABLE vev_it.account_child () INHERITS (vev_it.account)");
            } else {
                statement.execute("DROP TABLE IF EXISTS vev_it.account_child");
            }
        }
    }

    void installShadowedPolicyFunction() throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION vev_it.current_setting(text, boolean)
                    RETURNS text
                    LANGUAGE sql
                    IMMUTABLE
                    PARALLEL SAFE
                    AS 'SELECT ''7''::text'
                    """);
            statement.execute("ALTER ROLE " + APPLICATION_USER + " IN DATABASE " + DATABASE
                    + " SET search_path TO vev_it, pg_catalog");
            statement.execute("SET search_path TO vev_it, pg_catalog");
            statement.execute("DROP POLICY account_tenant ON vev_it.account");
            statement.execute("""
                    CREATE POLICY account_tenant ON vev_it.account
                        FOR ALL TO vev_it_app
                        USING (tenant_id = current_setting('vev.tenant_id', true)::integer)
                        WITH CHECK (tenant_id = current_setting('vev.tenant_id', true)::integer)
                    """);
        }
    }

    void restoreTrustedPolicyFunction() throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("RESET search_path");
            statement.execute("DROP POLICY IF EXISTS account_tenant ON vev_it.account");
            statement.execute("""
                    CREATE POLICY account_tenant ON vev_it.account
                        FOR ALL TO vev_it_app
                        USING (tenant_id = pg_catalog.current_setting('vev.tenant_id', true)::integer)
                        WITH CHECK (tenant_id = pg_catalog.current_setting('vev.tenant_id', true)::integer)
                    """);
            statement.execute("ALTER ROLE " + APPLICATION_USER + " IN DATABASE " + DATABASE
                    + " RESET search_path");
            statement.execute("DROP FUNCTION IF EXISTS vev_it.current_setting(text, boolean)");
        }
    }

    DataSource applicationDataSource() {
        return applicationDataSource("pg_catalog");
    }

    DataSource hostileSearchPathDataSource() {
        return applicationDataSource("vev_hostile,pg_catalog");
    }

    Connection openAdminTransaction() throws SQLException {
        Connection connection = adminConnection();
        try {
            connection.setAutoCommit(false);
            return connection;
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                connection.close();
            } catch (SQLException | RuntimeException | Error closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    void awaitBlockedBatchUpdate(int blockerProcessId) throws SQLException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            try (Connection connection = adminConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         SELECT EXISTS (
                             SELECT 1
                              FROM pg_catalog.pg_stat_activity
                             WHERE usename = ?
                                AND pg_catalog.cardinality(pg_catalog.pg_blocking_pids(pid)) > 0
                                AND ? = ANY(pg_catalog.pg_blocking_pids(pid))
                         )
                         """)) {
                statement.setString(1, APPLICATION_USER);
                statement.setInt(2, blockerProcessId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next() && resultSet.getBoolean(1)) {
                        return;
                    }
                }
            }
            Thread.sleep(Duration.ofMillis(10));
        }
        throw new IllegalStateException("Timed out waiting for the concurrent batch update lock");
    }

    private DataSource applicationDataSource(String currentSchema) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(databaseUrl);
        dataSource.setUser(APPLICATION_USER);
        dataSource.setPassword(APPLICATION_PASSWORD);
        if (currentSchema != null) {
            dataSource.setCurrentSchema(currentSchema);
        }
        return dataSource;
    }

    void installHostileBootstrapOperator() throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS vev_hostile CASCADE");
            statement.execute("CREATE SCHEMA vev_hostile");
            statement.execute("CREATE TABLE vev_hostile.bootstrap_tripwire (invoked boolean NOT NULL)");
            statement.execute("""
                    CREATE FUNCTION vev_hostile.hostile_name_equals(
                        left_value pg_catalog.name,
                        right_value pg_catalog.name)
                    RETURNS pg_catalog.bool
                    LANGUAGE sql
                    VOLATILE
                    SECURITY DEFINER
                    SET search_path TO pg_catalog
                    AS $vev$
                        WITH invocation AS (
                            INSERT INTO vev_hostile.bootstrap_tripwire(invoked)
                            VALUES (true)
                            RETURNING invoked
                        )
                        SELECT left_value OPERATOR(pg_catalog.=) right_value
                          FROM invocation
                    $vev$
                    """);
            statement.execute("REVOKE ALL ON FUNCTION vev_hostile.hostile_name_equals(name, name) FROM PUBLIC");
            statement.execute("GRANT EXECUTE ON FUNCTION vev_hostile.hostile_name_equals(name, name) TO "
                    + APPLICATION_USER);
            statement.execute("CREATE OPERATOR vev_hostile.= ("
                    + "FUNCTION = vev_hostile.hostile_name_equals, "
                    + "LEFTARG = pg_catalog.name, RIGHTARG = pg_catalog.name)");
            statement.execute("GRANT USAGE ON SCHEMA vev_hostile TO " + APPLICATION_USER);
        }
    }

    boolean invokeHostileBootstrapOperatorProbe() throws SQLException {
        try (Connection connection = hostileSearchPathDataSource().getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT database_identity.datname = pg_catalog.current_database()
                       FROM pg_catalog.pg_database database_identity
                      WHERE database_identity.datname OPERATOR(pg_catalog.=) pg_catalog.current_database()
                     """)) {
            return resultSet.next() && resultSet.getBoolean(1) && !resultSet.next();
        }
    }

    void resetHostileBootstrapTripwire() throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE vev_hostile.bootstrap_tripwire");
        }
    }

    long hostileBootstrapTripwireCount() throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT pg_catalog.count(*) FROM vev_hostile.bootstrap_tripwire")) {
            if (!resultSet.next()) {
                throw new IllegalStateException("Hostile bootstrap tripwire count was unavailable");
            }
            long count = resultSet.getLong(1);
            if (resultSet.next()) {
                throw new IllegalStateException("Hostile bootstrap tripwire count returned multiple rows");
            }
            return count;
        }
    }

    void removeHostileBootstrapOperator() throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS vev_hostile CASCADE");
        }
    }

    void installHostileTempDomainTripwire() throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS vev_temp_hostile CASCADE");
            statement.execute("CREATE SCHEMA vev_temp_hostile");
            statement.execute("CREATE TABLE vev_temp_hostile.domain_tripwire (invoked boolean NOT NULL)");
            statement.execute("""
                    CREATE FUNCTION vev_temp_hostile.domain_check(value pg_catalog.text)
                    RETURNS pg_catalog.bool
                    LANGUAGE sql
                    VOLATILE
                    SECURITY DEFINER
                    SET search_path TO pg_catalog
                    AS $vev$
                        WITH invocation AS (
                            INSERT INTO vev_temp_hostile.domain_tripwire(invoked)
                            VALUES (true)
                            RETURNING invoked
                        )
                        SELECT true FROM invocation
                    $vev$
                    """);
            statement.execute("REVOKE ALL ON FUNCTION vev_temp_hostile.domain_check(text) FROM PUBLIC");
            statement.execute("GRANT EXECUTE ON FUNCTION vev_temp_hostile.domain_check(text) TO "
                    + APPLICATION_USER);
        }
    }

    Connection openHostileTempDomainConnection() throws SQLException {
        Connection connection = adminConnection();
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE DOMAIN pg_temp.text AS pg_catalog.text "
                        + "CHECK (vev_temp_hostile.domain_check(VALUE))");
                statement.execute("SET SESSION AUTHORIZATION " + APPLICATION_USER);
                statement.execute("SET search_path TO pg_catalog");
            }
            return connection;
        } catch (Throwable failure) {
            try {
                connection.close();
            } catch (Throwable closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    boolean invokeHostileTempDomainProbe(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT 'probe'::text = 'probe'::pg_catalog.text")) {
            return resultSet.next() && resultSet.getBoolean(1) && !resultSet.next();
        }
    }

    void resetHostileTempDomainTripwire() throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE vev_temp_hostile.domain_tripwire");
        }
    }

    long hostileTempDomainTripwireCount() throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT pg_catalog.count(*) FROM vev_temp_hostile.domain_tripwire")) {
            if (!resultSet.next()) {
                throw new IllegalStateException("Hostile temporary-domain tripwire count was unavailable");
            }
            long count = resultSet.getLong(1);
            if (resultSet.next()) {
                throw new IllegalStateException("Hostile temporary-domain tripwire count returned multiple rows");
            }
            return count;
        }
    }

    void removeHostileTempDomainTripwire() throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS vev_temp_hostile CASCADE");
        }
    }

    DataSource adminDataSource() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(databaseUrl);
        dataSource.setUser(adminUser);
        dataSource.setPassword(adminPassword);
        return dataSource;
    }

    void insertInfiniteAuditEvent(UUID id, int tenantId, boolean positive) throws SQLException {
        try (Connection connection = adminConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO vev_it.audit_event(
                         id, tenant_id, occurred_at, local_occurred_at, business_date, event_type)
                     VALUES (?, ?, ?::timestamptz, ?, ?, ?)
                     """)) {
            statement.setObject(1, id);
            statement.setInt(2, tenantId);
            statement.setString(3, positive ? "infinity" : "-infinity");
            statement.setObject(4, LocalDateTime.parse("2026-08-30T12:34:56.123456"));
            statement.setObject(5, LocalDate.parse("2026-08-30"));
            statement.setString(6, positive ? "POSITIVE_INFINITY" : "NEGATIVE_INFINITY");
            statement.executeUpdate();
        }
    }

    void setFingerprint(String modelName, String fingerprint) throws SQLException {
        try (Connection connection = adminConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE public.vev_schema_fingerprint SET fingerprint = ? WHERE model_name = ?")) {
            statement.setString(1, fingerprint);
            statement.setString(2, modelName);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Expected one schema fingerprint row");
            }
        }
    }

    void setForceRowSecurity(boolean force) throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE vev_it.account " + (force ? "FORCE" : "NO FORCE") + " ROW LEVEL SECURITY");
        }
    }

    void setAccountPolicySafe(boolean safe) throws SQLException {
        String predicate = safe
                ? "tenant_id = current_setting('vev.tenant_id', true)::integer"
                : "true";
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP POLICY account_tenant ON vev_it.account");
            statement.execute("CREATE POLICY account_tenant ON vev_it.account FOR ALL TO "
                    + APPLICATION_USER + " USING (" + predicate + ") WITH CHECK (" + predicate + ")");
        }
    }

    private void createDatabase() throws SQLException {
        try (Connection connection = DriverManager.getConnection(adminUrl, adminUser, adminPassword)) {
            installTrustedAdminPath(connection);
            String marker = null;
            boolean exists = false;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT pg_catalog.shobj_description(oid, 'pg_database') FROM pg_catalog.pg_database WHERE datname = ?")) {
                statement.setString(1, DATABASE);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        exists = true;
                        marker = resultSet.getString(1);
                    }
                }
            }
            if (!exists) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE DATABASE vev_it");
                    statement.execute("COMMENT ON DATABASE vev_it IS '" + FIXTURE_MARKER + "'");
                }
            } else if (!FIXTURE_MARKER.equals(marker)) {
                throw new IllegalStateException(
                        "Refusing to reset existing database vev_it without the exact Vev fixture marker");
            }
        }
    }

    private void createRoles() throws SQLException {
        try (Connection connection = DriverManager.getConnection(adminUrl, adminUser, adminPassword)) {
            installTrustedAdminPath(connection);
            requireOwnedDatabase(connection);
            requireOwnedRole(
                    connection,
                    OWNER_ROLE,
                    "CREATE ROLE vev_it_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
            requireOwnedRole(
                    connection,
                    APPLICATION_USER,
                    "CREATE ROLE vev_it_app LOGIN PASSWORD 'vev_it_password' "
                            + "NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER ROLE vev_it_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
                statement.execute("ALTER ROLE vev_it_app PASSWORD 'vev_it_password' "
                        + "NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS");
                statement.execute("ALTER ROLE vev_it_app IN DATABASE vev_it RESET search_path");
            }
        }
    }

    private static void requireOwnedDatabase(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_catalog.shobj_description(oid, 'pg_database') "
                        + "FROM pg_catalog.pg_database WHERE datname = ?")) {
            statement.setString(1, DATABASE);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()
                        || !FIXTURE_MARKER.equals(resultSet.getString(1))
                        || resultSet.next()) {
                    throw new IllegalStateException(
                            "Refusing to alter roles without the exact Vev fixture database marker");
                }
            }
        }
    }

    private static void requireOwnedRole(Connection connection, String role, String createSql) throws SQLException {
        String marker = null;
        boolean exists = false;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_catalog.shobj_description(oid, 'pg_authid') FROM pg_catalog.pg_roles WHERE rolname = ?")) {
            statement.setString(1, role);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    exists = true;
                    marker = resultSet.getString(1);
                }
            }
        }
        if (exists && !FIXTURE_MARKER.equals(marker)) {
            throw new IllegalStateException(
                    "Refusing to alter existing role " + role + " without the exact Vev fixture marker");
        }
        if (!exists) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(createSql);
                statement.execute("COMMENT ON ROLE " + role + " IS '" + FIXTURE_MARKER + "'");
            }
        }
    }

    private Connection adminConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(databaseUrl, adminUser, adminPassword);
        try {
            requireCurrentFixtureOwnership(connection);
            return connection;
        } catch (Throwable failure) {
            try {
                connection.close();
            } catch (Throwable closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private static void requireCurrentFixtureOwnership(Connection connection) throws SQLException {
        installTrustedAdminPath(connection);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pg_catalog.current_database(),
                       (SELECT pg_catalog.shobj_description(fixture_database.oid, 'pg_database')
                          FROM pg_catalog.pg_database fixture_database
                         WHERE fixture_database.datname = ?),
                       (SELECT pg_catalog.shobj_description(fixture_owner.oid, 'pg_authid')
                          FROM pg_catalog.pg_roles fixture_owner
                         WHERE fixture_owner.rolname = ?),
                       (SELECT pg_catalog.shobj_description(fixture_application.oid, 'pg_authid')
                          FROM pg_catalog.pg_roles fixture_application
                         WHERE fixture_application.rolname = ?)
                """)) {
            statement.setString(1, DATABASE);
            statement.setString(2, OWNER_ROLE);
            statement.setString(3, APPLICATION_USER);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()
                        || !DATABASE.equals(resultSet.getString(1))
                        || !FIXTURE_MARKER.equals(resultSet.getString(2))
                        || !FIXTURE_MARKER.equals(resultSet.getString(3))
                        || !FIXTURE_MARKER.equals(resultSet.getString(4))
                        || resultSet.next()) {
                    throw new IllegalStateException(
                            "Refusing destructive integration work outside the exact owned Vev fixture");
                }
            }
        }
    }

    private static void installTrustedAdminPath(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                WITH trusted_path AS MATERIALIZED (
                    SELECT pg_catalog.set_config('search_path', 'pg_catalog', false) AS search_path,
                           pg_catalog.pg_my_temp_schema() AS temp_schema
                )
                SELECT trusted_path.search_path,
                       trusted_path.temp_schema,
                       pg_catalog.current_setting('search_path')
                  FROM trusted_path
                """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()
                        || !"pg_catalog".equals(resultSet.getString(1))
                        || resultSet.getLong(2) != 0
                        || resultSet.wasNull()
                        || !"pg_catalog".equals(resultSet.getString(3))
                        || resultSet.next()) {
                    throw new IllegalStateException(
                            "Refusing destructive integration work outside the exact owned Vev fixture");
                }
            }
        }
    }

    private static List<String> schemaStatements() {
        return List.of(
                "REVOKE CREATE, TEMPORARY ON DATABASE vev_it FROM PUBLIC",
                "DROP SCHEMA IF EXISTS vev_it CASCADE",
                "DROP TABLE IF EXISTS public.vev_fingerprint_reference",
                "DROP TABLE IF EXISTS public.vev_schema_fingerprint",
                "CREATE SCHEMA vev_it AUTHORIZATION " + OWNER_ROLE,
                """
                        CREATE TABLE vev_it.account (
                            id uuid NOT NULL,
                            tenant_id integer NOT NULL,
                            version bigint NOT NULL,
                            email varchar(255),
                            balance numeric(19, 4) NOT NULL,
                            PRIMARY KEY (tenant_id, id)
                        )
                        """,
                "ALTER TABLE vev_it.account OWNER TO " + OWNER_ROLE,
                "CREATE INDEX account_email_vev_idx ON vev_it.account USING btree (tenant_id, email, id)",
                "ALTER TABLE vev_it.account ENABLE ROW LEVEL SECURITY",
                "ALTER TABLE vev_it.account FORCE ROW LEVEL SECURITY",
                """
                        CREATE POLICY account_tenant ON vev_it.account
                            FOR ALL TO vev_it_app
                            USING (tenant_id = current_setting('vev.tenant_id', true)::integer)
                            WITH CHECK (tenant_id = current_setting('vev.tenant_id', true)::integer)
                        """,
                """
                        CREATE TABLE vev_it.audit_event (
                            id uuid NOT NULL,
                            tenant_id integer NOT NULL,
                            occurred_at timestamptz NOT NULL,
                            local_occurred_at timestamp NOT NULL,
                            business_date date NOT NULL,
                            event_type varchar(255) NOT NULL,
                            PRIMARY KEY (tenant_id, id)
                        )
                        """,
                "ALTER TABLE vev_it.audit_event OWNER TO " + OWNER_ROLE,
                "ALTER TABLE vev_it.audit_event ENABLE ROW LEVEL SECURITY",
                "ALTER TABLE vev_it.audit_event FORCE ROW LEVEL SECURITY",
                """
                        CREATE POLICY audit_event_tenant ON vev_it.audit_event
                            FOR ALL TO vev_it_app
                            USING (tenant_id = current_setting('vev.tenant_id', true)::integer)
                            WITH CHECK (tenant_id = current_setting('vev.tenant_id', true)::integer)
                        """,
                "GRANT USAGE ON SCHEMA vev_it TO " + APPLICATION_USER,
                "GRANT SELECT ON TABLE vev_it.account TO " + APPLICATION_USER,
                "GRANT INSERT (id, tenant_id, version, email, balance) ON TABLE vev_it.account TO " + APPLICATION_USER,
                "GRANT UPDATE (version, email, balance) ON TABLE vev_it.account TO " + APPLICATION_USER,
                "GRANT SELECT ON TABLE vev_it.audit_event TO " + APPLICATION_USER,
                "GRANT INSERT (id, tenant_id, occurred_at, local_occurred_at, business_date, event_type) ON TABLE vev_it.audit_event TO "
                        + APPLICATION_USER,
                """
                        CREATE TABLE public.vev_schema_fingerprint (
                            model_name varchar(128) PRIMARY KEY,
                            fingerprint varchar(71) NOT NULL
                        )
                        """,
                "REVOKE ALL ON TABLE public.vev_schema_fingerprint FROM PUBLIC",
                "GRANT SELECT ON TABLE public.vev_schema_fingerprint TO " + APPLICATION_USER);
    }

    private static String databaseUrl(String adminUrl, String database) {
        int queryStart = adminUrl.indexOf('?');
        int end = queryStart < 0 ? adminUrl.length() : queryStart;
        int slash = adminUrl.lastIndexOf('/', end - 1);
        if (slash < "jdbc:postgresql://".length()) {
            throw new IllegalArgumentException("VEV_TEST_ADMIN_JDBC_URL must include a database name");
        }
        String query = queryStart < 0 ? "" : adminUrl.substring(queryStart);
        return adminUrl.substring(0, slash + 1) + database + query;
    }

    static void requireSafeAdminUrl(String adminUrl, String remoteOptIn) {
        if (isLiteralLoopbackJdbcUrl(adminUrl)
                || ("vev_it".equals(remoteOptIn) && isSingleHostPostgresqlJdbcUrl(adminUrl))) {
            return;
        }
        throw new IllegalStateException(
                "Destructive integration setup requires a literal 127.0.0.1 or [::1] JDBC URL; "
                        + "set VEV_TEST_ALLOW_REMOTE_DESTRUCTIVE_SETUP=vev_it exactly to allow another target");
    }

    private static boolean isSingleHostPostgresqlJdbcUrl(String adminUrl) {
        if (adminUrl == null || !adminUrl.startsWith(POSTGRESQL_JDBC_PREFIX)) {
            return false;
        }
        try {
            URI parsed = URI.create(adminUrl.substring("jdbc:".length()));
            String path = parsed.getPath();
            int port = parsed.getPort();
            return parsed.getHost() != null
                    && parsed.getUserInfo() == null
                    && parsed.getRawQuery() == null
                    && parsed.getFragment() == null
                    && (port == -1 || port >= 1 && port <= 65_535)
                    && path != null
                    && path.length() > 1
                    && path.indexOf('/', 1) == -1
                    && isSimpleDatabaseName(path.substring(1));
        } catch (IllegalArgumentException invalidUrl) {
            return false;
        }
    }

    private static boolean isLiteralLoopbackJdbcUrl(String adminUrl) {
        if (adminUrl == null || !adminUrl.startsWith(POSTGRESQL_JDBC_PREFIX)) {
            return false;
        }
        int databaseSeparator = adminUrl.indexOf('/', POSTGRESQL_JDBC_PREFIX.length());
        if (databaseSeparator < 0 || databaseSeparator == adminUrl.length() - 1) {
            return false;
        }
        String authority = adminUrl.substring(POSTGRESQL_JDBC_PREFIX.length(), databaseSeparator);
        String database = adminUrl.substring(databaseSeparator + 1);
        if (!isSimpleDatabaseName(database)) {
            return false;
        }
        return isLiteralLoopbackAuthority(authority, "127.0.0.1")
                || isLiteralLoopbackAuthority(authority, "[::1]");
    }

    private static boolean isSimpleDatabaseName(String database) {
        for (int index = 0; index < database.length(); index++) {
            char character = database.charAt(index);
            if (!(character >= 'a' && character <= 'z')
                    && !(character >= 'A' && character <= 'Z')
                    && !(character >= '0' && character <= '9')
                    && character != '_'
                    && character != '-'
                    && character != '.') {
                return false;
            }
        }
        return !database.isEmpty();
    }

    private static boolean isLiteralLoopbackAuthority(String authority, String host) {
        if (authority.equals(host)) {
            return true;
        }
        String portPrefix = host + ':';
        if (!authority.startsWith(portPrefix)) {
            return false;
        }
        String port = authority.substring(portPrefix.length());
        if (port.isEmpty() || port.length() > 5) {
            return false;
        }
        for (int index = 0; index < port.length(); index++) {
            char character = port.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        int portNumber = Integer.parseInt(port);
        return portNumber >= 1 && portNumber <= 65_535;
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
