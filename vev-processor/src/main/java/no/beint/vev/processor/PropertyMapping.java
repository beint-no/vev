package no.beint.vev.processor;

import javax.lang.model.element.Element;

record PropertyMapping(
        Element declaration,
        String name,
        String javaType,
        String boxedType,
        String codec,
        String arrayElementType,
        String columnName,
        boolean nullable,
        int maximumLength,
        int numericPrecision,
        int numericScale,
        boolean id,
        boolean tenant,
        boolean version,
        String indexName,
        String indexFieldName) {
    String quotedColumn() {
        return '"' + columnName + '"';
    }

    boolean indexed() {
        return !indexName.isEmpty();
    }
}
