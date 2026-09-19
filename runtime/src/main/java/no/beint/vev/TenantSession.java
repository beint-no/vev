package no.beint.vev;

import java.sql.Connection;
import java.sql.SQLException;

/** Explicit tenant capability. SQL predicates and database RLS remain the enforcement boundary. */
public final class TenantSession {
    private final Session session;
    private final int tenantId;
    private boolean active = true;

    private TenantSession(Session session, int tenantId) { this.session = session; this.tenantId = tenantId; }
    @FunctionalInterface public interface Work<R> { R run(TenantSession session) throws SQLException; }

    public Session session() {
        if (!active) throw new IllegalStateException("Tenant session escaped its scope");
        session.check();
        return session;
    }
    public int tenantId() { session(); return tenantId; }

    public static <R> R borrow(Connection connection, int tenantId, Work<R> work) throws SQLException {
        return Session.borrow(connection, session -> within(session, tenantId, work));
    }

    static <R> R within(Session session, int tenantId, Work<R> work) throws SQLException {
        if (tenantId <= 0) throw new IllegalArgumentException("Tenant identifier must be positive");
        TenantSession scope = new TenantSession(session, tenantId);
        try { return work.run(scope); }
        finally { scope.active = false; }
    }

    /** Explicitly install transaction-local vev.tenant_id for RLS, then restore its previous value. */
    public <R> R withRls(Work<R> work) throws SQLException {
        session();
        String previous = session.one("SELECT current_setting('vev.tenant_id', true)", b -> {}, r -> new String[] {r.getString(1)})[0];
        session.one("SELECT set_config('vev.tenant_id', ?, true)", b -> b.statement().setString(1, Integer.toString(tenantId)), r -> r.getString(1));
        TenantSession scope = new TenantSession(session, tenantId);
        Throwable failure = null;
        try { return work.run(scope); }
        catch (SQLException | RuntimeException | Error error) { failure = error; throw error; }
        finally {
            scope.active = false;
            try {
                session.one("SELECT set_config('vev.tenant_id', ?, true)", b -> b.statement().setString(1, previous == null ? "" : previous), r -> r.getString(1));
            } catch (SQLException | RuntimeException | Error restore) {
                if (failure != null) failure.addSuppressed(restore); else throw restore;
            }
        }
    }
}
