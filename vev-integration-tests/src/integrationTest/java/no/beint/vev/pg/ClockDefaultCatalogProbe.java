package no.beint.vev.pg;

import no.beint.vev.it.ClockDefaultEntryVev;
import no.beint.vev.it.IntegrationModelVev;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tests default-only clock approval on an ownership-attested disposable fixture connection. */
public final class ClockDefaultCatalogProbe {
    private ClockDefaultCatalogProbe() {
    }

    public static void verifyScopeAndSignatures(Connection connection) throws SQLException {
        configure(connection);
        var catalog = new PgCheckCatalog(connection);
        PgDefaults.verify(connection, catalog, IntegrationModelVev.POSTGRES.frozenPlan(ClockDefaultEntryVev.INSTANCE));
        try (var statement = connection.prepareStatement("""
                SELECT definition.adbin::text
                  FROM pg_catalog.pg_attrdef definition
                  JOIN pg_catalog.pg_attribute attribute ON attribute.attrelid = definition.adrelid AND attribute.attnum = definition.adnum
                 WHERE definition.adrelid = 'vev_it.clock_default'::pg_catalog.regclass
                   AND attribute.attname IN ('now_value', 'transaction_value') ORDER BY attribute.attnum
                """); var rows = statement.executeQuery()) {
            int count = 0;
            while (rows.next()) {
                count++;
                String tree = rows.getString(1);
                var dependencies = PgCheckTree.inspectDefaultExpression(tree).dependencies();
                // Approval cached while processing DEFAULTs must never enable a later CHECK.
                assertThrows(IllegalStateException.class, () -> catalog.verify(dependencies));
                catalog.verifyDefault(dependencies);
                var reverse = new PgCheckCatalog(connection);
                assertThrows(IllegalStateException.class, () -> reverse.verify(dependencies));
                reverse.verifyDefault(dependencies);
                assertThrows(IllegalStateException.class, () -> reverse.verify(dependencies));
                String constant = "{CONST :consttype 23 :consttypmod -1 :constcollid 0 :constlen 4 :constbyval true :constisnull false :location -1 :constvalue 4 [ 0 0 0 0 0 0 0 0 ]}";
                for (String malformed : List.of(tree.replace(":funcresulttype 1184", ":funcresulttype 23"),
                        tree.replace(":args <>", ":args (" + constant + ")"), tree.replace(":funcvariadic false", ":funcvariadic true"))) {
                    assertThrows(IllegalStateException.class, () -> catalog.verifyDefault(PgCheckTree.inspectDefaultExpression(malformed).dependencies()));
                }
                catalog.verifyDefault(dependencies);
            }
            assertEquals(2, count);
        }
    }

    public static void rejectBeforeDeparse(Connection connection) throws SQLException {
        configure(connection);
        var deparses = new AtomicInteger();
        var observed = (Connection) Proxy.newProxyInstance(ClockDefaultCatalogProbe.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("prepareStatement") && arguments[0] instanceof String sql && sql.contains("pg_catalog.pg_get_expr(adbin")) {
                        deparses.incrementAndGet();
                    }
                    try { return method.invoke(connection, arguments); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
        assertThrows(IllegalStateException.class, () -> PgDefaults.verify(observed, new PgCheckCatalog(observed),
                IntegrationModelVev.POSTGRES.frozenPlan(ClockDefaultEntryVev.INSTANCE)));
        assertEquals(0, deparses.get());
    }

    private static void configure(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SET TIME ZONE 'UTC'");
            statement.execute("SET DateStyle = 'ISO, MDY'");
            statement.execute("SET IntervalStyle = 'postgres'");
        }
    }
}
