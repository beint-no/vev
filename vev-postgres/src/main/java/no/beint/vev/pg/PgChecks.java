package no.beint.vev.pg;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

final class PgChecks {
    private PgChecks() {
    }

    static void verify(Connection connection, PgCheckCatalog catalog, PgPlan<?, ?, ?, ?> plan) throws SQLException {
        Map<String, PgCheck> expected = new HashMap<>();
        for (PgCheck check : plan.checkConstraints()) expected.put(check.name(), check);
        Map<Integer, PgCheckTree.Variable> columns = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT attribute.attnum, attribute.atttypid, attribute.atttypmod, attribute.attcollation, attribute.attname
                  FROM pg_catalog.pg_attribute attribute
                  JOIN pg_catalog.pg_class relation ON relation.oid = attribute.attrelid
                  JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
                 WHERE namespace.nspname = ? AND relation.relname = ? AND attribute.attnum > 0 AND NOT attribute.attisdropped
                 LIMIT 65
                """)) {
            statement.setString(1, plan.schemaName());
            statement.setString(2, plan.tableName());
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    String name = row.getString(5);
                    if (plan.columns().stream().noneMatch(column -> column.name().equals(name))) throw invalid(plan);
                    int number = row.getInt(1);
                    columns.put(number, new PgCheckTree.Variable(number, row.getLong(2), row.getInt(3), row.getLong(4)));
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT constraint_definition.oid, constraint_definition.conname,
                       pg_catalog.substr(constraint_definition.conbin::pg_catalog.text, 1, ?),
                       constraint_definition.convalidated AND constraint_definition.conenforced
                       AND constraint_definition.conislocal AND constraint_definition.coninhcount = 0
                       AND constraint_definition.conparentid = 0 AND NOT constraint_definition.conperiod
                       AND NOT constraint_definition.condeferrable AND NOT constraint_definition.condeferred
                       AND NOT constraint_definition.connoinherit AND constraint_definition.contypid = 0
                  FROM pg_catalog.pg_constraint constraint_definition
                  JOIN pg_catalog.pg_class relation ON relation.oid = constraint_definition.conrelid
                  JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
                 WHERE namespace.nspname = ? AND relation.relname = ? AND constraint_definition.contype = 'c'
                 ORDER BY constraint_definition.conname LIMIT ?
                """)) {
            statement.setInt(1, PgCheckTree.MAXIMUM_LENGTH + 1);
            statement.setString(2, plan.schemaName());
            statement.setString(3, plan.tableName());
            statement.setInt(4, expected.size() + 1);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    PgCheck check = expected.remove(row.getString(2));
                    if (check == null || !row.getBoolean(4)) throw invalid(plan);
                    PgCheckTree.Dependencies dependencies = PgCheckTree.inspect(row.getString(3));
                    for (PgCheckTree.Variable variable : dependencies.variables()) {
                        if (!variable.equals(columns.get(variable.number()))) throw invalid(plan);
                    }
                    catalog.verify(dependencies);
                    // Deparsing a Const can invoke its type's output function. Attest all types BEFORE deparsing.
                    verifyDefinition(connection, row.getLong(1), check, plan);
                }
            }
        }
        if (!expected.isEmpty()) throw invalid(plan);
    }

    private static void verifyDefinition(Connection connection, long oid, PgCheck check, PgPlan<?, ?, ?, ?> plan)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pg_catalog.substr(pg_catalog.pg_get_expr(conbin, conrelid, false), 1, ?),
                       CASE WHEN ?::pg_catalog.text = '' THEN ?::pg_catalog.text
                            ELSE pg_catalog.format(?::pg_catalog.text, ?::pg_catalog.text, ?::pg_catalog.int4) END
                  FROM pg_catalog.pg_constraint WHERE oid = ?::pg_catalog.oid
                """)) {
            statement.setInt(1, PgCheck.MAXIMUM_EXPRESSION_LENGTH + 1);
            statement.setString(2, check.boundColumn());
            statement.setString(3, check.expression());
            statement.setString(4, check.boundFormat());
            statement.setString(5, check.boundColumn());
            statement.setInt(6, check.maximumLength());
            statement.setLong(7, oid);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || !row.getString(2).equals(row.getString(1)) || row.next()) throw invalid(plan);
            }
        }
    }

    private static IllegalStateException invalid(PgPlan<?, ?, ?, ?> plan) {
        return new IllegalStateException("Check constraints must match the exact declared, validated local definitions: "
                + plan.schemaName() + '.' + plan.tableName());
    }
}
