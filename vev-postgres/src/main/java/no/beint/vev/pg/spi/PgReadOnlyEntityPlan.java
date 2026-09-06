package no.beint.vev.pg.spi;

/**
 * Generated read-only mapping contract with no mutation capabilities or database write privileges.
 *
 * @param <M> closed-model marker type
 * @param <E> entity snapshot type
 * @param <K> primary-key type
 * @param <T> tenant-key type
 */
public interface PgReadOnlyEntityPlan<M, E, K, T> extends PgEntityPlan<M, E, K, T> {
    /**
     * Returns whether incoming foreign keys from tables outside this model are outside schema attestation.
     * Outgoing and within-model references always retain their exact generated contracts.
     *
     * @return the explicit read-only mapping choice
     */
    boolean externalIncomingReferences();
}
