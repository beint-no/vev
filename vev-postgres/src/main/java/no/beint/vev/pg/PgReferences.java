package no.beint.vev.pg;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

final class PgReferences {
    private PgReferences() {
    }

    static void verify(Connection connection, PgModel<?, ?> model, PgPlan<?, ?, ?, ?> plan) throws SQLException {
        Map<ConstraintKey, ExpectedReference> expected = new HashMap<>();
        for (PgPlan<?, ?, ?, ?> source : model.frozenPlans()) {
            for (PgReference reference : source.references()) {
                PgPlan<?, ?, ?, ?> target = model.frozenPlan(reference.targetType());
                if (source == plan || target == plan) {
                    expected.put(new ConstraintKey(source.schemaName(), source.tableName(), reference.name()),
                            new ExpectedReference(source, reference, target));
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(ATTESTATION_SQL)) {
            statement.setString(1, plan.schemaName());
            statement.setString(2, plan.tableName());
            statement.setString(3, plan.schemaName());
            statement.setString(4, plan.tableName());
            statement.setInt(5, expected.size() + 1);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ConstraintKey key = new ConstraintKey(rows.getString(1), rows.getString(2), rows.getString(3));
                    ExpectedReference reference = expected.remove(key);
                    if (reference == null) {
                        throw new IllegalStateException("Undeclared foreign key touches a mapped table: " + key);
                    }
                    reference.verify(rows);
                }
                if (!expected.isEmpty()) {
                    throw new IllegalStateException("Generated foreign keys are missing from PostgreSQL: " + expected.keySet());
                }
            }
        }
    }

    private record ConstraintKey(String schema, String table, String name) {
    }

    private record ExpectedReference(PgPlan<?, ?, ?, ?> source, PgReference reference, PgPlan<?, ?, ?, ?> target) {
        void verify(ResultSet row) throws SQLException {
            String targetId = target.columns().stream().filter(column -> column.role() == PgColumn.Role.ID)
                    .findFirst().orElseThrow().name();
            String sourceId = source.columns().get(reference.columnIndex()).name();
            boolean tenantFirst = reference.tenantFirst();
            if (!target.schemaName().equals(row.getString(4))
                    || !target.tableName().equals(row.getString(5))
                    || !(tenantFirst ? source.tenantColumn() : sourceId).equals(row.getString(6))
                    || !(tenantFirst ? sourceId : source.tenantColumn()).equals(row.getString(7))
                    || !(tenantFirst ? target.tenantColumn() : targetId).equals(row.getString(8))
                    || !(tenantFirst ? targetId : target.tenantColumn()).equals(row.getString(9))) {
                throw new IllegalStateException("Foreign key does not match its generated tenant-composite reference: "
                        + source.logicalName() + '.' + reference.name());
            }
            for (int column = 10; column <= 14; column++) {
                if (!row.getBoolean(column)) {
                    throw new IllegalStateException("Foreign key has unsupported enforcement, actions, or operators: "
                            + source.logicalName() + '.' + reference.name());
                }
            }
        }
    }

    private static final String ATTESTATION_SQL = """
            SELECT source_namespace.nspname, source_relation.relname, constraint_definition.conname,
                   target_namespace.nspname, target_relation.relname,
                   source_tenant.attname, source_value.attname, target_tenant.attname, target_id.attname,
                   NOT constraint_definition.condeferrable
                       AND NOT constraint_definition.condeferred
                       AND constraint_definition.convalidated AND constraint_definition.conenforced
                       AND constraint_definition.conislocal AND constraint_definition.coninhcount = 0
                       AND constraint_definition.conparentid = 0 AND NOT constraint_definition.conperiod
                       AND constraint_definition.confmatchtype = 's'
                       AND constraint_definition.confupdtype = 'a' AND constraint_definition.confdeltype = 'a'
                       AND constraint_definition.confdelsetcols IS NULL
                       AND pg_catalog.cardinality(constraint_definition.conkey) = 2
                       AND pg_catalog.cardinality(constraint_definition.confkey) = 2,
                   source_tenant.atttypid = target_tenant.atttypid
                       AND source_tenant.atttypmod = target_tenant.atttypmod
                       AND source_tenant.attcollation = target_tenant.attcollation
                       AND source_value.atttypid = target_id.atttypid
                       AND source_value.atttypmod = target_id.atttypmod
                       AND source_value.attcollation = target_id.attcollation,
                   pg_catalog.cardinality(constraint_definition.conpfeqop) = 2
                       AND pg_catalog.cardinality(constraint_definition.conppeqop) = 2
                       AND pg_catalog.cardinality(constraint_definition.conffeqop) = 2
                       AND NOT EXISTS (
                           SELECT 1 FROM pg_catalog.unnest(constraint_definition.conpfeqop
                                   || constraint_definition.conppeqop || constraint_definition.conffeqop) AS equality(operator_oid)
                           LEFT JOIN pg_catalog.pg_operator comparison ON comparison.oid = equality.operator_oid
                           LEFT JOIN pg_catalog.pg_namespace operator_namespace ON operator_namespace.oid = comparison.oprnamespace
                           WHERE operator_namespace.nspname IS DISTINCT FROM 'pg_catalog'
                              OR comparison.oprname IS DISTINCT FROM '='),
                   (SELECT pg_catalog.count(*) = 4
                               AND pg_catalog.bool_and(trigger.tgisinternal AND trigger.tgenabled = 'O'
                                   AND NOT trigger.tgdeferrable AND NOT trigger.tginitdeferred
                                   AND trigger.tgconstrindid = constraint_definition.conindid
                                   AND trigger.tgnargs = 0 AND pg_catalog.octet_length(trigger.tgargs) = 0
                                   AND trigger.tgqual IS NULL AND trigger.tgoldtable IS NULL AND trigger.tgnewtable IS NULL
                                   AND pg_catalog.cardinality(trigger.tgattr::pg_catalog.int2[]) = 0
                                   AND function_namespace.nspname = 'pg_catalog'
                                   AND CASE function.proname
                                       WHEN 'RI_FKey_check_ins' THEN trigger.tgtype = 5
                                           AND trigger.tgrelid = source_relation.oid AND trigger.tgconstrrelid = target_relation.oid
                                       WHEN 'RI_FKey_check_upd' THEN trigger.tgtype = 17
                                           AND trigger.tgrelid = source_relation.oid AND trigger.tgconstrrelid = target_relation.oid
                                       WHEN 'RI_FKey_noaction_del' THEN trigger.tgtype = 9
                                           AND trigger.tgrelid = target_relation.oid AND trigger.tgconstrrelid = source_relation.oid
                                       WHEN 'RI_FKey_noaction_upd' THEN trigger.tgtype = 17
                                           AND trigger.tgrelid = target_relation.oid AND trigger.tgconstrrelid = source_relation.oid
                                       ELSE false END)
                               AND pg_catalog.string_agg(function.proname::pg_catalog.text, ',' ORDER BY function.proname::pg_catalog.text)
                                   = 'RI_FKey_check_ins,RI_FKey_check_upd,RI_FKey_noaction_del,RI_FKey_noaction_upd'
                        FROM pg_catalog.pg_trigger trigger
                        JOIN pg_catalog.pg_proc function ON function.oid = trigger.tgfoid
                        JOIN pg_catalog.pg_namespace function_namespace ON function_namespace.oid = function.pronamespace
                       WHERE trigger.tgconstraint = constraint_definition.oid),
                   EXISTS (SELECT 1 FROM pg_catalog.pg_index primary_index
                            WHERE primary_index.indrelid = target_relation.oid
                              AND primary_index.indexrelid = constraint_definition.conindid
                              AND primary_index.indisprimary)
              FROM pg_catalog.pg_constraint constraint_definition
              JOIN pg_catalog.pg_class source_relation ON source_relation.oid = constraint_definition.conrelid
              JOIN pg_catalog.pg_namespace source_namespace ON source_namespace.oid = source_relation.relnamespace
              JOIN pg_catalog.pg_class target_relation ON target_relation.oid = constraint_definition.confrelid
              JOIN pg_catalog.pg_namespace target_namespace ON target_namespace.oid = target_relation.relnamespace
              LEFT JOIN pg_catalog.pg_attribute source_tenant
                ON source_tenant.attrelid = source_relation.oid AND source_tenant.attnum = constraint_definition.conkey[1]
              LEFT JOIN pg_catalog.pg_attribute source_value
                ON source_value.attrelid = source_relation.oid AND source_value.attnum = constraint_definition.conkey[2]
              LEFT JOIN pg_catalog.pg_attribute target_tenant
                ON target_tenant.attrelid = target_relation.oid AND target_tenant.attnum = constraint_definition.confkey[1]
              LEFT JOIN pg_catalog.pg_attribute target_id
                ON target_id.attrelid = target_relation.oid AND target_id.attnum = constraint_definition.confkey[2]
             WHERE constraint_definition.contype = 'f'
               AND ((source_namespace.nspname = ? AND source_relation.relname = ?)
                 OR (target_namespace.nspname = ? AND target_relation.relname = ?))
             ORDER BY source_namespace.nspname, source_relation.relname, constraint_definition.conname
             LIMIT ?
            """;
}
