package no.beint.vev;

/**
 * Opaque generated query identity with compile-time checked result type.
 *
 * <p>Providers must accept only their own verified generated implementations. Application code
 * never supplies SQL or runtime query strings through this contract.</p>
 *
 * @param <M> closed-model marker type
 * @param <R> result type
 */
public interface GeneratedQuery<M, R> {
    /**
     * Returns the closed model that owns this query.
     *
     * @return identity of the closed model which generated this query
     */
    ModelIdentity modelIdentity();

    /**
     * Returns the exact result class.
     *
     * @return exact result class
     */
    Class<R> resultType();
}
