package no.beint.vev.pg;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Generated metadata for an immediate, non-cascading tenant-composite foreign key.
 *
 * @param name exact database constraint name
 * @param columnIndex zero-based index of the scalar reference column
 * @param targetType referenced entity in the same closed model
 * @param tenantFirst whether both sides put the tenant column before the identifier column
 */
public record PgReference(String name, int columnIndex, Class<?> targetType, boolean tenantFirst) {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,62}");

    /**
     * Validates reference metadata before model assembly.
     *
     * @param name exact database constraint name
     * @param columnIndex zero-based index of the reference column
     * @param targetType referenced entity class
     * @param tenantFirst whether the tenant column is first on both sides
     */
    public PgReference {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException("Unsafe generated PostgreSQL foreign-key identifier");
        }
        if (columnIndex < 0 || columnIndex >= no.beint.vev.VevModel.MAXIMUM_COLUMNS) {
            throw new IllegalArgumentException("Reference column is outside the generated row bounds");
        }
        targetType = Objects.requireNonNull(targetType, "targetType");
    }

    /**
     * Creates metadata for a tenant-first reference.
     *
     * @param name exact database constraint name
     * @param columnIndex zero-based index of the reference column
     * @param targetType referenced entity class
     */
    public PgReference(String name, int columnIndex, Class<?> targetType) {
        this(name, columnIndex, targetType, true);
    }
}
