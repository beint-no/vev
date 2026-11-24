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
public record SqlSource(Path path, String name, String cardinality, String sql,
                        List<String> parameters, Set<String> nullable, Map<String, String> domains,
                        Map<String, String> enums, Set<String> notNull, List<String> tokens, String tenant,
                        String positionalSql, boolean customPlan, boolean forceWrite) {
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final String DIRECTIVES = "(?:name|nullable|notnull|type|enum|scope|plan|access):.*";

    /** Returns the directive without its prefix, or null for an ordinary comment. */
    private static String directive(Path path, String comment) {
        if (!comment.startsWith("vev.")) {
            if (comment.matches(DIRECTIVES)) throw error(path, "Prefix Vev directives with vev.: -- vev." + comment);
            return null;
        }
        String value = comment.substring(4);
        if (!value.matches(DIRECTIVES)) throw error(path, "Unknown Vev directive: " + comment);
        return value;
    }

    private static int lineEnd(String source, int start) {
        int i = start;
        while (i < source.length() && source.charAt(i) != '\n' && source.charAt(i) != '\r') i++;
        return i;
    }

    private static int afterBlockComment(Path path, String source, int start) {
        int depth = 1;
        int i = start + 2;
        while (i < source.length()) {
            if (source.startsWith("/*", i)) { depth++; i += 2; }
            else if (source.startsWith("*/", i)) {
                i += 2;
                if (--depth == 0) return i;
            } else i++;
        }
        throw error(path, "Unterminated SQL comment");
    }

    public static SqlSource parse(Path path, String source) {
        String name = null;
        String cardinality = null;
        String tenant = "tenantId";
        boolean scopeDeclared = false;
        boolean customPlan = false;
        boolean forceWrite = false;
        Set<String> nullable = new LinkedHashSet<>();
        Set<String> notNull = new LinkedHashSet<>();
        Map<String, String> domains = new LinkedHashMap<>();
        Map<String, String> enums = new LinkedHashMap<>();
        Set<String> singletonDirectives = new LinkedHashSet<>();
        int sqlStart = 0;
        while (sqlStart < source.length()) {
            if (Character.isWhitespace(source.charAt(sqlStart))) { sqlStart++; continue; }
            if (source.startsWith("/*", sqlStart)) {
                sqlStart = afterBlockComment(path, source, sqlStart);
                continue;
            }
            if (!source.startsWith("--", sqlStart)) break;
            int end = lineEnd(source, sqlStart + 2);
            String text = directive(path, source.substring(sqlStart + 2, end).strip());
            sqlStart = end;
            if (text == null) continue;
            String key = text.substring(0, text.indexOf(':'));
            if (Set.of("name", "scope", "plan", "access").contains(key) && !singletonDirectives.add(key)) {
                throw error(path, "Duplicate " + key + " directive");
            }
            if (text.startsWith("name:")) {
                if (name != null) throw error(path, "Duplicate name directive");
                String[] parts = text.substring(5).strip().split("\\s+");
                if (parts.length != 2 || !Set.of(":many", ":one", ":optional", ":stream", ":exec", ":void").contains(parts[1])) {
                    throw error(path, "Use -- vev.name: queryName :many|:one|:optional|:stream|:exec|:void");
                }
                name = identifier(path, parts[0]);
                cardinality = parts[1].substring(1);
            } else if (text.startsWith("nullable:")) {
                for (String value : text.substring(9).strip().split("[ ,]+")) nullable.add(identifier(path, value));
            } else if (text.startsWith("notnull:")) {
                for (String value : text.substring(8).strip().split("[ ,]+")) notNull.add(identifier(path, value));
            } else if (text.startsWith("enum:")) {
                String[] parts = text.substring(5).strip().split("\\s+");
                if (parts.length != 2) throw error(path, "Use -- vev.enum: parameterOrColumn fully.qualified.KotlinEnum");
                String previous = enums.put(identifier(path, parts[0]), enumName(path, parts[1]));
                if (previous != null) throw error(path, "Duplicate enum declaration: " + parts[0]);
            } else if (text.startsWith("type:")) {
                String[] parts = text.substring(5).strip().split("\\s+");
                if (parts.length != 2) throw error(path, "Use -- vev.type: parameterOrColumn DomainType");
                String previous = domains.put(identifier(path, parts[0]), typeName(path, parts[1]));
                if (previous != null) throw error(path, "Duplicate type declaration: " + parts[0]);
            } else if (text.startsWith("scope:")) {
                if (scopeDeclared || !text.substring(6).strip().equals("global")) throw error(path, "Use -- vev.scope: global once to opt out of tenant binding");
                scopeDeclared = true;
                tenant = null;
            } else if (text.startsWith("access:")) {
                if (!text.substring(7).strip().equals("write")) throw error(path, "Use -- vev.access: write to require a write scope");
                forceWrite = true;
            } else {
                if (!text.substring(5).strip().equals("custom")) throw error(path, "Use -- vev.plan: custom to plan every execution with its parameter values");
                customPlan = true;
            }
        }
        if (name == null) throw error(path, "Missing -- vev.name: queryName :cardinality");
        for (String both : enums.keySet()) {
            if (domains.containsKey(both)) throw error(path, "Declare either type or enum for " + both + ", not both");
        }
        List<String> parameters = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        // The same SQL with $n placeholders, for EXPLAIN (GENERIC_PLAN) outside JDBC's ? rewriting.
        StringBuilder positional = new StringBuilder();
        boolean terminated = false;
        for (int i = sqlStart; i < source.length();) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) { sql.append(c); positional.append(c); i++; continue; }
            if (source.startsWith("--", i)) {
                int end = lineEnd(source, i + 2);
                String comment = directive(path, source.substring(i + 2, end).strip());
                if (!tokens.isEmpty() && comment != null) throw error(path, "Query directives must precede SQL");
                i = end;
                sql.append(' '); positional.append(' ');
                continue;
            }
            if (source.startsWith("/*", i)) {
                i = afterBlockComment(path, source, i);
                sql.append(' '); positional.append(' ');
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
                sql.append(source, start, i); positional.append(source, start, i);
                tokens.add(c == '\'' ? "<literal>" : "<identifier>");
                continue;
            }
            if (c == '$') {
                var match = Pattern.compile("\\$(?:[A-Za-z_][A-Za-z0-9_]*)?\\$").matcher(source.substring(i));
                if (match.lookingAt()) {
                    String delimiter = match.group();
                    int end = source.indexOf(delimiter, i + delimiter.length());
                    if (end < 0) throw error(path, "Unterminated dollar-quoted SQL literal");
                    sql.append(source, i, end + delimiter.length()); positional.append(source, i, end + delimiter.length());
                    i = end + delimiter.length();
                    tokens.add("<literal>");
                    continue;
                }
                throw error(path, "Use named :parameters, not PostgreSQL positional parameters");
            }
            if (source.startsWith("::", i)) { sql.append("::"); positional.append("::"); tokens.add("::"); i += 2; continue; }
            if (c == ':' && i + 1 < source.length() && Character.isJavaIdentifierStart(source.charAt(i + 1))
                    && (i == 0 || !Character.isJavaIdentifierPart(source.charAt(i - 1)))) {
                int end = i + 2;
                while (end < source.length() && Character.isJavaIdentifierPart(source.charAt(end))) end++;
                String parameter = identifier(path, source.substring(i + 1, end));
                if (parameter.equals("tx") || parameter.equals("session")) throw error(path, "Parameter name " + parameter + " is reserved");
                if (cardinality.equals("stream") && parameter.equals("onRow")) throw error(path, "Parameter name onRow is reserved for :stream queries");
                parameters.add(parameter);
                sql.append('?'); positional.append('$').append(parameters.size()); tokens.add("<parameter>"); i = end; continue;
            }
            if (Character.isJavaIdentifierStart(c)) {
                int end = i + 1;
                while (end < source.length() && Character.isJavaIdentifierPart(source.charAt(end))) end++;
                String word = source.substring(i, end);
                sql.append(word); positional.append(word); tokens.add(word.toUpperCase(Locale.ROOT)); i = end; continue;
            }
            if (c == '?') sql.append("??"); else sql.append(c);
            positional.append(c);
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
        int parameterIndex = 0;
        for (int i = 0; i < tokens.size(); i++) {
            if (!tokens.get(i).equals("<parameter>")) continue;
            String parameter = parameters.get(parameterIndex++);
            boolean jsonOperand = i >= 2 && tokens.get(i - 1).equals(">")
                    && (tokens.get(i - 2).equals("-") || i >= 3 && tokens.get(i - 2).equals(">") && tokens.get(i - 3).equals("-"));
            if (jsonOperand && (i + 1 == tokens.size() || !tokens.get(i + 1).equals("::"))) {
                throw error(path, "Cast :" + parameter + " after -> or ->>; PostgreSQL resolves an uncast parameter to a text key,"
                        + " never an array index. Use :" + parameter + "::int for an index or :" + parameter + "::text for a key");
            }
        }
        if (!new LinkedHashSet<>(parameters).containsAll(nullable)) throw error(path, "nullable names an unknown parameter");
        if (!notNull.isEmpty() && Set.of("exec", "void").contains(cardinality)) throw error(path, "notnull requires a result set");
        if (tenant != null && (!parameters.contains(tenant) || nullable.contains(tenant) || domains.containsKey(tenant))) throw error(path, "Tenant queries require a non-null :tenantId parameter; use -- vev.scope: global for global SQL");
        if (parameters.size() > 65535) throw error(path, "PostgreSQL supports at most 65535 bind positions");
        return new SqlSource(path, name, cardinality, sql.toString().strip(),
                List.copyOf(parameters), Set.copyOf(nullable), Map.copyOf(domains), Map.copyOf(enums),
                Set.copyOf(notNull), List.copyOf(tokens), tenant, positional.toString().strip(), customPlan, forceWrite);
    }

    /** Detects PostgreSQL mutating/locking calls and FOR UPDATE/SHARE clauses directly in lexed tokens. */
    boolean hasMutatingOrLockingSyntax() {
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (i + 1 < tokens.size() && tokens.get(i + 1).equals("(")) {
                if (token.equals("NEXTVAL") || token.equals("SETVAL") || token.equals("PG_NOTIFY")
                        || token.startsWith("PG_ADVISORY_") || token.startsWith("PG_TRY_ADVISORY_")) {
                    return true;
                }
            }
            if (token.equals("FOR") && i + 1 < tokens.size()) {
                String next = tokens.get(i + 1);
                if (next.equals("UPDATE") || next.equals("SHARE")) return true;
                if (next.equals("KEY") && i + 2 < tokens.size() && tokens.get(i + 2).equals("SHARE")) return true;
                if (next.equals("NO") && i + 3 < tokens.size()
                        && tokens.get(i + 2).equals("KEY") && tokens.get(i + 3).equals("UPDATE")) return true;
            }
        }
        return false;
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
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (Set.of("WITH", "GROUP", "UNION", "EXCEPT", "INTERSECT", "VALUES", "TABLE").contains(token)) return false;
            if (Set.of("LEFT", "RIGHT", "FULL").contains(token) && i + 1 < tokens.size()
                    && Set.of("OUTER", "JOIN").contains(tokens.get(i + 1))) return false;
        }
        return true;
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
