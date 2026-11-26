package vn.ancu.rental.common;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Cấu hình DataSource fail-fast:
 * - Profile sqlserver (hoặc URL jdbc:sqlserver:): kết nối bắt buộc tới Microsoft SQL Server bằng đúng
 *   thông tin đã cấu hình; tuyệt đối KHÔNG thử mật khẩu mặc định, KHÔNG in secret, KHÔNG fallback ngầm sang H2.
 * - Profile local/test (URL jdbc:h2:): chỉ sử dụng H2 khi được chọn chủ động.
 * - Việc khởi tạo & nâng cấp lược đồ do một cơ chế duy nhất đảm nhiệm (Flyway + AnCuFlywayMigrationStrategy).
 */
@Configuration
public class ResilientDataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(ResilientDataSourceConfig.class);

    public static final String H2_LOCAL_URL =
            "jdbc:h2:mem:ancu;MODE=MSSQLServer;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1";

    @Bean
    @Primary
    public DataSource dataSource(Environment env) {
        List<String> activeProfiles = Arrays.asList(env.getActiveProfiles());
        boolean sqlServerProfile = activeProfiles.contains("sqlserver");
        String configuredUrl = trimToEmpty(env.getProperty("spring.datasource.url", ""));

        if (sqlServerProfile || configuredUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:sqlserver:")) {
            return createFailFastSqlServerDataSource(env, configuredUrl);
        }

        String h2Url = configuredUrl.isBlank() ? H2_LOCAL_URL : configuredUrl;
        if (!h2Url.toLowerCase(Locale.ROOT).startsWith("jdbc:h2:")) {
            throw new IllegalStateException(
                    "URL cơ sở dữ liệu không hợp lệ cho chế độ local/test. Hãy dùng jdbc:h2: cho local hoặc bật profile sqlserver với jdbc:sqlserver:."
            );
        }

        String username = env.getProperty("spring.datasource.username", "sa");
        String password = env.getProperty("spring.datasource.password", "");
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(h2Url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName("org.h2.Driver");
        verifyConnectivity(ds, "H2 (MODE=MSSQLServer)", sanitizeJdbcUrl(h2Url));
        log.info("[An Cư Database] Đang sử dụng cơ sở dữ liệu H2 local/test ({})", sanitizeJdbcUrl(h2Url));
        return ds;
    }

    private DataSource createFailFastSqlServerDataSource(Environment env, String configuredUrl) {
        if (!configuredUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:sqlserver:")) {
            throw new IllegalStateException(
                    "Profile sqlserver yêu cầu cấu hình DB_URL bắt đầu bằng 'jdbc:sqlserver://'. Không tự động chuyển sang H2."
            );
        }

        boolean integratedSecurity = configuredUrl.toLowerCase(Locale.ROOT).contains("integratedsecurity=true");
        String username = trimToEmpty(env.getProperty("spring.datasource.username", ""));
        String password = env.getProperty("spring.datasource.password", "");
        if (password == null) {
            password = "";
        }

        if (!integratedSecurity && (username.isBlank() || password.isBlank())) {
            throw new IllegalStateException(
                    "Thiếu thông tin xác thực SQL Server (DB_USERNAME / DB_PASSWORD). "
                            + "Hệ thống dừng khởi động thay vì dùng mật khẩu mặc định hoặc chuyển ngầm sang H2."
            );
        }

        String urlWithTimeout = configuredUrl.toLowerCase(Locale.ROOT).contains("logintimeout=")
                ? configuredUrl
                : configuredUrl + (configuredUrl.endsWith(";") ? "" : ";") + "loginTimeout=5";

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(urlWithTimeout);
        if (!integratedSecurity) {
            ds.setUsername(username);
            ds.setPassword(password);
        }
        ds.setDriverClassName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        ds.setConnectionTimeout(5000);
        ds.setInitializationFailTimeout(5000);

        String safeUrl = sanitizeJdbcUrl(urlWithTimeout);
        verifyConnectivity(ds, "Microsoft SQL Server", safeUrl);
        log.info("[An Cư Database] Đã kết nối thành công tới Microsoft SQL Server ({})", safeUrl);
        return ds;
    }

    private void verifyConnectivity(HikariDataSource ds, String engineLabel, String safeUrl) {
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT 1");
        } catch (Exception ex) {
            try {
                ds.close();
            } catch (Exception ignored) {
                // Close pool cleanly on startup failure
            }
            throw new IllegalStateException(
                    "[An Cư Database] Không thể kết nối tới " + engineLabel + " (" + safeUrl + "). "
                            + "Hệ thống dừng khởi động (fail-fast) và KHÔNG chuyển ngầm sang H2. "
                            + "Hãy kiểm tra dịch vụ SQL Server, TCP/IP cổng 1433, database đích và DB_URL/DB_USERNAME/DB_PASSWORD.",
                    ex
            );
        }
    }

    public static String sanitizeJdbcUrl(String url) {
        if (url == null) {
            return "";
        }
        return url.replaceAll("(?i)(password|pwd)=[^;]*", "$1=***");
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
