package no.beint.vev.processor;

import java.util.List;
import javax.lang.model.element.TypeElement;

record EntityMapping(
        TypeElement declaration,
        String packageName,
        String simpleName,
        String qualifiedName,
        String planQualifiedName,
        String modelQualifiedName,
        String schemaName,
        String tableName,
        String tableSql,
        List<PropertyMapping> properties,
        PropertyMapping id,
        PropertyMapping tenant,
        PropertyMapping version,
        boolean appendOnly) {
}
