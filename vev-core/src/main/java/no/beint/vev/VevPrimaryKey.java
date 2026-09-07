package no.beint.vev;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the exact physical PostgreSQL primary-key shape for a tenant-scoped record.
 *
 * <p>The default without this annotation is {@link Shape#TENANT_ID}. Other shapes require a declared
 * tenant-leading identifier index or unique constraint for bounded tenant traversal. A foreign key still requires
 * a tenant-qualified unique target. All application operations retain the lexical tenant boundary. Explicitly shared read-only
 * records require {@code ID} and omit the tenant traversal/reference columns.</p>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface VevPrimaryKey {
    /**
     * Returns the exact physical primary-key shape.
     *
     * @return declared column-role order
     */
    Shape value();

    /** Physical primary-key column-role orders accepted by the generated profile. */
    enum Shape {
        /** Tenant column followed by the scalar identifier. */
        TENANT_ID,
        /** Scalar identifier followed by the tenant column. */
        ID_TENANT,
        /** Globally unique scalar identifier, with separate tenant traversal and reference indexes. */
        ID
    }
}
