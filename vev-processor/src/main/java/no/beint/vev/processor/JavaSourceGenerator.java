package no.beint.vev.processor;

import java.util.List;

final class JavaSourceGenerator {
    String entityPlan(EntityMapping entity) {
        StringBuilder source = new StringBuilder();
        if (!entity.packageName().isEmpty()) {
            source.append("package ").append(entity.packageName()).append(";\n\n");
        }
        String modelMarker = entity.modelQualifiedName() + ".Model";
        source.append("/**\n")
                .append(" * Generated PostgreSQL plan for {@link ").append(entity.qualifiedName()).append("}.\n")
                .append(" *\n")
                .append(" * <p>This class is generated and must not be edited.</p>\n")
                .append(" */\n")
                .append("public final class ").append(entity.simpleName()).append("Vev implements ")
                .append(planInterfaces(entity, modelMarker))
                .append(" {\n")
                .append("    /** Singleton generated mapping plan for {@link ")
                .append(entity.qualifiedName()).append("}. */\n")
                .append("    public static final ").append(entity.simpleName()).append("Vev INSTANCE = new ")
                .append(entity.simpleName()).append("Vev();\n\n")
                .append("    private ").append(entity.simpleName()).append("Vev() {\n")
                .append("    }\n\n");
        if (entity.id().identity() && !entity.readOnly()) {
            appendCreationInput(source, entity);
        }
        for (int index = 0; index < entity.properties().size(); index++) {
            PropertyMapping property = entity.properties().get(index);
            if (!property.enumConstants().isEmpty()) {
                source.append("    private static final no.beint.vev.pg.PgCodec<").append(property.boxedType())
                        .append("> __VEV_CODEC_").append(index).append(" = ").append(property.codec()).append(";\n\n");
            }
        }
        source.append("    private static final java.util.List<no.beint.vev.pg.PgColumn> COLUMNS = java.util.List.of(\n");
        for (int index = 0; index < entity.properties().size(); index++) {
            PropertyMapping property = entity.properties().get(index);
            source.append("            new no.beint.vev.pg.PgColumn(\"")
                    .append(escape(property.columnName())).append("\", ")
                    .append(readerCodec(property, index)).append(", ")
                    .append(property.nullable()).append(", no.beint.vev.pg.PgColumn.Role.")
                    .append(columnRole(property)).append(", ")
                    .append(property.maximumLength()).append(", ")
                    .append(property.numericPrecision()).append(", ")
                    .append(property.numericScale()).append(")")
                    .append(index + 1 == entity.properties().size() ? ");\n\n" : ",\n");
        }
        appendIndexTokens(source, entity, modelMarker);
        appendIndexList(source, entity, modelMarker);
        appendReferences(source, entity);
        appendUniqueConstraints(source, entity);
        appendCheckConstraints(source, entity);
        // Embed this generator's contract, never a runtime version lookup in generated output.
        method(source, "public int generatedPlanAbi()", "return 2;");
        method(source, "public Class<" + entity.qualifiedName() + "> javaType()", "return " + entity.qualifiedName() + ".class;");
        method(source, "public Class<" + entity.id().boxedType() + "> keyType()", "return " + entity.id().boxedType() + ".class;");
        method(source, "public String logicalName()", "return \"" + escape(entity.qualifiedName()) + "\";");
        method(source, "public no.beint.vev.ModelIdentity modelIdentity()", "return " + entity.modelQualifiedName() + ".IDENTITY;");
        method(source, "public int maximumRows()", "return " + entity.maximumRows() + ";");
        method(source, "public no.beint.vev.pg.PgCodec<" + entity.id().boxedType() + "> keyCodec()", "return " + entity.id().codec() + ";");
        method(source, "public no.beint.vev.pg.PgCodec<" + entity.tenant().boxedType() + "> tenantCodec()",
                "return " + entity.tenant().codec() + ";");
        method(source, "public String schemaName()", "return \"" + escape(entity.schemaName()) + "\";");
        method(source, "public String tableName()", "return \"" + escape(entity.tableName()) + "\";");
        method(source, "public String tenantColumn()", "return \"" + escape(entity.tenant().columnName()) + "\";");
        method(source, "public no.beint.vev.VevPrimaryKey.Shape primaryKeyShape()",
                "return no.beint.vev.VevPrimaryKey.Shape." + entity.primaryKeyShape() + ";");
        method(source, "public java.util.List<no.beint.vev.pg.PgColumn> columns()", "return COLUMNS;");
        method(source, "public java.util.List<no.beint.vev.pg.PgIndex<" + modelMarker + ", "
                        + entity.qualifiedName() + ", " + entity.id().boxedType() + ", ?>> indexes()",
                "return INDEXES;");
        source.append("    @Override\n")
                .append("    public Object columnValue(").append(entity.qualifiedName())
                .append(" entity, int columnIndex) {\n")
                .append("        java.util.Objects.requireNonNull(entity, \"entity\");\n")
                .append("        return switch (columnIndex) {\n");
        for (int index = 0; index < entity.properties().size(); index++) {
            PropertyMapping property = entity.properties().get(index);
            source.append("            case ").append(index).append(" -> entity.")
                    .append(property.name()).append("();\n");
        }
        source.append("            default -> throw new IndexOutOfBoundsException(columnIndex);\n")
                .append("        };\n")
                .append("    }\n\n")
                .append("    @Override\n")
                .append("    public ").append(entity.qualifiedName())
                .append(" readRow(java.sql.ResultSet resultSet, int firstColumn) throws java.sql.SQLException {\n")
                .append("        java.util.Objects.requireNonNull(resultSet, \"resultSet\");\n")
                .append("        if (firstColumn < 1 || firstColumn > ").append(Integer.MAX_VALUE - entity.properties().size() + 1).append(") {\n")
                .append("            throw new IllegalArgumentException(\"Mapped result columns require valid one-based positions\");\n")
                .append("        }\n")
                .append("        return new ").append(entity.qualifiedName()).append("(\n");
        for (int index = 0; index < entity.properties().size(); index++) {
            PropertyMapping property = entity.properties().get(index);
            source.append("                ").append(readerCodec(property, index)).append(".readChecked(resultSet, firstColumn")
                    .append(index == 0 ? "" : " + " + index).append(", COLUMNS.get(").append(index).append("))");
            source.append(index + 1 == entity.properties().size() ? ");\n" : ",\n");
        }
        source.append("    }\n\n");
        method(source, "public " + entity.id().boxedType() + " keyOf(" + entity.qualifiedName() + " entity)",
                "return entity." + entity.id().name() + "();");
        method(source, "public " + entity.tenant().boxedType() + " tenantKeyOf(" + entity.qualifiedName() + " entity)",
                "return entity." + entity.tenant().name() + "();");
        if (!entity.appendOnly() && !entity.readOnly()) {
            appendVersionedMethods(source, entity);
        }
        source.append("}\n");
        return source.toString();
    }

    private static String planInterfaces(EntityMapping entity, String modelMarker) {
        String types = modelMarker + ", " + entity.qualifiedName() + ", " + entity.id().boxedType();
        String tenantTypes = types + ", " + entity.tenant().boxedType();
        var interfaces = new java.util.ArrayList<String>();
        if (entity.readOnly()) {
            interfaces.add("no.beint.vev.pg.spi.PgReadOnlyEntityPlan<" + tenantTypes + ">");
            interfaces.add("no.beint.vev.pg.spi.PgTenantEntityPlan<" + tenantTypes + ">");
            if (entity.id().identity()) interfaces.add("no.beint.vev.pg.spi.PgIdentityEntityPlan<" + tenantTypes + ">");
        } else {
            interfaces.add(entity.appendOnly() ? "no.beint.vev.pg.spi.PgTenantEntityPlan<" + tenantTypes + ">"
                    : "no.beint.vev.pg.spi.PgVersionedEntityPlan<" + tenantTypes + ", " + entity.version().boxedType() + ">");
            interfaces.add(entity.id().identity() ? "no.beint.vev.pg.spi.PgGeneratedEntityPlan<" + tenantTypes + ", " + entity.simpleName() + "Vev.New>"
                    : "no.beint.vev.AssignedEntityType<" + types + ">");
            if (entity.deletable()) interfaces.add("no.beint.vev.DeletableEntityType<" + types + ", " + entity.version().boxedType() + ">");
        }
        return String.join(", ", interfaces);
    }

    private void appendCreationInput(StringBuilder source, EntityMapping entity) {
        List<PropertyMapping> values = entity.properties().stream()
                .filter(property -> !property.id() && !property.tenant() && !property.version()).toList();
        source.append("    /**\n     * Immutable application values for a new database-identified snapshot.\n     *\n");
        for (PropertyMapping value : values) {
            source.append("     * @param ").append(value.name()).append(" value for column ").append(value.columnName()).append("\n");
        }
        source.append("     */\n    public record New(")
                .append(values.stream().map(value -> value.javaType() + " " + value.name())
                        .collect(java.util.stream.Collectors.joining(", "))).append(") {\n    }\n\n");
        method(source, "public Class<New> creationType()", "return New.class;");
        if (values.isEmpty()) {
            method(source, "public Object creationColumnValue(New input, int columnIndex)",
                    "throw new IndexOutOfBoundsException(columnIndex);");
            return;
        }
        source.append("    @Override\n    public Object creationColumnValue(New input, int columnIndex) {\n")
                .append("        java.util.Objects.requireNonNull(input, \"input\");\n")
                .append("        return switch (columnIndex) {\n");
        for (PropertyMapping value : values) {
            source.append("            case ").append(entity.properties().indexOf(value)).append(" -> input.")
                    .append(value.name()).append("();\n");
        }
        source.append("            default -> throw new IndexOutOfBoundsException(columnIndex);\n")
                .append("        };\n    }\n\n");
    }

    private void appendCheckConstraints(StringBuilder source, EntityMapping entity) {
        source.append("    private static final java.util.List<no.beint.vev.pg.PgCheck> __VEV_CHECKS = java.util.List.of(");
        for (int index = 0; index < entity.checkConstraints().size(); index++) {
            CheckMapping check = entity.checkConstraints().get(index);
            if (check.kind() != CheckMapping.Kind.EXACT) {
                source.append(index == 0 ? "\n" : ",\n")
                        .append("            no.beint.vev.pg.PgCheck.")
                        .append(check.kind() == CheckMapping.Kind.BINARY_MAXIMUM ? "binaryMaximum" : "textMaximum")
                        .append("(\"").append(escape(check.name()))
                        .append("\", \"").append(escape(check.boundColumn())).append("\", ").append(check.maximumLength()).append(')');
                continue;
            }
            source.append(index == 0 ? "\n" : ",\n")
                    .append("            new no.beint.vev.pg.PgCheck(\"").append(escape(check.name()))
                    .append("\", \"").append(escape(check.expression())).append("\")");
        }
        source.append(");\n\n");
        method(source, "public java.util.List<no.beint.vev.pg.PgCheck> checkConstraints()", "return __VEV_CHECKS;");
    }

    private void appendUniqueConstraints(StringBuilder source, EntityMapping entity) {
        source.append("    private static final java.util.List<no.beint.vev.pg.PgUnique> UNIQUE_CONSTRAINTS = java.util.List.of(");
        for (int index = 0; index < entity.uniqueConstraints().size(); index++) {
            UniqueMapping unique = entity.uniqueConstraints().get(index);
            source.append(index == 0 ? "\n" : ",\n")
                    .append("            new no.beint.vev.pg.PgUnique(\"").append(escape(unique.name()))
                    .append("\", java.util.List.of(")
                    .append(unique.columns().stream().map(column -> Integer.toString(entity.properties().indexOf(column)))
                            .collect(java.util.stream.Collectors.joining(", "))).append("))");
        }
        source.append(");\n\n");
        method(source, "public java.util.List<no.beint.vev.pg.PgUnique> uniqueConstraints()", "return UNIQUE_CONSTRAINTS;");
    }

    private void appendReferences(StringBuilder source, EntityMapping entity) {
        source.append("    private static final java.util.List<no.beint.vev.pg.PgReference> REFERENCES = java.util.List.of(");
        List<PropertyMapping> references = entity.properties().stream().filter(PropertyMapping::reference).toList();
        for (int index = 0; index < references.size(); index++) {
            PropertyMapping property = references.get(index);
            source.append(index == 0 ? "\n" : ",\n")
                    .append("            new no.beint.vev.pg.PgReference(\"").append(escape(property.referenceName()))
                    .append("\", ").append(entity.properties().indexOf(property)).append(", ")
                    .append(property.referenceTarget()).append(property.referenceTenantFirst() ? ".class)" : ".class, false)");
        }
        source.append(");\n\n");
        method(source, "public java.util.List<no.beint.vev.pg.PgReference> references()", "return REFERENCES;");
    }

    private void appendIndexTokens(StringBuilder source, EntityMapping entity, String modelMarker) {
        for (int columnIndex = 0; columnIndex < entity.properties().size(); columnIndex++) {
            PropertyMapping property = entity.properties().get(columnIndex);
            if (!property.indexed()) {
                continue;
            }
            String indexType = property.nullable() ? "PgNullableIndex" : "PgRequiredIndex";
            source.append("    /** Compile-time query token for PostgreSQL index ")
                    .append(escape(property.indexName())).append(". */\n")
                    .append("    public static final no.beint.vev.pg.").append(indexType).append('<')
                    .append(modelMarker).append(", ")
                    .append(entity.qualifiedName()).append(", ")
                    .append(entity.id().boxedType()).append(", ")
                    .append(property.boxedType()).append("> ")
                    .append(property.indexFieldName()).append(" = new no.beint.vev.pg.")
                    .append(indexType).append("<>(INSTANCE, \"")
                    .append(escape(property.indexName())).append("\", ")
                    .append(columnIndex).append(", ")
                    .append(property.boxedType()).append(".class);\n\n");
        }
    }

    private void appendIndexList(StringBuilder source, EntityMapping entity, String modelMarker) {
        source.append("    private static final java.util.List<no.beint.vev.pg.PgIndex<")
                .append(modelMarker).append(", ")
                .append(entity.qualifiedName()).append(", ")
                .append(entity.id().boxedType()).append(", ?>> INDEXES = java.util.List.of(");
        List<PropertyMapping> indexes = entity.properties().stream().filter(PropertyMapping::indexed).toList();
        if (indexes.isEmpty()) {
            source.append(");\n\n");
            return;
        }
        source.append('\n');
        for (int index = 0; index < indexes.size(); index++) {
            source.append("            ").append(indexes.get(index).indexFieldName())
                    .append(index + 1 == indexes.size() ? ");\n\n" : ",\n");
        }
    }

    String modelRegistry(CompiledModel model) {
        StringBuilder source = new StringBuilder();
        if (!model.packageName().isEmpty()) {
            source.append("package ").append(model.packageName()).append(";\n\n");
        }
        source.append("/**\n")
                .append(" * Generated closed-model registry for {@link ").append(model.qualifiedName()).append("}.\n")
                .append(" *\n")
                .append(" * <p>This class is generated and must not be edited.</p>\n")
                .append(" */\n")
                .append("public final class ").append(model.simpleName()).append(" {\n")
                .append("    /** Phantom marker that makes entity and tenant capabilities model-specific. */\n")
                .append("    public static final class Model {\n")
                .append("        private Model() {\n")
                .append("        }\n")
                .append("    }\n\n")
                .append("    /** Stable generated name and mapping fingerprint for this closed model. */\n")
                .append("    public static final no.beint.vev.ModelIdentity IDENTITY = new no.beint.vev.ModelIdentity(\n")
                .append("            \"").append(escape(model.qualifiedName())).append("\",\n")
                .append("            \"").append(escape(model.fingerprint())).append("\");\n")
                .append("    /** Class-output resource describing this model's PostgreSQL schema contract. */\n")
                .append("    public static final String SCHEMA_MANIFEST = \"")
                .append(SchemaManifestGenerator.resourceName(model)).append("\";\n")
                .append("    /** Validated immutable PostgreSQL plan set for this closed model. */\n")
                .append("    public static final no.beint.vev.pg.PgModel<Model, ")
                .append(model.entities().getFirst().tenant().boxedType())
                .append("> POSTGRES = no.beint.vev.pg.PgModel.of(\n")
                .append("            IDENTITY");
        for (EntityMapping entity : model.entities()) {
            source.append(",\n            ").append(entity.planQualifiedName()).append(".INSTANCE");
        }
        String tenantType = model.entities().getFirst().tenant().boxedType();
        source.append(");\n\n")
                .append("    /**\n")
                .append("     * Creates the single-use authority which may be claimed by one verified runtime.\n")
                .append("     *\n")
                .append("     * @return a new unclaimed authority for this closed model\n")
                .append("     */\n")
                .append("    public static no.beint.vev.TenantAuthority<Model, ")
                .append(tenantType)
                .append("> newTenantAuthority() {\n")
                .append("        return no.beint.vev.TenantAuthority.create(Model.class, IDENTITY, ")
                .append(tenantType)
                .append(".class);\n")
                .append("    }\n\n")
                .append("    private ").append(model.simpleName()).append("() {\n")
                .append("    }\n")
                .append("}\n");
        return source.toString();
    }

    private void appendVersionedMethods(StringBuilder source, EntityMapping entity) {
        method(source, "public Class<" + entity.version().boxedType() + "> versionType()",
                "return " + entity.version().boxedType() + ".class;");
        method(source, "public no.beint.vev.pg.PgCodec<" + entity.version().boxedType() + "> versionCodec()",
                "return " + entity.version().codec() + ";");
        method(source, "public " + entity.version().boxedType() + " versionOf(" + entity.qualifiedName() + " entity)",
                "return entity." + entity.version().name() + "();");
    }

    private String readerCodec(PropertyMapping property, int index) {
        return property.enumConstants().isEmpty() ? property.codec() : "__VEV_CODEC_" + index;
    }

    private void method(StringBuilder source, String declaration, String statement) {
        source.append("    @Override\n")
                .append("    ").append(declaration).append(" {\n")
                .append("        ").append(statement).append("\n")
                .append("    }\n\n");
    }

    private String columnRole(PropertyMapping property) {
        if (property.id()) {
            return "ID";
        }
        if (property.tenant()) {
            return "TENANT";
        }
        if (property.version()) {
            return "VERSION";
        }
        return "VALUE";
    }

    private String escape(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                default -> {
                    if (character < 32 || character == 127) {
                        result.append('\\').append(String.format(java.util.Locale.ROOT, "%03o", (int) character));
                    } else result.append(character);
                }
            }
        }
        return result.toString();
    }

}
