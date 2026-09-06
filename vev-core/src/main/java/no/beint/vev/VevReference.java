package no.beint.vev;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a scalar reference to another entity's identifier in the same closed model.
 *
 * <p>The component keeps the target's exact identifier type. This annotation does not load another entity or
 * imply cascading writes. PostgreSQL must enforce the named immediate foreign key from the owning tenant and
 * component to the target tenant and identifier, with {@code MATCH SIMPLE} and {@code NO ACTION} behavior. A {@link VevShared} target instead
 * requires a scalar component-to-ID foreign key. Shared rows cannot reference tenant-owned rows.</p>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT})
public @interface VevReference {
    /**
     * Returns the exact foreign-key constraint name installed by the migration.
     *
     * @return explicit constraint name
     */
    String name();

    /**
     * Returns the referenced entity in the same closed model.
     *
     * @return entity whose scoped or explicitly shared identifier is referenced
     */
    Class<?> target();

    /**
     * Selects the exact order of both sides of a tenant-qualified database foreign key.
     * Shared targets require the default; their scalar foreign keys have no tenant column.
     *
     * @return true for (tenant, reference) to (tenant, ID); false for (reference, tenant) to (ID, tenant)
     */
    boolean tenantFirst() default true;
}
