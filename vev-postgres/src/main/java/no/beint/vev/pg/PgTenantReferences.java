package no.beint.vev.pg;

import java.sql.Connection;
import java.sql.SQLException;

/** Verifies the external registry key once per bootstrap; never reads registry rows. */
final class PgTenantReferences {
    private PgTenantReferences() {
    }

    static void verify(Connection connection, PgModel<?, ?> model) throws SQLException {
        for (PgPlan<?, ?, ?, ?> plan : model.frozenPlans()) {
            for (PgTenantReference reference : plan.tenantReferences()) {
                PgColumn tenant = plan.columns().stream().filter(column -> column.role() == PgColumn.Role.TENANT)
                        .findFirst().orElseThrow();
                verify(connection, reference, tenant);
                // Model construction requires the same registry and scalar bounds on every declaration.
                return;
            }
        }
    }

    private static void verify(Connection connection, PgTenantReference reference, PgColumn tenant) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT relation.relkind = 'r' AND relation.relpersistence = 'p' AND NOT relation.relispartition
                           AND table_method.amname = 'heap'
                           AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_inherits inheritance
                                            WHERE inheritance.inhparent = relation.oid OR inheritance.inhrelid = relation.oid)
                           AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_rewrite rewrite WHERE rewrite.ev_class = relation.oid),
                       NOT pg_catalog.pg_has_role(current_user, pg_catalog.pg_get_userbyid(relation.relowner), 'MEMBER')
                           AND NOT pg_catalog.has_schema_privilege(current_user, namespace.oid, 'CREATE')
                           AND NOT pg_catalog.has_table_privilege(current_user, relation.oid, 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN')
                           AND NOT pg_catalog.has_any_column_privilege(current_user, relation.oid, 'SELECT,INSERT,UPDATE,REFERENCES'),
                       attribute.attnum > 0 AND NOT attribute.attisdropped AND attribute.attnotnull
                           AND attribute.atttypid = pg_catalog.to_regtype(?) AND attribute.atttypmod = ?
                           AND attribute.attgenerated = '' AND attribute.attidentity IN ('', 'a', 'd')
                           AND (NOT attribute.atthasmissing OR attribute.attmissingval IS NOT NULL)
                           AND type_namespace.nspname = 'pg_catalog' AND type.typtype = 'b',
                       registry_index.indisprimary AND registry_index.indisunique AND registry_index.indimmediate
                           AND registry_index.indisvalid AND registry_index.indisready AND registry_index.indislive
                           AND registry_index.indnkeyatts = 1 AND registry_index.indnatts = 1
                           AND registry_index.indkey[0] = attribute.attnum
                           AND registry_index.indexprs IS NULL AND registry_index.indpred IS NULL
                           AND registry_index.indcollation[0] = attribute.attcollation AND registry_index.indoption[0] = 0
                           AND index_method.amname = 'btree' AND operator_namespace.nspname = 'pg_catalog'
                           AND operator_class.opcdefault
                           AND primary_constraint.contype = 'p' AND primary_constraint.conrelid = relation.oid
                           AND primary_constraint.conislocal AND primary_constraint.coninhcount = 0
                           AND primary_constraint.conparentid = 0 AND NOT primary_constraint.conperiod
                           AND NOT primary_constraint.condeferrable AND NOT primary_constraint.condeferred
                           AND primary_constraint.convalidated AND primary_constraint.conenforced
                           AND pg_catalog.cardinality(primary_constraint.conkey) = 1
                           AND primary_constraint.conkey[1] = attribute.attnum
                  FROM pg_catalog.pg_class relation
                  JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
                  JOIN pg_catalog.pg_am table_method ON table_method.oid = relation.relam
                  JOIN pg_catalog.pg_attribute attribute ON attribute.attrelid = relation.oid AND attribute.attname = ?
                  JOIN pg_catalog.pg_type type ON type.oid = attribute.atttypid
                  JOIN pg_catalog.pg_namespace type_namespace ON type_namespace.oid = type.typnamespace
                  LEFT JOIN pg_catalog.pg_index registry_index ON registry_index.indrelid = relation.oid AND registry_index.indisprimary
                  LEFT JOIN pg_catalog.pg_class index_relation ON index_relation.oid = registry_index.indexrelid
                  LEFT JOIN pg_catalog.pg_am index_method ON index_method.oid = index_relation.relam
                  LEFT JOIN pg_catalog.pg_opclass operator_class ON operator_class.oid = registry_index.indclass[0]
                  LEFT JOIN pg_catalog.pg_namespace operator_namespace ON operator_namespace.oid = operator_class.opcnamespace
                  LEFT JOIN pg_catalog.pg_constraint primary_constraint ON primary_constraint.conindid = registry_index.indexrelid AND primary_constraint.contype = 'p'
                 WHERE namespace.nspname = ? AND relation.relname = ? LIMIT 2
                """)) {
            statement.setString(1, tenant.codec().databaseType());
            statement.setInt(2, tenant.expectedTypeModifier());
            statement.setString(3, reference.columnName());
            statement.setString(4, reference.schemaName());
            statement.setString(5, reference.tableName());
            try (var row = statement.executeQuery()) {
                if (!row.next()) throw invalid();
                for (int column = 1; column <= 4; column++) if (!row.getBoolean(column)) throw invalid();
                if (row.next()) throw invalid();
            }
        }
    }

    private static IllegalStateException invalid() {
        return new IllegalStateException("Tenant registry requires an external built-in heap and exact primary key, without application data privileges");
    }
}
