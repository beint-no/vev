package no.beint.vev.it;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

final class DeleteObservationDataSource {
    private DeleteObservationDataSource() {
    }

    static DataSource observe(DataSource delegate, AtomicInteger statements, String corruption) {
        return proxy(DataSource.class, (method, arguments) -> {
            Object result = invoke(delegate, method, arguments);
            if (!(result instanceof Connection connection)) return result;
            return proxy(Connection.class, (operation, parameters) -> {
                if (corruption.equals("arrayLength") && operation.getName().equals("createArrayOf")) {
                    Object[] changed = parameters.clone();
                    Object[] input = (Object[]) parameters[1];
                    changed[1] = java.util.Arrays.copyOf(input, input.length - 1);
                    return invoke(connection, operation, changed);
                }
                Object value = invoke(connection, operation, parameters);
                if (!operation.getName().equals("prepareStatement") || !(parameters[0] instanceof String sql)
                        || !sql.contains("DELETE FROM ONLY \"vev_it\"")) return value;
                statements.incrementAndGet();
                if (corruption.isEmpty()) return value;
                return proxy(PreparedStatement.class, (action, inputs) -> {
                    if (corruption.equals("driverFailure") && action.getName().equals("executeQuery")) {
                        throw new SQLException("Synthetic delete execution failure");
                    }
                    Object executed = invoke(value, action, inputs);
                    if (corruption.equals("statementClose") && action.getName().equals("close")) {
                        throw new SQLException("Synthetic delete statement cleanup failure");
                    }
                    if (!(executed instanceof ResultSet rows)) return executed;
                    var extra = new AtomicInteger();
                    return proxy(ResultSet.class, (read, positions) -> {
                        Object original = invoke(rows, read, positions);
                        int column = positions != null && positions.length == 1 && positions[0] instanceof Integer index ? index : -1;
                        return switch (corruption) {
                            case "key" -> read.getName().equals("getInt") && column == 2 ? -1 : original;
                            case "tenant" -> read.getName().equals("getInt") && column == 3 ? 8 : original;
                            case "version" -> read.getName().equals("getInt") && column == 4 ? 1 : original;
                            case "outcome" -> read.getName().equals("getInt") && column == 1 ? 2 : original;
                            case "ordinal" -> read.getName().equals("getLong") && column == 1 ? 0L : original;
                            case "null" -> read.getName().equals("wasNull") ? true : original;
                            case "missing" -> read.getName().equals("next") ? false : original;
                            case "extra" -> read.getName().equals("next") && Boolean.FALSE.equals(original)
                                    && extra.getAndIncrement() == 0 ? true : original;
                            case "readerError" -> {
                                if (read.getName().equals("getInt") && column == 2) throw new AssertionError("Synthetic delete reader error");
                                yield original;
                            }
                            case "resultClose" -> {
                                if (read.getName().equals("close")) throw new SQLException("Synthetic delete result cleanup failure");
                                yield original;
                            }
                            case "statementClose", "arrayLength", "driverFailure" -> original;
                            default -> throw new IllegalArgumentException(corruption);
                        };
                    });
                });
            });
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
        return type.cast(Proxy.newProxyInstance(DeleteObservationDataSource.class.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> invocation.invoke(method, arguments)));
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(Method method, Object[] arguments) throws Throwable;
    }
}
