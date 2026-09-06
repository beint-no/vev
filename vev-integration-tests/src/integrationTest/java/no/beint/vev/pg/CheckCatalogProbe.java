package no.beint.vev.pg;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Synthetic catalog cases on an ownership-attested disposable fixture connection. */
public final class CheckCatalogProbe {
    private CheckCatalogProbe() {
    }

    public static void verify(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SET search_path = pg_catalog");
            statement.execute("CREATE TEMP TABLE check_probe(id integer, label varchar(64), n numeric(12,2), stamp timestamptz, opened date, closed date)");
        }
        var catalog = new PgCheckCatalog(connection);
        for (String expression : List.of(
                "id > 0 AND (length(btrim(label)) > 0 OR label IS NULL)",
                "label = ANY(ARRAY['alpha', 'beta'])",
                "label = ANY(ARRAY['alpha', 'beta']::varchar(16)[])",
                "NULLIF(n, 0) IS NOT DISTINCT FROM n",
                "CASE id WHEN 1 THEN true ELSE false END",
                "COALESCE(label, 'fallback') <> ''",
                "(id > 0) IS NOT FALSE",
                "num_nonnulls(label, n) = 1",
                "char_length(lower(label)) <= 64 AND upper(label) <> ''",
                "regexp_replace(label, '[0-9]', '', 'g') <> ''",
                "concat_ws('-', label, id, stamp) <> ''",
                "concat(label IS NULL) <> ''",
                "date_trunc('day', stamp)::date <= stamp::date",
                "stamp >= ('2024-01-01'::date)::timestamptz",
                "id::int8 * 2 > 0 AND n + 1 >= 1",
                "closed >= opened + id", "opened <= closed - id", "closed - opened = id",
                "octet_length('sample'::bytea) > 0 AND jsonb_typeof('{}'::jsonb) = 'object'",
                "label ~ '^[a-z]+$'", "label LIKE 'a%'", "label COLLATE \"C\" <> ''")) {
            String tree = install(connection, expression);
            var dependencies = PgCheckTree.inspect(tree);
            catalog.verify(dependencies);
            assertFalse(definition(connection).isBlank(), expression);
        }
        // Catalog inspection must not invoke even an IMMUTABLE, non-security-definer user function.
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE FUNCTION pg_temp.check_tripwire(text) RETURNS boolean LANGUAGE plpgsql IMMUTABLE AS
                    $$ BEGIN RAISE EXCEPTION 'check tripwire was executed'; END $$
                    """);
            assertThrows(SQLException.class, () -> statement.execute("SELECT pg_temp.check_tripwire('probe')"));
            statement.execute("CREATE FUNCTION pg_temp.check_equal(text, text) RETURNS boolean LANGUAGE sql IMMUTABLE AS 'SELECT $1 = $2'");
            statement.execute("CREATE OPERATOR pg_temp.=== (LEFTARG = text, RIGHTARG = text, FUNCTION = pg_temp.check_equal)");
            statement.execute("CREATE COLLATION pg_temp.check_collation FROM \"C\"");
            statement.execute("CREATE FUNCTION pg_temp.date_pli(date, integer) RETURNS date LANGUAGE plpgsql IMMUTABLE AS $$ BEGIN RAISE EXCEPTION 'date arithmetic tripwire was executed'; END $$");
            assertThrows(SQLException.class, () -> statement.execute("SELECT pg_temp.date_pli('2024-02-28'::date, 2)"));
        }
        for (String expression : List.of("pg_temp.check_tripwire(label)", "random() > 0", "txid_current() > 0",
                "pg_temp.date_pli(opened, id) <= closed", "id + opened <= closed",
                "current_setting('application_name') <> ''", "to_regclass(label) IS NOT NULL",
                "label OPERATOR(pg_temp.===) 'alpha'", "label COLLATE pg_temp.check_collation <> ''",
                "concat_ws('-', label, 'sample'::bytea) <> ''", "concat(ARRAY[label]) <> ''")) {
            String tree = install(connection, expression);
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> catalog.verify(PgCheckTree.inspect(tree)), expression);
            assertTrue(failure.getMessage().contains("unapproved"), failure.getMessage());
        }
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TYPE pg_temp.check_kind AS ENUM ('alpha', 'beta')");
        }
        String customType = install(connection, "'alpha'::pg_temp.check_kind IS NOT NULL");
        var dependencies = PgCheckTree.inspect(customType);
        assertTrue(dependencies.functions().isEmpty());
        var failure = assertThrows(IllegalStateException.class, () -> catalog.verify(dependencies));
        assertTrue(failure.getMessage().contains("unapproved PostgreSQL type"));
        assertThrows(IllegalStateException.class, () -> PgCheckTree.inspect(install(connection, "label::integer > 0")));
        // Current system expressions and scalar subqueries are outside the row-local node profile.
        assertThrows(IllegalStateException.class, () -> PgCheckTree.inspect(install(connection, "CURRENT_DATE > '2024-01-01'::date")));
        // Verify catalog IDs cannot bypass the allowlist just by supplying a builtin function's name in another schema.
        var unsafe = new PgCheckTree.Dependencies(Set.of(0xffff_ffffL), java.util.Map.of(), Set.of(), Set.of(), Set.of(), java.util.Map.of(), java.util.Map.of());
        assertThrows(IllegalStateException.class, () -> catalog.verify(unsafe));
    }

    private static String install(Connection connection, String expression) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE pg_temp.check_probe DROP CONSTRAINT IF EXISTS check_probe_constraint");
            // Test-owned literals above only; production never executes PgCheck.expression().
            statement.execute("ALTER TABLE pg_temp.check_probe ADD CONSTRAINT check_probe_constraint CHECK (" + expression + ")");
            try (var row = statement.executeQuery("SELECT conbin::text FROM pg_catalog.pg_constraint WHERE conrelid = 'pg_temp.check_probe'::regclass")) {
                assertTrue(row.next());
                return row.getString(1);
            }
        }
    }

    private static String definition(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var row = statement.executeQuery(
                "SELECT pg_catalog.pg_get_expr(conbin, conrelid, false) FROM pg_catalog.pg_constraint WHERE conrelid = 'pg_temp.check_probe'::regclass")) {
            assertTrue(row.next());
            return row.getString(1);
        }
    }
}
