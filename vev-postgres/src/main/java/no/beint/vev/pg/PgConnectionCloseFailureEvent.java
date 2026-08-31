package no.beint.vev.pg;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

import java.sql.SQLException;

@Name("no.beint.vev.PostgreSQLConnectionCloseFailure")
@Label("PostgreSQL connection close failure after commit")
@Category({"Vev", "PostgreSQL"})
@StackTrace(false)
final class PgConnectionCloseFailureEvent extends Event {
    @Label("Failure category")
    String failureCategory;

    @Label("SQLSTATE")
    String sqlState;

    static void emit(Throwable failure) {
        try {
            PgConnectionCloseFailureEvent event = new PgConnectionCloseFailureEvent();
            if (!event.isEnabled()) {
                return;
            }
            if (failure instanceof SQLException sqlFailure) {
                event.failureCategory = "sql";
                String observedState = sqlFailure.getSQLState();
                event.sqlState = observedState != null && observedState.matches("[0-9A-Z]{5}")
                        ? observedState
                        : "unknown";
            } else if (failure instanceof Error) {
                event.failureCategory = "error";
                event.sqlState = "not-applicable";
            } else {
                event.failureCategory = "runtime";
                event.sqlState = "not-applicable";
            }
            event.commit();
        } catch (Throwable ignored) {
        }
    }
}
