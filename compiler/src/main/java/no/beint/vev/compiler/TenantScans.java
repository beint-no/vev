package no.beint.vev.compiler;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks tenant restrictions on relation scans visible in PostgreSQL's generic plan.
 *
 * <p>Every scan of a relation with a {@code tenant_id} column must carry a top-level
 * {@code alias.tenant_id = $n} condition, where {@code $n} is a {@code :tenantId} bind, or
 * equal the {@code tenant_id} of another scan already proven that way. PostgreSQL derives
 * those conditions through inner joins, outer joins, views and subqueries when the SQL joins
 * on tenant keys, so a join, view or subquery that matches rows by ID alone fails the build.
 * Relations without {@code tenant_id}, opaque function bodies and values assigned by mutations
 * are outside this check.
 */
final class TenantScans {
    private TenantScans() {}

    record Scan(String relation, String alias, List<String> conjuncts) {}

    private static final String SCANS = """
            SELECT quote_ident(n->>'Schema') || '.' || quote_ident(n->>'Relation Name'), quote_ident(n->>'Alias'),
                   n->>'Index Cond', n->>'Recheck Cond', n->>'Filter'
            FROM jsonb_path_query(?::jsonb, 'strict $.**') n
            WHERE jsonb_typeof(n) = 'object' AND n->>'Relation Name' IS NOT NULL AND n->>'Node Type' <> 'ModifyTable'
              AND EXISTS (SELECT 1 FROM pg_attribute a
                          WHERE a.attrelid = to_regclass(quote_ident(n->>'Schema') || '.' || quote_ident(n->>'Relation Name'))
                            AND a.attname = 'tenant_id' AND a.attnum > 0 AND NOT a.attisdropped)
            """;

    static void require(Connection connection, SqlSource source) throws SQLException {
        if (source.tenant() == null) return;
        String plan;
        // The simple protocol leaves $n to EXPLAIN; the extended protocol would expect bound values.
        try (var statement = connection.createStatement().unwrap(org.postgresql.core.BaseStatement.class)) {
            statement.executeWithFlags("EXPLAIN (GENERIC_PLAN, VERBOSE, COSTS OFF, FORMAT JSON) " + source.positionalSql(),
                    org.postgresql.core.QueryExecutor.QUERY_EXECUTE_AS_SIMPLE);
            try (var result = statement.getResultSet()) {
                result.next();
                plan = result.getString(1);
            }
        }
        List<Scan> scans = new ArrayList<>();
        try (var statement = connection.prepareStatement(SCANS)) {
            statement.setString(1, plan);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    List<String> conjuncts = new ArrayList<>();
                    for (int i = 3; i <= 5; i++) if (result.getString(i) != null) conjuncts(result.getString(i), conjuncts);
                    scans.add(new Scan(result.getString(1), result.getString(2), conjuncts));
                }
            }
        }
        Set<String> tenant = new HashSet<>();
        for (int i = 0; i < source.parameters().size(); i++) {
            if (source.parameters().get(i).equals(source.tenant())) tenant.add("$" + (i + 1));
        }
        List<Scan> unrestricted = unrestricted(scans, tenant);
        if (unrestricted.isEmpty()) return;
        throw SqlSource.error(source.path(), "Tenant query reads "
                + String.join(", ", unrestricted.stream().map(scan -> scan.relation + " as " + scan.alias).toList())
                + " without proving rows belong to :tenantId. PostgreSQL's plan must show alias.tenant_id = :tenantId"
                + " on each scan; join on tenant keys, compare with :tenantId directly where a materialized CTE,"
                + " LATERAL or subquery boundary hides it, or declare -- vev.scope: global for cross-tenant SQL");
    }

    /**
     * Scans not proven to belong to the bound tenant. EXPLAIN names every relation in a plan
     * uniquely, so an outer reference such as {@code v.tenant_id} identifies exactly one scan.
     */
    static List<Scan> unrestricted(List<Scan> scans, Set<String> tenantValues) {
        Set<String> restricted = new HashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Scan scan : scans) {
                if (restricted.contains(scan.alias)) continue;
                String column = scan.alias + ".tenant_id";
                for (String conjunct : scan.conjuncts) {
                    String[] sides = equality(conjunct);
                    if (sides == null) continue;
                    String other = sides[0].equals(column) ? sides[1] : sides[1].equals(column) ? sides[0] : null;
                    if (other == null) continue;
                    if (tenantValues.contains(other) || other.endsWith(".tenant_id")
                            && restricted.contains(other.substring(0, other.length() - ".tenant_id".length()))) {
                        restricted.add(scan.alias);
                        changed = true;
                        break;
                    }
                }
            }
        }
        return scans.stream().filter(scan -> !restricted.contains(scan.alias)).toList();
    }

    /** Top-level AND operands of a deparsed qualifier; OR, NOT and nested expressions stay whole. */
    static void conjuncts(String expression, List<String> out) {
        String e = unwrap(expression);
        List<String> parts = split(e, " AND ");
        if (parts.size() == 1) out.add(e);
        else for (String part : parts) conjuncts(part, out);
    }

    /** The two operands of a top-level {@code =}, or null. */
    static String[] equality(String conjunct) {
        List<String> sides = split(conjunct, " = ");
        return sides.size() == 2 ? new String[] {unwrap(sides.get(0)), unwrap(sides.get(1))} : null;
    }

    private static String unwrap(String expression) {
        String e = expression.strip();
        while (e.startsWith("(") && close(e, 0) == e.length() - 1) e = e.substring(1, e.length() - 1).strip();
        return e;
    }

    /** Index of the parenthesis closing the one at {@code open}, skipping quoted text. */
    private static int close(String e, int open) {
        int depth = 0;
        for (int i = open; i < e.length(); i++) {
            char c = e.charAt(i);
            if (c == '\'' || c == '"') { i = endQuote(e, i); continue; }
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    private static List<String> split(String e, String separator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < e.length(); i++) {
            char c = e.charAt(i);
            if (c == '\'' || c == '"') { i = endQuote(e, i); continue; }
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            else if (depth == 0 && e.startsWith(separator, i)) {
                parts.add(e.substring(start, i));
                start = i + separator.length();
                i = start - 1;
            }
        }
        parts.add(e.substring(start));
        return parts;
    }

    private static int endQuote(String e, int open) {
        char quote = e.charAt(open);
        for (int i = open + 1; i < e.length(); i++) {
            if (e.charAt(i) == quote) {
                if (i + 1 < e.length() && e.charAt(i + 1) == quote) { i++; continue; }
                return i;
            }
        }
        return e.length() - 1;
    }
}
