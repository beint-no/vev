package no.beint.vev;

/** Capability proving an active read-write Vev transaction for global write or locking queries. */
public sealed interface WriteScope extends ReadScope permits TenantWriteScope, Session.GlobalWrite {
    /** Registers work to run on this transaction immediately before commit. */
    void beforeCommit(Runnable action);
}
