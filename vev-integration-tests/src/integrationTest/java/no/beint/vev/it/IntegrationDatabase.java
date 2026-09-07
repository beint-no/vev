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
            for (String sql : identitySchemaStatements()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
            for (String sql : largeTextSchemaStatements()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
            for (String sql : binarySchemaStatements()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
            for (String sql : textSchemaStatements()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
            for (String sql : clockSchemaStatements()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
            for (String sql : readOnlySchemaStatements()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
            for (String sql : sharedSchemaStatements()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
            for (String sql : sharedOnlySchemaStatements()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
            for (String sql : defaultSchemaStatements()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
            for (String sql : registrySchemaStatements()) {
                try (Statement statement = connection.createStatement()) { statement.execute(sql); }
            }
            for (String sql : clockDefaultSchemaStatements()) {
                try (Statement statement = connection.createStatement()) { statement.execute(sql); }
            }
            for (String sql : dateWindowSchemaStatements()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
            for (String sql : orderedSchemaStatements()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO public.vev_schema_fingerprint(model_name, fingerprint) VALUES (?, ?)")) {
                statement.setString(1, modelName);
                statement.setString(2, fingerprint);
                statement.executeUpdate();
                statement.setString(1, SharedOnlyModelVev.IDENTITY.name());
                statement.setString(2, SharedOnlyModelVev.IDENTITY.fingerprint());
                statement.executeUpdate();
            }
        }
    }

    void truncateAccounts() throws SQLException {
        try (Connection connection = adminConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE vev_it.account, vev_it.audit_event, vev_it.work_item, vev_it.snapshot_probe, vev_it.kotlin_entry, vev_it.identity_entry, vev_it.identity_counter, vev_it.identity_event, vev_it.kotlin_identity, vev_it.large_text, vev_it.binary_asset, vev_it.binary_sample, vev_it.kotlin_binary, vev_it.text_document, vev_it.kotlin_text, vev_it.kotlin_clock, vev_it.readonly_snapshot, vev_it.readonly_identity, vev_it.kotlin_readonly, vev_it.shared_catalog, vev_it.catalog_selection, vev_it.kotlin_shared, vev_it.ranked_item, vev_it.kotlin_ranked, vev_it.date_window, vev_it.only_category, vev_it.only_note, vev_it.default_sample, vev_it.kotlin_default, vev_it.clock_default, vev_it.kotlin_clock_default, vev_it.registry_entry, vev_it.kotlin_registry_entry, vev_it.tenant_registry");
        }
    }

    void externalIncomingVariant(String variant) throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS vev_it_external CASCADE");
            statement.execute("DROP TABLE IF EXISTS vev_it.external_links CASCADE");
            for (String target : List.of("only_category", "only_note")) {
                statement.execute("ALTER TABLE vev_it." + target + " DROP CONSTRAINT IF EXISTS unexpected_reference");
            }
            statement.execute("ALTER TABLE vev_it.only_category DROP CONSTRAINT IF EXISTS only_category_parent_fk");
            statement.execute("ALTER TABLE vev_it.only_category ADD CONSTRAINT only_category_parent_fk FOREIGN KEY(parent_id) REFERENCES vev_it.only_category(id)");
            if (variant.equals("valid")) return;
            statement.execute("CREATE SCHEMA vev_it_external");
            statement.execute("CREATE TABLE vev_it.external_links (category_id integer, note_id bigint, tenant_id integer, snapshot_id uuid)");
            // More incoming constraints than the declared-reference count proves filtering happens before LIMIT.
            for (int index = 0; index < 12; index++) {
                statement.execute("ALTER TABLE vev_it.external_links ADD CONSTRAINT external_category_" + index
                        + " FOREIGN KEY(category_id) REFERENCES vev_it.only_category(id)");
            }
            statement.execute("ALTER TABLE vev_it.external_links ADD CONSTRAINT external_note FOREIGN KEY(note_id) REFERENCES vev_it.only_note(id)");
            statement.execute("ALTER TABLE vev_it.external_links ADD CONSTRAINT external_snapshot FOREIGN KEY(tenant_id,snapshot_id) REFERENCES vev_it.readonly_snapshot(tenant_id,id)");
            // An external relation may have the same unqualified name as a mapped table.
            statement.execute("CREATE TABLE vev_it_external.only_category (id integer REFERENCES vev_it.only_category(id))");
            statement.execute("CREATE FUNCTION vev_it_external.reference_tripwire(integer) RETURNS boolean LANGUAGE plpgsql IMMUTABLE AS 'BEGIN RAISE EXCEPTION ''external source expression must not execute''; END'");
            statement.execute("CREATE DOMAIN vev_it_external.reference_key AS integer CHECK (vev_it_external.reference_tripwire(VALUE))");
            statement.execute("CREATE TABLE vev_it_external.custom_source (id vev_it_external.reference_key REFERENCES vev_it.only_category(id))");
            switch (variant) {
                case "incoming" -> { }
                case "externalWriteSemantics" -> {
                    statement.execute("ALTER TABLE vev_it.external_links DROP CONSTRAINT external_category_0");
                    statement.execute("ALTER TABLE vev_it.external_links ADD CONSTRAINT external_category_0 FOREIGN KEY(category_id) REFERENCES vev_it.only_category(id) MATCH FULL ON UPDATE CASCADE ON DELETE SET NULL DEFERRABLE INITIALLY DEFERRED NOT VALID");
                }
                case "outgoing" -> {
                    statement.execute("CREATE TABLE vev_it_external.lookup (id integer PRIMARY KEY)");
                    statement.execute("INSERT INTO vev_it_external.lookup VALUES (1)");
                    statement.execute("ALTER TABLE vev_it.only_category ADD CONSTRAINT unexpected_reference FOREIGN KEY(parent_id) REFERENCES vev_it_external.lookup(id)");
                }
                case "mappedSource" -> statement.execute("ALTER TABLE vev_it.only_note ADD CONSTRAINT unexpected_reference FOREIGN KEY(id) REFERENCES vev_it.only_category(id)");
                case "self" -> statement.execute("ALTER TABLE vev_it.only_category ADD CONSTRAINT unexpected_reference FOREIGN KEY(parent_id) REFERENCES vev_it.only_category(id)");
                case "missing", "cascade", "deferred", "unvalidated", "disabledTrigger" -> {
                    statement.execute("ALTER TABLE vev_it.only_category DROP CONSTRAINT only_category_parent_fk");
                    if (!variant.equals("missing")) {
                        String options = switch (variant) {
                            case "cascade" -> " ON DELETE CASCADE";
                            case "deferred" -> " DEFERRABLE INITIALLY DEFERRED";
                            case "unvalidated" -> " NOT VALID";
                            default -> "";
                        };
                        statement.execute("ALTER TABLE vev_it.only_category ADD CONSTRAINT only_category_parent_fk FOREIGN KEY(parent_id) REFERENCES vev_it.only_category(id)" + options);
                        if (variant.equals("disabledTrigger")) statement.execute("ALTER TABLE vev_it.only_category DISABLE TRIGGER ALL");
                    }
                }
                case "strictTarget" -> statement.execute("ALTER TABLE vev_it.external_links ADD CONSTRAINT external_strict FOREIGN KEY(category_id) REFERENCES vev_it.shared_catalog(id)");
                case "writableTarget" -> statement.execute("ALTER TABLE vev_it.external_links ADD CONSTRAINT external_writable FOREIGN KEY(tenant_id,snapshot_id) REFERENCES vev_it.account(tenant_id,id)");
                default -> throw new IllegalArgumentException(variant);
            }
        }
    }

    void verifyExternalIncomingConstraintsRemainEffective() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO vev_it.external_links(category_id, note_id) VALUES (1,1)");
            try {
                statement.execute("INSERT INTO vev_it.external_links(category_id) VALUES (999)");
                throw new AssertionError("The external source must retain physical foreign-key enforcement");
            } catch (SQLException failure) {
                if (!"23503".equals(failure.getSQLState())) throw failure;
            }
            try {
                statement.execute("DELETE FROM vev_it.only_note WHERE id = 1");
                throw new AssertionError("Existing references must still block administrative target deletion");
            } catch (SQLException failure) {
                if (!"23503".equals(failure.getSQLState())) throw failure;
            }
        }
    }

    private static List<String> registrySchemaStatements() {
        return List.of(
                "CREATE TABLE vev_it.tenant_registry(id integer PRIMARY KEY, private_payload jsonb NOT NULL DEFAULT '{\"fixture\":true}'::jsonb)",
                "ALTER TABLE vev_it.tenant_registry OWNER TO vev_it_owner",
                "CREATE FUNCTION vev_it.registry_tripwire(integer) RETURNS boolean LANGUAGE plpgsql VOLATILE AS 'BEGIN RAISE EXCEPTION ''Registry policy must not execute through a tenant-key FK''; END'",
                "ALTER TABLE vev_it.tenant_registry ENABLE ROW LEVEL SECURITY",
                "ALTER TABLE vev_it.tenant_registry FORCE ROW LEVEL SECURITY",
                "CREATE POLICY registry_private ON vev_it.tenant_registry USING (vev_it.registry_tripwire(id))",
                "CREATE TABLE vev_it.registry_entry(id bigint GENERATED BY DEFAULT AS IDENTITY, tenant_id integer NOT NULL, version bigint NOT NULL, label varchar(64), PRIMARY KEY(tenant_id,id), CONSTRAINT registry_entry_tenant_fk FOREIGN KEY(tenant_id) REFERENCES vev_it.tenant_registry(id) ON DELETE CASCADE)",
                "ALTER TABLE vev_it.registry_entry OWNER TO vev_it_owner",
                "ALTER TABLE vev_it.registry_entry ENABLE ROW LEVEL SECURITY",
                "ALTER TABLE vev_it.registry_entry FORCE ROW LEVEL SECURITY",
                "CREATE POLICY registry_entry_tenant ON vev_it.registry_entry FOR ALL TO vev_it_app USING (tenant_id = current_setting('vev.tenant_id',true)::integer) WITH CHECK (tenant_id = current_setting('vev.tenant_id',true)::integer)",
                "GRANT SELECT, INSERT(id,tenant_id,version,label), UPDATE(version,label) ON vev_it.registry_entry TO vev_it_app",
                "GRANT USAGE ON SEQUENCE vev_it.registry_entry_id_seq TO vev_it_app",
                "CREATE TABLE vev_it.kotlin_registry_entry(id bigint GENERATED ALWAYS AS IDENTITY, tenant_id integer NOT NULL, version bigint NOT NULL, label varchar(64), PRIMARY KEY(tenant_id,id), CONSTRAINT kotlin_registry_tenant_fk FOREIGN KEY(tenant_id) REFERENCES vev_it.tenant_registry(id))",
                "ALTER TABLE vev_it.kotlin_registry_entry OWNER TO vev_it_owner",
                "ALTER TABLE vev_it.kotlin_registry_entry ENABLE ROW LEVEL SECURITY",
                "ALTER TABLE vev_it.kotlin_registry_entry FORCE ROW LEVEL SECURITY",
                "CREATE POLICY kotlin_registry_entry_tenant ON vev_it.kotlin_registry_entry FOR ALL TO vev_it_app USING (tenant_id = current_setting('vev.tenant_id',true)::integer) WITH CHECK (tenant_id = current_setting('vev.tenant_id',true)::integer)",
                "GRANT SELECT, INSERT(id,tenant_id,version,label), UPDATE(version,label) ON vev_it.kotlin_registry_entry TO vev_it_app",
                "GRANT USAGE ON SEQUENCE vev_it.kotlin_registry_entry_id_seq TO vev_it_app");
    }

    void alterTenantRegistry(String sql) throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    void seedTenantRegistry() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO vev_it.tenant_registry(id) VALUES (7),(8)");
        }
    }

    void deleteRegistryTenant(int tenant) throws SQLException {
        try (Connection connection = adminConnection(); PreparedStatement statement = connection.prepareStatement("DELETE FROM vev_it.tenant_registry WHERE id=?")) {
            statement.setInt(1, tenant);
            statement.executeUpdate();
        }
    }

    void clearRegistryNoActionRows() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE vev_it.kotlin_registry_entry");
        }
    }

    private static List<String> clockDefaultSchemaStatements() {
        return List.of(
                "CREATE TABLE vev_it.clock_default (id integer GENERATED BY DEFAULT AS IDENTITY, tenant_id integer NOT NULL, version integer NOT NULL, day date DEFAULT CURRENT_DATE, moment timestamptz DEFAULT CURRENT_TIMESTAMP, rounded_moment timestamptz DEFAULT CURRENT_TIMESTAMP(3), clock time DEFAULT LOCALTIME, rounded_clock time DEFAULT LOCALTIME(6), stamp timestamp DEFAULT LOCALTIMESTAMP, rounded_stamp timestamp DEFAULT LOCALTIMESTAMP(0), now_value timestamptz DEFAULT now(), transaction_value timestamptz DEFAULT transaction_timestamp(), PRIMARY KEY(tenant_id,id))",
                "ALTER TABLE vev_it.clock_default OWNER TO vev_it_owner",
                "ALTER TABLE vev_it.clock_default ENABLE ROW LEVEL SECURITY",
                "ALTER TABLE vev_it.clock_default FORCE ROW LEVEL SECURITY",
                "CREATE POLICY clock_default_tenant ON vev_it.clock_default FOR ALL TO vev_it_app USING (tenant_id = current_setting('vev.tenant_id',true)::integer) WITH CHECK (tenant_id = current_setting('vev.tenant_id',true)::integer)",
                "GRANT SELECT, INSERT(id,tenant_id,version,day,moment,rounded_moment,clock,rounded_clock,stamp,rounded_stamp,now_value,transaction_value), UPDATE(version,day,moment,rounded_moment,clock,rounded_clock,stamp,rounded_stamp,now_value,transaction_value) ON vev_it.clock_default TO vev_it_app",
                "GRANT USAGE ON SEQUENCE vev_it.clock_default_id_seq TO vev_it_app",
                "CREATE TABLE vev_it.kotlin_clock_default (id bigint GENERATED ALWAYS AS IDENTITY, tenant_id integer NOT NULL, version bigint NOT NULL, day date DEFAULT CURRENT_DATE, moment timestamptz DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(tenant_id,id))",
                "ALTER TABLE vev_it.kotlin_clock_default OWNER TO vev_it_owner",
                "ALTER TABLE vev_it.kotlin_clock_default ENABLE ROW LEVEL SECURITY",
                "ALTER TABLE vev_it.kotlin_clock_default FORCE ROW LEVEL SECURITY",
                "CREATE POLICY kotlin_clock_default_tenant ON vev_it.kotlin_clock_default FOR ALL TO vev_it_app USING (tenant_id = current_setting('vev.tenant_id',true)::integer) WITH CHECK (tenant_id = current_setting('vev.tenant_id',true)::integer)",
                "GRANT SELECT, INSERT(id,tenant_id,version,day,moment), UPDATE(version,day,moment) ON vev_it.kotlin_clock_default TO vev_it_app",
                "GRANT USAGE ON SEQUENCE vev_it.kotlin_clock_default_id_seq TO vev_it_app");
    }

    ClockDefaultEntry seedClockDefaults() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("SET LOCAL TIME ZONE 'UTC'");
                statement.execute("INSERT INTO vev_it.clock_default(id,tenant_id,version) VALUES (1001,7,0),(1001,8,0)");
                statement.execute("INSERT INTO vev_it.kotlin_clock_default(id,tenant_id,version) OVERRIDING SYSTEM VALUE VALUES (1001,7,0),(1001,8,0)");
                ClockDefaultEntry reference;
                try (ResultSet row = statement.executeQuery("SELECT day,moment,rounded_moment,clock,rounded_clock,stamp,rounded_stamp,now_value,transaction_value FROM vev_it.clock_default WHERE tenant_id=7 AND id=1001")) {
                    if (!row.next()) throw new SQLException("Missing clock-default fixture row");
                    reference = new ClockDefaultEntry(1001, 7, 0, row.getObject(1, LocalDate.class),
                            row.getObject(2, java.time.OffsetDateTime.class).toInstant(), row.getObject(3, java.time.OffsetDateTime.class).toInstant(),
                            row.getObject(4, java.time.LocalTime.class), row.getObject(5, java.time.LocalTime.class),
                            row.getObject(6, LocalDateTime.class), row.getObject(7, LocalDateTime.class),
                            row.getObject(8, java.time.OffsetDateTime.class).toInstant(), row.getObject(9, java.time.OffsetDateTime.class).toInstant());
                    if (row.next()) throw new SQLException("Extra clock-default fixture row");
                }
                for (String sequence : List.of("clock_default_id_seq", "kotlin_clock_default_id_seq")) {
                    statement.execute("SELECT pg_catalog.setval('vev_it." + sequence + "', GREATEST((SELECT last_value FROM vev_it." + sequence + "),1001),true)");
                }
                connection.commit();
                return reference;
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
        }
    }

    void clockDefaultVariant(String variant) throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE vev_it.clock_default ALTER COLUMN day SET DEFAULT CURRENT_DATE, ALTER COLUMN moment SET DEFAULT CURRENT_TIMESTAMP, ALTER COLUMN rounded_moment SET DEFAULT CURRENT_TIMESTAMP(3)");
            statement.execute("DROP FUNCTION IF EXISTS vev_it.clock_tripwire()");
            switch (variant) {
                case "valid" -> { }
                case "missing" -> statement.execute("ALTER TABLE vev_it.clock_default ALTER COLUMN moment DROP DEFAULT");
                case "differentSpelling" -> statement.execute("ALTER TABLE vev_it.clock_default ALTER COLUMN moment SET DEFAULT now()");
                case "differentPrecision" -> statement.execute("ALTER TABLE vev_it.clock_default ALTER COLUMN rounded_moment SET DEFAULT CURRENT_TIMESTAMP(0)");
                case "volatile" -> statement.execute("ALTER TABLE vev_it.clock_default ALTER COLUMN day SET DEFAULT clock_timestamp()::date");
                case "statementClock" -> statement.execute("ALTER TABLE vev_it.clock_default ALTER COLUMN day SET DEFAULT statement_timestamp()::date");
                case "identity" -> statement.execute("ALTER TABLE vev_it.clock_default ALTER COLUMN day SET DEFAULT CASE WHEN CURRENT_USER IS NULL THEN NULL ELSE CURRENT_DATE END");
                case "userFunction" -> {
                    statement.execute("CREATE FUNCTION vev_it.clock_tripwire() RETURNS date LANGUAGE plpgsql STABLE AS 'BEGIN RAISE EXCEPTION ''clock default must not execute''; END'");
                    statement.execute("ALTER TABLE vev_it.clock_default ALTER COLUMN day SET DEFAULT vev_it.clock_tripwire()");
                }
                default -> throw new IllegalArgumentException(variant);
            }
        }
    }

    void verifyClockDefaultContracts(boolean rejected) throws SQLException {
        try (Connection connection = adminConnection()) {
            if (rejected) no.beint.vev.pg.ClockDefaultCatalogProbe.rejectBeforeDeparse(connection);
            else no.beint.vev.pg.ClockDefaultCatalogProbe.verifyScopeAndSignatures(connection);
        }
    }

    private static List<String> defaultSchemaStatements() {
        return List.of(
                "CREATE TABLE vev_it.default_sample (id integer GENERATED BY DEFAULT AS IDENTITY, tenant_id integer NOT NULL, version integer NOT NULL, enabled boolean NOT NULL DEFAULT true, small_value smallint NOT NULL DEFAULT 0, counter integer NOT NULL DEFAULT (2 + 3), long_value bigint NOT NULL DEFAULT 0, amount numeric(12,2) NOT NULL DEFAULT 0, label varchar(64) DEFAULT 'fallback'::character varying, body text DEFAULT 'defaults; are metadata'::text, day date DEFAULT '2024-01-01'::date, clock time DEFAULT '12:00:00'::time without time zone, stamp timestamp DEFAULT '2024-01-01 12:00:00.123456'::timestamp without time zone, moment timestamptz DEFAULT '2024-01-01 00:00:00+00'::timestamp with time zone, token uuid DEFAULT '00000000-0000-0000-0000-000000000000'::uuid, payload bytea DEFAULT '\\x73616d706c65'::bytea, state varchar(8) NOT NULL DEFAULT 'OPEN'::character varying, PRIMARY KEY(tenant_id,id), CONSTRAINT default_sample_counter_check CHECK(counter >= 0), CONSTRAINT default_sample_body_length CHECK(char_length(body) <= 128), CONSTRAINT default_sample_payload_length CHECK(octet_length(payload) <= 64))",
                "CREATE INDEX default_sample_enabled_idx ON vev_it.default_sample(tenant_id,enabled,id)",
                "CREATE TABLE vev_it.kotlin_default (id bigint GENERATED ALWAYS AS IDENTITY, tenant_id integer NOT NULL, version bigint NOT NULL, enabled boolean NOT NULL DEFAULT true, label varchar(64) DEFAULT 'kotlin', attempts integer DEFAULT 7, PRIMARY KEY(tenant_id,id))",
                "ALTER TABLE vev_it.default_sample OWNER TO vev_it_owner",
                "ALTER TABLE vev_it.default_sample ENABLE ROW LEVEL SECURITY",
                "ALTER TABLE vev_it.default_sample FORCE ROW LEVEL SECURITY",
                "CREATE POLICY default_sample_tenant ON vev_it.default_sample FOR ALL TO vev_it_app USING (tenant_id = current_setting('vev.tenant_id',true)::integer) WITH CHECK (tenant_id = current_setting('vev.tenant_id',true)::integer)",
                "GRANT SELECT, INSERT(id,tenant_id,version,enabled,small_value,counter,long_value,amount,label,body,day,clock,stamp,moment,token,payload,state), UPDATE(version,enabled,small_value,counter,long_value,amount,label,body,day,clock,stamp,moment,token,payload,state) ON vev_it.default_sample TO vev_it_app",
                "GRANT USAGE ON SEQUENCE vev_it.default_sample_id_seq TO vev_it_app",
                "ALTER TABLE vev_it.kotlin_default OWNER TO vev_it_owner",
                "ALTER TABLE vev_it.kotlin_default ENABLE ROW LEVEL SECURITY",
                "ALTER TABLE vev_it.kotlin_default FORCE ROW LEVEL SECURITY",
                "CREATE POLICY kotlin_default_tenant ON vev_it.kotlin_default FOR ALL TO vev_it_app USING (tenant_id = current_setting('vev.tenant_id',true)::integer) WITH CHECK (tenant_id = current_setting('vev.tenant_id',true)::integer)",
                "GRANT SELECT, INSERT(id,tenant_id,version,enabled,label,attempts), UPDATE(version,enabled,label,attempts) ON vev_it.kotlin_default TO vev_it_app",
                "GRANT USAGE ON SEQUENCE vev_it.kotlin_default_id_seq TO vev_it_app");
    }

    void seedDatabaseDefaults() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO vev_it.default_sample(id,tenant_id,version) OVERRIDING SYSTEM VALUE VALUES (1001,7,0), (1001,8,0)");
            statement.execute("INSERT INTO vev_it.kotlin_default(id,tenant_id,version) OVERRIDING SYSTEM VALUE VALUES (1001,7,0), (1001,8,0)");
            statement.execute("INSERT INTO vev_it.only_note(id,version) OVERRIDING SYSTEM VALUE VALUES (1001,0)");
            for (String sequence : List.of("default_sample_id_seq", "kotlin_default_id_seq", "only_note_id_seq")) {
                statement.execute("SELECT pg_catalog.setval('vev_it." + sequence + "', GREATEST((SELECT last_value FROM vev_it." + sequence + "),1001),true)");
            }
        }
    }

    // Preserve relation and identity-sequence OIDs: the suite's existing runtime owns their contracts.
    void seedMissingValues(String variant) throws SQLException {
        List<String> sampleColumns = List.of(
                "version integer NOT NULL DEFAULT " + (variant.equals("negativeVersion") ? "-1" : "0"),
                "enabled boolean NOT NULL DEFAULT vev_it.historical_default()",
                "small_value smallint NOT NULL DEFAULT 0", "counter integer NOT NULL DEFAULT 1",
                "long_value bigint NOT NULL DEFAULT 0", "amount numeric(12,2) NOT NULL DEFAULT 0",
                "label varchar(32) DEFAULT 'prior'", "body text DEFAULT NULL",
                "day date DEFAULT '2024-01-01'::date",
                "clock time DEFAULT '" + (variant.equals("endOfDay") ? "24:00:00" : "12:00:00") + "'::time",
                "stamp timestamp DEFAULT '2024-01-01 12:00:00.123456'::timestamp",
                "moment timestamptz DEFAULT '2024-01-01 00:00:00+00'::timestamptz",
                "token uuid DEFAULT '00000000-0000-0000-0000-000000000000'::uuid",
                "payload bytea DEFAULT '\\x73616d706c65'::bytea",
                "state varchar(8) NOT NULL DEFAULT '" + (variant.equals("unknownEnum") ? "INVALID" : "OPEN") + "'");
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                statement.execute("TRUNCATE vev_it.default_sample, vev_it.kotlin_default, vev_it.only_note, vev_it.snapshot_probe");
                var before = new java.util.LinkedHashMap<String, Long>();
                for (String table : missingValueTables()) before.put(table, storageFile(connection, table));
                statement.execute("CREATE FUNCTION vev_it.historical_default() RETURNS boolean LANGUAGE plpgsql STABLE AS 'BEGIN RETURN false; END'");
                replaceMissingColumns(statement, "default_sample", sampleColumns,
                        "INSERT INTO vev_it.default_sample(id,tenant_id) VALUES (1001,7),(1001,8)");
                statement.execute("ALTER TABLE vev_it.default_sample ALTER COLUMN version DROP DEFAULT, ALTER COLUMN enabled SET DEFAULT true, ALTER COLUMN counter SET DEFAULT (2 + 3), ALTER COLUMN label TYPE varchar(64), ALTER COLUMN label SET DEFAULT 'fallback'::character varying, ALTER COLUMN body SET DEFAULT 'defaults; are metadata'::text, ALTER COLUMN clock SET DEFAULT '12:00:00'::time, ALTER COLUMN state SET DEFAULT 'OPEN'::character varying");
                statement.execute("ALTER TABLE vev_it.default_sample ADD CONSTRAINT default_sample_counter_check CHECK(counter >= 0), ADD CONSTRAINT default_sample_body_length CHECK(char_length(body) <= 128), ADD CONSTRAINT default_sample_payload_length CHECK(octet_length(payload) <= 64)");
                statement.execute("CREATE INDEX default_sample_enabled_idx ON vev_it.default_sample(tenant_id,enabled,id)");
                statement.execute("GRANT INSERT(id,tenant_id,version,enabled,small_value,counter,long_value,amount,label,body,day,clock,stamp,moment,token,payload,state), UPDATE(version,enabled,small_value,counter,long_value,amount,label,body,day,clock,stamp,moment,token,payload,state) ON vev_it.default_sample TO vev_it_app");
                // The old expression is no longer present; reads can only use its stored datum.
                statement.execute("DROP FUNCTION vev_it.historical_default()");
                replaceMissingColumns(statement, "kotlin_default", List.of("version bigint NOT NULL DEFAULT 0",
                                "enabled boolean NOT NULL DEFAULT false", "label varchar(64) DEFAULT 'earlier'", "attempts integer DEFAULT 3"),
                        "INSERT INTO vev_it.kotlin_default(id,tenant_id) OVERRIDING SYSTEM VALUE VALUES (1001,7),(1001,8)");
                statement.execute("ALTER TABLE vev_it.kotlin_default ALTER COLUMN version DROP DEFAULT, ALTER COLUMN enabled SET DEFAULT true, ALTER COLUMN label SET DEFAULT 'kotlin', ALTER COLUMN attempts SET DEFAULT 7");
                statement.execute("GRANT INSERT(id,tenant_id,version,enabled,label,attempts), UPDATE(version,enabled,label,attempts) ON vev_it.kotlin_default TO vev_it_app");
                replaceMissingColumns(statement, "only_note", List.of("version integer NOT NULL DEFAULT 0", "label varchar(64) DEFAULT 'historic-note'"),
                        "INSERT INTO vev_it.only_note(id) OVERRIDING SYSTEM VALUE VALUES (1001)");
                statement.execute("ALTER TABLE vev_it.only_note ALTER COLUMN version DROP DEFAULT, ALTER COLUMN label SET DEFAULT 'from-schema'");
                statement.execute("ALTER TABLE vev_it.snapshot_probe ADD COLUMN placeholder integer");
                statement.execute("DROP POLICY snapshot_probe_tenant ON vev_it.snapshot_probe");
                replaceMissingColumns(statement, "snapshot_probe", List.of("id bigint NOT NULL DEFAULT 1001",
                                "tenant_id integer NOT NULL DEFAULT 7", "version bigint NOT NULL DEFAULT 0", "value varchar(64) NOT NULL DEFAULT 'historical'"),
                        "INSERT INTO vev_it.snapshot_probe(placeholder) VALUES (1)");
                statement.execute("ALTER TABLE vev_it.snapshot_probe ALTER COLUMN id DROP DEFAULT, ALTER COLUMN tenant_id DROP DEFAULT, ALTER COLUMN version DROP DEFAULT, ALTER COLUMN value DROP DEFAULT, DROP COLUMN placeholder, ADD PRIMARY KEY(id)");
                statement.execute("CREATE INDEX snapshot_probe_tenant_id_idx ON vev_it.snapshot_probe(tenant_id,id)");
                statement.execute("CREATE POLICY snapshot_probe_tenant ON vev_it.snapshot_probe FOR ALL TO vev_it_app USING (tenant_id = current_setting('vev.tenant_id',true)::integer) WITH CHECK (tenant_id = current_setting('vev.tenant_id',true)::integer)");
                statement.execute("GRANT INSERT(id,tenant_id,version,value), UPDATE(version,value) ON vev_it.snapshot_probe TO vev_it_app");
                for (var entry : before.entrySet()) {
                    if (entry.getValue() != storageFile(connection, entry.getKey())) {
                        throw new SQLException("Historical-value fixture unexpectedly rewrote " + entry.getKey());
                    }
                }
                statement.execute("INSERT INTO vev_it.default_sample(id,tenant_id,version) VALUES (1002,7,0),(1002,8,0)");
                statement.execute("INSERT INTO vev_it.kotlin_default(id,tenant_id,version) OVERRIDING SYSTEM VALUE VALUES (1002,7,0),(1002,8,0)");
                statement.execute("INSERT INTO vev_it.only_note(id,version) OVERRIDING SYSTEM VALUE VALUES (1002,0)");
                for (String sequence : List.of("default_sample_id_seq", "kotlin_default_id_seq", "only_note_id_seq")) {
                    statement.execute("SELECT pg_catalog.setval('vev_it." + sequence + "', GREATEST((SELECT last_value FROM vev_it." + sequence + "),1002),true)");
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
        }
    }

    private static void replaceMissingColumns(Statement statement, String table, List<String> definitions,
                                              String insertOldRows) throws SQLException {
        for (String definition : definitions) {
            statement.execute("ALTER TABLE vev_it." + table + " DROP COLUMN " + definition.substring(0, definition.indexOf(' ')));
        }
        statement.execute(insertOldRows);
        for (String definition : definitions) statement.execute("ALTER TABLE vev_it." + table + " ADD COLUMN " + definition);
    }

    List<String> missingValueColumns(String table) throws SQLException {
        try (Connection connection = adminConnection(); PreparedStatement statement = connection.prepareStatement("""
                SELECT attname FROM pg_catalog.pg_attribute
                 WHERE attrelid = pg_catalog.to_regclass(?) AND attnum > 0 AND NOT attisdropped
                   AND atthasmissing AND attmissingval IS NOT NULL ORDER BY attname
                """)) {
            statement.setString(1, "vev_it." + table);
            try (ResultSet rows = statement.executeQuery()) {
                var columns = new java.util.ArrayList<String>();
                while (rows.next()) columns.add(rows.getString(1));
                return List.copyOf(columns);
            }
        }
    }

    void rewriteMissingValues(boolean truncate) throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            if (truncate) statement.execute("TRUNCATE vev_it.default_sample, vev_it.kotlin_default, vev_it.only_note, vev_it.snapshot_probe");
            for (String table : missingValueTables()) {
                long before = storageFile(connection, table);
                statement.execute("VACUUM (FULL, ANALYZE) vev_it." + table);
                if (before == storageFile(connection, table)) throw new SQLException("Fixture rewrite did not replace storage: " + table);
            }
        }
    }

    private static List<String> missingValueTables() {
        return List.of("default_sample", "kotlin_default", "only_note", "snapshot_probe");
    }

    private static long storageFile(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_catalog.pg_relation_filenode(pg_catalog.to_regclass(?))")) {
            statement.setString(1, "vev_it." + table);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Missing fixture relation " + table);
                return rows.getLong(1);
            }
        }
    }

    void defaultVariant(String variant) throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE vev_it.default_sample ALTER COLUMN enabled SET DEFAULT true");
            statement.execute("ALTER TABLE vev_it.default_sample ALTER COLUMN counter SET DEFAULT (2 + 3)");
            statement.execute("ALTER TABLE vev_it.default_sample ALTER COLUMN label SET DEFAULT 'fallback'::character varying");
            statement.execute("ALTER TABLE vev_it.account ALTER COLUMN balance DROP DEFAULT");
            statement.execute("DROP FUNCTION IF EXISTS vev_it.default_tripwire()");
            statement.execute("DROP TYPE IF EXISTS vev_it.default_kind");
            switch (variant) {
                case "valid" -> { }
                case "missing" -> statement.execute("ALTER TABLE vev_it.default_sample ALTER COLUMN enabled DROP DEFAULT");
                case "changed" -> statement.execute("ALTER TABLE vev_it.default_sample ALTER COLUMN enabled SET DEFAULT false");
                case "arithmetic" -> statement.execute("ALTER TABLE vev_it.default_sample ALTER COLUMN counter SET DEFAULT (3 + 2)");
                case "unexpected" -> statement.execute("ALTER TABLE vev_it.account ALTER COLUMN balance SET DEFAULT 0");
                case "userFunction" -> {
                    statement.execute("CREATE FUNCTION vev_it.default_tripwire() RETURNS boolean LANGUAGE plpgsql IMMUTABLE AS 'BEGIN RAISE EXCEPTION ''default must not execute''; END'");
                    statement.execute("ALTER TABLE vev_it.default_sample ALTER COLUMN enabled SET DEFAULT vev_it.default_tripwire()");
                }
                case "customType" -> {
                    statement.execute("CREATE TYPE vev_it.default_kind AS ENUM ('sample')");
                    statement.execute("ALTER TABLE vev_it.default_sample ALTER COLUMN enabled SET DEFAULT ('sample'::vev_it.default_kind IS NOT NULL)");
                }
                case "volatile" -> statement.execute("ALTER TABLE vev_it.default_sample ALTER COLUMN enabled SET DEFAULT (random() > 0)");
                case "systemExpression" -> statement.execute("ALTER TABLE vev_it.default_sample ALTER COLUMN enabled SET DEFAULT (CURRENT_USER IS NOT NULL)");
                case "unapprovedCast" -> statement.execute("ALTER TABLE vev_it.default_sample ALTER COLUMN enabled SET DEFAULT ('false'::text)::boolean");
                default -> throw new IllegalArgumentException(variant);
            }
        }
    }

    private static List<String> sharedOnlySchemaStatements() {
        return List.of(
                "CREATE TABLE vev_it.only_category (id integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY, code varchar(32) NOT NULL, label varchar(64), position integer NOT NULL, parent_id integer, CONSTRAINT only_category_code_key UNIQUE(code), CONSTRAINT only_category_parent_fk FOREIGN KEY(parent_id) REFERENCES vev_it.only_category(id))",
                "ALTER TABLE vev_it.only_category OWNER TO vev_it_owner",
                "CREATE INDEX only_category_code_idx ON vev_it.only_category(code,id)",
                "CREATE INDEX only_category_label_idx ON vev_it.only_category(label,position,id)",
                "GRANT SELECT ON vev_it.only_category TO vev_it_app",
                "CREATE TABLE vev_it.only_note (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, version integer NOT NULL, label varchar(64) DEFAULT 'from-schema')",
                "ALTER TABLE vev_it.only_note OWNER TO vev_it_owner",
                "CREATE INDEX only_note_id_idx ON vev_it.only_note(id)",
                "GRANT SELECT ON vev_it.only_note TO vev_it_app");
    }

    void seedSharedOnlyRows() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO vev_it.only_category(id,code,label,position,parent_id) OVERRIDING SYSTEM VALUE VALUES (1,'root','group',20,NULL), (2,'first','group',10,1), (3,'second','group',10,1), (4,'unset',NULL,0,1)");
            statement.execute("INSERT INTO vev_it.only_note(id,version,label) OVERRIDING SYSTEM VALUE VALUES (1,0,'shared'), (2,2147483647,NULL)");
        }
    }

    void sharedOnlyVariant(String variant) throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("REVOKE INSERT, UPDATE, DELETE ON vev_it.only_category FROM vev_it_app");
            statement.execute("REVOKE ALL ON SEQUENCE vev_it.only_category_id_seq FROM vev_it_app");
            statement.execute("DROP POLICY IF EXISTS unexpected ON vev_it.only_category");
            statement.execute("ALTER TABLE vev_it.only_category DISABLE ROW LEVEL SECURITY");
            statement.execute("ALTER TABLE vev_it.only_category NO FORCE ROW LEVEL SECURITY");
            switch (variant) {
                case "valid" -> { }
                case "write" -> statement.execute("GRANT INSERT, UPDATE, DELETE ON vev_it.only_category TO vev_it_app");
                case "sequence" -> statement.execute("GRANT USAGE ON SEQUENCE vev_it.only_category_id_seq TO vev_it_app");
                case "policy" -> statement.execute("CREATE POLICY unexpected ON vev_it.only_category USING (true)");
                case "rls" -> statement.execute("ALTER TABLE vev_it.only_category ENABLE ROW LEVEL SECURITY");
                case "negativeVersion" -> statement.execute("UPDATE vev_it.only_note SET version = -1 WHERE id = 1");
                default -> throw new IllegalArgumentException(variant);
            }
        }
    }

    private static List<String> dateWindowSchemaStatements() {
        return List.of(
                "CREATE TABLE vev_it.date_window (id integer NOT NULL, tenant_id integer NOT NULL, opened date NOT NULL, closed date, lead_days integer NOT NULL DEFAULT 0, lag_days integer NOT NULL, span_days integer NOT NULL, PRIMARY KEY(tenant_id,id), CONSTRAINT date_window_plus CHECK (closed >= opened + lead_days), CONSTRAINT date_window_minus CHECK (opened <= closed - lag_days), CONSTRAINT date_window_span CHECK (closed - opened = span_days))",
                "ALTER TABLE vev_it.date_window OWNER TO vev_it_owner",
                "ALTER TABLE vev_it.date_window ENABLE ROW LEVEL SECURITY",
                "ALTER TABLE vev_it.date_window FORCE ROW LEVEL SECURITY",
                "CREATE POLICY date_window_tenant ON vev_it.date_window FOR ALL TO vev_it_app USING (tenant_id = current_setting('vev.tenant_id', true)::integer) WITH CHECK (tenant_id = current_setting('vev.tenant_id', true)::integer)",
                "GRANT SELECT, INSERT(id,tenant_id,opened,closed,lead_days,lag_days,span_days) ON vev_it.date_window TO vev_it_app");
    }

    void seedOrderedRows() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO vev_it.ranked_item VALUES (1,7,0,'a',true,20,'zulu'), (2,7,0,'a',true,10,'alpha'), (3,7,0,'a',false,10,'beta'), (4,7,0,'a',true,20,'delta'), (5,7,0,NULL,true,20,'echo'), (6,7,0,NULL,false,10,'foxtrot'), (7,7,0,'b',true,0,'gamma'), (8,7,0,NULL,true,10,'hotel'), (1,8,0,'a',true,30,'foreign'), (2,8,0,'a',true,-1,'foreign')");
            statement.execute("INSERT INTO vev_it.kotlin_ranked(id, category, position) OVERRIDING SYSTEM VALUE VALUES (1,'g',2.00), (2,'g',1.00), (3,'g',1.00), (4,NULL,0.00), (5,NULL,0.00), (6,'g',3.00)");
        }
    }

    void orderedVariant(String variant) throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX IF EXISTS vev_it.ranked_item_category_idx");
            statement.execute("DROP INDEX IF EXISTS vev_it.ranked_item_enabled_idx");
            statement.execute("DROP INDEX IF EXISTS vev_it.kotlin_ranked_category_idx");
            String keys = switch (variant) {
                case "wrongOrder" -> "tenant_id, category, id, rank_value";
                case "descending" -> "tenant_id, category, rank_value DESC, id";
                case "nullsFirst" -> "tenant_id, category, rank_value NULLS FIRST, id";
                case "missingTieBreaker" -> "tenant_id, category, rank_value";
                case "expression" -> "tenant_id, category, (rank_value + 1), id";
                default -> "tenant_id, category, rank_value, id";
            };
            statement.execute("CREATE INDEX ranked_item_category_idx ON vev_it.ranked_item (" + keys + ")"
                    + (variant.equals("included") ? " INCLUDE (version)" : "")
                    + (variant.equals("partial") ? " WHERE enabled" : ""));
            statement.execute("CREATE INDEX kotlin_ranked_category_idx ON vev_it.kotlin_ranked ("
                    + (variant.equals("sharedOrder") ? "category, id DESC, position DESC" : "category, position DESC, id DESC") + ")");
            String descendingKeys = switch (variant) {
                case "descendingAsAscending" -> "tenant_id, enabled, \"order\", id";
                case "descendingMixed" -> "tenant_id, enabled, \"order\" DESC, id";
                case "descendingNullsLast" -> "tenant_id, enabled, \"order\" DESC NULLS LAST, id DESC";
                case "descendingPrefix" -> "tenant_id DESC, enabled, \"order\" DESC, id DESC";
                default -> "tenant_id, enabled, \"order\" DESC, id DESC";
            };
            statement.execute("CREATE INDEX ranked_item_enabled_idx ON vev_it.ranked_item (" + descendingKeys + ")");
            if (variant.equals("negativeVersion")) statement.execute("UPDATE vev_it.ranked_item SET version = -1 WHERE tenant_id = 7 AND id = 2");
        }
    }

    void seedOrderedExplainRows() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO vev_it.ranked_item SELECT value, 7, 0, 'group', true, value % 100, value::text FROM pg_catalog.generate_series(1, 20000) value");
            statement.execute("ANALYZE vev_it.ranked_item");
        }
    }

    String explainOrderedQuery(String sql, boolean descending) throws SQLException {
        try (Connection connection = applicationDataSource().getConnection()) {
            connection.setAutoCommit(false);
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            try (Statement setting = connection.createStatement()) {
                setting.execute("SET LOCAL vev.tenant_id = '7'");
            }
            try (PreparedStatement statement = connection.prepareStatement("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql)) {
                statement.setInt(1, 7);
                if (descending) {
                    statement.setBoolean(2, true);
                    statement.setString(3, "15000");
                } else {
                    statement.setString(2, "group");
                    statement.setInt(3, 50);
                }
                statement.setInt(4, 10000);
                statement.setInt(5, 9);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("Missing synthetic EXPLAIN result");
                    String result = rows.getString(1);
                    if (rows.next()) throw new IllegalStateException("Unexpected second EXPLAIN result");
                    connection.rollback();
                    return result;
                }
            }
        }
    }

    private static List<String> orderedSchemaStatements() {
        return List.of(
                "CREATE TABLE vev_it.ranked_item (id integer NOT NULL, tenant_id integer NOT NULL, version integer NOT NULL, category varchar(64), enabled boolean NOT NULL, rank_value integer NOT NULL, \"order\" varchar(64) NOT NULL, PRIMARY KEY(tenant_id,id))",
                "ALTER TABLE vev_it.ranked_item OWNER TO vev_it_owner",
                "ALTER TABLE vev_it.ranked_item ENABLE ROW LEVEL SECURITY",
                "ALTER TABLE vev_it.ranked_item FORCE ROW LEVEL SECURITY",
                "CREATE POLICY ranked_item_tenant ON vev_it.ranked_item FOR ALL TO vev_it_app USING (tenant_id = current_setting('vev.tenant_id', true)::integer) WITH CHECK (tenant_id = current_setting('vev.tenant_id', true)::integer)",
                "CREATE INDEX ranked_item_category_idx ON vev_it.ranked_item(tenant_id,category,rank_value,id)",
                "CREATE INDEX ranked_item_enabled_idx ON vev_it.ranked_item(tenant_id,enabled,\"order\" DESC,id DESC)",
                "GRANT SELECT, INSERT(id,tenant_id,version,category,enabled,rank_value,\"order\"), UPDATE(version,category,enabled,rank_value,\"order\") ON vev_it.ranked_item TO vev_it_app",
                "CREATE TABLE vev_it.kotlin_ranked (id integer GENERATED ALWAYS AS IDENTITY NOT NULL PRIMARY KEY, category varchar(64), position numeric(19,2) NOT NULL)",
                "ALTER TABLE vev_it.kotlin_ranked OWNER TO vev_it_owner",
                "CREATE INDEX kotlin_ranked_category_idx ON vev_it.kotlin_ranked(category,position DESC,id DESC)",
                "GRANT SELECT ON vev_it.kotlin_ranked TO vev_it_app");
    }

    void seedSharedRows() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO vev_it.shared_catalog(id, version, code, label, parent_id) OVERRIDING SYSTEM VALUE VALUES (1,0,'root','group',NULL), (2,9223372036854775807,'child',NULL,1), (3,1,'peer','group',1), (4,2,'leaf',NULL,2)");
            statement.execute("INSERT INTO vev_it.kotlin_shared(id, label) OVERRIDING SYSTEM VALUE VALUES (1,'common')");
        }
    }

    void sharedVariant(String variant) throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            for (String table : List.of("catalog_selection", "shared_catalog", "kotlin_shared")) {
                statement.execute("DROP TABLE IF EXISTS vev_it." + table + " CASCADE");
            }
            for (String sql : sharedSchemaStatements()) statement.execute(sql);
            for (String sql : switch (variant) {
                case "valid" -> List.<String>of();
                case "noSelect" -> List.of("REVOKE SELECT ON vev_it.shared_catalog FROM vev_it_app");
                case "insert" -> List.of("GRANT INSERT (label) ON vev_it.shared_catalog TO vev_it_app");
                case "insertTable" -> List.of("GRANT INSERT ON vev_it.shared_catalog TO vev_it_app");
                case "selectGrantOption" -> List.of("GRANT SELECT ON vev_it.shared_catalog TO vev_it_app WITH GRANT OPTION");
                case "update" -> List.of("GRANT UPDATE (label) ON vev_it.shared_catalog TO vev_it_app");
                case "delete" -> List.of("GRANT DELETE ON vev_it.shared_catalog TO vev_it_app");
                case "sequence" -> List.of("GRANT USAGE ON SEQUENCE vev_it.shared_catalog_id_seq TO vev_it_app");
                case "enabledRls" -> List.of("ALTER TABLE vev_it.shared_catalog ENABLE ROW LEVEL SECURITY");
                case "forcedRls" -> List.of("ALTER TABLE vev_it.shared_catalog FORCE ROW LEVEL SECURITY");
                case "dormantPolicy" -> List.of("CREATE POLICY unexpected_shared_policy ON vev_it.shared_catalog USING (true)");
                case "indexOrder" -> List.of("DROP INDEX vev_it.shared_catalog_label_idx", "CREATE INDEX shared_catalog_label_idx ON vev_it.shared_catalog (id, label)");
                case "wrongPrimaryKey" -> List.of("ALTER TABLE vev_it.kotlin_shared DROP CONSTRAINT kotlin_shared_pkey", "ALTER TABLE vev_it.kotlin_shared ADD PRIMARY KEY (id, label)");
                case "missingUnique" -> List.of("ALTER TABLE vev_it.shared_catalog DROP CONSTRAINT shared_catalog_code_key");
                case "wrongUnique" -> List.of("ALTER TABLE vev_it.shared_catalog DROP CONSTRAINT shared_catalog_code_key", "ALTER TABLE vev_it.shared_catalog ADD CONSTRAINT shared_catalog_code_key UNIQUE (label)");
                case "missingReference" -> List.of("ALTER TABLE vev_it.catalog_selection DROP CONSTRAINT catalog_selection_catalog_fk");
                case "wrongReference" -> List.of("ALTER TABLE vev_it.catalog_selection DROP CONSTRAINT catalog_selection_catalog_fk", "ALTER TABLE vev_it.catalog_selection ADD CONSTRAINT catalog_selection_catalog_fk FOREIGN KEY (catalog_id) REFERENCES vev_it.kotlin_shared (id)");
                case "cascade" -> List.of("ALTER TABLE vev_it.catalog_selection DROP CONSTRAINT catalog_selection_catalog_fk", "ALTER TABLE vev_it.catalog_selection ADD CONSTRAINT catalog_selection_catalog_fk FOREIGN KEY (catalog_id) REFERENCES vev_it.shared_catalog (id) ON DELETE CASCADE");
                case "matchFull" -> List.of("ALTER TABLE vev_it.catalog_selection DROP CONSTRAINT catalog_selection_catalog_fk", "ALTER TABLE vev_it.catalog_selection ADD CONSTRAINT catalog_selection_catalog_fk FOREIGN KEY (catalog_id) REFERENCES vev_it.shared_catalog (id) MATCH FULL");
                case "unvalidated" -> List.of("ALTER TABLE vev_it.catalog_selection DROP CONSTRAINT catalog_selection_catalog_fk", "ALTER TABLE vev_it.catalog_selection ADD CONSTRAINT catalog_selection_catalog_fk FOREIGN KEY (catalog_id) REFERENCES vev_it.shared_catalog (id) NOT VALID");
                case "deferred" -> List.of("ALTER TABLE vev_it.catalog_selection ALTER CONSTRAINT catalog_selection_catalog_fk DEFERRABLE");
                case "disabledTrigger" -> List.of("ALTER TABLE vev_it.shared_catalog DISABLE TRIGGER ALL");
                case "unmappedIncoming" -> List.of("CREATE TABLE vev_it.shared_external (id integer REFERENCES vev_it.shared_catalog(id))");
                case "extraTenant" -> List.of("ALTER TABLE vev_it.shared_catalog ADD COLUMN tenant_id integer NOT NULL");
                default -> throw new IllegalArgumentException(variant);
            }) statement.execute(sql);
            // Cleanup the unmapped relation after its constraint has been removed by DROP ... CASCADE on reset.
            if (!variant.equals("unmappedIncoming")) statement.execute("DROP TABLE IF EXISTS vev_it.shared_external");
        }
    }

    void negativeSharedVersion() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("UPDATE vev_it.shared_catalog SET version = -1 WHERE id = 1");
        }
    }

    private static List<String> sharedSchemaStatements() {
        return List.of(
                "CREATE TABLE vev_it.shared_catalog (id integer GENERATED ALWAYS AS IDENTITY NOT NULL, version bigint NOT NULL, code varchar(32) NOT NULL, label varchar(64), parent_id integer, PRIMARY KEY(id), CONSTRAINT shared_catalog_code_key UNIQUE(code), CONSTRAINT shared_catalog_parent_fk FOREIGN KEY(parent_id) REFERENCES vev_it.shared_catalog(id))",
                "ALTER TABLE vev_it.shared_catalog OWNER TO vev_it_owner",
                "CREATE INDEX shared_catalog_label_idx ON vev_it.shared_catalog(label, id)",
                "GRANT SELECT ON vev_it.shared_catalog TO vev_it_app",
                "CREATE TABLE vev_it.kotlin_shared (id integer GENERATED ALWAYS AS IDENTITY NOT NULL PRIMARY KEY, label varchar(64) NOT NULL)",
                "ALTER TABLE vev_it.kotlin_shared OWNER TO vev_it_owner",
                "GRANT SELECT ON vev_it.kotlin_shared TO vev_it_app",
                "CREATE INDEX kotlin_shared_id_idx ON vev_it.kotlin_shared(id)",
                "CREATE TABLE vev_it.catalog_selection (id uuid NOT NULL, tenant_id integer NOT NULL, catalog_id integer, PRIMARY KEY(tenant_id,id), CONSTRAINT catalog_selection_catalog_fk FOREIGN KEY(catalog_id) REFERENCES vev_it.shared_catalog(id))",
                "ALTER TABLE vev_it.catalog_selection OWNER TO vev_it_owner",
                "ALTER TABLE vev_it.catalog_selection ENABLE ROW LEVEL SECURITY",
                "ALTER TABLE vev_it.catalog_selection FORCE ROW LEVEL SECURITY",
                "CREATE POLICY catalog_selection_tenant ON vev_it.catalog_selection FOR ALL TO vev_it_app USING (tenant_id = current_setting('vev.tenant_id', true)::integer) WITH CHECK (tenant_id = current_setting('vev.tenant_id', true)::integer)",
                "GRANT SELECT, INSERT (id,tenant_id,catalog_id) ON vev_it.catalog_selection TO vev_it_app");
    }

    void seedReadOnlyRows(UUID key) throws SQLException {
        try (Connection connection = adminConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO vev_it.readonly_snapshot(id, tenant_id, label) VALUES (?, 7, 'visible'), (?, 8, 'foreign')")) {
            statement.setObject(1, key);
            statement.setObject(2, key);
            statement.executeUpdate();
            try (Statement seed = connection.createStatement()) {
                seed.execute("INSERT INTO vev_it.readonly_identity(id, tenant_id, version, label) OVERRIDING SYSTEM VALUE VALUES (1,7,0,'first'), (2,7,9223372036854775807,NULL), (1,8,0,'foreign')");
                seed.execute("INSERT INTO vev_it.kotlin_readonly(id, tenant_id, label) OVERRIDING SYSTEM VALUE VALUES (1,7,'visible'), (1,8,'foreign')");
            }
        }
    }

    void readOnlyVariant(String variant) throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("REVOKE ALL ON vev_it.readonly_identity FROM vev_it_app");
            statement.execute("REVOKE INSERT (id, tenant_id, version, label), UPDATE (id, tenant_id, version, label), REFERENCES (id, tenant_id, version, label) ON vev_it.readonly_identity FROM vev_it_app");
            statement.execute("REVOKE ALL ON SEQUENCE vev_it.readonly_identity_id_seq FROM vev_it_app");
            statement.execute("GRANT SELECT ON vev_it.readonly_identity TO vev_it_app");
            String mutation = switch (variant) {
                case "valid" -> null;
                case "noSelect" -> "REVOKE SELECT ON vev_it.readonly_identity FROM vev_it_app";
                case "insert" -> "GRANT INSERT (label) ON vev_it.readonly_identity TO vev_it_app";
                case "update" -> "GRANT UPDATE (label) ON vev_it.readonly_identity TO vev_it_app";
                case "delete" -> "GRANT DELETE ON vev_it.readonly_identity TO vev_it_app";
                case "usage" -> "GRANT USAGE ON SEQUENCE vev_it.readonly_identity_id_seq TO vev_it_app";
                case "sequenceUpdate" -> "GRANT UPDATE ON SEQUENCE vev_it.readonly_identity_id_seq TO vev_it_app";
                case "negativeVersion" -> "UPDATE vev_it.readonly_identity SET version = -1 WHERE tenant_id = 7 AND id = 1";
                default -> throw new IllegalArgumentException(variant);
            };
            if (mutation != null) statement.execute(mutation);
        }
    }

    private static List<String> readOnlySchemaStatements() {
        var statements = new java.util.ArrayList<String>();
        for (String table : List.of("readonly_snapshot", "readonly_identity", "kotlin_readonly")) {
            String id = switch (table) {
                case "readonly_snapshot" -> "uuid";
                case "readonly_identity" -> "smallint GENERATED ALWAYS AS IDENTITY";
                default -> "integer GENERATED ALWAYS AS IDENTITY";
            };
            boolean versioned = table.equals("readonly_identity");
            statements.add("CREATE TABLE vev_it." + table + " (id " + id + " NOT NULL, tenant_id integer NOT NULL, "
                    + (versioned ? "version bigint NOT NULL, " : "") + "label varchar(64)" + (versioned ? "" : " NOT NULL")
                    + ", PRIMARY KEY (tenant_id, id))");
            statements.add("ALTER TABLE vev_it." + table + " OWNER TO " + OWNER_ROLE);
            if (!table.equals("kotlin_readonly")) statements.add("CREATE INDEX " + table + "_label_idx ON vev_it." + table + " (tenant_id, label, id)");
            statements.add("ALTER TABLE vev_it." + table + " ENABLE ROW LEVEL SECURITY");
            statements.add("ALTER TABLE vev_it." + table + " FORCE ROW LEVEL SECURITY");
            statements.add("CREATE POLICY " + table + "_tenant ON vev_it." + table
                    + " FOR ALL TO vev_it_app USING (tenant_id = current_setting('vev.tenant_id', true)::integer)"
                    + " WITH CHECK (tenant_id = current_setting('vev.tenant_id', true)::integer)");
            statements.add("GRANT SELECT ON vev_it." + table + " TO vev_it_app");
        }
        return statements;
    }

    void deletionPrivilege(String variant) throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("REVOKE DELETE ON vev_it.identity_counter, vev_it.audit_event FROM vev_it_app CASCADE");
            if (!variant.equals("missing")) {
                statement.execute("GRANT DELETE ON vev_it.identity_counter TO vev_it_app"
                        + (variant.equals("grantOption") ? " WITH GRANT OPTION" : ""));
            }
            if (variant.equals("undeclared")) statement.execute("GRANT DELETE ON vev_it.audit_event TO vev_it_app");
        }
    }

    void setCounterVersion(int id, int version) throws SQLException {
        try (Connection connection = adminConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE vev_it.identity_counter SET version = ? WHERE id = ? AND tenant_id = 7")) {
            statement.setInt(1, version);
            statement.setInt(2, id);
            if (statement.executeUpdate() != 1) throw new IllegalStateException("Expected one synthetic counter");
        }
    }

    void verifyRejectedDefaultBeforeDeparse() throws SQLException {
        try (Connection connection = adminConnection()) {
            no.beint.vev.pg.DefaultCatalogProbe.rejectBeforeDeparse(connection);
        }
    }

    void verifyDefaultResultContracts() throws SQLException {
        try (Connection connection = adminConnection()) {
            no.beint.vev.pg.DefaultCatalogProbe.verifyResultContracts(connection);
        }
    }

    void verifyCheckExpressionCatalog() throws SQLException {
        try (Connection connection = adminConnection()) {
            no.beint.vev.pg.CheckCatalogProbe.verify(connection);
        }
    }

    void identityEntryCheck(String variant) throws SQLException {
        String check = switch (variant) {
            case "valid", "renamed", "unvalidated", "unenforced", "noinherit", "extra" -> "length(btrim(label)) > 0";
            case "weakened" -> "length(btrim(label)) >= 0";
            case "unsafe" -> "length(btrim(label)) > 0 AND current_setting('application_name') IS NOT NULL";
            case "missing" -> null;
            default -> throw new IllegalArgumentException(variant);
        };
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE vev_it.identity_entry DROP CONSTRAINT IF EXISTS identity_entry_label_check");
            statement.execute("ALTER TABLE vev_it.identity_entry DROP CONSTRAINT IF EXISTS renamed_label_check");
            statement.execute("ALTER TABLE vev_it.identity_entry DROP CONSTRAINT IF EXISTS extra_label_check");
            if (check != null) {
                String name = variant.equals("renamed") ? "renamed_label_check" : "identity_entry_label_check";
                String suffix = switch (variant) {
                    case "unvalidated" -> " NOT VALID";
                    case "unenforced" -> " NOT ENFORCED";
                    case "noinherit" -> " NO INHERIT";
                    default -> "";
                };
                statement.execute("ALTER TABLE vev_it.identity_entry ADD CONSTRAINT " + name + " CHECK (" + check + ")" + suffix);
                if (variant.equals("extra")) statement.execute("ALTER TABLE vev_it.identity_entry ADD CONSTRAINT extra_label_check CHECK (label IS NOT NULL)");
            }
        }
    }

    void binarySampleBound(String variant) throws SQLException {
        String expression = switch (variant) {
            case "valid", "renamed", "unvalidated", "unenforced", "noinherit", "extra" -> "octet_length(\"user\") <= 65536";
            case "weakened" -> "octet_length(\"user\") <= 65537";
            case "tightened" -> "octet_length(\"user\") <= 65535";
            case "wrongcolumn" -> "octet_length(digest) <= 65536";
            case "unsafe" -> "octet_length(\"user\") <= 65536 AND current_setting('application_name') IS NOT NULL";
            case "missing" -> null;
            default -> throw new IllegalArgumentException(variant);
        };
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            for (String name : List.of("binary_sample_user_max", "renamed_binary_bound", "extra_binary_bound")) {
                statement.execute("ALTER TABLE vev_it.binary_sample DROP CONSTRAINT IF EXISTS " + name);
            }
            if (expression != null) {
                String name = variant.equals("renamed") ? "renamed_binary_bound" : "binary_sample_user_max";
                String suffix = switch (variant) {
                    case "unvalidated" -> " NOT VALID";
                    case "unenforced" -> " NOT ENFORCED";
                    case "noinherit" -> " NO INHERIT";
                    default -> "";
                };
                statement.execute("ALTER TABLE vev_it.binary_sample ADD CONSTRAINT " + name + " CHECK (" + expression + ")" + suffix);
                if (variant.equals("extra")) statement.execute("ALTER TABLE vev_it.binary_sample ADD CONSTRAINT extra_binary_bound CHECK (octet_length(\"user\") >= 0)");
            }
        }
    }

    void textDocumentBound(String variant) throws SQLException {
        String expression = switch (variant) {
            case "valid", "renamed", "unvalidated", "unenforced", "noinherit", "extra" -> "char_length(\"user\") <= 1048576";
            case "weakened" -> "char_length(\"user\") <= 1048577";
            case "tightened" -> "char_length(\"user\") <= 1048575";
            case "wrongcolumn" -> "char_length(label) <= 1048576";
            case "wrongunits" -> "octet_length(\"user\") <= 1048576";
            case "missing" -> null;
            default -> throw new IllegalArgumentException(variant);
        };
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            for (String name : List.of("text_document_user_max", "renamed_text_bound", "extra_text_bound")) {
                statement.execute("ALTER TABLE vev_it.text_document DROP CONSTRAINT IF EXISTS " + name);
            }
            if (expression != null) {
                String name = variant.equals("renamed") ? "renamed_text_bound" : "text_document_user_max";
                String suffix = switch (variant) {
                    case "unvalidated" -> " NOT VALID";
                    case "unenforced" -> " NOT ENFORCED";
                    case "noinherit" -> " NO INHERIT";
                    default -> "";
                };
                statement.execute("ALTER TABLE vev_it.text_document ADD CONSTRAINT " + name + " CHECK (" + expression + ")" + suffix);
                if (variant.equals("extra")) statement.execute("ALTER TABLE vev_it.text_document ADD CONSTRAINT extra_text_bound CHECK (char_length(\"user\") >= 0)");
            }
        }
    }

    void textDocumentUsesVarchar(boolean varchar) throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE vev_it.text_document ALTER COLUMN \"user\" TYPE " + (varchar ? "varchar(1048576)" : "text"));
        }
    }

    void insertTextBeyondDatabaseBound() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO vev_it.text_document(id, tenant_id, version, label) VALUES (999, 7, 0, repeat('🙂', 129))");
        }
    }

    void identityMode(String mode) throws SQLException {
        if (!List.of("ALWAYS", "BY DEFAULT").contains(mode)) throw new IllegalArgumentException(mode);
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            for (String table : List.of("identity_entry", "identity_counter", "identity_event", "kotlin_identity")) {
                statement.execute("ALTER TABLE vev_it." + table + " ALTER COLUMN id SET GENERATED " + mode);
            }
        }
    }

    void identityEntryPrimaryKey(String variant) throws SQLException {
        String key = switch (variant) {
            case "valid" -> "PRIMARY KEY (id)";
            case "tenantFirst" -> "PRIMARY KEY (tenant_id, id)";
            case "idTenant" -> "PRIMARY KEY (id, tenant_id)";
            case "included" -> "PRIMARY KEY (id) INCLUDE (version)";
            case "deferred" -> "PRIMARY KEY (id) DEFERRABLE";
            default -> throw new IllegalArgumentException(variant);
        };
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE vev_it.identity_entry DROP CONSTRAINT identity_entry_pkey");
            statement.execute("ALTER TABLE vev_it.identity_entry ADD CONSTRAINT identity_entry_pkey " + key);
        }
    }

    void identityEntryTraversalIndex(String variant) throws SQLException {
        String definition = switch (variant) {
            case "valid", "missing", "unique" -> "(tenant_id, id)";
            case "reversed" -> "(id, tenant_id)";
            case "duplicate" -> "(tenant_id, id, id)";
            case "partial" -> "(tenant_id, id) WHERE id > 0";
            default -> throw new IllegalArgumentException(variant);
        };
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP INDEX IF EXISTS vev_it.identity_entry_tenant_id_idx");
            if (!variant.equals("missing")) {
                statement.execute("CREATE " + (variant.equals("unique") ? "UNIQUE " : "")
                        + "INDEX identity_entry_tenant_id_idx ON vev_it.identity_entry " + definition);
            }
        }
    }

    void identityEntryAlternateKeyTenantFirst(boolean tenantFirst) throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE vev_it.identity_event DROP CONSTRAINT identity_event_entry_fk");
            statement.execute("ALTER TABLE vev_it.identity_entry DROP CONSTRAINT identity_entry_id_tenant_key");
            statement.execute("ALTER TABLE vev_it.identity_entry ADD CONSTRAINT identity_entry_id_tenant_key UNIQUE "
                    + (tenantFirst ? "(tenant_id, id)" : "(id, tenant_id)"));
            statement.execute("ALTER TABLE vev_it.identity_event ADD CONSTRAINT identity_event_entry_fk "
                    + "FOREIGN KEY (entry_id, tenant_id) REFERENCES vev_it.identity_entry (id, tenant_id)");
        }
    }

    void identityReferenceTenantFirst(boolean tenantFirst) throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE vev_it.identity_entry DROP CONSTRAINT identity_entry_account_fk");
            statement.execute("ALTER TABLE vev_it.identity_entry ADD CONSTRAINT identity_entry_account_fk FOREIGN KEY "
                    + (tenantFirst ? "(tenant_id, account_id) REFERENCES vev_it.account (tenant_id, id)"
                            : "(account_id, tenant_id) REFERENCES vev_it.account (id, tenant_id)"));
        }
    }

    void identitySequenceVariant(String variant) throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("REVOKE ALL ON SEQUENCE vev_it.identity_event_id_seq FROM vev_it_app");
            statement.execute("GRANT USAGE ON SEQUENCE vev_it.identity_event_id_seq TO vev_it_app");
            statement.execute("ALTER SEQUENCE vev_it.identity_event_id_seq AS smallint INCREMENT 1 MINVALUE 1 MAXVALUE 32767 START 1 NO CYCLE CACHE 1");
            String mutation = switch (variant) {
                case "valid" -> null;
                case "increment" -> "ALTER SEQUENCE vev_it.identity_event_id_seq INCREMENT 2";
                case "minimum" -> "ALTER SEQUENCE vev_it.identity_event_id_seq MINVALUE 0";
                case "maximum" -> "ALTER SEQUENCE vev_it.identity_event_id_seq MAXVALUE 32000";
                case "start" -> "ALTER SEQUENCE vev_it.identity_event_id_seq START 2";
                case "cycle" -> "ALTER SEQUENCE vev_it.identity_event_id_seq CYCLE";
                case "type" -> "ALTER SEQUENCE vev_it.identity_event_id_seq AS bigint";
                case "missingUsage" -> "REVOKE USAGE ON SEQUENCE vev_it.identity_event_id_seq FROM vev_it_app";
                case "select" -> "GRANT SELECT ON SEQUENCE vev_it.identity_event_id_seq TO vev_it_app";
                case "update" -> "GRANT UPDATE ON SEQUENCE vev_it.identity_event_id_seq TO vev_it_app";
                case "grantOption" -> "GRANT USAGE ON SEQUENCE vev_it.identity_event_id_seq TO vev_it_app WITH GRANT OPTION";
                case "cache" -> "ALTER SEQUENCE vev_it.identity_event_id_seq CACHE 10";
                default -> throw new IllegalArgumentException(variant);
            };
            if (mutation != null) statement.execute(mutation);
        }
    }

    void restartSmallIdentity(int next) throws SQLException {
        try (Connection connection = adminConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT pg_catalog.setval('vev_it.identity_event_id_seq'::pg_catalog.regclass, ?, false)")) {
            statement.setInt(1, next);
            statement.execute();
        }
    }

    void corruptWorkState(UUID id) throws SQLException {
        try (Connection connection = adminConnection();
             PreparedStatement statement = connection.prepareStatement("UPDATE vev_it.work_item SET state = 'UNKNOWN' WHERE id = ?")) {
            statement.setObject(1, id);
            statement.executeUpdate();
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
            if (enabled) {
                statement.execute("ALTER TABLE vev_it.identity_event SET UNLOGGED");
                statement.execute("ALTER TABLE vev_it.work_item SET UNLOGGED");
                statement.execute("ALTER TABLE vev_it.identity_entry SET UNLOGGED");
            }
            statement.execute("ALTER TABLE vev_it.account SET " + (enabled ? "UNLOGGED" : "LOGGED"));
            if (!enabled) {
                statement.execute("ALTER TABLE vev_it.work_item SET LOGGED");
                statement.execute("ALTER TABLE vev_it.identity_entry SET LOGGED");
                statement.execute("ALTER TABLE vev_it.identity_event SET LOGGED");
            }
        }
    }

    void setWorkItemUniqueConstraint(String variant) throws SQLException {
        String definition = switch (variant) {
            case "valid", "missing" -> "UNIQUE (tenant_id, account_id, state)";
            case "wrongColumns" -> "UNIQUE (tenant_id, id, state)";
            case "wrongOrder" -> "UNIQUE (account_id, tenant_id, state)";
            case "global" -> "UNIQUE (account_id, state)";
            case "deferred" -> "UNIQUE (tenant_id, account_id, state) DEFERRABLE INITIALLY DEFERRED";
            case "nullsNotDistinct" -> "UNIQUE NULLS NOT DISTINCT (tenant_id, account_id, state)";
            case "included" -> "UNIQUE (tenant_id, account_id, state) INCLUDE (version)";
            case "nonUniqueIndex", "standaloneUniqueIndex" -> "";
            default -> throw new IllegalArgumentException("Unknown synthetic unique-constraint variant");
        };
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE vev_it.work_item DROP CONSTRAINT IF EXISTS work_item_account_state_key");
            statement.execute("DROP INDEX IF EXISTS vev_it.work_item_account_state_key");
            if (variant.equals("nonUniqueIndex") || variant.equals("standaloneUniqueIndex")) {
                statement.execute("CREATE " + (variant.equals("standaloneUniqueIndex") ? "UNIQUE " : "")
                        + "INDEX work_item_account_state_key ON vev_it.work_item (tenant_id, account_id, state)");
            } else if (!variant.equals("missing")) {
                statement.execute("ALTER TABLE vev_it.work_item ADD CONSTRAINT work_item_account_state_key " + definition);
            }
        }
    }

    void setWorkItemReference(String variant) throws SQLException {
        String definition = switch (variant) {
            case "valid", "missing", "disabledTrigger" ->
                    "FOREIGN KEY (tenant_id, account_id) REFERENCES vev_it.account (tenant_id, id)";
            case "wrongColumn" -> "FOREIGN KEY (tenant_id, id) REFERENCES vev_it.account (tenant_id, id)";
            case "reversedColumns" -> "FOREIGN KEY (account_id, tenant_id) REFERENCES vev_it.account (id, tenant_id)";
            case "cascadeDelete" ->
                    "FOREIGN KEY (tenant_id, account_id) REFERENCES vev_it.account (tenant_id, id) ON DELETE CASCADE";
            case "cascadeUpdate" ->
                    "FOREIGN KEY (tenant_id, account_id) REFERENCES vev_it.account (tenant_id, id) ON UPDATE CASCADE";
            case "deferrable" ->
                    "FOREIGN KEY (tenant_id, account_id) REFERENCES vev_it.account (tenant_id, id) DEFERRABLE";
            case "unvalidated" ->
                    "FOREIGN KEY (tenant_id, account_id) REFERENCES vev_it.account (tenant_id, id) NOT VALID";
            case "fullMatch" ->
                    "FOREIGN KEY (tenant_id, account_id) REFERENCES vev_it.account (tenant_id, id) MATCH FULL";
            default -> throw new IllegalArgumentException("Unknown synthetic foreign-key variant");
        };
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE vev_it.work_item ENABLE TRIGGER ALL");
            statement.execute("ALTER TABLE vev_it.work_item DROP CONSTRAINT IF EXISTS work_item_account_fk");
            if (!variant.equals("missing")) {
                statement.execute("ALTER TABLE vev_it.work_item ADD CONSTRAINT work_item_account_fk " + definition);
            }
            if (variant.equals("disabledTrigger")) {
                statement.execute("ALTER TABLE vev_it.work_item DISABLE TRIGGER ALL");
            }
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

    DataSource applicationDataSource(boolean binaryTransfer) {
        var source = (PGSimpleDataSource) applicationDataSource();
        source.setBinaryTransfer(binaryTransfer);
        source.setPrepareThreshold(binaryTransfer ? -1 : 0);
        return source;
    }

    int insertEndOfDayClock() throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement();
             var rows = statement.executeQuery("INSERT INTO vev_it.kotlin_clock(tenant_id, version, observed_at) VALUES (7, 0, '24:00:00') RETURNING id")) {
            if (!rows.next()) throw new SQLException("Synthetic clock insert returned no identifier");
            return rows.getInt(1);
        }
    }

    void clockUsesMilliseconds(boolean milliseconds) throws SQLException {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE vev_it.kotlin_clock ALTER COLUMN observed_at TYPE " + (milliseconds ? "time(3)" : "time"));
        }
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

    private static List<String> clockSchemaStatements() {
        return List.of(
                "CREATE TABLE vev_it.kotlin_clock (id integer GENERATED ALWAYS AS IDENTITY, tenant_id integer NOT NULL, version integer NOT NULL,"
                        + " observed_at time, PRIMARY KEY (tenant_id, id), CONSTRAINT kotlin_clock_time_check CHECK (observed_at <= '24:00:00'::time))",
                "ALTER TABLE vev_it.kotlin_clock OWNER TO " + OWNER_ROLE,
                "ALTER TABLE vev_it.kotlin_clock ENABLE ROW LEVEL SECURITY",
                "ALTER TABLE vev_it.kotlin_clock FORCE ROW LEVEL SECURITY",
                "CREATE INDEX kotlin_clock_time_idx ON vev_it.kotlin_clock (tenant_id, observed_at, id)",
                "CREATE POLICY kotlin_clock_tenant ON vev_it.kotlin_clock FOR ALL TO vev_it_app USING (tenant_id = current_setting('vev.tenant_id', true)::integer) WITH CHECK (tenant_id = current_setting('vev.tenant_id', true)::integer)",
                "GRANT SELECT ON vev_it.kotlin_clock TO " + APPLICATION_USER,
                "GRANT INSERT (id, tenant_id, version, observed_at) ON vev_it.kotlin_clock TO " + APPLICATION_USER,
                "GRANT UPDATE (version, observed_at) ON vev_it.kotlin_clock TO " + APPLICATION_USER,
                "GRANT USAGE ON SEQUENCE vev_it.kotlin_clock_id_seq TO " + APPLICATION_USER);
    }

    private static List<String> textSchemaStatements() {
        var statements = new java.util.ArrayList<String>();
        for (String table : List.of("text_document", "kotlin_text")) {
            boolean generated = table.equals("kotlin_text");
            String values = generated ? ", payload text, CONSTRAINT kotlin_text_payload_max CHECK (char_length(payload) <= 64)"
                    : ", \"user\" text, label text, CONSTRAINT text_document_user_max CHECK (char_length(\"user\") <= 1048576),"
                    + " CONSTRAINT text_document_label_max CHECK (char_length(label) <= 128), CONSTRAINT text_document_label_key UNIQUE (tenant_id, label)";
            String writable = "version, " + (generated ? "payload" : "\"user\", label");
            statements.add("CREATE TABLE vev_it." + table + " (id integer" + (generated ? " GENERATED ALWAYS AS IDENTITY" : " NOT NULL")
                    + ", tenant_id integer NOT NULL, version integer NOT NULL" + values + ", PRIMARY KEY (tenant_id, id))");
            statements.add("ALTER TABLE vev_it." + table + " OWNER TO " + OWNER_ROLE);
            statements.add("ALTER TABLE vev_it." + table + " ENABLE ROW LEVEL SECURITY");
            statements.add("ALTER TABLE vev_it." + table + " FORCE ROW LEVEL SECURITY");
            statements.add("CREATE POLICY " + table + "_tenant ON vev_it." + table
                    + " FOR ALL TO vev_it_app USING (tenant_id = current_setting('vev.tenant_id', true)::integer)"
                    + " WITH CHECK (tenant_id = current_setting('vev.tenant_id', true)::integer)");
            statements.add("GRANT SELECT ON vev_it." + table + " TO " + APPLICATION_USER);
            statements.add("GRANT INSERT (id, tenant_id, " + writable + ") ON vev_it." + table + " TO " + APPLICATION_USER);
            statements.add("GRANT UPDATE (" + writable + ") ON vev_it." + table + " TO " + APPLICATION_USER);
            if (generated) statements.add("GRANT USAGE ON SEQUENCE vev_it." + table + "_id_seq TO " + APPLICATION_USER);
        }
        statements.add("CREATE INDEX text_document_label_idx ON vev_it.text_document (tenant_id, label, id)");
        return statements;
    }

    private static List<String> binarySchemaStatements() {
        var statements = new java.util.ArrayList<String>();
        for (String table : List.of("binary_asset", "binary_sample", "kotlin_binary")) {
            boolean generated = !table.equals("binary_sample");
            String values = switch (table) {
                case "binary_asset" -> ", content bytea NOT NULL, CONSTRAINT binary_asset_content_max CHECK (octet_length(content) <= 20971520)";
                case "kotlin_binary" -> ", payload bytea, CONSTRAINT kotlin_binary_payload_max CHECK (octet_length(payload) <= 128)";
                default -> ", \"user\" bytea, digest bytea, CONSTRAINT binary_sample_user_max CHECK (octet_length(\"user\") <= 65536),"
                        + " CONSTRAINT binary_sample_digest_max CHECK (octet_length(digest) <= 32), CONSTRAINT binary_sample_digest_key UNIQUE (tenant_id, digest)";
            };
            String writable = "version, " + (table.equals("binary_asset") ? "content" : table.equals("kotlin_binary") ? "payload" : "\"user\", digest");
            statements.add("CREATE TABLE vev_it." + table + " (id " + (table.equals("binary_asset") ? "bigint" : "integer")
                    + (generated ? " GENERATED ALWAYS AS IDENTITY" : " NOT NULL")
                    + ", tenant_id integer NOT NULL, version integer NOT NULL" + values + ", PRIMARY KEY (tenant_id, id))");
            statements.add("ALTER TABLE vev_it." + table + " OWNER TO " + OWNER_ROLE);
            statements.add("ALTER TABLE vev_it." + table + " ENABLE ROW LEVEL SECURITY");
            statements.add("ALTER TABLE vev_it." + table + " FORCE ROW LEVEL SECURITY");
            statements.add("CREATE POLICY " + table + "_tenant ON vev_it." + table
                    + " FOR ALL TO vev_it_app USING (tenant_id = current_setting('vev.tenant_id', true)::integer)"
                    + " WITH CHECK (tenant_id = current_setting('vev.tenant_id', true)::integer)");
            statements.add("GRANT SELECT ON vev_it." + table + " TO " + APPLICATION_USER);
            statements.add("GRANT INSERT (id, tenant_id, " + writable + ") ON vev_it." + table + " TO " + APPLICATION_USER);
            statements.add("GRANT UPDATE (" + writable + ") ON vev_it." + table + " TO " + APPLICATION_USER);
            if (generated) statements.add("GRANT USAGE ON SEQUENCE vev_it." + table + "_id_seq TO " + APPLICATION_USER);
        }
        statements.add("CREATE INDEX binary_sample_digest_idx ON vev_it.binary_sample (tenant_id, digest, id)");
        return statements;
    }

    private static List<String> largeTextSchemaStatements() {
        return List.of(
                "CREATE TABLE vev_it.large_text (id integer NOT NULL, tenant_id integer NOT NULL, version integer NOT NULL, category varchar(8), body varchar(65535) NOT NULL, PRIMARY KEY (tenant_id, id))",
                "ALTER TABLE vev_it.large_text OWNER TO " + OWNER_ROLE,
                "ALTER TABLE vev_it.large_text ENABLE ROW LEVEL SECURITY",
                "ALTER TABLE vev_it.large_text FORCE ROW LEVEL SECURITY",
                "CREATE INDEX large_text_category_idx ON vev_it.large_text (tenant_id, category, id)",
                "CREATE POLICY large_text_tenant ON vev_it.large_text FOR ALL TO vev_it_app USING (tenant_id = current_setting('vev.tenant_id', true)::integer) WITH CHECK (tenant_id = current_setting('vev.tenant_id', true)::integer)",
                "GRANT SELECT ON vev_it.large_text TO " + APPLICATION_USER,
                "GRANT INSERT (id, tenant_id, version, category, body) ON vev_it.large_text TO " + APPLICATION_USER,
                "GRANT UPDATE (version, category, body) ON vev_it.large_text TO " + APPLICATION_USER);
    }

    private static List<String> identitySchemaStatements() {
        var statements = new java.util.ArrayList<String>();
        for (String table : List.of("identity_entry", "identity_counter", "identity_event", "kotlin_identity")) {
            String idType = (table.equals("identity_entry") || table.equals("kotlin_identity")) ? "bigint" : table.equals("identity_counter") ? "integer" : "smallint";
            String values = switch (table) {
                case "identity_entry" -> ", version smallint NOT NULL"
                        + java.util.stream.IntStream.range(0, 67).mapToObj(index -> ", retired_" + index + " integer")
                                .collect(java.util.stream.Collectors.joining())
                        + ", label varchar(64) NOT NULL, code varchar(64), account_id uuid"
                        + ", CONSTRAINT identity_entry_label_check CHECK (length(btrim(label)) > 0)"
                        + ", CONSTRAINT identity_entry_code_key UNIQUE (tenant_id, code)"
                        + ", CONSTRAINT identity_entry_id_tenant_key UNIQUE (id, tenant_id)"
                        + ", CONSTRAINT identity_entry_account_fk FOREIGN KEY (account_id, tenant_id) REFERENCES vev_it.account(id, tenant_id)";
                case "identity_counter" -> ", version integer NOT NULL";
                case "kotlin_identity" -> ", version bigint NOT NULL, label varchar(64) NOT NULL, note varchar(64)"
                        + ", CONSTRAINT kotlin_identity_label_check CHECK (length(label) > 0)";
                default -> ", message varchar(64), entry_id bigint"
                        + ", CONSTRAINT identity_event_entry_fk FOREIGN KEY (entry_id, tenant_id) REFERENCES vev_it.identity_entry(id, tenant_id)";
            };
            String mutable = switch (table) {
                case "identity_entry" -> "version, label, code, account_id";
                case "identity_counter" -> "version";
                case "kotlin_identity" -> "version, label, note";
                default -> "message, entry_id";
            };
            statements.add("CREATE TABLE vev_it." + table + "(id " + idType
                    + " GENERATED ALWAYS AS IDENTITY, tenant_id integer NOT NULL" + values + ", PRIMARY KEY ("
                    + (table.equals("kotlin_identity") ? "tenant_id, id" : table.equals("identity_counter") ? "id, tenant_id" : "id") + "))");
            statements.add("ALTER TABLE vev_it." + table + " OWNER TO " + OWNER_ROLE);
            if (table.equals("identity_entry")) {
                // Retired migration columns leave real attnum values above the generated record-width bound.
                for (int retired = 0; retired < 67; retired++) {
                    statements.add("ALTER TABLE vev_it.identity_entry DROP COLUMN retired_" + retired);
                }
            }
            if (!table.equals("kotlin_identity")) {
                statements.add("CREATE INDEX " + table + "_tenant_id_idx ON vev_it." + table + " (tenant_id, id)");
            }
            statements.add("ALTER TABLE vev_it." + table + " ENABLE ROW LEVEL SECURITY");
            statements.add("ALTER TABLE vev_it." + table + " FORCE ROW LEVEL SECURITY");
            statements.add("CREATE POLICY " + table + "_tenant ON vev_it." + table
                    + " FOR ALL TO vev_it_app USING (tenant_id = current_setting('vev.tenant_id', true)::integer)"
                    + " WITH CHECK (tenant_id = current_setting('vev.tenant_id', true)::integer)");
            statements.add("GRANT SELECT ON vev_it." + table + " TO " + APPLICATION_USER);
            statements.add("GRANT INSERT (id, tenant_id, " + mutable + ") ON vev_it." + table + " TO " + APPLICATION_USER);
            if (!table.equals("identity_event")) {
                statements.add("GRANT DELETE ON vev_it." + table + " TO " + APPLICATION_USER);
                statements.add("GRANT UPDATE (" + mutable + ") ON vev_it." + table + " TO " + APPLICATION_USER);
            }
            statements.add("GRANT USAGE ON SEQUENCE vev_it." + table + "_id_seq TO " + APPLICATION_USER);
        }
        return statements;
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
                """
                        CREATE TABLE vev_it.work_item (
                            id uuid NOT NULL,
                            tenant_id integer NOT NULL,
                            version bigint NOT NULL,
                            state varchar(16),
                            account_id uuid,
                            PRIMARY KEY (tenant_id, id),
                            CONSTRAINT work_item_account_state_key UNIQUE (tenant_id, account_id, state),
                            CONSTRAINT work_item_account_fk FOREIGN KEY (tenant_id, account_id)
                                REFERENCES vev_it.account (tenant_id, id)
                        )
                        """,
                "ALTER TABLE vev_it.work_item OWNER TO " + OWNER_ROLE,
                "CREATE INDEX work_item_state_vev_idx ON vev_it.work_item USING btree (tenant_id, state, id)",
                "ALTER TABLE vev_it.work_item ENABLE ROW LEVEL SECURITY",
                "ALTER TABLE vev_it.work_item FORCE ROW LEVEL SECURITY",
                """
                        CREATE POLICY work_item_tenant ON vev_it.work_item
                            FOR ALL TO vev_it_app
                            USING (tenant_id = current_setting('vev.tenant_id', true)::integer)
                            WITH CHECK (tenant_id = current_setting('vev.tenant_id', true)::integer)
                        """,
                "GRANT SELECT ON TABLE vev_it.work_item TO " + APPLICATION_USER,
                "GRANT INSERT (id, tenant_id, version, state, account_id) ON TABLE vev_it.work_item TO " + APPLICATION_USER,
                "GRANT UPDATE (version, state, account_id) ON TABLE vev_it.work_item TO " + APPLICATION_USER,
                """
                        CREATE TABLE vev_it.snapshot_probe (
                            id bigint NOT NULL, tenant_id integer NOT NULL, version bigint NOT NULL,
                            value varchar(64) NOT NULL, PRIMARY KEY (id)
                        )
                        """,
                "ALTER TABLE vev_it.snapshot_probe OWNER TO " + OWNER_ROLE,
                "CREATE INDEX snapshot_probe_tenant_id_idx ON vev_it.snapshot_probe (tenant_id, id)",
                "ALTER TABLE vev_it.snapshot_probe ENABLE ROW LEVEL SECURITY",
                "ALTER TABLE vev_it.snapshot_probe FORCE ROW LEVEL SECURITY",
                """
                        CREATE POLICY snapshot_probe_tenant ON vev_it.snapshot_probe
                            FOR ALL TO vev_it_app
                            USING (tenant_id = current_setting('vev.tenant_id', true)::integer)
                            WITH CHECK (tenant_id = current_setting('vev.tenant_id', true)::integer)
                        """,
                "GRANT SELECT ON TABLE vev_it.snapshot_probe TO " + APPLICATION_USER,
                "GRANT INSERT (id, tenant_id, version, value) ON TABLE vev_it.snapshot_probe TO " + APPLICATION_USER,
                "GRANT UPDATE (version, value) ON TABLE vev_it.snapshot_probe TO " + APPLICATION_USER,
                """
                        CREATE TABLE vev_it.kotlin_entry (
                            id bigint NOT NULL, tenant_id integer NOT NULL, version bigint NOT NULL,
                            label varchar(128) NOT NULL, alias varchar(64), PRIMARY KEY (tenant_id, id)
                        )
                        """,
                "ALTER TABLE vev_it.kotlin_entry OWNER TO " + OWNER_ROLE,
                "CREATE INDEX kotlin_entry_label_idx ON vev_it.kotlin_entry USING btree (tenant_id, label, id)",
                "ALTER TABLE vev_it.kotlin_entry ENABLE ROW LEVEL SECURITY",
                "ALTER TABLE vev_it.kotlin_entry FORCE ROW LEVEL SECURITY",
                """
                        CREATE POLICY kotlin_entry_tenant ON vev_it.kotlin_entry
                            FOR ALL TO vev_it_app
                            USING (tenant_id = current_setting('vev.tenant_id', true)::integer)
                            WITH CHECK (tenant_id = current_setting('vev.tenant_id', true)::integer)
                        """,
                "GRANT SELECT ON TABLE vev_it.kotlin_entry TO " + APPLICATION_USER,
                "GRANT INSERT (id, tenant_id, version, label, alias) ON TABLE vev_it.kotlin_entry TO " + APPLICATION_USER,
                "GRANT UPDATE (version, label, alias) ON TABLE vev_it.kotlin_entry TO " + APPLICATION_USER,
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
