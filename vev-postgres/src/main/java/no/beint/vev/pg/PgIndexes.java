package no.beint.vev.pg;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

final class PgIndexes {
    private PgIndexes() {
    }

    private record ExpectedIndex(String name, boolean unique, List<String> columns) {
    }

    static void verify(Connection connection, PgPlan<?, ?, ?, ?> plan) throws SQLException {
        String shapeSql = """
                SELECT index_relation.relname,
                       access_method.amname = 'btree',
                       index_namespace.nspname = namespace.nspname,
                       index_relation.relkind = 'i',
                       index_relation.relpersistence = 'p',
                       NOT index_relation.relispartition,
                       index_relation.reltablespace = 0,
                       index_relation.reloptions IS NULL,
                       mapped_index.indisunique,
                       NOT mapped_index.indisprimary,
                       NOT mapped_index.indisexclusion,
                       mapped_index.indimmediate,
                       mapped_index.indisvalid,
                       mapped_index.indisready,
                       mapped_index.indislive,
                       NOT mapped_index.indcheckxmin,
                       NOT mapped_index.indisclustered,
                       NOT mapped_index.indisreplident,
                       NOT mapped_index.indnullsnotdistinct,
                       mapped_index.indexprs IS NULL,
                       mapped_index.indpred IS NULL,
                       mapped_index.indnkeyatts,
                       mapped_index.indnatts,
                       CASE WHEN mapped_index.indisunique THEN
                           index_constraint.contype = 'u'
                           AND index_constraint.conname = index_relation.relname
                           AND index_constraint.connamespace = namespace.oid
                           AND NOT index_constraint.condeferrable AND NOT index_constraint.condeferred
                           AND index_constraint.convalidated AND index_constraint.conenforced
                           AND index_constraint.conislocal AND index_constraint.coninhcount = 0
                           AND index_constraint.conparentid = 0 AND NOT index_constraint.conperiod
                           AND index_constraint.conkey = ARRAY(SELECT pg_catalog.unnest(mapped_index.indkey::pg_catalog.int2[]))
                       ELSE index_constraint.oid IS NULL END
                  FROM pg_catalog.pg_index mapped_index
                  JOIN pg_catalog.pg_class relation ON relation.oid = mapped_index.indrelid
                  JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
                  JOIN pg_catalog.pg_class index_relation ON index_relation.oid = mapped_index.indexrelid
                  JOIN pg_catalog.pg_namespace index_namespace ON index_namespace.oid = index_relation.relnamespace
                  JOIN pg_catalog.pg_am access_method ON access_method.oid = index_relation.relam
                  LEFT JOIN pg_catalog.pg_constraint index_constraint
                    ON index_constraint.conindid = mapped_index.indexrelid
                   AND index_constraint.conrelid = relation.oid
                 WHERE namespace.nspname = ?
                   AND relation.relname = ?
                   AND NOT mapped_index.indisprimary
                 ORDER BY index_relation.relname
                 LIMIT ?
                """;
        List<ExpectedIndex> expected = new ArrayList<>();
        String idColumn = plan.columns().stream().filter(column -> column.role() == PgColumn.Role.ID)
                .findFirst().orElseThrow().name();
        for (PgIndex<?, ?, ?, ?> index : plan.indexes()) {
            expected.add(new ExpectedIndex(index.indexName(), false,
                    List.of(plan.tenantColumn(), plan.columns().get(index.columnIndex()).name(), idColumn)));
        }
        for (PgUnique unique : plan.uniqueConstraints()) {
            expected.add(new ExpectedIndex(unique.name(), true,
                    unique.columnIndexes().stream().map(position -> plan.columns().get(position).name()).toList()));
        }
        expected.sort(java.util.Comparator.comparing(ExpectedIndex::name));
        try (PreparedStatement statement = connection.prepareStatement(shapeSql)) {
            statement.setString(1, plan.schemaName());
            statement.setString(2, plan.tableName());
            statement.setInt(3, expected.size() + 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                for (ExpectedIndex index : expected) {
                    if (!resultSet.next() || !index.name().equals(resultSet.getString(1))) {
                        throw new IllegalStateException("Mapped secondary-index set does not match generated model: "
                                + plan.schemaName() + '.' + plan.tableName());
                    }
                    if (resultSet.getBoolean(9) != index.unique()
                            || resultSet.getInt(22) != index.columns().size()
                            || resultSet.getInt(23) != index.columns().size()) {
                        throw new IllegalStateException("Mapped PostgreSQL index uniqueness or key count differs: " + index.name());
                    }
                    for (int column = 2; column <= 24; column++) {
                        if (column != 9 && column != 22 && column != 23 && !resultSet.getBoolean(column)) {
                            throw new IllegalStateException("Mapped PostgreSQL index shape is unsafe: "
                                    + plan.schemaName() + '.' + index.name());
                        }
                    }
                    verifySecondaryIndexKeys(connection, plan, index);
                }
                if (resultSet.next()) {
                    throw new IllegalStateException("Undeclared PostgreSQL secondary index exists: "
                            + plan.schemaName() + '.' + resultSet.getString(1));
                }
            }
        }
    }

    private static void verifySecondaryIndexKeys(
            Connection connection,
            PgPlan<?, ?, ?, ?> plan,
            ExpectedIndex index) throws SQLException {
        String keysSql = """
                SELECT attribute.attname,
                       operator_namespace.nspname = 'pg_catalog',
                       operator_class.opcdefault,
                       (mapped_index.indcollation::pg_catalog.oid[])[key_position.position]
                           = attribute.attcollation,
                       (mapped_index.indoption::pg_catalog.int2[])[key_position.position] = 0
                  FROM pg_catalog.pg_index mapped_index
                  JOIN pg_catalog.pg_class relation ON relation.oid = mapped_index.indrelid
                  JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
                  JOIN pg_catalog.pg_class index_relation ON index_relation.oid = mapped_index.indexrelid
                  JOIN pg_catalog.pg_namespace index_namespace ON index_namespace.oid = index_relation.relnamespace
                  CROSS JOIN LATERAL pg_catalog.generate_subscripts(
                      mapped_index.indkey::pg_catalog.int2[], 1) AS key_position(position)
                  JOIN pg_catalog.pg_attribute attribute
                    ON attribute.attrelid = relation.oid
                   AND attribute.attnum = (mapped_index.indkey::pg_catalog.int2[])[key_position.position]
                  JOIN pg_catalog.pg_opclass operator_class
                    ON operator_class.oid = (mapped_index.indclass::pg_catalog.oid[])[key_position.position]
                  JOIN pg_catalog.pg_namespace operator_namespace
                    ON operator_namespace.oid = operator_class.opcnamespace
                 WHERE namespace.nspname = ?
                   AND relation.relname = ?
                   AND index_namespace.nspname = ?
                   AND index_relation.relname = ?
                 ORDER BY key_position.position
                """;
        try (PreparedStatement statement = connection.prepareStatement(keysSql)) {
            statement.setString(1, plan.schemaName());
            statement.setString(2, plan.tableName());
            statement.setString(3, plan.schemaName());
            statement.setString(4, index.name());
            try (ResultSet resultSet = statement.executeQuery()) {
                for (String expectedColumn : index.columns()) {
                    if (!resultSet.next()
                            || !expectedColumn.equals(resultSet.getString(1))
                            || !resultSet.getBoolean(2)
                            || !resultSet.getBoolean(3)
                            || !resultSet.getBoolean(4)
                            || !resultSet.getBoolean(5)) {
                        throw new IllegalStateException("Mapped PostgreSQL index keys do not match generated query: "
                                + plan.schemaName() + '.' + index.name());
                    }
                }
                if (resultSet.next()) {
                    throw new IllegalStateException("Mapped PostgreSQL index has undeclared key columns: "
                            + plan.schemaName() + '.' + index.name());
                }
            }
        }
    }

}
