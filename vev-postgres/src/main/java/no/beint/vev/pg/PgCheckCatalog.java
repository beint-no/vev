package no.beint.vev.pg;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

/** Bootstrap-only allowlist; no database function in a check is invoked to verify it. */
final class PgCheckCatalog {
    private static final Set<String> SCALAR_TYPES = Set.of("bool", "int2", "int4", "int8", "numeric", "text",
            "varchar", "bpchar", "uuid", "date", "timestamp", "timestamptz", "time", "interval", "bytea", "jsonb");
    private static final Set<String> FUNCTIONS = functions();
    // These operations use approved scalar inputs and the verified UTC/ISO/MDY/postgres display context.
    private static final Set<String> STABLE_FUNCTIONS = Set.of("concat", "concat_ws", "date", "date_trunc", "timestamptz");
    private static final Set<String> OPERATORS = Set.of("=", "<>", "<", "<=", ">", ">=", "+", "-", "*", "/", "%",
            "~", "!~", "~*", "!~*", "~~", "!~~", "~~*", "!~~*", "||");
    private final Connection connection;
    private final Set<Long> types = new HashSet<>();
    private final java.util.Map<Long, String> functions = new java.util.HashMap<>();
    private final Set<Long> defaultOnlyFunctions = new HashSet<>();
    private final java.util.Map<Long, String> typeNames = new java.util.HashMap<>();
    private final Set<Long> collations = new HashSet<>();
    private final java.util.Map<Long, Long> operators = new java.util.HashMap<>();

    PgCheckCatalog(Connection connection) {
        this.connection = connection;
    }

    void verify(PgCheckTree.Dependencies dependencies) throws SQLException {
        verify(dependencies, false);
    }

    void verifyDefault(PgCheckTree.Dependencies dependencies) throws SQLException {
        verify(dependencies, true);
    }

    private void verify(PgCheckTree.Dependencies dependencies, boolean defaultExpression) throws SQLException {
        for (long type : dependencies.types()) verifyType(type);
        for (long collation : dependencies.collations()) verifyCollation(collation);
        for (long function : dependencies.functions()) {
            verifyFunction(function, defaultExpression);
            if (defaultOnlyFunctions.contains(function)
                    && (!Set.of(1184L).equals(dependencies.functionResults().get(function))
                        || !Set.of().equals(dependencies.functionInputs().get(function)))) {
                throw unsupported("transaction-clock signature", function);
            }
        }
        for (var inputs : dependencies.functionInputs().entrySet()) {
            String name = functions.get(inputs.getKey());
            if ("concat".equals(name) || "concat_ws".equals(name)) {
                for (long type : inputs.getValue()) {
                    String typeName = typeNames.get(type);
                    // Polymorphic formatting of bytea/arrays can consult additional, unpinned display settings.
                    if (typeName == null || !SCALAR_TYPES.contains(typeName) || typeName.equals("bytea") || typeName.equals("jsonb")) {
                        throw unsupported("polymorphic formatting input", type);
                    }
                }
            }
        }
        for (var operator : dependencies.operators().entrySet()) verifyOperator(operator.getKey(), operator.getValue());
    }

    private void verifyType(long oid) throws SQLException {
        if (types.contains(oid)) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT type.typname, type.typelem, element.typname,
                       type.oid < 16384 AND namespace.nspname = 'pg_catalog' AND type.typtype = 'b'
                       AND type.typbasetype = 0
                       AND (type.typelem = 0 OR (element.oid < 16384 AND element_namespace.nspname = 'pg_catalog'
                            AND element.typtype = 'b' AND element.typelem = 0 AND type.typsubscript = 'pg_catalog.array_subscript_handler'::pg_catalog.regproc))
                  FROM pg_catalog.pg_type type
                  JOIN pg_catalog.pg_namespace namespace ON namespace.oid = type.typnamespace
                  LEFT JOIN pg_catalog.pg_type element ON element.oid = type.typelem
                  LEFT JOIN pg_catalog.pg_namespace element_namespace ON element_namespace.oid = element.typnamespace
                 WHERE type.oid = ?::pg_catalog.oid
                """)) {
            statement.setLong(1, oid);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || !row.getBoolean(4)
                        || !(row.getLong(2) == 0 ? SCALAR_TYPES.contains(row.getString(1))
                        : SCALAR_TYPES.contains(row.getString(3)) && row.getString(1).equals("_" + row.getString(3)))
                        ) throw unsupported("type", oid);
                typeNames.put(oid, row.getString(1));
                if (row.next()) throw unsupported("type", oid);
            }
        }
        types.add(oid);
    }

    private void verifyFunction(long oid, boolean defaultExpression) throws SQLException {
        if (functions.containsKey(oid)) {
            if (!defaultExpression && defaultOnlyFunctions.contains(oid)) throw unsupported("clock in check", oid);
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT function.proname, function.provolatile,
                       function.oid < 16384 AND namespace.nspname = 'pg_catalog'
                       AND language.lanname IN ('internal', 'c') AND function.prokind = 'f'
                       AND NOT function.prosecdef AND NOT function.proretset AND function.proconfig IS NULL,
                       function.proname IN ('now', 'transaction_timestamp')
                       AND function.prosrc = 'now' AND language.lanname = 'internal'
                       AND function.provolatile = 's' AND function.pronargs = 0
                       AND function.prorettype = 1184 AND function.provariadic = 0
                       AND function.proisstrict AND function.proargtypes = ''::pg_catalog.oidvector
                       AND function.proallargtypes IS NULL AND function.proargmodes IS NULL
                  FROM pg_catalog.pg_proc function
                  JOIN pg_catalog.pg_namespace namespace ON namespace.oid = function.pronamespace
                  JOIN pg_catalog.pg_language language ON language.oid = function.prolang
                 WHERE function.oid = ?::pg_catalog.oid
                """)) {
            statement.setLong(1, oid);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || !row.getBoolean(3)) throw unsupported("function", oid);
                boolean clock = row.getBoolean(4);
                boolean ordinary = FUNCTIONS.contains(row.getString(1)) && (row.getString(2).equals("i")
                        || row.getString(2).equals("s") && STABLE_FUNCTIONS.contains(row.getString(1)));
                if (!ordinary && !(defaultExpression && clock)) throw unsupported("function", oid);
                if (clock) defaultOnlyFunctions.add(oid);
                functions.put(oid, row.getString(1));
                if (row.next()) throw unsupported("function", oid);
            }
        }
    }

    private void verifyOperator(long oid, long function) throws SQLException {
        Long verifiedFunction = operators.get(oid);
        if (verifiedFunction != null) {
            if (verifiedFunction != function) throw unsupported("operator function", oid);
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operator.oprname, operator.oprcode::pg_catalog.oid,
                       operator.oid < 16384 AND namespace.nspname = 'pg_catalog'
                  FROM pg_catalog.pg_operator operator
                  JOIN pg_catalog.pg_namespace namespace ON namespace.oid = operator.oprnamespace
                 WHERE operator.oid = ?::pg_catalog.oid
                """)) {
            statement.setLong(1, oid);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || !row.getBoolean(3) || !OPERATORS.contains(row.getString(1))
                        || row.getLong(2) != function || row.next()) throw unsupported("operator", oid);
            }
        }
        operators.put(oid, function);
    }

    private void verifyCollation(long oid) throws SQLException {
        if (collations.contains(oid)) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT mapped_collation.collisdeterministic AND namespace.nspname = 'pg_catalog'
                       AND CASE WHEN mapped_collation.collprovider = 'd' THEN
                           database.datcollversion IS NOT DISTINCT FROM pg_catalog.pg_database_collation_actual_version(database.oid)
                       ELSE mapped_collation.collversion IS NOT DISTINCT FROM pg_catalog.pg_collation_actual_version(mapped_collation.oid) END
                  FROM pg_catalog.pg_collation mapped_collation
                  JOIN pg_catalog.pg_namespace namespace ON namespace.oid = mapped_collation.collnamespace
                  JOIN pg_catalog.pg_database database ON database.datname = pg_catalog.current_database()
                 WHERE mapped_collation.oid = ?::pg_catalog.oid
                """)) {
            statement.setLong(1, oid);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || !row.getBoolean(1) || row.next()) throw unsupported("collation", oid);
            }
        }
        collations.add(oid);
    }

    private static Set<String> functions() {
        Set<String> result = new HashSet<>(Set.of("btrim", "ltrim", "rtrim", "length", "char_length", "octet_length",
                "lower", "upper", "regexp_replace", "textregexeq", "textregexne", "texticregexeq", "texticregexne",
                "bpcharregexeq", "bpcharregexne", "bpcharicregexeq", "bpcharicregexne", "textlike", "textnlike",
                "texticlike", "texticnlike", "bpcharlike", "bpcharnlike", "bpchariclike", "bpcharicnlike",
                "num_nonnulls", "num_nulls", "jsonb_typeof", "concat", "concat_ws", "textcat",
                "text", "bpchar", "varchar", "int2", "int4", "int8", "numeric", "date", "timestamp", "timestamptz",
                "date_trunc", "date_pli", "date_mii", "date_mi", "numeric_abs", "numeric_round", "numeric_trunc", "numeric_ceil", "numeric_floor"));
        for (String type : Set.of("bool", "int2", "int4", "int8", "int24", "int28", "int42", "int48", "int82", "int84",
                "text", "bpchar", "numeric_", "date_", "timestamp_", "timestamptz_", "time_", "uuid_")) {
            for (String comparison : Set.of("eq", "ne", "lt", "le", "gt", "ge")) result.add(type + comparison);
        }
        for (String type : Set.of("int2", "int4", "int8", "int24", "int28", "int42", "int48", "int82", "int84")) {
            for (String operation : Set.of("pl", "mi", "mul", "div", "mod", "um", "up", "abs")) result.add(type + operation);
        }
        for (String operation : Set.of("add", "sub", "mul", "div", "mod", "uminus", "uplus")) result.add("numeric_" + operation);
        result.addAll(Set.of("text_lt", "text_le", "text_gt", "text_ge"));
        return Set.copyOf(result);
    }

    private static IllegalStateException unsupported(String kind, long oid) {
        return new IllegalStateException("Check constraint uses an unapproved PostgreSQL " + kind + " (OID " + oid + ')');
    }
}
