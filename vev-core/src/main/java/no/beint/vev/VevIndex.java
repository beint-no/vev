package no.beint.vev;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares one compile-time-safe PostgreSQL lookup index for a scalar value or identifier.
 *
 * <p>The processor emits a closed typed query token. Runtime bootstrap then attests the exact migration-installed,
 * non-unique B-tree over tenant key, annotated value, and entity identifier. An identifier index has only the
 * tenant and identifier columns. Explicitly shared records omit the tenant prefix.
 * An explicit ordering column precedes the identifier and shares its declared ASC/DESC direction.
 * Tenant and version components cannot be annotated. This annotation never
 * implies uniqueness.</p>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT})
public @interface VevIndex {
    /** Maximum generated query indexes accepted for one entity. */
    int MAXIMUM_INDEXES_PER_ENTITY = 16;

    /** Maximum declared character length accepted for an indexed String component. */
    int MAXIMUM_STRING_LENGTH = 256;

    /** Conservative maximum encoded bytes accepted across every generated index key, including the optional ordering value. */
    int MAXIMUM_RETAINED_KEY_BYTES = 1_536;

    /**
     * Returns the exact PostgreSQL index name verified when the model starts.
     *
     * @return the explicit database index name
     */
    String name();

    /**
     * Selects a non-null VALUE column before the identifier tie-breaker in the declared index/page order.
     * The default orders solely by identifier. This is an exact mapped column name, never a SQL expression.
     *
     * @return explicit ordering column name, or empty for identifier order
     */
    String orderBy() default "";

    /**
     * Selects the direction of both the ordering value and identifier tie-breaker.
     * Descending order requires an explicit {@link #orderBy()} column.
     * @return fixed generated query direction
     */
    Direction direction() default Direction.ASC;

    /** Closed set of ordered query directions. */
    enum Direction {
        /** Increasing ordering value, then increasing identifier. */
        ASC,
        /** Decreasing ordering value, then decreasing identifier. */
        DESC
    }
}
