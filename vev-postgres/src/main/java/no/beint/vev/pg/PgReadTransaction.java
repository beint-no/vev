package no.beint.vev.pg;

import no.beint.vev.ReadEntities;
import no.beint.vev.ReadTx;
import no.beint.vev.TenantScope;
import no.beint.vev.spi.TransactionGuard;

record PgReadTransaction<M, T>(
        TenantScope<M, T> tenantValue,
        ReadEntities<M> entitiesValue,
        TransactionGuard guard) implements ReadTx<M, T> {
    @Override
    public TenantScope<M, T> tenant() {
        guard.checkUsable();
        return tenantValue;
    }

    @Override
    public ReadEntities<M> entities() {
        guard.checkUsable();
        return entitiesValue;
    }
}
