package no.beint.vev;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares one compile-time-safe PostgreSQL lookup index for an ordinary scalar entity value.
 *
 * <p>The processor emits a closed typed query token. Runtime bootstrap then attests the exact migration-installed,
 * non-unique B-tree over tenant key, annotated value, and entity identifier. Identifier, tenant, and version
 * components cannot be annotated, and this annotation never implies uniqueness.</p>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.RECORD_COMPONENT)
public @interface VevIndex {
    /** Maximum generated query indexes accepted for one entity. */
    int MAXIMUM_INDEXES_PER_ENTITY = 16;

    /** Maximum declared character length accepted for an indexed String component. */
    int MAXIMUM_STRING_LENGTH = 256;

    /** Conservative maximum encoded bytes accepted across tenant, value, and identifier index keys. */
    int MAXIMUM_RETAINED_KEY_BYTES = 1_536;

    /**
     * Returns the exact PostgreSQL index name verified when the model starts.
     *
     * @return the explicit database index name
     */
    String name();
}
