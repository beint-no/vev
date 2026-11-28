package no.beint.vev.compiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Verifies that every migrated table in the checked schemas is reachable from at least one compiled Vev query,
 * database view, materialized view, trigger, or user-defined function invoked by those queries.
 */
public final class TableUsage {
    private TableUsage() {}

    private static final Set<String> MIGRATION_HISTORY_TABLES = Set.of("flyway_schema_history", "pgway_history");
    private static final Set<String> CLAUSE_END = Set.of(
            "WHERE", "SET", "GROUP", "ORDER", "HAVING", "LIMIT", "OFFSET", "FETCH",
            "UNION", "INTERSECT", "EXCEPT", "ON", "USING", "VALUES", "SELECT", "RETURNING",
            "RESTART", "CONTINUE", "CASCADE", "RESTRICT", "WINDOW", "FOR", "CROSS",
            "INNER", "LEFT", "RIGHT", "FULL", "NATURAL", "JOIN", "LATERAL", "WITH");

    public record Relation(String schema, String name) implements Comparable<Relation> {
        private static final Comparator<Relation> ORDER =
                Comparator.comparing(Relation::schema).thenComparing(Relation::name);

        public String qualified() {
            return schema + "." + name;
        }

        @Override public int compareTo(Relation other) {
            return ORDER.compare(this, other);
        }

        /** Parses {@code table} (defaulting to {@code public}) or {@code schema.table}. */
        public static Relation parse(String spec) {
            if (spec == null || spec.isBlank()) {
                throw new IllegalArgumentException("Table name must not be blank");
            }
            String trimmed = spec.strip();
            int dot = trimmed.indexOf('.');
            if (dot < 0) return new Relation("public", trimmed);
            if (dot == 0 || dot == trimmed.length() - 1 || trimmed.indexOf('.', dot + 1) >= 0) {
                throw new IllegalArgumentException("Use table or schema.table, not: " + spec);
            }
            return new Relation(trimmed.substring(0, dot).strip(), trimmed.substring(dot + 1).strip());
        }
    }

    public record FunctionRef(String schema, String name) implements Comparable<FunctionRef> {
        private static final Comparator<FunctionRef> ORDER =
                Comparator.comparing(FunctionRef::schema).thenComparing(FunctionRef::name);

        @Override public int compareTo(FunctionRef other) {
            return ORDER.compare(this, other);
        }
    }

    public record Manifest(Set<Relation> relations, Set<FunctionRef> functions) {}

    public record Result(
            Set<Relation> catalogTables,
            Set<Relation> usedTables,
            Set<Relation> ignoredTables,
            Set<Relation> unusedTables) {}

    private record SqlToken(String text, boolean identifier, boolean quoted) {
        String keyword() {
            return !quoted && identifier ? text.toUpperCase(Locale.ROOT) : "";
        }
    }

    private record CatalogRelation(long oid, Relation relation, String relkind, boolean partition, boolean extensionOwned) {}

    private record CatalogFunction(long oid, FunctionRef ref, String source) {}

    static void writeManifest(Path path, Set<Relation> relations, Set<FunctionRef> functions) throws IOException {
        var sortedRelations = new TreeSet<>(relations);
        var sortedFunctions = new TreeSet<>(functions);
        var out = new StringBuilder();
        for (Relation relation : sortedRelations) {
            out.append("relation\t").append(relation.schema()).append('\t').append(relation.name()).append('\n');
        }
        for (FunctionRef function : sortedFunctions) {
            out.append("function\t").append(function.schema()).append('\t').append(function.name()).append('\n');
        }
        String content = out.toString();
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        if (!Files.exists(path) || !Files.readString(path, StandardCharsets.UTF_8).equals(content)) {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        }
    }

    public static Manifest readManifest(Path path) throws IOException {
        Set<Relation> relations = new TreeSet<>();
        Set<FunctionRef> functions = new TreeSet<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] parts = line.split("\t", -1);
            if (parts.length != 3) throw new IllegalArgumentException("Malformed Vev relations manifest line in " + path + ": " + line);
            switch (parts[0]) {
                case "relation" -> relations.add(new Relation(parts[1], parts[2]));
                case "function" -> functions.add(new FunctionRef(parts[1], parts[2]));
                default -> throw new IllegalArgumentException("Unknown Vev relations manifest entry in " + path + ": " + line);
            }
        }
        return new Manifest(Collections.unmodifiableSet(relations), Collections.unmodifiableSet(functions));
    }

    static Set<FunctionRef> userFunctions(Connection connection) throws SQLException {
        Set<FunctionRef> functions = new TreeSet<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT DISTINCT n.nspname, p.proname
                FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
                  AND n.nspname NOT LIKE 'pg_toast%'
                  AND NOT EXISTS (
                      SELECT 1 FROM pg_depend d
                      WHERE d.classid = 'pg_proc'::regclass
                        AND d.objid = p.oid
                        AND d.deptype = 'e'
                  )
                """)) {
            while (rows.next()) functions.add(new FunctionRef(rows.getString(1), rows.getString(2)));
        }
        return functions;
    }

    static Set<Relation> emptyPartitionedTables(Connection connection) throws SQLException {
        Set<Relation> tables = new TreeSet<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT n.nspname, c.relname
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE c.relkind = 'p'
                  AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                  AND n.nspname NOT LIKE 'pg_toast%'
                  AND NOT EXISTS (SELECT 1 FROM pg_inherits i WHERE i.inhparent = c.oid)
                """)) {
            while (rows.next()) tables.add(new Relation(rows.getString(1), rows.getString(2)));
        }
        return tables;
    }

    static Set<FunctionRef> referencedFunctions(String sql, Set<FunctionRef> catalogFunctions) {
        if (catalogFunctions.isEmpty()) return Set.of();
        Map<String, List<FunctionRef>> byName = new HashMap<>();
        for (FunctionRef ref : catalogFunctions) {
            byName.computeIfAbsent(ref.name(), ignored -> new ArrayList<>()).add(ref);
        }
        return matchFunctionCalls(lexSql(sql), catalogFunctions, byName, null);
    }

    static Set<Relation> referencedRelationsInSql(String sql, Set<Relation> candidates) {
        if (candidates.isEmpty()) return Set.of();
        Map<String, List<Relation>> byName = new HashMap<>();
        for (Relation rel : candidates) {
            byName.computeIfAbsent(rel.name(), ignored -> new ArrayList<>()).add(rel);
        }
        return matchRelationReferences(lexSql(sql), candidates, byName, null);
    }

    public static Result verify(
            Connection connection,
            List<Path> manifestFiles,
            Set<String> schemas,
            Set<String> ignoredTableSpecs) throws SQLException, IOException {
        if (connection.getMetaData().getDatabaseMajorVersion() != 19
                || !connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")) {
            throw new IllegalArgumentException("Vev compiler requires PostgreSQL 19");
        }
        if (schemas == null || schemas.isEmpty()) {
            throw new IllegalArgumentException("At least one schema must be configured for verifyVevTableUsage");
        }
        Set<String> checkedSchemas = new TreeSet<>();
        for (String schema : schemas) {
            if (schema == null || schema.isBlank()) throw new IllegalArgumentException("Schema name must not be blank");
            checkedSchemas.add(schema.strip());
        }

        Set<Relation> seedRelations = new TreeSet<>();
        Set<FunctionRef> seedFunctions = new TreeSet<>();
        for (Path manifestFile : manifestFiles) {
            Manifest manifest = readManifest(manifestFile);
            seedRelations.addAll(manifest.relations());
            seedFunctions.addAll(manifest.functions());
        }

        Map<Long, CatalogRelation> relationsByOid = new HashMap<>();
        Map<Relation, Long> oidByRelation = new HashMap<>();
        Map<String, List<Relation>> relationsByName = new HashMap<>();
        Set<Relation> catalogTables = new TreeSet<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT c.oid, n.nspname, c.relname, c.relkind::text, c.relispartition,
                       EXISTS (
                           SELECT 1 FROM pg_depend d
                           WHERE d.classid = 'pg_class'::regclass
                             AND d.objid = c.oid
                             AND d.deptype = 'e'
                       )
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
                  AND n.nspname NOT LIKE 'pg_toast%'
                  AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
                """)) {
            while (rows.next()) {
                long oid = rows.getLong(1);
                Relation relation = new Relation(rows.getString(2), rows.getString(3));
                String relkind = rows.getString(4);
                boolean partition = rows.getBoolean(5);
                boolean extensionOwned = rows.getBoolean(6);
                CatalogRelation entry = new CatalogRelation(oid, relation, relkind, partition, extensionOwned);
                relationsByOid.put(oid, entry);
                oidByRelation.put(relation, oid);
                relationsByName.computeIfAbsent(relation.name(), ignored -> new ArrayList<>()).add(relation);
                if (checkedSchemas.contains(relation.schema())
                        && (relkind.equals("r") || relkind.equals("p"))
                        && !partition
                        && !extensionOwned
                        && !MIGRATION_HISTORY_TABLES.contains(relation.name())) {
                    catalogTables.add(relation);
                }
            }
        }

        Set<Relation> ignoredTables = new TreeSet<>();
        List<String> unknownIgnored = new ArrayList<>();
        if (ignoredTableSpecs != null) {
            for (String spec : ignoredTableSpecs) {
                Relation parsed = Relation.parse(spec);
                if (!catalogTables.contains(parsed)) unknownIgnored.add(parsed.qualified());
                else ignoredTables.add(parsed);
            }
        }
        if (!unknownIgnored.isEmpty()) {
            Collections.sort(unknownIgnored);
            throw new IllegalArgumentException("ignoredTables names table(s) not found in checked schemas "
                    + checkedSchemas + ": " + String.join(", ", unknownIgnored));
        }

        Map<Long, Set<Long>> relationEdges = new HashMap<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT inhrelid, inhparent FROM pg_inherits
                """)) {
            while (rows.next()) {
                long child = rows.getLong(1);
                long parent = rows.getLong(2);
                relationEdges.computeIfAbsent(child, ignored -> new HashSet<>()).add(parent);
                relationEdges.computeIfAbsent(parent, ignored -> new HashSet<>()).add(child);
            }
        }
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT DISTINCT r.ev_class, d.refobjid
                FROM pg_rewrite r
                JOIN pg_depend d ON d.classid = 'pg_rewrite'::regclass AND d.objid = r.oid
                WHERE d.refclassid = 'pg_class'::regclass
                  AND d.refobjid <> r.ev_class
                """)) {
            while (rows.next()) {
                relationEdges.computeIfAbsent(rows.getLong(1), ignored -> new HashSet<>()).add(rows.getLong(2));
            }
        }

        Map<Long, Set<Long>> triggerFunctions = new HashMap<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT tgrelid, tgfoid FROM pg_trigger WHERE NOT tgisinternal
                """)) {
            while (rows.next()) {
                triggerFunctions.computeIfAbsent(rows.getLong(1), ignored -> new HashSet<>()).add(rows.getLong(2));
            }
        }

        Map<Long, CatalogFunction> functionsByOid = new HashMap<>();
        Map<FunctionRef, List<Long>> oidsByFunction = new HashMap<>();
        Set<FunctionRef> allUserFunctions = new HashSet<>();
        Map<String, List<FunctionRef>> functionsByName = new HashMap<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT p.oid, n.nspname, p.proname, COALESCE(p.prosrc, '')
                FROM pg_proc p
                JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
                  AND n.nspname NOT LIKE 'pg_toast%'
                  AND NOT EXISTS (
                      SELECT 1 FROM pg_depend d
                      WHERE d.classid = 'pg_proc'::regclass
                        AND d.objid = p.oid
                        AND d.deptype = 'e'
                  )
                """)) {
            while (rows.next()) {
                long oid = rows.getLong(1);
                FunctionRef ref = new FunctionRef(rows.getString(2), rows.getString(3));
                CatalogFunction fn = new CatalogFunction(oid, ref, rows.getString(4));
                functionsByOid.put(oid, fn);
                oidsByFunction.computeIfAbsent(ref, ignored -> new ArrayList<>()).add(oid);
                if (allUserFunctions.add(ref)) {
                    functionsByName.computeIfAbsent(ref.name(), ignored -> new ArrayList<>()).add(ref);
                }
            }
        }

        Map<Long, Set<Long>> functionRelationDeps = new HashMap<>();
        Map<Long, Set<Long>> functionFunctionDeps = new HashMap<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                SELECT d.objid, d.refclassid = 'pg_class'::regclass, d.refobjid
                FROM pg_depend d
                WHERE d.classid = 'pg_proc'::regclass
                  AND d.refclassid IN ('pg_class'::regclass, 'pg_proc'::regclass)
                """)) {
            while (rows.next()) {
                long fnOid = rows.getLong(1);
                boolean isClass = rows.getBoolean(2);
                long refOid = rows.getLong(3);
                if (isClass) functionRelationDeps.computeIfAbsent(fnOid, ignored -> new HashSet<>()).add(refOid);
                else if (refOid != fnOid) functionFunctionDeps.computeIfAbsent(fnOid, ignored -> new HashSet<>()).add(refOid);
            }
        }

        // Phase 1: Fixpoint reachability from compiled Vev queries/functions only.
        Set<Long> visitedRelations = new HashSet<>();
        Set<Long> visitedFunctions = new HashSet<>();
        Deque<Long> relationQueue = new ArrayDeque<>();
        Deque<Long> functionQueue = new ArrayDeque<>();

        for (Relation rel : seedRelations) {
            Long oid = oidByRelation.get(rel);
            if (oid != null && visitedRelations.add(oid)) relationQueue.add(oid);
        }
        for (FunctionRef ref : seedFunctions) {
            for (Long oid : oidsByFunction.getOrDefault(ref, List.of())) {
                if (visitedFunctions.add(oid)) functionQueue.add(oid);
            }
        }

        Set<Relation> allCatalogRelations = oidByRelation.keySet();
        expandReachability(relationQueue, functionQueue, visitedRelations, visitedFunctions,
                relationEdges, relationsByOid, triggerFunctions, functionsByOid,
                functionRelationDeps, functionFunctionDeps,
                allCatalogRelations, relationsByName, allUserFunctions, functionsByName,
                oidByRelation, oidsByFunction);

        // Reject stale ignoredTables entries that are already reachable from Vev queries so allowlists only shrink.
        List<String> staleIgnored = new ArrayList<>();
        for (Relation rel : ignoredTables) {
            Long oid = oidByRelation.get(rel);
            if (oid != null && visitedRelations.contains(oid)) staleIgnored.add(rel.qualified());
        }
        if (!staleIgnored.isEmpty()) {
            Collections.sort(staleIgnored);
            throw new IllegalArgumentException("ignoredTables names table(s) already referenced by Vev queries; "
                    + "remove them from ignoredTables: " + String.join(", ", staleIgnored));
        }

        // Phase 2: Expand from genuinely ignored tables so their triggers/views/functions mark helper tables as used.
        for (Relation rel : ignoredTables) {
            Long oid = oidByRelation.get(rel);
            if (oid != null && visitedRelations.add(oid)) relationQueue.add(oid);
        }
        expandReachability(relationQueue, functionQueue, visitedRelations, visitedFunctions,
                relationEdges, relationsByOid, triggerFunctions, functionsByOid,
                functionRelationDeps, functionFunctionDeps,
                allCatalogRelations, relationsByName, allUserFunctions, functionsByName,
                oidByRelation, oidsByFunction);

        Set<Relation> usedTables = new TreeSet<>();
        Set<Relation> unusedTables = new TreeSet<>();
        for (Relation table : catalogTables) {
            if (ignoredTables.contains(table)) continue;
            Long oid = oidByRelation.get(table);
            if (oid != null && visitedRelations.contains(oid)) usedTables.add(table);
            else unusedTables.add(table);
        }
        return new Result(
                Collections.unmodifiableSet(catalogTables),
                Collections.unmodifiableSet(usedTables),
                Collections.unmodifiableSet(ignoredTables),
                Collections.unmodifiableSet(unusedTables));
    }

    private static void expandReachability(
            Deque<Long> relationQueue,
            Deque<Long> functionQueue,
            Set<Long> visitedRelations,
            Set<Long> visitedFunctions,
            Map<Long, Set<Long>> relationEdges,
            Map<Long, CatalogRelation> relationsByOid,
            Map<Long, Set<Long>> triggerFunctions,
            Map<Long, CatalogFunction> functionsByOid,
            Map<Long, Set<Long>> functionRelationDeps,
            Map<Long, Set<Long>> functionFunctionDeps,
            Set<Relation> allCatalogRelations,
            Map<String, List<Relation>> relationsByName,
            Set<FunctionRef> allUserFunctions,
            Map<String, List<FunctionRef>> functionsByName,
            Map<Relation, Long> oidByRelation,
            Map<FunctionRef, List<Long>> oidsByFunction) {
        while (!relationQueue.isEmpty() || !functionQueue.isEmpty()) {
            while (!relationQueue.isEmpty()) {
                long relOid = relationQueue.removeFirst();
                for (long nextRel : relationEdges.getOrDefault(relOid, Set.of())) {
                    if (relationsByOid.containsKey(nextRel) && visitedRelations.add(nextRel)) {
                        relationQueue.add(nextRel);
                    }
                }
                for (long fnOid : triggerFunctions.getOrDefault(relOid, Set.of())) {
                    if (functionsByOid.containsKey(fnOid) && visitedFunctions.add(fnOid)) {
                        functionQueue.add(fnOid);
                    }
                }
            }
            while (!functionQueue.isEmpty()) {
                long fnOid = functionQueue.removeFirst();
                for (long depRel : functionRelationDeps.getOrDefault(fnOid, Set.of())) {
                    if (relationsByOid.containsKey(depRel) && visitedRelations.add(depRel)) {
                        relationQueue.add(depRel);
                    }
                }
                for (long depFn : functionFunctionDeps.getOrDefault(fnOid, Set.of())) {
                    if (functionsByOid.containsKey(depFn) && visitedFunctions.add(depFn)) {
                        functionQueue.add(depFn);
                    }
                }
                CatalogFunction fn = functionsByOid.get(fnOid);
                if (fn != null && !fn.source().isBlank()) {
                    List<SqlToken> tokens = lexSql(fn.source());
                    for (Relation rel : matchRelationReferences(tokens, allCatalogRelations, relationsByName, fn.ref().schema())) {
                        Long oid = oidByRelation.get(rel);
                        if (oid != null && visitedRelations.add(oid)) relationQueue.add(oid);
                    }
                    for (FunctionRef called : matchFunctionCalls(tokens, allUserFunctions, functionsByName, fn.ref().schema())) {
                        for (Long oid : oidsByFunction.getOrDefault(called, List.of())) {
                            if (visitedFunctions.add(oid)) functionQueue.add(oid);
                        }
                    }
                }
            }
        }
    }

    public static String formatReport(Result result) {
        return "tables=" + result.catalogTables().size()
                + "\nused=" + result.usedTables().size()
                + "\nignored=" + result.ignoredTables().size()
                + "\nunused=" + String.join(",", result.unusedTables().stream().map(Relation::qualified).toList())
                + "\n";
    }

    public static String formatFailure(Result result) {
        var message = new StringBuilder("Vev found migrated tables that no query references:\n");
        for (Relation table : result.unusedTables()) {
            message.append("  ").append(table.qualified()).append('\n');
        }
        message.append("Drop unused tables in a migration, reference them from a Vev query, ")
                .append("or add intentional exceptions to verifyVevTableUsage.ignoredTables.");
        return message.toString();
    }

    private static Set<FunctionRef> matchFunctionCalls(
            List<SqlToken> tokens,
            Set<FunctionRef> catalogFunctions,
            Map<String, List<FunctionRef>> byName,
            String defaultSchema) {
        Set<FunctionRef> matched = new LinkedHashSet<>();
        for (int i = 0; i < tokens.size(); i++) {
            SqlToken current = tokens.get(i);
            if (!current.identifier()) continue;
            if (i + 3 < tokens.size() && tokens.get(i + 1).text().equals(".")
                    && tokens.get(i + 2).identifier() && tokens.get(i + 3).text().equals("(")) {
                FunctionRef qualified = new FunctionRef(current.text(), tokens.get(i + 2).text());
                if (catalogFunctions.contains(qualified)) matched.add(qualified);
                i += 2;
                continue;
            }
            if (i + 1 < tokens.size() && tokens.get(i + 1).text().equals("(")) {
                // Skip if preceded by '.' (already handled as schema.func or column/method access).
                if (i > 0 && tokens.get(i - 1).text().equals(".")) continue;
                List<FunctionRef> candidates = byName.get(current.text());
                if (candidates == null) continue;
                boolean resolved = false;
                if (defaultSchema != null) {
                    for (FunctionRef ref : candidates) {
                        if (ref.schema().equals(defaultSchema)) { matched.add(ref); resolved = true; }
                    }
                }
                if (!resolved) {
                    for (FunctionRef ref : candidates) {
                        if (ref.schema().equals("public")) { matched.add(ref); resolved = true; }
                    }
                }
                if (!resolved) matched.addAll(candidates);
            }
        }
        return matched;
    }

    private static Set<Relation> matchRelationReferences(
            List<SqlToken> tokens,
            Set<Relation> catalogRelations,
            Map<String, List<Relation>> byName,
            String defaultSchema) {
        Set<Relation> matched = new LinkedHashSet<>();
        for (int i = 0; i < tokens.size(); i++) {
            String keyword = tokens.get(i).keyword();
            boolean commaList = keyword.equals("FROM") || keyword.equals("TRUNCATE") || keyword.equals("USING");
            boolean isDmlInto = keyword.equals("INTO") && i > 0
                    && (tokens.get(i - 1).keyword().equals("INSERT") || tokens.get(i - 1).keyword().equals("MERGE"));
            boolean singleTarget = keyword.equals("JOIN") || isDmlInto
                    || keyword.equals("UPDATE") || keyword.equals("TABLE");
            if (keyword.equals("REFRESH") && i + 2 < tokens.size()
                    && tokens.get(i + 1).keyword().equals("MATERIALIZED")
                    && tokens.get(i + 2).keyword().equals("VIEW")) {
                int j = i + 3;
                if (j < tokens.size() && tokens.get(j).keyword().equals("CONCURRENTLY")) j++;
                resolveRelationAt(tokens, j, false, catalogRelations, byName, defaultSchema, matched);
                continue;
            }
            if (!commaList && !singleTarget) continue;
            // Skip `DELETE FROM` double trigger and `INSERT INTO ...` handled cleanly; avoid `UPDATE OF col` in triggers.
            if (keyword.equals("UPDATE") && i + 1 < tokens.size() && tokens.get(i + 1).keyword().equals("OF")) continue;
            // Function calls like `FROM unnest(...)`, `JOIN generate_series(...)`, or `USING unnest(...)` appear after
            // FROM/JOIN/USING, whereas `INSERT INTO table(col1, col2)` uses `(` for the target column list.
            boolean allowFunctionCall = keyword.equals("FROM") || keyword.equals("JOIN") || keyword.equals("USING");
            int j = i + 1;
            if (keyword.equals("TRUNCATE") && j < tokens.size() && tokens.get(j).keyword().equals("TABLE")) j++;
            while (j < tokens.size()) {
                if (tokens.get(j).keyword().equals("ONLY")) j++;
                int consumed = resolveRelationAt(tokens, j, allowFunctionCall, catalogRelations, byName, defaultSchema, matched);
                if (!commaList) break;
                if (consumed == 0) break;
                j += consumed;
                // Skip optional alias (`AS alias` or `alias`) or `*` before checking for comma-joined tables.
                if (j < tokens.size() && tokens.get(j).text().equals("*")) j++;
                if (j < tokens.size() && tokens.get(j).keyword().equals("AS")) j += 2;
                else if (j < tokens.size() && tokens.get(j).identifier() && !CLAUSE_END.contains(tokens.get(j).keyword())) j++;
                if (j < tokens.size() && tokens.get(j).text().equals(",")) {
                    j++;
                    continue;
                }
                break;
            }
        }
        return matched;
    }

    private static int resolveRelationAt(
            List<SqlToken> tokens,
            int index,
            boolean skipFunctionCall,
            Set<Relation> catalogRelations,
            Map<String, List<Relation>> byName,
            String defaultSchema,
            Set<Relation> out) {
        if (index >= tokens.size() || !tokens.get(index).identifier()) return 0;
        if (index + 2 < tokens.size() && tokens.get(index + 1).text().equals(".") && tokens.get(index + 2).identifier()) {
            if (skipFunctionCall && index + 3 < tokens.size() && tokens.get(index + 3).text().equals("(")) return 0;
            Relation qualified = new Relation(tokens.get(index).text(), tokens.get(index + 2).text());
            if (catalogRelations.contains(qualified)) out.add(qualified);
            return 3;
        }
        // Ignore function calls in FROM/JOIN (e.g. `FROM unnest(...)`), but keep `INSERT INTO table(col1, ...)`.
        if (skipFunctionCall && index + 1 < tokens.size() && tokens.get(index + 1).text().equals("(")) return 0;
        String name = tokens.get(index).text();
        List<Relation> candidates = byName.get(name);
        if (candidates != null) {
            boolean resolved = false;
            if (defaultSchema != null) {
                for (Relation rel : candidates) {
                    if (rel.schema().equals(defaultSchema)) { out.add(rel); resolved = true; }
                }
            }
            if (!resolved) {
                for (Relation rel : candidates) {
                    if (rel.schema().equals("public")) { out.add(rel); resolved = true; }
                }
            }
            if (!resolved) out.addAll(candidates);
        }
        return 1;
    }

    /** Lexes SQL identifiers and key punctuation, skipping comments and string literals. */
    private static List<SqlToken> lexSql(String sql) {
        List<SqlToken> tokens = new ArrayList<>();
        int length = sql.length();
        for (int i = 0; i < length;) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '-' && i + 1 < length && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < length && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') i++;
                continue;
            }
            if (c == '/' && i + 1 < length && sql.charAt(i + 1) == '*') {
                int depth = 1;
                i += 2;
                while (i < length && depth > 0) {
                    if (sql.startsWith("/*", i)) { depth++; i += 2; }
                    else if (sql.startsWith("*/", i)) { depth--; i += 2; }
                    else i++;
                }
                continue;
            }
            if (c == '\'') {
                boolean escaped = i > 0 && (sql.charAt(i - 1) == 'E' || sql.charAt(i - 1) == 'e')
                        && (i < 2 || !isIdentPart(sql.charAt(i - 2)));
                i++;
                while (i < length) {
                    if (escaped && sql.charAt(i) == '\\') { i += 2; continue; }
                    if (sql.charAt(i++) == '\'') {
                        if (i < length && sql.charAt(i) == '\'') { i++; continue; }
                        break;
                    }
                }
                continue;
            }
            if (c == '$') {
                String tag = dollarTag(sql, i);
                if (tag != null) {
                    int end = sql.indexOf(tag, i + tag.length());
                    i = end < 0 ? length : end + tag.length();
                    continue;
                }
                i++;
                continue;
            }
            if (c == '"') {
                StringBuilder ident = new StringBuilder();
                i++;
                while (i < length) {
                    char ch = sql.charAt(i++);
                    if (ch == '"') {
                        if (i < length && sql.charAt(i) == '"') { ident.append('"'); i++; continue; }
                        break;
                    }
                    ident.append(ch);
                }
                tokens.add(new SqlToken(ident.toString(), true, true));
                continue;
            }
            if (isIdentStart(c)) {
                int start = i++;
                while (i < length && isIdentPart(sql.charAt(i))) i++;
                tokens.add(new SqlToken(sql.substring(start, i).toLowerCase(Locale.ROOT), true, false));
                continue;
            }
            if (c == '.' || c == '(' || c == ')' || c == ',' || c == ';' || c == '*') {
                tokens.add(new SqlToken(String.valueOf(c), false, false));
            }
            i++;
        }
        return tokens;
    }

    private static String dollarTag(String sql, int start) {
        int i = start + 1;
        if (i < sql.length() && sql.charAt(i) == '$') return "$$";
        if (i >= sql.length() || !isIdentStart(sql.charAt(i))) return null;
        i++;
        while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) i++;
        if (i < sql.length() && sql.charAt(i) == '$') return sql.substring(start, i + 1);
        return null;
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
