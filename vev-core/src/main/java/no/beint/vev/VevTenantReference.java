package no.beint.vev;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the tenant key's foreign key to an external tenant registry.
 * The registry has no generated entity or read/write capability in this model.
 * Its referenced column must be its single-column primary key with the tenant key's exact scalar shape.
 * Registry administration requires a separate role; the Vev role must have no registry data privileges.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT})
public @interface VevTenantReference {
    /**
     * Names the foreign-key constraint.
     * @return exact constraint name
     */
    String name();
    /**
     * Names the registry schema.
     * @return explicit schema name
     */
    String schema();
    /**
     * Names the registry table.
     * @return explicit table name
     */
    String table();
    /**
     * Names the registry primary key.
     * @return explicit column name
     */
    String column();
    /**
     * Declares registry deletion behavior.
     * @return the migration's deletion action
     */
    OnDelete onDelete() default OnDelete.NO_ACTION;

    /** Supported actions when a separate administrator deletes a registry row. */
    enum OnDelete {
        /** Referencing rows prevent registry deletion. */
        NO_ACTION,
        /** PostgreSQL deletes referencing rows when their registry row is deleted. */
        CASCADE
    }
}
