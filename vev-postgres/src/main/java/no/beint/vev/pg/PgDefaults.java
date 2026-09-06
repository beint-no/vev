package no.beint.vev.pg;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/** Verifies stored defaults as catalog data. Vev never evaluates or emits a default expression. */
final class PgDefaults {
    private PgDefaults() {
    }

    static void verify(Connection connection, PgCheckCatalog catalog, PgPlan<?, ?, ?, ?> plan) throws SQLException {
        Map<String, PgColumn> expected = new HashMap<>();
        for (PgColumn column : plan.columns()) {
            if (!column.defaultExpression().isEmpty()) expected.put(column.name(), column);
        }
        if (expected.isEmpty()) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT definition.oid, attribute.attname, attribute.atttypid,
                       pg_catalog.substr(definition.adbin::pg_catalog.text, 1, ?)
                  FROM pg_catalog.pg_attrdef definition
                  JOIN pg_catalog.pg_attribute attribute
                    ON attribute.attrelid = definition.adrelid AND attribute.attnum = definition.adnum
                  JOIN pg_catalog.pg_class relation ON relation.oid = attribute.attrelid
                  JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
                 WHERE namespace.nspname = ? AND relation.relname = ?
                   AND attribute.attnum > 0 AND NOT attribute.attisdropped
                 ORDER BY attribute.attnum LIMIT ?
                """)) {
            statement.setInt(1, PgCheckTree.MAXIMUM_LENGTH + 1);
            statement.setString(2, plan.schemaName());
            statement.setString(3, plan.tableName());
            statement.setInt(4, expected.size() + 1);
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    PgColumn column = expected.remove(row.getString(2));
                    if (column == null) throw invalid(plan);
                    var inspection = PgCheckTree.inspectExpression(row.getString(4));
                    if (!inspection.dependencies().variables().isEmpty() || inspection.resultType() != row.getLong(3)) {
                        throw invalid(plan);
                    }
                    // Deparsing a Const invokes its type's output function: attest dependencies first.
                    catalog.verify(inspection.dependencies());
                    verifyDefinition(connection, row.getLong(1), column, plan);
                }
            }
        }
        if (!expected.isEmpty()) throw invalid(plan);
    }

    private static void verifyDefinition(Connection connection, long oid, PgColumn column, PgPlan<?, ?, ?, ?> plan)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pg_catalog.substr(pg_catalog.pg_get_expr(adbin, adrelid, false), 1, ?)
                  FROM pg_catalog.pg_attrdef WHERE oid = ?::pg_catalog.oid
                """)) {
            statement.setInt(1, PgCheck.MAXIMUM_EXPRESSION_LENGTH + 1);
            statement.setLong(2, oid);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || !column.defaultExpression().equals(row.getString(1)) || row.next()) throw invalid(plan);
            }
        }
    }

    private static IllegalStateException invalid(PgPlan<?, ?, ?, ?> plan) {
        return new IllegalStateException("Database defaults must match the exact declared, approved column expressions: "
                + plan.schemaName() + '.' + plan.tableName());
    }
}
