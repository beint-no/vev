package no.beint.vev.processor;

record TenantReferenceMapping(String name, String schema, String table, String column, String onDelete) {
    String registry() {
        return schema + '.' + table + '.' + column;
    }
}
