package no.beint.vev.compiler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** PostgreSQL Parse/Describe validates SQL without executing application queries. */
public final class QueryCompiler {
    private QueryCompiler() {}
    record Value(String name, PgType type, boolean nullable, String domain) {
        String kotlin() { return (domain == null ? type.kotlin() : domain) + (nullable ? "?" : ""); }
    }
    record Query(SqlSource source, List<Value> parameters, List<Value> columns) {}

    public static void compile(Connection connection, Path queries, Path output, String packageName, String className)
            throws SQLException, IOException {
        if (connection.getMetaData().getDatabaseMajorVersion() != 18
                || !connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")) {
            throw new IllegalArgumentException("Vev compiler requires PostgreSQL 18");
        }
        for (String part : packageName.split("\\.", -1)) SqlSource.identifier(queries, part);
        SqlSource.typeName(queries, className);
        List<Query> compiled = new ArrayList<>();
        HashSet<String> names = new HashSet<>();
        Map<String, PgType> domains = new java.util.TreeMap<>();
        try (var files = Files.walk(queries)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".sql")).sorted().toList()) {
                SqlSource source = SqlSource.parse(path, Files.readString(path));
                if (!names.add(source.name())) throw SqlSource.error(path, "Duplicate query name: " + source.name());
                Query query = describe(connection, source);
                for (Value value : java.util.stream.Stream.concat(query.parameters.stream(), query.columns.stream()).toList()) {
                    if (value.domain != null) {
                        if (value.type.element() != null || value.type.kotlin().equals("ByteArray")) {
                            throw SqlSource.error(path, "Domain wrappers require a scalar value type");
                        }
                        PgType previous = domains.putIfAbsent(value.domain, value.type);
                        if (previous != null && !previous.kotlin().equals(value.type.kotlin())) {
                            throw SqlSource.error(path, "Domain " + value.domain + " has conflicting underlying types");
                        }
                    }
                }
                compiled.add(query);
            }
        }
        if (compiled.isEmpty()) throw SqlSource.error(queries, "No .sql queries found");
        HashSet<String> types = new HashSet<>();
        types.add(className);
        for (String domain : domains.keySet()) {
            if (!types.add(domain)) throw SqlSource.error(queries, "Generated type name collision: " + domain);
        }
        for (Query query : compiled) {
            if (!query.columns.isEmpty() && !types.add(KotlinWriter.rowName(query.source.name()))) {
                throw SqlSource.error(query.source.path(), "Generated row type name collision");
            }
        }
        String generated = KotlinWriter.write(packageName, className, compiled, domains);
        Files.createDirectories(output);
        Path file = output.resolve(className + ".kt");
        Path owner = output.resolve(".vev-output");
        if (Files.exists(owner)) {
            String previous = Files.readString(owner).strip();
            if (!previous.matches("[A-Za-z][A-Za-z0-9_]*\\.kt")) throw new IOException("Invalid Vev output ownership marker");
            if (!previous.equals(file.getFileName().toString())) Files.deleteIfExists(output.resolve(previous));
        }
        Files.writeString(file, generated, StandardCharsets.UTF_8);
        Files.writeString(owner, file.getFileName().toString(), StandardCharsets.UTF_8);
        String manifest = compiled.stream().map(q -> q.source.path().getFileName() + " " + q.source.name()
                + " " + q.source.cardinality() + " " + digest(q.source.sql())).collect(java.util.stream.Collectors.joining("\n"));
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
                Value value = new Value(name, type, source.nullable().contains(name), source.domains().get(name));
                Value previous = parameters.putIfAbsent(name, value);
                if (previous != null && !previous.type.equals(type)) throw SqlSource.error(source.path(), "Parameter " + name + " has incompatible types across occurrences; use separate names or consistent casts");
            }
            ResultSetMetaData result = statement.getMetaData();
            if ((result == null) != source.cardinality().equals("exec")) throw SqlSource.error(source.path(), "Declared cardinality does not match the SQL result; use :exec for row counts and :one/:optional/:many for rows");
            List<Value> columns = new ArrayList<>();
            HashSet<String> labels = new HashSet<>();
            if (result != null) {
                for (int i = 1; i <= result.getColumnCount(); i++) {
                    String name = SqlSource.identifier(source.path(), result.getColumnLabel(i));
                    if (!labels.add(KotlinWriter.camel(name))) throw SqlSource.error(source.path(), "Duplicate result property: " + name + "; give columns distinct aliases");
                    boolean nullable = !source.requiredExpression(i - 1)
                            && (!source.directNullability() || result.isNullable(i) != ResultSetMetaData.columnNoNulls);
                    columns.add(new Value(name, PgType.of(source.path(), result.getColumnTypeName(i)), nullable, source.domains().get(name)));
                }
            }
            for (String name : source.domains().keySet()) {
                if (!parameters.containsKey(name) && columns.stream().noneMatch(c -> c.name.equals(name))) throw SqlSource.error(source.path(), "type names an unknown parameter or result column: " + name);
            }
            return new Query(source, List.copyOf(parameters.values()), List.copyOf(columns));
        } catch (SQLException exception) {
            throw new SQLException(source.path() + ": PostgreSQL rejected query " + source.name() + ": " + exception.getMessage(), exception.getSQLState(), exception);
        }
    }

    private static String digest(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
