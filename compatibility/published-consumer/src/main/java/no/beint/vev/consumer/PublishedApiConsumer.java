package no.beint.vev.consumer;

import no.beint.vev.TenantAuthority;
import no.beint.vev.jakarta.VevEntityAgents;
import no.beint.vev.pg.PgModel;
import no.beint.vev.pg.PgVev;

import javax.sql.DataSource;
import java.util.UUID;

public final class PublishedApiConsumer {
    private static final PgModel<PublishedModelVev.Model, Integer> GENERATED_MODEL = PublishedModelVev.POSTGRES;
    private static final TenantAuthority<PublishedModelVev.Model, Integer> GENERATED_AUTHORITY =
            PublishedModelVev.newTenantAuthority();

    private PublishedApiConsumer() {
    }

    public static void exerciseGeneratedApi(DataSource dataSource) {
        PgVev<PublishedModelVev.Model, Integer> vev =
                new PgVev<>(dataSource, GENERATED_MODEL, GENERATED_AUTHORITY);
        var tenant = GENERATED_AUTHORITY.scope(7);
        VevEntityAgents.runInTransaction(
                vev,
                tenant,
                agent -> agent.find(PublishedAccount.class, new UUID(0L, 0L)));
        PublishedAccountVev.INSTANCE.modelIdentity();
    }

    public static <M> void exercise(
            DataSource dataSource,
            PgModel<M, Integer> model,
            TenantAuthority<M, Integer> tenantAuthority) {
        PgVev<M, Integer> vev = new PgVev<>(dataSource, model, tenantAuthority);
        var tenant = tenantAuthority.scope(7);
        VevEntityAgents.runInTransaction(vev, tenant, agent -> agent.getProperties());
    }
}
