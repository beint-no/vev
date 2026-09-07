package no.beint.vev;

/**
 * Generated creation capability whose identifier is allocated by the database.
 *
 * <p>The creation input contains only application values. The lexical transaction supplies the tenant and Vev
 * initializes the version. Creation returns a new, fully identified immutable snapshot.</p>
 *
 * @param <M> closed-model marker type
 * @param <E> persisted snapshot type
 * @param <K> generated primary-key type
 * @param <N> generated creation-input type
 */
public interface GeneratedEntityType<M, E, K, N> extends EntityType<M, E, K> {
    /**
     * Returns the exact generated immutable creation-input class.
     *
     * @return creation-input class
     */
    Class<N> creationType();
}
