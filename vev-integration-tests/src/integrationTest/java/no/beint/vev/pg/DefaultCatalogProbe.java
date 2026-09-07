package no.beint.vev.pg;

import no.beint.vev.it.DefaultSampleVev;
import no.beint.vev.it.IntegrationModelVev;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Catalog verification on an ownership-attested disposable fixture connection. */
public final class DefaultCatalogProbe {
    private DefaultCatalogProbe() {
    }

    public static void rejectBeforeDeparse(Connection connection) throws SQLException {
        configure(connection);
        var deparses = new AtomicInteger();
        var observed = observe(connection, deparses, "");
        assertThrows(IllegalStateException.class, () -> PgDefaults.verify(observed, new PgCheckCatalog(observed),
                IntegrationModelVev.POSTGRES.frozenPlan(DefaultSampleVev.INSTANCE)));
        assertEquals(0, deparses.get(), "Unapproved dependencies must fail before deparsing any default");
    }

    public static void verifyResultContracts(Connection connection) throws SQLException {
        configure(connection);
        var deparses = new AtomicInteger();
        var valid = observe(connection, deparses, "");
        PgDefaults.verify(valid, new PgCheckCatalog(valid), IntegrationModelVev.POSTGRES.frozenPlan(DefaultSampleVev.INSTANCE));
        assertEquals(14, deparses.get());
        for (String corruption : List.of("resultType", "variable")) {
            deparses.set(0);
            var invalid = observe(connection, deparses, corruption);
            assertThrows(IllegalStateException.class, () -> PgDefaults.verify(invalid, new PgCheckCatalog(invalid),
                    IntegrationModelVev.POSTGRES.frozenPlan(DefaultSampleVev.INSTANCE)));
            assertEquals(0, deparses.get(), corruption);
        }
    }

    private static void configure(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SET TIME ZONE 'UTC'");
            statement.execute("SET DateStyle = 'ISO, MDY'");
            statement.execute("SET IntervalStyle = 'postgres'");
            statement.execute("SET bytea_output = 'hex'");
        }
    }

    private static Connection observe(Connection delegate, AtomicInteger deparses, String corruption) {
        return (Connection) Proxy.newProxyInstance(DefaultCatalogProbe.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("prepareStatement") && arguments[0] instanceof String sql) {
                        if (sql.contains("pg_catalog.pg_get_expr(adbin")) deparses.incrementAndGet();
                        if (sql.contains("FROM pg_catalog.pg_attrdef definition")) {
                            var statement = (PreparedStatement) invoke(delegate, method, arguments);
                            return Proxy.newProxyInstance(DefaultCatalogProbe.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                    (statementProxy, statementMethod, statementArguments) -> {
                                        Object result = invoke(statement, statementMethod, statementArguments);
                                        if (!(result instanceof ResultSet rows)) return result;
                                        return Proxy.newProxyInstance(DefaultCatalogProbe.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                                                (rowProxy, rowMethod, rowArguments) -> {
                                                    Object value = invoke(rows, rowMethod, rowArguments);
                                                    if (rowArguments != null && rowArguments.length == 1) {
                                                        if (corruption.equals("resultType") && rowMethod.getName().equals("getLong") && rowArguments[0].equals(3)) return 23L;
                                                        if (corruption.equals("variable") && rowMethod.getName().equals("getString") && rowArguments[0].equals(4)) {
                                                            return "{VAR :varno 1 :varattno 1 :vartype 16 :vartypmod -1 :varcollid 0 :varnullingrels (b) :varlevelsup 0 :varreturningtype 0 :varnosyn 1 :varattnosyn 1 :location -1}";
                                                        }
                                                    }
                                                    return value;
                                                });
                                    });
                        }
                    }
                    return invoke(delegate, method, arguments);
                });
    }

    private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
