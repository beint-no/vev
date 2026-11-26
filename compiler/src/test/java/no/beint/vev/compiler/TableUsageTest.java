package no.beint.vev.compiler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies table reachability across queries, views, materialized views, partitions, triggers, and functions. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TableUsageTest {
    private Postgres postgres;

    @BeforeAll void start(@TempDir Path migrations) throws Exception {
        Files.writeString(migrations.resolve("V1__schema.sql"), """
                CREATE SCHEMA external_svc;

                CREATE TABLE pgway_history (
                    version integer PRIMARY KEY
                );

                CREATE TABLE direct_select (id integer PRIMARY KEY, name text NOT NULL);
                CREATE TABLE direct_insert (id integer PRIMARY KEY, name text NOT NULL);
                CREATE TABLE direct_update (id integer PRIMARY KEY, name text NOT NULL);
                CREATE TABLE direct_delete (id integer PRIMARY KEY);
                CREATE TABLE direct_merge (id integer PRIMARY KEY, name text NOT NULL);
                CREATE TABLE via_view_base (id integer PRIMARY KEY, label text NOT NULL);
                CREATE VIEW via_view AS SELECT id, label FROM via_view_base;
                CREATE TABLE via_matview_base (id integer PRIMARY KEY, total integer NOT NULL);
                CREATE MATERIALIZED VIEW via_matview AS SELECT id, total FROM via_matview_base;

                CREATE TABLE partitioned_events (id integer NOT NULL, occurred_at date NOT NULL) PARTITION BY RANGE (occurred_at);
                CREATE TABLE partitioned_events_2026 PARTITION OF partitioned_events FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
                CREATE TABLE empty_partitioned_events (id integer NOT NULL, occurred_at date NOT NULL) PARTITION BY RANGE (occurred_at);

                CREATE TABLE trigger_counter (tenant_id integer PRIMARY KEY, last_number integer NOT NULL);
                CREATE TABLE trigger_owner (id integer PRIMARY KEY, tenant_id integer NOT NULL, number integer);
                CREATE FUNCTION assign_trigger_number() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    -- Mentioning unused_table in a comment or 'unused_table' string must not mark it used.
                    INSERT INTO trigger_counter (tenant_id, last_number) VALUES (NEW.tenant_id, 1)
                    ON CONFLICT (tenant_id) DO UPDATE SET last_number = trigger_counter.last_number + 1
                    RETURNING last_number INTO NEW.number;
                    RETURN NEW;
                END;
                $$;
                CREATE TRIGGER trg_assign_number BEFORE INSERT ON trigger_owner
                    FOR EACH ROW EXECUTE FUNCTION assign_trigger_number();

                CREATE TABLE fn_lookup (id integer PRIMARY KEY, code text NOT NULL UNIQUE);
                CREATE FUNCTION resolve_inner_lookup(c text) RETURNS integer LANGUAGE plpgsql AS $$
                DECLARE
                    found_id integer;
                BEGIN
                    SELECT id INTO found_id FROM fn_lookup WHERE code = c;
                    RETURN found_id;
                END;
                $$;
                CREATE FUNCTION resolve_outer_lookup(c text) RETURNS integer LANGUAGE plpgsql AS $$
                BEGIN
                    RETURN resolve_inner_lookup(c);
                END;
                $$;

                CREATE TABLE truncated_stage (id integer PRIMARY KEY);
                CREATE TABLE using_source (id integer PRIMARY KEY);
                CREATE TABLE refreshed_matview_base (id integer PRIMARY KEY);
                CREATE MATERIALIZED VIEW refreshed_matview AS SELECT id FROM refreshed_matview_base;
                CREATE FUNCTION maintain_staging() RETURNS void LANGUAGE plpgsql AS $fn$
                DECLARE
                    unused_table integer;
                    msg text := $tag$FROM unused_table JOIN unused_with_trigger$tag$;
                BEGIN
                    SELECT id INTO unused_table FROM direct_select LIMIT 1;
                    DELETE FROM truncated_stage s USING using_source u WHERE s.id = u.id;
                    TRUNCATE TABLE truncated_stage;
                    REFRESH MATERIALIZED VIEW refreshed_matview;
                END;
                $fn$;

                CREATE TABLE unused_table (id integer PRIMARY KEY);
                CREATE TABLE unused_with_trigger (id integer PRIMARY KEY);
                CREATE FUNCTION touch_unused_owner() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    PERFORM 1 FROM direct_select WHERE id = NEW.id;
                    RETURN NEW;
                END;
                $$;
                CREATE TRIGGER trg_unused BEFORE INSERT ON unused_with_trigger
                    FOR EACH ROW EXECUTE FUNCTION touch_unused_owner();

                CREATE TABLE externally_managed (id integer PRIMARY KEY);
                CREATE TABLE externally_managed_audit (id integer PRIMARY KEY);
                CREATE FUNCTION audit_external() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    INSERT INTO externally_managed_audit (id) VALUES (NEW.id);
                    RETURN NEW;
                END;
                $$;
                CREATE TRIGGER trg_audit_external AFTER INSERT ON externally_managed
                    FOR EACH ROW EXECUTE FUNCTION audit_external();
                CREATE TABLE external_svc.messages (id integer PRIMARY KEY);
                """);
        postgres = new Postgres("");
        postgres.migrate(migrations);
    }

    @AfterAll void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    @Test void verifiesReachabilityAndFlagsOnlyUnusedTables(@TempDir Path work) throws Exception {
        Path queries = Files.createDirectories(work.resolve("sql"));
        Path output = work.resolve("out");
        Path manifest = work.resolve("relations.txt");
        Files.writeString(queries.resolve("qSelect.sql"), "-- vev.name: qSelect :many\n-- vev.scope: global\nSELECT id FROM direct_select;");
        Files.writeString(queries.resolve("qInsert.sql"), "-- vev.name: qInsert :exec\n-- vev.scope: global\nINSERT INTO direct_insert (id, name) VALUES (:id, :name);");
        Files.writeString(queries.resolve("qUpdate.sql"), "-- vev.name: qUpdate :exec\n-- vev.scope: global\nUPDATE direct_update SET name = :name WHERE id = :id;");
        Files.writeString(queries.resolve("qDelete.sql"), "-- vev.name: qDelete :exec\n-- vev.scope: global\nDELETE FROM direct_delete WHERE id = :id;");
        Files.writeString(queries.resolve("qMerge.sql"), "-- vev.name: qMerge :exec\n-- vev.scope: global\nMERGE INTO direct_merge t USING (VALUES (:id::int, :name::text)) s(id, name) ON t.id = s.id WHEN MATCHED THEN UPDATE SET name = s.name WHEN NOT MATCHED THEN INSERT (id, name) VALUES (s.id, s.name);");
        Files.writeString(queries.resolve("qView.sql"), "-- vev.name: qView :many\n-- vev.scope: global\nSELECT id, label FROM via_view;");
        Files.writeString(queries.resolve("qMatview.sql"), "-- vev.name: qMatview :many\n-- vev.scope: global\nSELECT id, total FROM via_matview;");
        Files.writeString(queries.resolve("qPart.sql"), "-- vev.name: qPart :many\n-- vev.scope: global\nSELECT id FROM partitioned_events;");
        Files.writeString(queries.resolve("qEmptyPart.sql"), "-- vev.name: qEmptyPart :many\n-- vev.scope: global\nSELECT id FROM empty_partitioned_events;");
        Files.writeString(queries.resolve("qTrigger.sql"), "-- vev.name: qTrigger :exec\n-- vev.scope: global\nINSERT INTO trigger_owner (id, tenant_id) VALUES (:id, :tenant);");
        Files.writeString(queries.resolve("qFunction.sql"), "-- vev.name: qFunction :optional\n-- vev.scope: global\nSELECT resolve_outer_lookup(:code) AS id;");
        Files.writeString(queries.resolve("qMaintain.sql"), "-- vev.name: qMaintain :void\n-- vev.scope: global\nSELECT maintain_staging() AS done;");

        try (var connection = postgres.connect()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            QueryCompiler.compile(connection, queries, output, "sample", "Queries", manifest);
            connection.rollback();
        }
        assertTrue(Files.exists(manifest));

        try (var connection = postgres.connect()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);

            var result = TableUsage.verify(connection, List.of(manifest), Set.of("public"), Set.of("externally_managed"));
            assertEquals(
                    Set.of(
                            new TableUsage.Relation("public", "unused_table"),
                            new TableUsage.Relation("public", "unused_with_trigger")),
                    result.unusedTables(),
                    "Only truly unreachable public tables should be flagged");
            assertTrue(result.usedTables().contains(new TableUsage.Relation("public", "trigger_counter")));
            assertTrue(result.usedTables().contains(new TableUsage.Relation("public", "fn_lookup")));
            assertTrue(result.usedTables().contains(new TableUsage.Relation("public", "via_view_base")));
            assertTrue(result.usedTables().contains(new TableUsage.Relation("public", "via_matview_base")));
            assertTrue(result.usedTables().contains(new TableUsage.Relation("public", "refreshed_matview_base")));
            assertTrue(result.usedTables().contains(new TableUsage.Relation("public", "truncated_stage")));
            assertTrue(result.usedTables().contains(new TableUsage.Relation("public", "using_source")));
            assertTrue(result.usedTables().contains(new TableUsage.Relation("public", "externally_managed_audit")));
            assertTrue(result.usedTables().contains(new TableUsage.Relation("public", "partitioned_events")));
            assertTrue(result.usedTables().contains(new TableUsage.Relation("public", "empty_partitioned_events")));
            assertFalse(result.catalogTables().contains(new TableUsage.Relation("public", "partitioned_events_2026")));
            assertFalse(result.catalogTables().contains(new TableUsage.Relation("public", "flyway_schema_history")));
            assertFalse(result.catalogTables().contains(new TableUsage.Relation("public", "pgway_history")));
            assertFalse(result.catalogTables().contains(new TableUsage.Relation("external_svc", "messages")));
            assertEquals(Set.of(new TableUsage.Relation("public", "externally_managed")), result.ignoredTables());

            // An actively queried table in ignoredTables must fail fast so allowlists only shrink.
            var alreadyUsed = assertThrows(IllegalArgumentException.class, () ->
                    TableUsage.verify(connection, List.of(manifest), Set.of("public"),
                            Set.of("externally_managed", "public.direct_select", "unused_table", "unused_with_trigger")));
            assertTrue(alreadyUsed.getMessage().contains("already referenced by Vev queries"), alreadyUsed.getMessage());
            assertTrue(alreadyUsed.getMessage().contains("public.direct_select"), alreadyUsed.getMessage());

            // Including external_svc in schemas flags external_svc.messages unless ignored.
            var multiSchema = TableUsage.verify(connection, List.of(manifest), Set.of("public", "external_svc"),
                    Set.of("externally_managed", "unused_table", "unused_with_trigger"));
            assertEquals(Set.of(new TableUsage.Relation("external_svc", "messages")), multiSchema.unusedTables());

            var multiSchemaIgnored = TableUsage.verify(connection, List.of(manifest), Set.of("public", "external_svc"),
                    Set.of("externally_managed", "unused_table", "unused_with_trigger", "external_svc.messages"));
            assertTrue(multiSchemaIgnored.unusedTables().isEmpty());

            // Unknown or stale ignoredTables entries fail fast.
            var stale = assertThrows(IllegalArgumentException.class, () ->
                    TableUsage.verify(connection, List.of(manifest), Set.of("public"), Set.of("does_not_exist")));
            assertTrue(stale.getMessage().contains("public.does_not_exist"), stale.getMessage());

            connection.rollback();
        }
    }
}
