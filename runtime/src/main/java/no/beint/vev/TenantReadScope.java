package no.beint.vev;

/** Capability proving an active tenant-bound Vev transaction for tenant read queries. */
public sealed interface TenantReadScope extends ReadScope permits TenantWriteScope, Session.TenantRead {
    /** Returns the positive tenant identifier bound to this scope. */
    int tenantId();
}
