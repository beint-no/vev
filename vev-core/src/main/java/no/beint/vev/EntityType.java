package no.beint.vev;

/**
 * Generated identity and Java type seam for one entity mapping.
 *
 * <p>This contract deliberately contains no SQL or reflective member access.</p>
 *
 * @param <M> closed-model marker type
 * @param <E> entity snapshot type
 * @param <K> primary-key type
 */
public interface EntityType<M, E, K> {
    /**
     * Returns the exact entity class.
     *
     * @return exact entity class
     */
    Class<E> javaType();

    /**
     * Returns the exact boxed primary-key class.
     *
     * @return exact non-primitive primary-key class
     */
    Class<K> keyType();

    /**
     * Returns the stable entity name in the closed model.
     *
     * @return stable logical name inside the closed model
     */
    String logicalName();

    /**
     * Returns the identity shared by every plan in the closed model.
     *
     * @return identity shared by all generated plans in the closed model
     */
    ModelIdentity modelIdentity();

    /**
     * Creates a type-bound key.
     *
     * @param value primary-key value
     * @return validated key
     */
    default EntityKey<M, E, K> key(K value) {
        return new EntityKey<>(this, value);
    }
}
