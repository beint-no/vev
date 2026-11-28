package no.beint.vev;

/** Capability proving an active tenant-bound read-write Vev transaction for tenant write or locking queries. */
public sealed interface TenantWriteScope extends TenantReadScope, WriteScope permits Session.TenantWrite {
}
