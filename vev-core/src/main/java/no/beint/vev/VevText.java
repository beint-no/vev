package no.beint.vev;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Selects bounded PostgreSQL text for a String VALUE with an explicit Jakarta Column.length. */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD})
public @interface VevText {
    /** Maximum declared code-point bound, also subject to the complete entity row budget. */
    int MAXIMUM_LENGTH = 8 * 1024 * 1024;

    /**
     * Returns the migration-installed {@code char_length(column) <= Column.length} constraint name.
     *
     * @return safe lowercase PostgreSQL constraint identifier
     */
    String check();
}
