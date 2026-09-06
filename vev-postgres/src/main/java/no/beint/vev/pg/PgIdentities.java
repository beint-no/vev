package no.beint.vev.pg;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.IdentityHashMap;
import java.util.Map;

final class PgIdentities {
    private PgIdentities() {
    }

    static Map<PgPlan<?, ?, ?, ?>, Long> verify(Connection connection, Iterable<? extends PgPlan<?, ?, ?, ?>> plans)
            throws SQLException {
        Map<PgPlan<?, ?, ?, ?>, Long> sequences = new IdentityHashMap<>();
        String sql = """
                SELECT sequence.oid,
                       sequence.relkind = 'S' AND sequence.relpersistence = 'p',
                       sequence.relowner = relation.relowner AND sequence.relnamespace = relation.relnamespace,
                       settings.seqtypid = attribute.atttypid,
                       settings.seqstart = 1 AND settings.seqincrement = 1 AND settings.seqmin = 1,
                       settings.seqmax, NOT settings.seqcycle AND settings.seqcache >= 1,
                       pg_catalog.has_sequence_privilege(current_user, sequence.oid, 'USAGE'),
                       pg_catalog.has_sequence_privilege(current_user, sequence.oid, 'SELECT'),
                       pg_catalog.has_sequence_privilege(current_user, sequence.oid, 'UPDATE'),
                       pg_catalog.has_sequence_privilege(current_user, sequence.oid, 'USAGE WITH GRANT OPTION'),
                       attribute.attidentity IN ('a', 'd')
                  FROM pg_catalog.pg_class relation
                  JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
                  JOIN pg_catalog.pg_attribute attribute ON attribute.attrelid = relation.oid
                  JOIN pg_catalog.pg_depend dependency
                    ON dependency.refclassid = 'pg_catalog.pg_class'::pg_catalog.regclass
                   AND dependency.refobjid = relation.oid AND dependency.refobjsubid = attribute.attnum
                   AND dependency.classid = 'pg_catalog.pg_class'::pg_catalog.regclass
                   AND dependency.objsubid = 0 AND dependency.deptype = 'i'
                  JOIN pg_catalog.pg_class sequence ON sequence.oid = dependency.objid
                  JOIN pg_catalog.pg_sequence settings ON settings.seqrelid = sequence.oid
                 WHERE namespace.nspname = ? AND relation.relname = ? AND attribute.attname = ?
                   AND attribute.attnum > 0 AND NOT attribute.attisdropped
                 LIMIT 2
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (PgPlan<?, ?, ?, ?> plan : plans) {
                if (!plan.generatedIdentity()) continue;
                PgColumn id = plan.columns().stream().filter(column -> column.role() == PgColumn.Role.ID)
                        .findFirst().orElseThrow();
                statement.setString(1, plan.schemaName());
                statement.setString(2, plan.tableName());
                statement.setString(3, id.name());
                long maximum = plan.keyType() == Short.class ? Short.MAX_VALUE
                        : plan.keyType() == Integer.class ? Integer.MAX_VALUE : Long.MAX_VALUE;
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw invalid(plan);
                    long oid = rows.getLong(1);
                    if (oid < 1 || oid > 0xffff_ffffL || !rows.getBoolean(2) || !rows.getBoolean(3)
                            || !rows.getBoolean(4) || !rows.getBoolean(5) || rows.getLong(6) != maximum
                            || !rows.getBoolean(7) || !rows.getBoolean(8) || rows.getBoolean(9)
                            || rows.getBoolean(10) || rows.getBoolean(11) || !rows.getBoolean(12) || rows.next()) {
                        throw invalid(plan);
                    }
                    sequences.put(plan, oid);
                }
            }
        }
        return java.util.Collections.unmodifiableMap(sequences);
    }

    private static IllegalStateException invalid(PgPlan<?, ?, ?, ?> plan) {
        return new IllegalStateException("Identity sequence ownership, shape, or privileges do not match generated model: "
                + plan.schemaName() + '.' + plan.tableName());
    }
}
