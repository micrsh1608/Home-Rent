package vn.ancu.rental.common;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;

public enum DatabaseDialect {
    SQL_SERVER("Microsoft SQL Server"),
    H2("H2");

    private final String displayName;

    DatabaseDialect(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static DatabaseDialect detect(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            return detect(connection);
        } catch (SQLException ex) {
            throw new IllegalStateException("Không thể xác định loại cơ sở dữ liệu đang kết nối.", ex);
        }
    }

    public static DatabaseDialect detect(Connection connection) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        String product = meta.getDatabaseProductName();
        String normalized = product == null ? "" : product.toLowerCase(Locale.ROOT);
        if (normalized.contains("sql server")) {
            return SQL_SERVER;
        }
        if (normalized.contains("h2")) {
            return H2;
        }
        throw new IllegalStateException("Hệ quản trị cơ sở dữ liệu chưa được hỗ trợ: " + product);
    }
}
