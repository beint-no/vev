package no.beint.vev;

/**
 * A generated query which returns an explicitly bounded page of rows.
 *
 * @param <M> closed-model marker type
 * @param <R> result type
 */
public interface BoundedQuery<M, R> extends GeneratedQuery<M, R> {
    /**
     * Returns the mandatory application-visible result bound.
     *
     * @return mandatory application result bound
     */
    QueryLimit limit();
}
