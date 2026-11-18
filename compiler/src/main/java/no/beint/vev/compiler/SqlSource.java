package no.beint.vev.compiler;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Lexes bind parameters without interpreting SQL semantics; PostgreSQL owns SQL validation. */
public record SqlSource(Path path, String name, String cardinality, int maxRows, String sql,
                        List<String> parameters, Set<String> nullable, Map<String, String> domains,
                        Map<String, String> enums, Set<String> notNull, List<String> tokens, String tenant) {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final String DIRECTIVES = "(?:name|nullable|notnull|type|enum|rows|scope):.*";

    private static String directive(Path path, String comment) {
        if (!comment.startsWith("vev.")) return comment;
        String value = comment.substring(4);
        if (!value.matches(DIRECTIVES)) throw error(path, "Unknown Vev directive: " + comment);
        return value;
    }

    public static SqlSource parse(Path path, String source) {
        String name = null;
        String cardinality = null;
        String tenant = "tenantId";
        boolean scopeDeclared = false;
        int maxRows = 1000;
        Set<String> nullable = new LinkedHashSet<>();
        Set<String> notNull = new LinkedHashSet<>();
        Map<String, String> domains = new LinkedHashMap<>();
        Map<String, String> enums = new LinkedHashMap<>();
        Set<String> singletonDirectives = new LinkedHashSet<>();
        int lineStart = 0;
        while (lineStart < source.length()) {
            int lineEnd = source.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = source.length();
            String line = source.substring(lineStart, lineEnd);
            lineStart = lineEnd + 1;
            String text = line.strip();
            if (text.isEmpty()) continue;
            if (!text.startsWith("--")) break;
            text = directive(path, text.substring(2).strip());
            if (!text.matches(DIRECTIVES)) continue;
            String key = text.substring(0, text.indexOf(':'));
            if (Set.of("name", "rows", "scope").contains(key) && !singletonDirectives.add(key)) {
                throw error(path, "Duplicate " + key + " directive");
            }
            if (text.startsWith("name:")) {
                if (name != null) throw error(path, "Duplicate name directive");
                String[] parts = text.substring(5).strip().split("\\s+");
                if (parts.length != 2 || !Set.of(":many", ":one", ":optional", ":exec", ":void").contains(parts[1])) {
                    throw error(path, "Use -- name: queryName :many|:one|:optional|:exec|:void");
                }
                name = identifier(path, parts[0]);
                cardinality = parts[1].substring(1);
            } else if (text.startsWith("nullable:")) {
                for (String value : text.substring(9).strip().split("[ ,]+")) nullable.add(identifier(path, value));
            } else if (text.startsWith("notnull:")) {
                for (String value : text.substring(8).strip().split("[ ,]+")) notNull.add(identifier(path, value));
            } else if (text.startsWith("enum:")) {
                String[] parts = text.substring(5).strip().split("\\s+");
                if (parts.length != 2) throw error(path, "Use -- enum: parameterOrColumn fully.qualified.KotlinEnum");
                String previous = enums.put(identifier(path, parts[0]), enumName(path, parts[1]));
                if (previous != null) throw error(path, "Duplicate enum declaration: " + parts[0]);
            } else if (text.startsWith("type:")) {
                String[] parts = text.substring(5).strip().split("\\s+");
                if (parts.length != 2) throw error(path, "Use -- type: parameterOrColumn DomainType");
                String previous = domains.put(identifier(path, parts[0]), typeName(path, parts[1]));
                if (previous != null) throw error(path, "Duplicate type declaration: " + parts[0]);
            } else if (text.startsWith("scope:")) {
                if (scopeDeclared || !text.substring(6).strip().equals("global")) throw error(path, "Use -- scope: global once to opt out of tenant binding");
                scopeDeclared = true;
                tenant = null;
            } else {
                try { maxRows = Integer.parseInt(text.substring(5).strip()); }
                catch (NumberFormatException failure) { throw error(path, "rows must be an integer"); }
                if (maxRows < 1 || maxRows > 1_000_000) throw error(path, "rows must be between 1 and 1000000");
            }
        }
        if (name == null) throw error(path, "Missing -- name: queryName :cardinality");
        for (String both : enums.keySet()) {
            if (domains.containsKey(both)) throw error(path, "Declare either type or enum for " + both + ", not both");
        }
        List<String> parameters = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        boolean terminated = false;
        for (int i = 0; i < source.length();) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) { sql.append(c); i++; continue; }
            if (source.startsWith("--", i)) {
                int end = source.indexOf('\n', i);
                String comment = directive(path, source.substring(i + 2, end < 0 ? source.length() : end).strip());
                if (!tokens.isEmpty() && comment.matches(DIRECTIVES)) throw error(path, "Query directives must precede SQL");
                i = end < 0 ? source.length() : end;
                sql.append(' ');
                continue;
            }
            if (source.startsWith("/*", i)) {
                int depth = 1;
                i += 2;
                while (i < source.length() && depth > 0) {
                    if (source.startsWith("/*", i)) { depth++; i += 2; }
                    else if (source.startsWith("*/", i)) { depth--; i += 2; }
                    else i++;
                }
                if (depth != 0) throw error(path, "Unterminated SQL comment");
                sql.append(' ');
                continue;
            }
            if (terminated) throw error(path, "Exactly one SQL statement is allowed per query file");
            if (c == ';') { terminated = true; i++; continue; }
            if (c == '\'' || c == '"') {
                int start = i++;
                boolean escaped = c == '\'' && start > 0 && (source.charAt(start - 1) == 'E' || source.charAt(start - 1) == 'e')
                        && (start < 2 || !Character.isJavaIdentifierPart(source.charAt(start - 2)));
                boolean closed = false;
                while (i < source.length()) {
                    if (escaped && source.charAt(i) == '\\') { i += 2; continue; }
                    if (source.charAt(i++) == c) {
                        if (i < source.length() && source.charAt(i) == c) { i++; continue; }
                        closed = true; break;
                    }
                }
                if (!closed) throw error(path, "Unterminated quoted SQL token");
                sql.append(source, start, i);
                tokens.add(c == '\'' ? "<literal>" : "<identifier>");
                continue;
            }
            if (c == '$') {
                var match = Pattern.compile("\\$(?:[A-Za-z_][A-Za-z0-9_]*)?\\$").matcher(source.substring(i));
                if (match.lookingAt()) {
                    String delimiter = match.group();
                    int end = source.indexOf(delimiter, i + delimiter.length());
                    if (end < 0) throw error(path, "Unterminated dollar-quoted SQL literal");
                    sql.append(source, i, end + delimiter.length());
                    i = end + delimiter.length();
                    tokens.add("<literal>");
                    continue;
                }
                throw error(path, "Use named :parameters, not PostgreSQL positional parameters");
            }
            if (source.startsWith("::", i)) { sql.append("::"); tokens.add("::"); i += 2; continue; }
            if (c == ':' && i + 1 < source.length() && Character.isJavaIdentifierStart(source.charAt(i + 1))) {
                int end = i + 2;
                while (end < source.length() && Character.isJavaIdentifierPart(source.charAt(end))) end++;
                String parameter = identifier(path, source.substring(i + 1, end));
                if (parameter.equals("session")) throw error(path, "Parameter name session is reserved");
                parameters.add(parameter);
                sql.append('?'); tokens.add("<parameter>"); i = end; continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int end = i + 1;
                while (end < source.length() && Character.isJavaIdentifierPart(source.charAt(end))) end++;
                String word = source.substring(i, end);
                sql.append(word); tokens.add(word.toUpperCase(Locale.ROOT)); i = end; continue;
            }
            if (c == '?') sql.append("??"); else sql.append(c);
            tokens.add(String.valueOf(c)); i++;
        }
        if (tokens.isEmpty() || !Set.of("SELECT", "WITH", "INSERT", "UPDATE", "DELETE", "MERGE", "VALUES").contains(tokens.getFirst())) {
            throw error(path, "Queries must be SELECT, WITH, VALUES, INSERT, UPDATE, DELETE, or MERGE");
        }
        int depth = 0;
        String statement = null;
        boolean projection = false;
        boolean returningAliases = false;
        int returningAliasesEnd = -2;
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (token.equals(")")) depth--;
            if (depth == 0) {
                if (token.equals("RETURNING")) returningAliases = i + 2 < tokens.size()
                        && tokens.get(i + 1).equals("WITH") && tokens.get(i + 2).equals("(");
                if (returningAliases && token.equals(")")) {
                    returningAliasesEnd = i;
                    returningAliases = false;
                }
                if (statement == null && Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "VALUES").contains(token)) statement = token;
                if (token.equals("RETURNING") || token.equals("SELECT") && "SELECT".equals(statement)) projection = true;
                if (Set.of("FROM", "INTO", "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET", "FETCH", "FOR", "UNION", "EXCEPT", "INTERSECT").contains(token)) projection = false;
                if (projection && token.equals("*") && i > 0
                        && (i == returningAliasesEnd + 1
                        || Set.of("SELECT", "RETURNING", "DISTINCT", "ALL", ",", ".").contains(tokens.get(i - 1))
                        || i + 1 < tokens.size() && Set.of("FROM", "INTO").contains(tokens.get(i + 1)))) {
                    throw error(path, "List returned columns explicitly; SELECT/RETURNING * is not a stable query contract");
                }
            }
            if (token.equals("(")) depth++;
        }
        if (!new LinkedHashSet<>(parameters).containsAll(nullable)) throw error(path, "nullable names an unknown parameter");
        if (!notNull.isEmpty() && Set.of("exec", "void").contains(cardinality)) throw error(path, "notnull requires a result set");
        if (tenant != null && (!parameters.contains(tenant) || nullable.contains(tenant) || domains.containsKey(tenant))) throw error(path, "Tenant queries require a non-null :tenantId parameter; use -- scope: global for global SQL");
        if (parameters.size() > 65535) throw error(path, "PostgreSQL supports at most 65535 bind positions");
        return new SqlSource(path, name, cardinality, maxRows, sql.toString().strip(),
                List.copyOf(parameters), Set.copyOf(nullable), Map.copyOf(domains), Map.copyOf(enums),
                Set.copyOf(notNull), List.copyOf(tokens), tenant);
    }

    static String identifier(Path path, String name) {
        if (!IDENTIFIER.matcher(name).matches()) throw error(path, "Invalid identifier: " + name);
        return name;
    }

    static IllegalArgumentException error(Path path, String message) {
        return new IllegalArgumentException(path + ": " + message);
    }

    /** A reference to an existing Kotlin enum, which Vev imports rather than generates. */
    static String enumName(Path path, String name) {
        String[] segments = name.split("\\.", -1);
        for (String segment : segments) identifier(path, segment);
        String simple = segments[segments.length - 1];
        if (!Character.isUpperCase(simple.charAt(0))) throw error(path, "Enum types must start uppercase: " + name);
        return name;
    }

    static String typeName(Path path, String name) {
        identifier(path, name);
        if (!Character.isUpperCase(name.charAt(0)) || Set.of("Int", "Long", "Short", "String", "Boolean", "Float", "Double", "ByteArray", "List", "Array", "Unit", "Any", "Nothing", "JvmInline").contains(name)) {
            throw error(path, "Generated types must start uppercase and must not shadow Kotlin built-in types: " + name);
        }
        return name;
    }

    /** Only a single plain SELECT can inherit NOT NULL from origin columns. Other shapes are conservative. */
    boolean directNullability() {
        if (!tokens.getFirst().equals("SELECT")) return false;
        if (tokens.stream().filter("SELECT"::equals).count() != 1) return false;
        return tokens.stream().noneMatch(Set.of("WITH", "LEFT", "RIGHT", "FULL", "GROUP", "UNION", "EXCEPT", "INTERSECT", "VALUES", "TABLE")::contains);
    }

    private static final Set<String> SELECT_END = Set.of("FROM", "INTO", "WHERE", "GROUP", "HAVING",
            "WINDOW", "ORDER", "LIMIT", "OFFSET", "FETCH", "FOR");
    private static final Set<String> SET_OPERATIONS = Set.of("UNION", "EXCEPT", "INTERSECT");

    /** Indices of projections that guarantee a value. PostgreSQL supplies the actual column types. */
    java.util.BitSet requiredExpressions(int columnCount) {
        var required = new java.util.BitSet();
        if (!tokens.getFirst().equals("SELECT") || tokens.stream().anyMatch(SET_OPERATIONS::contains)) return required;
        int start = 1;
        int column = 0;
        int depth = 0;
        for (int i = start; i <= tokens.size(); i++) {
            String token = i == tokens.size() ? "FROM" : tokens.get(i);
            if (depth == 0 && (token.equals(",") || SELECT_END.contains(token))) {
                if (requiredExpression(tokens.subList(start, i))) required.set(column);
                column++;
                if (!token.equals(",")) break;
                start = i + 1;
            } else if (token.equals("(") || token.equals("[")) {
                depth++;
            } else if (token.equals(")") || token.equals("]")) {
                depth--;
            }
        }
        // Only associate positional hints with columns when Describe agrees with the projection count.
        if (column != columnCount) required.clear();
        return required;
    }

    private static boolean requiredExpression(List<String> expression) {
        if (expression.size() >= 2 && expression.get(expression.size() - 2).equals("AS")) {
            expression = expression.subList(0, expression.size() - 2);
        }
        if (expression.equals(List.of("COUNT", "(", "*", ")"))) return true;
        if (expression.size() < 3 || !expression.getFirst().equals("EXISTS") || !expression.get(1).equals("(")) return false;
        int depth = 0;
        for (int i = 1; i < expression.size(); i++) {
            if (expression.get(i).equals("(")) depth++;
            if (expression.get(i).equals(")")) depth--;
            if (depth == 0) return i == expression.size() - 1;
        }
        return false;
    }
}
