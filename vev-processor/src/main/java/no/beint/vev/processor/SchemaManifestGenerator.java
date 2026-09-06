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
                            {"name": %s, "method": "btree", "unique": false, "columns": [%s, %s, %s]}"""
                        .formatted(quote(property.indexName()), quote(entity.tenant().columnName()),
                                quote(property.columnName()), quote(entity.id().columnName())))
                .collect(Collectors.joining(",\n")).indent(8).stripTrailing();
        String updates = entity.properties().stream()
                .filter(property -> !entity.appendOnly() && !property.id() && !property.tenant())
                .map(property -> quote(property.columnName())).collect(Collectors.joining(", "));
        return """
                    {
                      "javaType": %s,
                      "schema": %s,
                      "table": %s,
                      "appendOnly": %s,
                      "columns": [
                %s
                      ],
                      "primaryKey": [%s, %s],
                      "indexes": [%s],
                      "uniqueConstraints": [%s],
                      "references": [%s],
                      "rowSecurity": {"enabled": true, "forced": true, "tenantColumn": %s, "setting": "vev.tenant_id"},
                      "privileges": {"select": true, "insert": [%s], "update": [%s], "delete": false}
                    }""".formatted(
                quote(entity.qualifiedName()), quote(entity.schemaName()), quote(entity.tableName()),
                entity.appendOnly(), columns(entity.properties()), quote(entity.tenant().columnName()),
                quote(entity.id().columnName()), indexes.isEmpty() ? "" : "\n" + indexes + "\n      ", uniqueConstraints(entity), references(model, entity),
                quote(entity.tenant().columnName()), entity.properties().stream()
                        .map(property -> quote(property.columnName())).collect(Collectors.joining(", ")), updates);
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
                    .formatted(quote(property.referenceName()), quote(source.tenant().columnName()), quote(property.columnName()),
                            quote(target.schemaName()), quote(target.tableName()), quote(target.tenant().columnName()), quote(target.id().columnName()));
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
