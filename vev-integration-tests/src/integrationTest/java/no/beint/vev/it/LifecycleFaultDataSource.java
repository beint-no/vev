package no.beint.vev.it;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.logging.Logger;

final class LifecycleFaultDataSource implements DataSource {
    enum Mode {
        CLOSE_AFTER_COMMIT,
        COMMIT_AFTER_SUCCESS,
        FINGERPRINT_RESOURCE_AND_CONNECTION_CLOSE
    }

    private final DataSource delegate;
    private final Mode mode;

    LifecycleFaultDataSource(DataSource delegate, Mode mode) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    @Override
    public Connection getConnection() throws SQLException {
        return wrap(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return wrap(delegate.getConnection(username, password));
    }

    private Connection wrap(Connection connection) {
        class State {
            private boolean committed;

            Object invoke(Method method, Object[] arguments) throws Throwable {
                if (method.getName().equals("commit") && method.getParameterCount() == 0) {
                    Object result = invokeTarget(connection, method, arguments);
                    committed = true;
                    if (mode == Mode.COMMIT_AFTER_SUCCESS) {
                        throw sensitiveFailure("commit");
                    }
                    return result;
                }
                if (method.getName().equals("prepareStatement")
                        && arguments != null
                        && arguments.length > 0
                        && arguments[0] instanceof String sql
                        && sql.contains("FROM public.vev_schema_fingerprint WHERE model_name")
                        && mode == Mode.FINGERPRINT_RESOURCE_AND_CONNECTION_CLOSE) {
                    PreparedStatement statement = (PreparedStatement) invokeTarget(connection, method, arguments);
                    return wrapFingerprintStatement(statement);
                }
                if (method.getName().equals("close") && method.getParameterCount() == 0) {
                    Object result = invokeTarget(connection, method, arguments);
                    if (committed && mode == Mode.CLOSE_AFTER_COMMIT) {
                        throw sensitiveFailure("connection close after commit");
                    }
                    if (mode == Mode.FINGERPRINT_RESOURCE_AND_CONNECTION_CLOSE) {
                        throw sensitiveFailure("bootstrap connection close");
                    }
                    return result;
                }
                return invokeTarget(connection, method, arguments);
            }
        }
        State state = new State();
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> state.invoke(method, arguments));
    }

    private PreparedStatement wrapFingerprintStatement(PreparedStatement statement) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class},
                (proxy, method, arguments) -> {
                    Object result = invokeTarget(statement, method, arguments);
                    if (method.getName().equals("executeQuery") && result instanceof ResultSet resultSet) {
                        return wrapFingerprintResultSet(resultSet);
                    }
                    if (method.getName().equals("close") && method.getParameterCount() == 0) {
                        throw sensitiveFailure("fingerprint statement close");
                    }
                    return result;
                });
    }

    private ResultSet wrapFingerprintResultSet(ResultSet resultSet) {
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class},
                (proxy, method, arguments) -> {
                    Object result = invokeTarget(resultSet, method, arguments);
                    if (method.getName().equals("close") && method.getParameterCount() == 0) {
                        throw sensitiveFailure("fingerprint result close");
                    }
                    return result;
                });
    }

    private static Object invokeTarget(Object target, Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static SQLException sensitiveFailure(String operation) {
        return new SQLException("sensitive-fixture-value during " + operation, "08006");
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter output) throws SQLException {
        delegate.setLogWriter(output);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        return delegate.unwrap(type);
    }

    @Override
    public boolean isWrapperFor(Class<?> type) throws SQLException {
        return delegate.isWrapperFor(type);
    }
}
