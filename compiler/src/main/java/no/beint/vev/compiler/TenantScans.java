package no.beint.vev.compiler;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks tenant restrictions on relation scans and mutations visible in PostgreSQL's generic plan.
 *
 * <p>Every scan of a relation with a {@code tenant_id} column must carry a top-level
 * {@code alias.tenant_id = $n} condition, where {@code $n} is a {@code :tenantId} bind, or
 * equal the {@code tenant_id} of another scan already proven that way. For mutations on tables
 * with {@code tenant_id}, {@code INSERT} must assign {@code :tenantId} (or a proven scan's
 * {@code tenant_id}), {@code ON CONFLICT DO UPDATE} must arbitrate on a tenant-keyed index or
 * filter {@code tenant_id = :tenantId}, and {@code UPDATE}/{@code MERGE} must not reassign
 * {@code tenant_id}. Relations without {@code tenant_id}, triggers and opaque function bodies
 * remain outside this check.
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

    private static final String MODIFIES = """
            SELECT quote_ident(n->>'Schema') || '.' || quote_ident(n->>'Relation Name'),
                   quote_ident(n->>'Alias'),
                   n->>'Operation',
                   n->>'Conflict Resolution',
                   n->>'Conflict Filter',
                   COALESCE(n->'Conflict Arbiter Indexes', '[]'::jsonb)::text,
                   (SELECT p->'Output'->>(a.attnum - 1)
                    FROM jsonb_array_elements(COALESCE(n->'Plans', '[]'::jsonb)) p
                    WHERE p->>'Parent Relationship' = 'Outer'
                    LIMIT 1),
                   (SELECT bool_and(EXISTS (
                       SELECT 1
                       FROM pg_class ic
                       JOIN pg_namespace ins ON ins.oid = ic.relnamespace
                       JOIN pg_index ix ON ix.indexrelid = ic.oid
                       WHERE ins.nspname = n->>'Schema'
                         AND ic.relname = idx
                         AND a.attnum = ANY(ix.indkey::smallint[])
                    ))
                    FROM jsonb_array_elements_text(COALESCE(n->'Conflict Arbiter Indexes', '[]'::jsonb)) idx)
            FROM jsonb_path_query(?::jsonb, 'strict $.**') n
            JOIN pg_attribute a
              ON a.attrelid = to_regclass(quote_ident(n->>'Schema') || '.' || quote_ident(n->>'Relation Name'))
             AND a.attname = 'tenant_id' AND a.attnum > 0 AND NOT a.attisdropped
            WHERE jsonb_typeof(n) = 'object'
              AND n->>'Node Type' = 'ModifyTable'
              AND n->>'Relation Name' IS NOT NULL
            """;

    private static final String HAS_WRITE_OR_LOCK = """
            SELECT jsonb_path_exists(
                ?::jsonb,
                'strict $.** ? (@."Node Type" == "ModifyTable" || @."Node Type" == "LockRows")'
            )
            """;

    /**
     * Proves tenant scan/mutation restrictions for tenant queries and classifies whether the query
     * writes or acquires row/advisory locks (requiring a {@code WriteScope} / {@code TenantWriteScope}).
     */
    static boolean analyze(Connection connection, SqlSource source) throws SQLException {
        boolean writes = Set.of("exec", "void").contains(source.cardinality())
                || source.forceWrite()
                || source.hasMutatingOrLockingSyntax();
        if (source.tenant() == null && writes) return true;
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
        if (!writes) {
            try (var statement = connection.prepareStatement(HAS_WRITE_OR_LOCK)) {
                statement.setString(1, plan);
                try (var result = statement.executeQuery()) {
                    if (result.next() && result.getBoolean(1)) writes = true;
                }
            }
        }
        if (source.tenant() == null) return writes;
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
        Set<String> restricted = restrictedAliases(scans, tenant);
        List<Scan> unrestricted = scans.stream().filter(scan -> !restricted.contains(scan.alias)).toList();
        if (!unrestricted.isEmpty()) {
            throw SqlSource.error(source.path(), "Tenant query reads "
                    + String.join(", ", unrestricted.stream().map(scan -> scan.relation + " as " + scan.alias).toList())
                    + " without proving rows belong to :tenantId. PostgreSQL's plan must show alias.tenant_id = :tenantId"
                    + " on each scan; join on tenant keys, compare with :tenantId directly where a materialized CTE,"
                    + " LATERAL or subquery boundary hides it, or declare -- vev.scope: global for cross-tenant SQL");
        }
        try (var statement = connection.prepareStatement(MODIFIES)) {
            statement.setString(1, plan);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    String relation = result.getString(1);
                    String alias = result.getString(2);
                    String operation = result.getString(3);
                    String conflictResolution = result.getString(4);
                    String conflictFilter = result.getString(5);
                    String arbiters = result.getString(6);
                    String insertedExpr = result.getString(7);
                    Boolean arbitersIncludeTenant = result.getObject(8, Boolean.class);
                    if ("Insert".equals(operation)) {
                        if (insertedExpr == null || !isTenantValue(insertedExpr, tenant, restricted)) {
                            throw SqlSource.error(source.path(), "Tenant INSERT into " + relation
                                    + " must assign :tenantId (or a proven scan's tenant_id) to tenant_id; found "
                                    + insertedExpr + ". Use SELECT :tenantId, ... or -- vev.scope: global");
                        }
                    }
                    if ("UPDATE".equals(conflictResolution)) {
                        boolean safeArbiter = arbiters != null && !arbiters.equals("[]") && Boolean.TRUE.equals(arbitersIncludeTenant);
                        boolean safeFilter = false;
                        if (conflictFilter != null) {
                            List<String> filterConjuncts = new ArrayList<>();
                            conjuncts(conflictFilter, filterConjuncts);
                            safeFilter = hasTenantEquality(alias + ".tenant_id", filterConjuncts, tenant, restricted);
                        }
                        if (!safeArbiter && !safeFilter) {
                            throw SqlSource.error(source.path(), "Tenant ON CONFLICT DO UPDATE on " + relation
                                    + " must use a conflict index containing tenant_id or filter "
                                    + alias + ".tenant_id = :tenantId");
                        }
                    }
                    if (("Update".equals(operation) || "Merge".equals(operation) || "UPDATE".equals(conflictResolution))
                            && reassignsTenantId(source.tokens())) {
                        throw SqlSource.error(source.path(), "Tenant mutation on " + relation
                                + " must not reassign tenant_id; use -- vev.scope: global for cross-tenant changes");
                    }
                }
            }
        }
        return writes;
    }

    /**
     * Scans not proven to belong to the bound tenant. EXPLAIN names every relation in a plan
     * uniquely, so an outer reference such as {@code v.tenant_id} identifies exactly one scan.
     */
    static List<Scan> unrestricted(List<Scan> scans, Set<String> tenantValues) {
        Set<String> restricted = restrictedAliases(scans, tenantValues);
        return scans.stream().filter(scan -> !restricted.contains(scan.alias)).toList();
    }

    private static Set<String> restrictedAliases(List<Scan> scans, Set<String> tenantValues) {
        Set<String> restricted = new HashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Scan scan : scans) {
                if (restricted.contains(scan.alias)) continue;
                if (hasTenantEquality(scan.alias + ".tenant_id", scan.conjuncts, tenantValues, restricted)) {
                    restricted.add(scan.alias);
                    changed = true;
                }
            }
        }
        return restricted;
    }

    private static boolean hasTenantEquality(String column, List<String> conjuncts, Set<String> tenantValues, Set<String> restricted) {
        for (String conjunct : conjuncts) {
            String[] sides = equality(conjunct);
            if (sides == null) continue;
            String other = sides[0].equals(column) ? sides[1] : sides[1].equals(column) ? sides[0] : null;
            if (other != null && isTenantValue(other, tenantValues, restricted)) return true;
        }
        return false;
    }

    private static boolean isTenantValue(String expression, Set<String> tenantValues, Set<String> restricted) {
        String value = stripIntCast(unwrap(expression));
        return tenantValues.contains(value)
                || value.endsWith(".tenant_id")
                && restricted.contains(value.substring(0, value.length() - ".tenant_id".length()));
    }

    private static String stripIntCast(String value) {
        if (value.endsWith("::integer")) return unwrap(value.substring(0, value.length() - "::integer".length()));
        if (value.endsWith("::int4")) return unwrap(value.substring(0, value.length() - "::int4".length()));
        return value;
    }

    private static final Set<String> SET_END = Set.of("WHERE", "FROM", "RETURNING", "WHEN", "ON");

    /** Detects a SET clause targeting tenant_id (either {@code SET tenant_id =} or {@code SET (..., tenant_id) =}). */
    static boolean reassignsTenantId(List<String> tokens) {
        boolean inSet = false;
        int setDepth = 0;
        int depth = 0;
        boolean expectTarget = false;
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.equals("(")) {
                if (inSet && depth == setDepth && expectTarget) {
                    int end = i + 1;
                    int tupleDepth = 1;
                    while (end < tokens.size() && tupleDepth > 0) {
                        String inner = tokens.get(end);
                        if (inner.equals("(")) tupleDepth++;
                        else if (inner.equals(")")) tupleDepth--;
                        else if (tupleDepth == 1 && inner.equals("TENANT_ID")) return true;
                        end++;
                    }
                }
                depth++;
                continue;
            }
            if (token.equals(")")) {
                depth--;
                if (inSet && depth < setDepth) inSet = false;
                continue;
            }
            if (token.equals("SET")) {
                inSet = true;
                setDepth = depth;
                expectTarget = true;
                continue;
            }
            if (!inSet || depth != setDepth) continue;
            if (SET_END.contains(token)) {
                inSet = false;
                continue;
            }
            if (expectTarget && token.equals("TENANT_ID") && i + 1 < tokens.size() && tokens.get(i + 1).equals("=")) {
                return true;
            }
            if (token.equals(",")) expectTarget = true;
            else if (token.equals("=")) expectTarget = false;
        }
        return false;
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
        boolean escaped = quote == '\'' && open > 0 && (e.charAt(open - 1) == 'E' || e.charAt(open - 1) == 'e')
                && (open < 2 || !Character.isJavaIdentifierPart(e.charAt(open - 2)));
        for (int i = open + 1; i < e.length(); i++) {
            if (escaped && e.charAt(i) == '\\') { i++; continue; }
            if (e.charAt(i) == quote) {
                if (i + 1 < e.length() && e.charAt(i + 1) == quote) { i++; continue; }
                return i;
            }
        }
        return e.length() - 1;
    }
}
