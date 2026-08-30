package no.beint.vev.jakarta;

import jakarta.persistence.EntityAgent;
import no.beint.vev.TenantScope;
import no.beint.vev.pg.PgVev;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** Lexical transaction entry points for Vev's narrow Jakarta Persistence {@link EntityAgent} profile. */
public final class VevEntityAgents {
    private VevEntityAgents() {
    }

    /**
     * Runs work with a thread-confined agent inside one Vev write transaction.
     *
     * <p>The agent closes after the callback. A normal return commits only after Vev revalidates the transaction;
     * callback or validation failure rolls it back.</p>
     *
     * @param vev verified PostgreSQL runtime
     * @param tenant scope minted by that runtime's tenant authority
     * @param work lexical work which must not retain or cross-thread the agent
     * @param <M> closed-model marker type
     * @param <T> tenant-key type
     */
    public static <M, T> void runInTransaction(
            PgVev<M, T> vev,
            TenantScope<M, T> tenant,
            Consumer<? super EntityAgent> work) {
        Objects.requireNonNull(work, "work");
        callInTransaction(vev, tenant, agent -> {
            work.accept(agent);
            return null;
        });
    }

    /**
     * Calls work with a thread-confined agent inside one Vev write transaction.
     *
     * <p>The agent closes after the callback. The result is returned only after Vev revalidates and commits the
     * transaction; callback or validation failure rolls it back.</p>
     *
     * @param vev verified PostgreSQL runtime
     * @param tenant scope minted by that runtime's tenant authority
     * @param work lexical function which must not retain or cross-thread the agent
     * @param <M> closed-model marker type
     * @param <T> tenant-key type
     * @param <R> callback result type
     * @return callback result after a successful commit
     */
    public static <M, T, R> R callInTransaction(
            PgVev<M, T> vev,
            TenantScope<M, T> tenant,
            Function<? super EntityAgent, ? extends R> work) {
        Objects.requireNonNull(vev, "vev");
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(work, "work");
        return vev.write(tenant, transaction -> {
            VevEntityAgent<M, T> agent = new VevEntityAgent<>(
                    vev.model(), transaction.entities(), transaction.tenant().tenantId());
            try {
                R result = work.apply(agent);
                agent.requireCommittable();
                return result;
            } finally {
                agent.close();
            }
        });
    }
}
