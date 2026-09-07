package no.beint.vev;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Restricts a mapped snapshot to reads, including when it is used inside a write transaction.
 *
 * <p>The generated plan exposes no insertion, creation, update, or deletion capability. A declared identity or
 * version describes stored data without granting mutation. The database role must have only SELECT access to
 * this table and no privileges on its identity sequence. Tenant isolation and schema verification still apply.</p>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface VevReadOnly {
    /**
     * Accepts incoming foreign keys whose source tables are outside the generated model.
     *
     * <p>These external constraints and their write-time enforcement are outside this read-only mapping's
     * contract. Vev neither loads their rows nor changes their constraints. Outgoing references and references
     * from any mapped source remain fully declared and verified. The default requires complete closure.</p>
     *
     * @return whether unmapped tables may reference this SELECT-only table
     */
    boolean externalIncomingReferences() default false;
}
