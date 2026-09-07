package no.beint.vev.pg;

record PgDeletionSql(String single, String multiple, PgColumn id, PgColumn tenant, PgColumn version) {
    static PgDeletionSql compile(PgPlan<?, ?, ?, ?> plan) {
        PgColumn id = column(plan, PgColumn.Role.ID);
        PgColumn tenant = column(plan, PgColumn.Role.TENANT);
        PgColumn version = column(plan, PgColumn.Role.VERSION);
        String table = quoted(plan.schemaName()) + '.' + quoted(plan.tableName());
        String identityMatch = "r." + quoted(id.name()) + " = i.k AND r." + quoted(tenant.name()) + " = i.t";
        String versionMatch = "r." + quoted(version.name()) + " = i.v";
        String returned = "r." + quoted(id.name()) + " AS k, r." + quoted(tenant.name()) + " AS t, r."
                + quoted(version.name()) + " AS v";
        String single = "WITH i AS MATERIALIZED (SELECT ?::" + id.codec().sqlType() + " AS k, ?::"
                + tenant.codec().sqlType() + " AS t, ?::" + version.codec().sqlType() + " AS v), "
                + "d AS (DELETE FROM ONLY " + table + " AS r USING i WHERE " + identityMatch + " AND " + versionMatch
                + " RETURNING " + returned + ") SELECT 0, k, t, v FROM d UNION ALL SELECT 1, " + returned
                + " FROM " + table + " AS r, i WHERE " + identityMatch + " AND NOT EXISTS (SELECT 1 FROM d)";
        String multiple = "WITH a AS MATERIALIZED (SELECT ?::" + id.codec().sqlType() + "[] AS keys, ?::"
                + version.codec().sqlType() + "[] AS versions, ?::" + tenant.codec().sqlType()
                + " AS t, ?::\"pg_catalog\".\"int4\" AS expected), "
                + "i AS MATERIALIZED (SELECT x.k, a.t, x.v, x.ordinality FROM a CROSS JOIN LATERAL "
                + "ROWS FROM (pg_catalog.unnest(a.keys), pg_catalog.unnest(a.versions)) WITH ORDINALITY AS x(k, v, ordinality) "
                + "WHERE pg_catalog.cardinality(a.keys) = a.expected AND pg_catalog.cardinality(a.versions) = a.expected), "
                + "m AS MATERIALIZED (SELECT i.* FROM " + table + " AS r JOIN i ON " + identityMatch + " AND " + versionMatch + "), "
                + "d AS (DELETE FROM ONLY " + table + " AS r USING m AS i WHERE " + identityMatch + " AND " + versionMatch
                + " AND (SELECT pg_catalog.count(*) FROM m) = (SELECT expected FROM a) RETURNING i.ordinality, "
                + returned + ") SELECT ordinality, k, t, v FROM d ORDER BY ordinality";
        return new PgDeletionSql(single, multiple, id, tenant, version);
    }

    private static PgColumn column(PgPlan<?, ?, ?, ?> plan, PgColumn.Role role) {
        return plan.columns().stream().filter(column -> column.role() == role).findFirst().orElseThrow();
    }

    private static String quoted(String name) {
        return '"' + name + '"';
    }
}
