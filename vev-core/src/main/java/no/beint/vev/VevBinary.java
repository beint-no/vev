package no.beint.vev;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares a bounded {@link Binary} value and its required PostgreSQL byte-length check constraint. */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.RECORD_COMPONENT, ElementType.FIELD, ElementType.METHOD})
public @interface VevBinary {
    /**
     * Returns the maximum stored byte count.
     *
     * @return bound from 1 through {@link Binary#MAXIMUM_LENGTH}, also subject to the entity's row budget
     */
    int maximumBytes();

    /**
     * Returns the explicit name of the migration-installed {@code octet_length(column) <= maximumBytes} check.
     *
     * @return safe lowercase PostgreSQL constraint identifier
     */
    String check();
}
