package no.beint.vev.compiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** PostgreSQL Parse/Describe validates SQL without executing application queries. */
public final class QueryCompiler {
    private QueryCompiler() {}
    record Value(String name, PgType type, boolean nullable, String domain, String enumType) {
        String kotlin() {
            String declared = enumType != null ? enumType : domain != null ? domain : type.kotlin();
            return declared + (nullable ? "?" : "");
        }
    }
    record Query(SqlSource source, List<Value> parameters, List<Value> columns, boolean writes) {}

    public static void compile(Connection connection, Path queries, Path output, String packageName, String className)
            throws SQLException, IOException {
        if (connection.getMetaData().getDatabaseMajorVersion() != 19
                || !connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")) {
            throw new IllegalArgumentException("Vev compiler requires PostgreSQL 19");
        }
        for (String part : packageName.split("\\.", -1)) SqlSource.identifier(queries, part);
        SqlSource.typeName(queries, className);
        List<Query> compiled = new ArrayList<>();
        HashSet<String> names = new HashSet<>();
        Map<String, PgType> domains = new java.util.TreeMap<>();
        List<Exception> failures = new ArrayList<>();
        try (var files = Files.walk(queries)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".sql")).sorted().toList()) {
                // A rejected statement aborts an open describe transaction; the savepoint keeps later files checkable.
                var savepoint = connection.getAutoCommit() ? null : connection.setSavepoint();
                try {
                    SqlSource source = SqlSource.parse(path, Files.readString(path));
                    if (!names.add(source.name())) throw SqlSource.error(path, "Duplicate query name: " + source.name());
                    Query query = describe(connection, source);
                    Map<String, PgType> queryDomains = new java.util.TreeMap<>();
                    for (Value value : java.util.stream.Stream.concat(query.parameters.stream(), query.columns.stream()).toList()) {
                        if (value.domain != null) {
                            if (value.type.element() != null || value.type.kotlin().equals("ByteArray")) {
                                throw SqlSource.error(path, "Domain wrappers require a scalar value type");
                            }
                            PgType previous = domains.containsKey(value.domain) ? domains.get(value.domain) : queryDomains.putIfAbsent(value.domain, value.type);
                            if (previous != null && !previous.kotlin().equals(value.type.kotlin())) {
                                throw SqlSource.error(path, "Domain " + value.domain + " has conflicting underlying types");
                            }
                        }
                    }
                    queryDomains.forEach(domains::putIfAbsent);
                    compiled.add(query);
                    if (savepoint != null) connection.releaseSavepoint(savepoint);
                } catch (SQLException | IllegalArgumentException failure) {
                    if (savepoint != null) connection.rollback(savepoint);
                    failures.add(failure);
                }
            }
        }
        if (failures.size() == 1) throw rethrow(failures.getFirst());
        if (!failures.isEmpty()) {
            var failure = new IllegalArgumentException(failures.size() + " Vev queries failed:\n" + failures.stream()
                    .map(Exception::getMessage).collect(java.util.stream.Collectors.joining("\n")));
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
        if (compiled.isEmpty()) throw SqlSource.error(queries, "No .sql queries found");
        HashSet<String> types = new HashSet<>();
        types.add(className);
        for (String domain : domains.keySet()) {
            if (!types.add(domain)) throw SqlSource.error(queries, "Generated type name collision: " + domain);
        }
        for (Query query : compiled) {
            if (!query.columns.isEmpty() && !query.source.cardinality().equals("void")
                    && !types.add(KotlinWriter.rowName(query.source.name()))) {
                throw SqlSource.error(query.source.path(), "Generated row type name collision");
            }
        }
        Map<String, String> generated = new java.util.TreeMap<>();
        generated.put(className + ".kt", KotlinWriter.facade(packageName, className, compiled, domains));
        for (Query query : compiled) {
            String name = KotlinWriter.implementationName(className, query);
            if (!types.add(name)) throw SqlSource.error(query.source.path(), "Generated implementation name collision: " + name);
            generated.put(name + ".kt", KotlinWriter.implementation(packageName, name, query));
        }
        Files.createDirectories(output);
        Path owner = output.resolve(".vev-output");
        if (Files.exists(owner)) {
            for (String previous : Files.readAllLines(owner)) {
                if (!previous.matches("[A-Za-z][A-Za-z0-9_]*\\.kt")) throw new IOException("Invalid Vev output ownership marker");
                if (!generated.containsKey(previous)) Files.deleteIfExists(output.resolve(previous));
            }
        }
        for (var entry : generated.entrySet()) {
            Path file = output.resolve(entry.getKey());
            if (!Files.exists(file) || !Files.readString(file).equals(entry.getValue())) Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
        }
        Files.writeString(owner, String.join("\n", generated.keySet()) + "\n", StandardCharsets.UTF_8);
        String manifest = compiled.stream().map(q -> queries.relativize(q.source.path()).toString().replace('\\', '/')
                + " " + q.source.name() + " " + q.source.cardinality() + " " + (q.writes ? "write" : "read") + " " + digest(q.source.sql()))
                .collect(java.util.stream.Collectors.joining("\n"));
        Files.writeString(output.resolve("queries.sha256"), manifest + "\n", StandardCharsets.UTF_8);
    }

    static Query describe(Connection connection, SqlSource source) throws SQLException {
        try (var statement = connection.prepareStatement(source.sql())) {
            var parameterMetadata = statement.getParameterMetaData();
            if (parameterMetadata.getParameterCount() != source.parameters().size()) throw SqlSource.error(source.path(), "Parameter count differs from PostgreSQL");
            Map<String, Value> parameters = new LinkedHashMap<>();
            for (int i = 1; i <= parameterMetadata.getParameterCount(); i++) {
                String name = source.parameters().get(i - 1);
                PgType type = PgType.of(source.path(), parameterMetadata.getParameterTypeName(i));
                if (name.equals(source.tenant()) && !type.postgres().equals("int4")) throw SqlSource.error(source.path(), "Tenant parameter must be PostgreSQL integer");
                Value value = new Value(name, type, source.nullable().contains(name), source.domains().get(name),
                        enumType(source, name, type));
                Value previous = parameters.putIfAbsent(name, value);
                if (previous != null && !sameBinding(previous.type, type)) throw SqlSource.error(source.path(), "Parameter " + name + " has incompatible types across occurrences; use separate names or consistent casts");
            }
            // Kotlin compiles @JvmStatic object methods to an instance method taking `this`, the capability scope `tx`, plus API arguments.
            int methodSlots = 2 + (source.cardinality().equals("stream") ? 1 : 0)
                    + parameters.values().stream()
                    .filter(p -> !p.name.equals(source.tenant()))
                    .mapToInt(QueryCompiler::jvmSlots).sum();
            if (methodSlots > 255) throw SqlSource.error(source.path(), "Query parameters require " + methodSlots
                    + " JVM parameter slots; at most 255 are supported. Use a PostgreSQL array or fewer parameters");
            ResultSetMetaData result = statement.getMetaData();
            if ((result == null) != source.cardinality().equals("exec")) {
                throw SqlSource.error(source.path(), "Declared cardinality does not match the SQL result;"
                        + " use :exec for row counts, :void to call a void function for its effect, and :one/:optional/:many/:stream for rows");
            }
            List<Value> columns = new ArrayList<>();
            HashSet<String> labels = new HashSet<>();
            if (result != null) {
                int columnCount = result.getColumnCount();
                var required = source.requiredExpressions(columnCount);
                boolean direct = source.directNullability();
                for (int i = 1; i <= columnCount; i++) {
                    String label = result.getColumnLabel(i);
                    if (label.equals("?column?")) {
                        throw SqlSource.error(source.path(), "Result column " + i + " has no name; give the expression an alias such as AS present");
                    }
                    String name = SqlSource.identifier(source.path(), label);
                    if (!labels.add(KotlinWriter.camel(name))) throw SqlSource.error(source.path(), "Duplicate result property: " + name + "; give columns distinct aliases");
                    PgType type = PgType.of(source.path(), result.getColumnTypeName(i));
                    boolean nullable = !source.notNull().contains(name)
                            && !required.get(i - 1)
                            && (!direct || result.isNullable(i) != ResultSetMetaData.columnNoNulls);
                    columns.add(new Value(name, type, nullable, source.domains().get(name), enumType(source, name, type)));
                }
            }
            for (String name : source.domains().keySet()) {
                if (!parameters.containsKey(name) && columns.stream().noneMatch(c -> c.name.equals(name))) throw SqlSource.error(source.path(), "type names an unknown parameter or result column: " + name);
            }
            for (String name : source.enums().keySet()) {
                if (!parameters.containsKey(name) && columns.stream().noneMatch(c -> c.name.equals(name))) throw SqlSource.error(source.path(), "enum names an unknown parameter or result column: " + name);
            }
            for (String name : source.notNull()) {
                if (columns.stream().noneMatch(c -> c.name.equals(name))) throw SqlSource.error(source.path(), "notnull names an unknown result column: " + name);
            }
            boolean valueless = !columns.isEmpty() && columns.stream().allMatch(c -> c.type.valueless());
            if (source.cardinality().equals("void")) {
                if (!valueless) throw SqlSource.error(source.path(), ":void requires every result column to be a void function call; use :many, :one, :optional or :stream to read values");
            } else if (columns.stream().anyMatch(c -> c.type.valueless())) {
                throw SqlSource.error(source.path(), "A void result carries no value; declare the query :void to call it for its effect");
            }
            if (!source.cardinality().equals("void")) {
                int valueSlots = columns.stream().mapToInt(QueryCompiler::jvmSlots).sum();
                // copy$default adds its row receiver, one mask per 32 properties, and a marker.
                int copySlots = valueSlots + (columns.size() + 31) / 32 + 2;
                if (copySlots > 255) throw SqlSource.error(source.path(), "Result has " + columns.size()
                        + " columns requiring " + copySlots + " JVM parameter slots; Kotlin data-class copy methods allow at most 255. Select a narrower projection");
            }
            boolean writes = TenantScans.analyze(connection, source);
            return new Query(source, List.copyOf(parameters.values()), List.copyOf(columns), writes);
        } catch (SQLException exception) {
            throw new SQLException(source.path() + ": PostgreSQL rejected query " + source.name() + " [SQLSTATE " + exception.getSQLState() + "]: " + exception.getMessage(), exception.getSQLState(), exception);
        }
    }

    private static RuntimeException rethrow(Exception failure) throws SQLException {
        if (failure instanceof SQLException sql) throw sql;
        return (RuntimeException) failure;
    }

    /** text and varchar bind identically as JDBC strings, so a parameter may occur as either. */
    private static boolean sameBinding(PgType previous, PgType type) {
        if (previous.equals(type)) return true;
        var strings = Set.of("text", "varchar");
        return previous.element() == null && type.element() == null
                && strings.contains(previous.postgres()) && strings.contains(type.postgres());
    }

    private static int jvmSlots(Value value) {
        return !value.nullable && Set.of("Long", "Double").contains(value.type.kotlin()) ? 2 : 1;
    }

    /** A Kotlin enum constant travels as text, so only textual PostgreSQL types can carry one. */
    private static String enumType(SqlSource source, String name, PgType type) {
        String declared = source.enums().get(name);
        if (declared != null && !type.textual()) {
            throw SqlSource.error(source.path(), "enum " + name + " requires a textual PostgreSQL type, not " + type.postgres());
        }
        return declared;
    }

    private static String digest(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
