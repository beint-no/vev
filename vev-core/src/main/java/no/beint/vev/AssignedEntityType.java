package no.beint.vev;

/**
 * Generated insertion capability for snapshots whose identifiers are assigned by the caller.
 *
 * <p>An entity's read or versioned-update capability alone does not permit assigned-identifier insertion.</p>
 *
 * @param <M> closed-model marker type
 * @param <E> entity snapshot type
 * @param <K> primary-key type
 */
public interface AssignedEntityType<M, E, K> extends EntityType<M, E, K> {
}
