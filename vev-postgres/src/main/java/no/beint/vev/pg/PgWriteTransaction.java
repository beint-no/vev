package no.beint.vev.pg;

import no.beint.vev.TenantScope;
import no.beint.vev.WriteEntities;
import no.beint.vev.WriteTx;
import no.beint.vev.spi.TransactionGuard;

record PgWriteTransaction<M, T>(
        TenantScope<M, T> tenantValue,
        WriteEntities<M> entitiesValue,
        TransactionGuard guard) implements WriteTx<M, T> {
    @Override
    public TenantScope<M, T> tenant() {
        guard.checkUsable();
        return tenantValue;
    }

    @Override
    public WriteEntities<M> entities() {
        guard.checkUsable();
        return entitiesValue;
    }
}
