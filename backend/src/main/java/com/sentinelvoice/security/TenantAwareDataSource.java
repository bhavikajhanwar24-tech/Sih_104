package com.sentinelvoice.security;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * Sets {@code app.tenant_id} from {@link TenantContext} on every borrow.
 * Uses session-level {@code set_config(..., false)} because transaction-local
 * ({@code true}) is cleared when Hibernate obtains the connection before BEGIN.
 * Cleared again when the connection is returned to the pool.
 */
public final class TenantAwareDataSource implements DataSource {

    private final DataSource delegate;

    public TenantAwareDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    private Connection bind(Connection connection) throws SQLException {
        TenantContext ctx = TenantContext.get();
        String value = (ctx != null && ctx.tenantId() != null) ? ctx.tenantId().toString() : "";
        applyTenant(connection, value);
        return proxy(connection);
    }

    private static void applyTenant(Connection connection, String tenantId) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("SELECT set_config('app.tenant_id', '" + tenantId.replace("'", "''") + "', false)");
        }
    }

    private static Connection proxy(Connection connection) {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                if ("close".equals(method.getName()) && (args == null || args.length == 0)) {
                    try {
                        applyTenant(connection, "");
                    } catch (SQLException ignored) {
                        // still return connection to pool
                    }
                    connection.close();
                    return null;
                }
                return method.invoke(connection, args);
            }
        };
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                handler
        );
    }

    @Override
    public Connection getConnection() throws SQLException {
        return bind(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return bind(delegate.getConnection(username, password));
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        delegate.setLogWriter(out);
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
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isInstance(this) || delegate.isWrapperFor(iface);
    }
}
