package no.beint.vev.it;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.atomic.AtomicInteger;

final class IdentityObservationDataSource {
    private IdentityObservationDataSource() {
    }

    static DataSource observe(DataSource delegate, AtomicInteger statements, String corruption) {
        return proxy(DataSource.class, (method, arguments) -> {
            Object result = invoke(delegate, method, arguments);
            if (result instanceof Connection connection) {
                return proxy(Connection.class, (connectionMethod, connectionArguments) -> {
                    Object value = invoke(connection, connectionMethod, connectionArguments);
                    if (connectionMethod.getName().equals("prepareStatement")
                            && connectionArguments[0] instanceof String sql && sql.contains("\"__vev_created\" AS")) {
                        statements.incrementAndGet();
                        if (corruption.isEmpty()) return value;
                        return proxy(PreparedStatement.class, (statementMethod, statementArguments) -> {
                            Object rowResult = invoke(value, statementMethod, statementArguments);
                            if (rowResult instanceof ResultSet rows) {
                                return proxy(ResultSet.class, (rowMethod, rowArguments) -> {
                                    Object original = invoke(rows, rowMethod, rowArguments);
                                    if (rowArguments == null || rowArguments.length != 1) return original;
                                    int column = rowArguments[0] instanceof Integer index ? index : -1;
                                    return switch (corruption) {
                                        case "ordinal" -> rowMethod.getName().equals("getLong") && column == 1 ? 0L : original;
                                        case "key" -> rowMethod.getName().equals("getLong") && column == 2 ? -1L : original;
                                        case "tenant" -> rowMethod.getName().equals("getInt") && column == 4 ? 8 : original;
                                        case "version" -> rowMethod.getName().equals("getShort") && column == 5 ? (short) 1 : original;
                                        case "value" -> rowMethod.getName().equals("getString") && column == 6 ? "unexpected" : original;
                                        default -> throw new IllegalArgumentException(corruption);
                                    };
                                });
                            }
                            return rowResult;
                        });
                    }
                    return value;
                });
            }
            return result;
        });
    }

    private static Object invoke(Object delegate, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(delegate, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(IdentityObservationDataSource.class.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> invocation.invoke(method, arguments)));
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(Method method, Object[] arguments) throws Throwable;
    }
}
