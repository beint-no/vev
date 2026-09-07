package no.beint.vev;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Explicitly permits physical deletion with an expected optimistic version.
 *
 * <p>Only versioned entities with database-generated identities may opt in. Assigned identifiers could be
 * reinserted with an initial version after deletion, making stale references valid again. Append-only entities
 * cannot opt in. Deletion never cascades; declared immediate foreign keys remain enforced.</p>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface VevDelete {
}
