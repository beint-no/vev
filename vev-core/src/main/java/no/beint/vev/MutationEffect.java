package no.beint.vev;

/** Database effect selected by an applied versioned mutation. */
public enum MutationEffect {
    /** A new row was inserted. */
    INSERTED,
    /** An existing row was updated. */
    UPDATED
}
