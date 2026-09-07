package no.beint.vev;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares reference rows intentionally readable by every tenant in the closed model.
 *
 * <p>Requires {@link VevReadOnly}, forbids {@link TenantKey}, and uses an ID-only primary key.
 * The application role has SELECT access only, with no row-security policies or identity-sequence privileges.
 * Reads still require a verified model-specific tenant transaction. This is an explicit data-sharing decision;
 * absence of a tenant annotation never implies that authentication, administrative, or business data is shared.</p>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface VevShared {
}
