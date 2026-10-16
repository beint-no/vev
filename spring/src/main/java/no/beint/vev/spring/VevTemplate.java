package no.beint.vev.spring;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import no.beint.vev.Session;
import no.beint.vev.TenantSession;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs generated queries inside the caller's Spring transaction.
 *
 * <p>Vev never commits, rolls back or closes a Spring-owned connection. An active transaction is
 * required so that reads observe the same snapshot, and the same connection, as the surrounding
 * service method.
 *
 * <p>When Hibernate manages entities in the same transaction, its pending changes are not visible
 * to plain SQL until they are flushed. Supply {@code beforeQuery} as {@code entityManager::flush}
 * wherever generated queries must observe writes made earlier in the same transaction.
 */
public final class VevTemplate {
    /** A hook run before each query, normally an {@code EntityManager} flush. */
    @FunctionalInterface public interface BeforeQuery { void run(); }

    private final DataSource dataSource;
    private final BeforeQuery beforeQuery;

    public VevTemplate(DataSource dataSource) { this(dataSource, null); }

    public VevTemplate(DataSource dataSource, BeforeQuery beforeQuery) {
        this.dataSource = java.util.Objects.requireNonNull(dataSource, "dataSource");
        this.beforeQuery = beforeQuery;
    }

    public <R> R query(Session.Work<R> work) throws SQLException {
        return Session.borrow(connection(), work);
    }

    public <R> R query(int tenantId, TenantSession.Work<R> work) throws SQLException {
        return TenantSession.borrow(connection(), tenantId, work);
    }

    private Connection connection() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Vev requires an active Spring transaction; annotate the caller with @Transactional");
        }
        if (beforeQuery != null) beforeQuery.run();
        return DataSourceUtils.getConnection(dataSource);
    }
}
