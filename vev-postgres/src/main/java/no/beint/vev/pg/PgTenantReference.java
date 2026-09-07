package no.beint.vev.pg;

import no.beint.vev.VevTenantReference;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Generated metadata for the tenant key's reference to an external registry primary key.
 *
 * @param name exact constraint name on the mapped source
 * @param schemaName registry schema
 * @param tableName registry table, outside the entity model
 * @param columnName registry primary-key column
 * @param onDelete declared action for registry administration outside Vev
 */
public record PgTenantReference(String name, String schemaName, String tableName, String columnName,
                                VevTenantReference.OnDelete onDelete) {
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{0,62}");

    /**
     * Validates bounded registry metadata before model assembly.
     * @param name constraint name
     * @param schemaName registry schema
     * @param tableName registry table
     * @param columnName registry primary-key column
     * @param onDelete registry deletion action
     */
    public PgTenantReference {
        for (String identifier : new String[]{name, schemaName, tableName, columnName}) {
            if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
                throw new IllegalArgumentException("Unsafe generated tenant-registry identifier");
            }
        }
        onDelete = Objects.requireNonNull(onDelete, "onDelete");
    }
}
