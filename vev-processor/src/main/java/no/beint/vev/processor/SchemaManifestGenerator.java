package no.beint.vev.processor;

import java.util.List;
import java.util.stream.Collectors;

final class SchemaManifestGenerator {
    static String resourceName(CompiledModel model) {
        return "META-INF/vev/" + model.qualifiedName() + ".schema.json";
    }

    String generate(CompiledModel model) {
        return """
                {
                  "format": "vev-schema",
                  "formatVersion": 1,
                  "postgresqlMajor": 18,
                  "profile": "tenant-record-v1",
                  "model": %s,
                  "fingerprint": %s,
                  "entities": [
                %s
                  ]
                }
                """.formatted(quote(model.qualifiedName()), quote(model.fingerprint()),
                model.entities().stream().map(entity -> entity(model, entity)).collect(Collectors.joining(",\n")));
    }

    private String entity(CompiledModel model, EntityMapping entity) {
        String indexes = entity.properties().stream().filter(PropertyMapping::indexed)
                .map(property -> """
                            {"name": %s, "method": "btree", "unique": false, "columns": [%s]}"""
                        .formatted(quote(property.indexName()), property.id()
                                ? quote(entity.tenant().columnName()) + ", " + quote(entity.id().columnName())
                                : quote(entity.tenant().columnName()) + ", " + quote(property.columnName()) + ", " + quote(entity.id().columnName())))
                .collect(Collectors.joining(",\n")).indent(8).stripTrailing();
        String updates = entity.properties().stream()
                .filter(property -> !entity.appendOnly() && !property.id() && !property.tenant())
                .map(property -> quote(property.columnName())).collect(Collectors.joining(", "));
        return """
                    {
                      "javaType": %s,
                      "schema": %s,
                      "table": %s,
                      "appendOnly": %s,%s
                      "columns": [
                %s
                      ],
                      "primaryKey": [%s],
                      "indexes": [%s],
                      "uniqueConstraints": [%s],
                      "checkConstraints": [%s],
                      "references": [%s],
                      "rowSecurity": {"enabled": true, "forced": true, "tenantColumn": %s, "setting": "vev.tenant_id"},
                      "privileges": {"select": true, "insert": [%s], "update": [%s], "delete": false}
                    }""".formatted(
                quote(entity.qualifiedName()), quote(entity.schemaName()), quote(entity.tableName()),
                entity.appendOnly(), identity(entity), columns(entity.properties()), primaryKey(entity),
                indexes.isEmpty() ? "" : "\n" + indexes + "\n      ", uniqueConstraints(entity),
                entity.checkConstraints().stream().map(check -> "{\"name\": %s, \"expression\": %s}"
                        .formatted(quote(check.name()), quote(check.expression()))).collect(Collectors.joining(", ")),
                references(model, entity),
                quote(entity.tenant().columnName()), entity.properties().stream()
                        .map(property -> quote(property.columnName())).collect(Collectors.joining(", ")), updates);
    }

    private String identity(EntityMapping entity) {
        if (!entity.id().identity()) return "";
        long maximum = entity.id().boxedType().equals("java.lang.Short") ? Short.MAX_VALUE
                : entity.id().boxedType().equals("java.lang.Integer") ? Integer.MAX_VALUE : Long.MAX_VALUE;
        return "\n      \"identity\": {\"column\": %s, \"modes\": [\"ALWAYS\", \"BY DEFAULT\"], \"sequenceOwnership\": \"INTERNAL\", \"start\": 1, \"increment\": 1, \"minimum\": 1, \"maximum\": %d, \"cycle\": false, \"sequencePrivileges\": [\"USAGE\"]},"
                .formatted(quote(entity.id().columnName()), maximum);
    }

    private String primaryKey(EntityMapping entity) {
        return switch (entity.primaryKeyShape()) {
            case "TENANT_ID" -> quote(entity.tenant().columnName()) + ", " + quote(entity.id().columnName());
            case "ID_TENANT" -> quote(entity.id().columnName()) + ", " + quote(entity.tenant().columnName());
            case "ID" -> quote(entity.id().columnName());
            default -> throw new IllegalArgumentException("Unsupported primary-key shape");
        };
    }

    private String uniqueConstraints(EntityMapping entity) {
        return entity.uniqueConstraints().stream().map(unique ->
                "{\"name\": %s, \"columns\": [%s], \"method\": \"btree\", \"nullsDistinct\": true, \"deferrable\": false}"
                        .formatted(quote(unique.name()), unique.columns().stream().map(column -> quote(column.columnName()))
                                .collect(Collectors.joining(", ")))).collect(Collectors.joining(", "));
    }

    private String references(CompiledModel model, EntityMapping source) {
        return source.properties().stream().filter(PropertyMapping::reference).map(property -> {
            EntityMapping target = model.entities().stream()
                    .filter(candidate -> candidate.qualifiedName().equals(property.referenceTarget())).findFirst().orElseThrow();
            return """
                    {"name": %s, "columns": [%s, %s], "targetSchema": %s, "targetTable": %s, "targetColumns": [%s, %s], "match": "SIMPLE", "onUpdate": "NO ACTION", "onDelete": "NO ACTION", "deferrable": false}"""
                    .formatted(quote(property.referenceName()),
                            quote(property.referenceTenantFirst() ? source.tenant().columnName() : property.columnName()),
                            quote(property.referenceTenantFirst() ? property.columnName() : source.tenant().columnName()),
                            quote(target.schemaName()), quote(target.tableName()),
                            quote(property.referenceTenantFirst() ? target.tenant().columnName() : target.id().columnName()),
                            quote(property.referenceTenantFirst() ? target.id().columnName() : target.tenant().columnName()));
        }).collect(Collectors.joining(", "));
    }

    private String columns(List<PropertyMapping> properties) {
        return properties.stream().map(property -> """
                        {"name": %s, "javaType": %s, "databaseType": %s, "nullable": %s, "role": %s, "maximumLength": %d, "numericPrecision": %d, "numericScale": %d, "enumNames": [%s]}"""
                .formatted(quote(property.columnName()), quote(property.boxedType()),
                        quote(property.arrayElementType()), property.nullable(), quote(role(property)),
                        property.maximumLength(), property.numericPrecision(), property.numericScale(),
                        property.enumConstants().stream().map(SchemaManifestGenerator::quote).collect(Collectors.joining(", "))))
                .collect(Collectors.joining(",\n")).indent(8).stripTrailing();
    }

    private static String role(PropertyMapping property) {
        if (property.id()) return "ID";
        if (property.tenant()) return "TENANT";
        if (property.version()) return "VERSION";
        return "VALUE";
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                default -> {
                    if (character < 0x20 || Character.isSurrogate(character)) {
                        result.append("\\u").append(java.util.HexFormat.of().toHexDigits(character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.append('"').toString();
    }
}
