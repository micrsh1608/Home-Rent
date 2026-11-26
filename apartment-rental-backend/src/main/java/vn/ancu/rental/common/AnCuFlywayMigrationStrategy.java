package vn.ancu.rental.common;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * Chiến lược Flyway thống nhất cho cả SQL Server và H2:
 * - Chạy kiểm tra preflight dữ liệu trước mọi lần migrate.
 * - Chỉ baseline tự động khi xác minh chính xác dấu vết lược đồ V3 hoặc V4 hợp lệ (KHÔNG tự baseline schema lạ).
 * - Từ chối migrate và báo rõ danh sách xung đột nếu phát hiện dữ liệu vi phạm ràng buộc, tuyệt đối không xóa dữ liệu.
 */
@Component
public class AnCuFlywayMigrationStrategy implements FlywayMigrationStrategy {

    private static final Logger log = LoggerFactory.getLogger(AnCuFlywayMigrationStrategy.class);

    @Override
    public void migrate(Flyway flyway) {
        DataSource dataSource = flyway.getConfiguration().getDataSource();
        MigrationPreflightValidator.PreflightReport report = MigrationPreflightValidator.runPreflight(dataSource);

        for (String warning : report.legacyAuditWarnings()) {
            log.warn("[An Cư Migration Preflight - Audit] {}", warning);
        }

        if (report.hasBlockingConflicts()) {
            String details = String.join("\n - ", report.blockingConflicts());
            throw new IllegalStateException(
                    "[An Cư Migration Preflight] Phát hiện xung đột dữ liệu hoặc lược đồ chưa xác minh. "
                            + "Đã dừng migration để bảo toàn dữ liệu hiện có:\n - " + details
            );
        }

        if (report.fingerprint() == MigrationPreflightValidator.SchemaFingerprint.LEGACY_V3
                || report.fingerprint() == MigrationPreflightValidator.SchemaFingerprint.LEGACY_V4) {
            String verifiedVersion = report.fingerprint() == MigrationPreflightValidator.SchemaFingerprint.LEGACY_V3 ? "3" : "4";
            log.info("[An Cư Migration] Phát hiện lược đồ tiền-Flyway hợp lệ (V{}). Thực hiện baseline an toàn tại phiên bản {} trước khi nâng cấp.",
                    verifiedVersion, verifiedVersion);
            Flyway baselineFlyway = Flyway.configure()
                    .configuration(flyway.getConfiguration())
                    .baselineOnMigrate(true)
                    .baselineVersion(verifiedVersion)
                    .baselineDescription("Verified pre-Flyway schema V" + verifiedVersion)
                    .load();
            baselineFlyway.migrate();
            return;
        }

        flyway.migrate();
    }

    /**
     * Khởi tạo cấu hình Flyway chuẩn theo dialect (dùng chung cho test/CLI).
     */
    public static Flyway buildFlyway(DataSource dataSource) {
        DatabaseDialect dialect = DatabaseDialect.detect(dataSource);
        String vendorDir = dialect == DatabaseDialect.SQL_SERVER ? "sqlserver" : "h2";
        FluentConfiguration cfg = Flyway.configure()
                .dataSource(dataSource)
                .locations(
                        "classpath:db/migration",
                        "classpath:db/dialect/" + vendorDir,
                        "classpath:vn/ancu/rental/db/migration"
                )
                .baselineOnMigrate(false);
        return cfg.load();
    }
}
