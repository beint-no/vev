package no.beint.vev;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Limits materialized batches and query pages for one entity to retain a compile-verified memory budget.
 *
 * <p>Without this annotation the limit is 1,000. A smaller explicit limit permits larger bounded snapshots.
 * PostgreSQL queries may retrieve one additional sentinel row to determine whether another page exists.</p>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface VevRows {
    /**
     * Returns the maximum number of entity snapshots in one batch or query page.
     *
     * @return an explicit limit from 1 through 1,000
     */
    int value();
}
