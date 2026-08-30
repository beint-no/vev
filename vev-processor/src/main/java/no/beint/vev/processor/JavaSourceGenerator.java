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
                .append(entity.appendOnly()
                        ? "no.beint.vev.pg.spi.PgEntityPlan<" + modelMarker + ", " + entity.qualifiedName()
                                + ", " + entity.id().boxedType() + ", " + entity.tenant().boxedType() + ">"
                        : "no.beint.vev.pg.spi.PgVersionedEntityPlan<" + modelMarker + ", "
                                + entity.qualifiedName() + ", " + entity.id().boxedType() + ", "
                                + entity.tenant().boxedType() + ", " + entity.version().boxedType() + ">")
                .append(" {\n")
                .append("    /** Singleton generated mapping plan for {@link ")
                .append(entity.qualifiedName()).append("}. */\n")
                .append("    public static final ").append(entity.simpleName()).append("Vev INSTANCE = new ")
                .append(entity.simpleName()).append("Vev();\n\n")
                .append("    private ").append(entity.simpleName()).append("Vev() {\n")
                .append("    }\n\n");
        source.append("    private static final java.util.List<no.beint.vev.pg.PgColumn> COLUMNS = java.util.List.of(\n");
        for (int index = 0; index < entity.properties().size(); index++) {
            PropertyMapping property = entity.properties().get(index);
            source.append("            new no.beint.vev.pg.PgColumn(\"")
                    .append(escape(property.columnName())).append("\", ")
                    .append(property.codec()).append(", ")
                    .append(property.nullable()).append(", no.beint.vev.pg.PgColumn.Role.")
                    .append(columnRole(property)).append(", ")
                    .append(property.maximumLength()).append(", ")
                    .append(property.numericPrecision()).append(", ")
                    .append(property.numericScale()).append(")")
                    .append(index + 1 == entity.properties().size() ? ");\n\n" : ",\n");
        }
        method(source, "public Class<" + entity.qualifiedName() + "> javaType()", "return " + entity.qualifiedName() + ".class;");
        method(source, "public Class<" + entity.id().boxedType() + "> keyType()", "return " + entity.id().boxedType() + ".class;");
        method(source, "public String logicalName()", "return \"" + escape(entity.qualifiedName()) + "\";");
        method(source, "public no.beint.vev.ModelIdentity modelIdentity()", "return " + entity.modelQualifiedName() + ".IDENTITY;");
        method(source, "public no.beint.vev.pg.PgCodec<" + entity.id().boxedType() + "> keyCodec()", "return " + entity.id().codec() + ";");
        method(source, "public no.beint.vev.pg.PgCodec<" + entity.tenant().boxedType() + "> tenantCodec()",
                "return " + entity.tenant().codec() + ";");
        method(source, "public String schemaName()", "return \"" + escape(entity.schemaName()) + "\";");
        method(source, "public String tableName()", "return \"" + escape(entity.tableName()) + "\";");
        method(source, "public String tenantColumn()", "return \"" + escape(entity.tenant().columnName()) + "\";");
        method(source, "public java.util.List<no.beint.vev.pg.PgColumn> columns()", "return COLUMNS;");
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
                .append(" instantiate(Object[] columnValues) {\n")
                .append("        java.util.Objects.requireNonNull(columnValues, \"columnValues\");\n")
                .append("        if (columnValues.length != ").append(entity.properties().size()).append(") {\n")
                .append("            throw new IllegalArgumentException(\"Expected ")
                .append(entity.properties().size()).append(" column values\");\n")
                .append("        }\n")
                .append("        return new ").append(entity.qualifiedName()).append("(\n");
        for (int index = 0; index < entity.properties().size(); index++) {
            PropertyMapping property = entity.properties().get(index);
            source.append("                ").append(instantiateExpression(property, index));
            source.append(index + 1 == entity.properties().size() ? ");\n" : ",\n");
        }
        source.append("    }\n\n");
        method(source, "public " + entity.id().boxedType() + " keyOf(" + entity.qualifiedName() + " entity)",
                "return entity." + entity.id().name() + "();");
        method(source, "public " + entity.tenant().boxedType() + " tenantKeyOf(" + entity.qualifiedName() + " entity)",
                "return entity." + entity.tenant().name() + "();");
        if (!entity.appendOnly()) {
            appendVersionedMethods(source, entity);
        }
        source.append("}\n");
        return source.toString();
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

    private String instantiateExpression(PropertyMapping property, int index) {
        String value = "(" + property.boxedType() + ") columnValues[" + index + "]";
        return property.nullable() && !isPrimitive(property.javaType())
                ? value
                : "(" + property.boxedType() + ") java.util.Objects.requireNonNull(columnValues[" + index
                        + "], \"Database returned NULL for " + escape(property.columnName()) + "\")";
    }

    private void method(StringBuilder source, String declaration, String statement) {
        source.append("    @Override\n")
                .append("    ").append(declaration).append(" {\n")
                .append("        ").append(statement).append("\n")
                .append("    }\n\n");
    }

    private boolean isPrimitive(String type) {
        return SetHolder.PRIMITIVES.contains(type);
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
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static final class SetHolder {
        private static final java.util.Set<String> PRIMITIVES = java.util.Set.of("boolean", "int", "long", "short");

        private SetHolder() {
        }
    }
}
