package no.beint.vev;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares the complete, closed set of entity classes compiled into one Vev model. */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface VevModel {
    /** Largest closed entity set accepted by Vev's compiler and runtime. */
    int MAXIMUM_ENTITIES = 128;

    /** Largest mapped record shape accepted by Vev's compiler and runtime. */
    int MAXIMUM_COLUMNS = 64;

    /**
     * Lists the complete entity set compiled into this model.
     *
     * @return every entity in the model
     */
    Class<?>[] entities();
}
