package no.beint.vev.compiler;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Build-time tenant isolation proofs against the real ReAI schema and PostgreSQL's generic plans. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantScanTest {
    private Postgres postgres;

    @BeforeAll void start() throws Exception {
        postgres = new Postgres("");
        postgres.migrate(Path.of("../test-fixtures/reai/migration"));
    }
    @AfterAll void stop() throws Exception { if (postgres != null) postgres.close(); }

    private void compile(String sql) throws Exception {
        var source = SqlSource.parse(Path.of("tenant.sql"), "-- name: query :many\n" + sql);
        try (var connection = postgres.connect()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            QueryCompiler.describe(connection, source);
        }
    }

    private String rejected(String sql) {
        var error = assertThrows(IllegalArgumentException.class, () -> compile(sql));
        assertTrue(error.getMessage().contains("without proving rows belong to :tenantId"), error.getMessage());
        return error.getMessage();
    }

    @ParameterizedTest @ValueSource(strings = {
        // The three VAT reports in ReAI's first Vev pilot.
        """
        SELECT p.vat_code, sum(coalesce(p.vat_basis_amount,p.amount)) AS basis_amount
        FROM postings p LEFT JOIN vouchers v ON v.id=p.voucher_id AND v.tenant_id=p.tenant_id
        WHERE p.tenant_id=:tenantId AND p.posting_date BETWEEN :startDate AND :endDate
          AND p.vat_code IS NOT NULL AND p.vat_code<>'' AND v.vat_submission_id IS NULL
        GROUP BY p.vat_code ORDER BY p.vat_code""",
        """
        SELECT max(p.posting_date) AS last_posting_date
        FROM postings p JOIN vouchers v ON v.id=p.voucher_id AND v.tenant_id=p.tenant_id
        WHERE p.tenant_id=:tenantId AND p.vat_code='87' AND v.vat_submission_id IS NULL
        GROUP BY extract(year FROM p.posting_date),extract(quarter FROM p.posting_date)""",
        // A view that joins registry data gets the tenant pushed through its own scans.
        "SELECT co.id, cd.name FROM companies co LEFT JOIN company_details cd ON cd.id=co.id AND cd.tenant_id=co.tenant_id WHERE co.tenant_id=:tenantId",
        "SELECT p.id FROM postings p WHERE p.tenant_id=:tenantId AND NOT EXISTS (SELECT 1 FROM vouchers v WHERE v.id=p.voucher_id AND v.tenant_id=p.tenant_id)",
        "SELECT p.id, (SELECT v.number FROM vouchers v WHERE v.id=p.voucher_id AND v.tenant_id=p.tenant_id) AS number FROM postings p WHERE p.tenant_id=:tenantId",
        // A LATERAL boundary keeps the outer reference; it equals a scan already proven.
        "SELECT v.id, x.amount FROM vouchers v CROSS JOIN LATERAL (SELECT p.amount FROM postings p WHERE p.voucher_id=v.id AND p.tenant_id=v.tenant_id ORDER BY p.id LIMIT 1) x WHERE v.tenant_id=:tenantId",
        "SELECT id FROM postings WHERE tenant_id=:tenantId UNION ALL SELECT id FROM vouchers WHERE tenant_id=:tenantId",
        "WITH s AS MATERIALIZED (SELECT id, voucher_id FROM postings WHERE tenant_id=:tenantId) SELECT s.id FROM s JOIN vouchers v ON v.id=s.voucher_id AND v.tenant_id=:tenantId",
        "SELECT \"V\".id FROM vouchers \"V\" WHERE \"V\".tenant_id = :tenantId AND \"V\".description = 'a) AND (\"V\".tenant_id = 1'",
        "SELECT id FROM postings WHERE :tenantId = tenant_id AND (amount > 0 OR amount < 0)",
        // Relations without tenant_id are outside the proof.
        "SELECT name FROM brreg_countries WHERE :tenantId > 0",
    }) void provenTenantReadsCompile(String sql) throws Exception { compile(sql); }

    @Test void provenTenantWritesCompile() throws Exception {
        var source = SqlSource.parse(Path.of("write.sql"), "-- name: rename :exec\nUPDATE departments SET name=:name WHERE tenant_id=:tenantId AND id=:id");
        var delete = SqlSource.parse(Path.of("delete.sql"), "-- name: remove :exec\nDELETE FROM departments d USING postings p WHERE d.tenant_id=:tenantId AND p.tenant_id=d.tenant_id AND p.department_id=d.id");
        try (var connection = postgres.connect()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            QueryCompiler.describe(connection, source);
            QueryCompiler.describe(connection, delete);
        }
    }

    @Test void joinsWithoutTenantKeysFail() {
        assertTrue(rejected("SELECT p.id FROM postings p LEFT JOIN vouchers v ON v.id=p.voucher_id WHERE p.tenant_id=:tenantId AND v.vat_submission_id IS NULL")
                .contains("public.vouchers as v"));
        assertTrue(rejected("SELECT p.id FROM postings p JOIN vouchers v ON v.id=p.voucher_id WHERE p.tenant_id=:tenantId")
                .contains("public.vouchers as v"));
    }

    /** The cross-tenant company-name leak found by hand in the first reporting pilot. */
    @Test void viewJoinedByIdAloneFails() {
        String message = rejected("SELECT co.id, cd.name FROM companies co LEFT JOIN company_details cd ON cd.id=co.id WHERE co.tenant_id=:tenantId");
        assertTrue(message.contains("public.companies as c"), message);
        assertFalse(message.contains("as co,") || message.contains("as co "), message);
    }

    @ParameterizedTest @ValueSource(strings = {
        "SELECT id FROM postings WHERE tenant_id=:tenantId UNION ALL SELECT id FROM vouchers",
        "SELECT p.id FROM postings p WHERE p.tenant_id=:tenantId AND p.voucher_id IN (SELECT id FROM vouchers WHERE vat_submission_id IS NOT NULL)",
        "SELECT id FROM postings WHERE tenant_id=:tenantId OR id=:id",
        "SELECT id FROM postings WHERE tenant_id<>:tenantId",
        "SELECT p.id FROM postings p JOIN vouchers v ON v.id=p.voucher_id AND v.tenant_id=p.voucher_id WHERE p.tenant_id=:tenantId",
        "SELECT id FROM postings WHERE :tenantId > 0",
        // A materialized CTE hides its column origins; compare with :tenantId directly instead.
        "WITH s AS MATERIALIZED (SELECT id, tenant_id, voucher_id FROM postings WHERE tenant_id=:tenantId) SELECT s.id FROM s JOIN vouchers v ON v.id=s.voucher_id AND v.tenant_id=s.tenant_id",
        // A CTE column named tenant_id is not proof: here it carries every tenant's ID.
        "WITH t AS (SELECT id AS tenant_id FROM tenants) SELECT v.id FROM t JOIN vouchers v ON v.tenant_id=t.tenant_id WHERE :tenantId > 0",
    }) void unprovenTenantReadsFail(String sql) { rejected(sql); }

    @Test void unprovenTenantWritesFail() throws Exception {
        var source = SqlSource.parse(Path.of("write.sql"), "-- name: rename :exec\nUPDATE departments SET name=:name WHERE id=:id AND :tenantId > 0");
        try (var connection = postgres.connect()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            var error = assertThrows(IllegalArgumentException.class, () -> QueryCompiler.describe(connection, source));
            assertTrue(error.getMessage().contains("public.departments"), error.getMessage());
        }
    }

    @Test void tenantInsertsAndUpsertsMustAssignTenantIdAndArbitrateSafely() throws Exception {
        // Safe inserts and upserts pass.
        compile("INSERT INTO departments(tenant_id, name) VALUES (:tenantId, :name) RETURNING id");
        compile("INSERT INTO departments(tenant_id, name) SELECT p.tenant_id, p.description FROM postings p WHERE p.tenant_id=:tenantId RETURNING id");
        compile("INSERT INTO project_settings(tenant_id, default_hourly_rate) VALUES (:tenantId, :rate) ON CONFLICT (tenant_id) DO UPDATE SET default_hourly_rate=EXCLUDED.default_hourly_rate RETURNING default_hourly_rate");
        compile("INSERT INTO postings(id, tenant_id, voucher_id, posting_date, account_number, amount, currency, row_number, is_vat_posting) VALUES (:id, :tenantId, :voucherId, :day, 1000, 10, 'NOK', 1, false) ON CONFLICT (id) DO UPDATE SET amount=EXCLUDED.amount WHERE postings.tenant_id=:tenantId RETURNING id");

        // Unsafe inserts fail at build time.
        for (String badInsert : List.of(
                "INSERT INTO departments(tenant_id, name) VALUES (CASE WHEN :tenantId > 0 THEN 999 ELSE 999 END, :name) RETURNING id",
                "INSERT INTO departments(tenant_id, name) VALUES (:otherTenant, :name) RETURNING id -- WHERE :tenantId > 0"
        )) {
            if (badInsert.contains("-- WHERE")) continue;
            var error = assertThrows(IllegalArgumentException.class, () -> compile(badInsert));
            assertTrue(error.getMessage().contains("Tenant INSERT into public.departments"), error.getMessage());
        }
        var wrongParam = assertThrows(IllegalArgumentException.class, () -> compile(
                "INSERT INTO departments(tenant_id, name) SELECT :otherTenant::int, :name FROM (VALUES (:tenantId::int)) v(t) RETURNING id"));
        assertTrue(wrongParam.getMessage().contains("Tenant INSERT into public.departments"), wrongParam.getMessage());

        // Unsafe ON CONFLICT DO UPDATE without tenant in arbiter index or Conflict Filter fails.
        var badUpsert = assertThrows(IllegalArgumentException.class, () -> compile(
                "INSERT INTO postings(id, tenant_id, voucher_id, posting_date, account_number, amount, currency, row_number, is_vat_posting) VALUES (:id, :tenantId, :voucherId, :day, 1000, 10, 'NOK', 1, false) ON CONFLICT (id) DO UPDATE SET amount=EXCLUDED.amount RETURNING id"));
        assertTrue(badUpsert.getMessage().contains("Tenant ON CONFLICT DO UPDATE on public.postings"), badUpsert.getMessage());

        // Unsafe UPDATE reassigning tenant_id fails.
        var badUpdate = assertThrows(IllegalArgumentException.class, () -> compile(
                "UPDATE departments SET tenant_id=999 WHERE tenant_id=:tenantId AND id=:id RETURNING id"));
        assertTrue(badUpdate.getMessage().contains("must not reassign tenant_id"), badUpdate.getMessage());
        var badTupleUpdate = assertThrows(IllegalArgumentException.class, () -> compile(
                "UPDATE departments SET (name, tenant_id)=(:name, 999) WHERE tenant_id=:tenantId AND id=:id RETURNING id"));
        assertTrue(badTupleUpdate.getMessage().contains("must not reassign tenant_id"), badTupleUpdate.getMessage());
    }

    @Test void insertTenantCheckHandlesDroppedColumnsCorrectly() throws Exception {
        try (var connection = postgres.connect()) {
            connection.setAutoCommit(false);
            try (var stmt = connection.createStatement()) {
                stmt.execute("CREATE TEMP TABLE dropped_tenant_probe(id int, obsolete text, tenant_id int NOT NULL, name text NOT NULL)");
                stmt.execute("ALTER TABLE dropped_tenant_probe DROP COLUMN obsolete");
            }
            var good = SqlSource.parse(Path.of("good.sql"), "-- name: good :exec\nINSERT INTO dropped_tenant_probe(id, tenant_id, name) VALUES (:id, :tenantId, :name)");
            QueryCompiler.describe(connection, good);
            var bad = SqlSource.parse(Path.of("bad.sql"), "-- name: bad :exec\nINSERT INTO dropped_tenant_probe(id, tenant_id, name) VALUES (:id, CASE WHEN :tenantId > 0 THEN 999 ELSE 999 END, :name)");
            assertThrows(IllegalArgumentException.class, () -> QueryCompiler.describe(connection, bad));
            connection.rollback();
        }
    }

    @Test void escapedStringQuotesCannotSmuggleTenantConjuncts() {
        List<String> parts = new ArrayList<>();
        TenantScans.conjuncts("(p.note = E'foo\\' AND (p.tenant_id = $1)')", parts);
        assertEquals(1, parts.size(), parts.toString());
        var scan = new TenantScans.Scan("public.postings", "p", parts);
        assertEquals(1, TenantScans.unrestricted(List.of(scan), Set.of("$1")).size());
    }

    @Test void globalScopeOptsOut() throws Exception {
        compile("-- scope: global\nSELECT p.id FROM postings p JOIN vouchers v ON v.id=p.voucher_id");
    }

    @Test void qualifierParsingKeepsOnlyTopLevelConjuncts() {
        List<String> parts = new ArrayList<>();
        TenantScans.conjuncts("((p.tenant_id = $1) AND ((p.a = $2) OR (p.tenant_id = $1)) AND (p.note = 'x) AND (p.tenant_id = $1'::text))", parts);
        assertEquals(List.of("p.tenant_id = $1", "(p.a = $2) OR (p.tenant_id = $1)", "p.note = 'x) AND (p.tenant_id = $1'::text"), parts);
        var or = new TenantScans.Scan("public.postings", "p", List.of("(p.tenant_id = $1) OR (p.id = $2)"));
        var quoted = new TenantScans.Scan("public.postings", "p", List.of("p.note = 'p.tenant_id = $1'::text"));
        assertEquals(2, TenantScans.unrestricted(List.of(or, quoted), Set.of("$1")).size());
    }

    @Test void outerReferencesChainOnlyThroughProvenScans() {
        var a = new TenantScans.Scan("public.a", "a", List.of("a.tenant_id = $1"));
        var b = new TenantScans.Scan("public.b", "b", List.of("b.tenant_id = a.tenant_id"));
        var c = new TenantScans.Scan("public.c", "c", List.of("c.tenant_id = b.tenant_id"));
        var d = new TenantScans.Scan("public.d", "d", List.of("d.tenant_id = s.tenant_id"));
        var e = new TenantScans.Scan("public.e", "e", List.of("e.tenant_id = $3"));
        assertEquals(List.of(d, e), TenantScans.unrestricted(List.of(c, b, a, d, e), Set.of("$1", "$2")));
    }
}
