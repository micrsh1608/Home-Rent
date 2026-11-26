package vn.ancu.rental;

import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import vn.ancu.rental.common.AnCuFlywayMigrationStrategy;
import vn.ancu.rental.common.MigrationPreflightValidator;
import vn.ancu.rental.common.ResilientDataSourceConfig;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T15 (P0-09) & T21 (P1-15):
 * Kiểm thử nâng cấp lược đồ V1->V7 (fresh install, V3->V7, V4->V7, restart idempotent),
 * từ chối tự baseline schema không xác minh, báo xung đột dữ liệu trước migration (không xóa dữ liệu),
 * và kiểm chứng SQL Server fail-fast không fallback ngầm sang H2.
 */
class AccountSchemaUpgradeTest {

    private final AnCuFlywayMigrationStrategy strategy = new AnCuFlywayMigrationStrategy();

    @Test
    void freshInstallAppliesAllMigrationsAndSeedsCleanly() {
        var env = localH2Environment();
        try (var dataSource = (HikariDataSource) new ResilientDataSourceConfig().dataSource(env)) {
            Flyway flyway = AnCuFlywayMigrationStrategy.buildFlyway(dataSource);
            strategy.migrate(flyway);

            var jdbc = new JdbcTemplate(dataSource);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE is_demo = 1", Integer.class)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_otp_challenges", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_otp_rate_limits", Integer.class)).isZero();
            // Seeded legacy PAID contracts are marked LEGACY_UNVERIFIED with NULL received amount (never fabricated)
            assertThat(jdbc.queryForObject("SELECT deposit_verification_status FROM contracts WHERE id = 'HD-1001'", String.class))
                    .isEqualTo("LEGACY_UNVERIFIED");
            assertThat(jdbc.queryForObject("SELECT deposit_received_amount FROM contracts WHERE id = 'HD-1001'", Long.class))
                    .isNull();
            // Seeded legacy invoice INV-1001 has period_index backfilled
            assertThat(jdbc.queryForObject("SELECT period_index FROM invoices WHERE id = 'INV-1001'", Integer.class))
                    .isNotNull()
                    .isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void upgradesPreFlywayV3AndV4WithoutLosingDataAndRestartIsIdempotent() {
        var env = localH2Environment();
        try (var dataSource = (HikariDataSource) new ResilientDataSourceConfig().dataSource(env)) {
            applyRawSql(dataSource,
                    "db/migration/V1__init_schema.sql",
                    "db/migration/V2__seed_data.sql",
                    "db/migration/V3__automatic_no_show_cancellation.sql");
            var jdbc = new JdbcTemplate(dataSource);
            jdbc.update("UPDATE users SET full_name = ? WHERE id = 't1'", "Giữ nguyên tên hiện có");

            assertThat(MigrationPreflightValidator.inspectSchemaFingerprint(dataSource))
                    .isEqualTo(MigrationPreflightValidator.SchemaFingerprint.LEGACY_V3);

            // Run upgrade twice to verify idempotency and no duplicate seed rows
            for (int restart = 0; restart < 2; restart++) {
                strategy.migrate(AnCuFlywayMigrationStrategy.buildFlyway(dataSource));
                assertThat(jdbc.queryForObject("SELECT full_name FROM users WHERE id = 't1'", String.class))
                        .isEqualTo("Giữ nguyên tên hiện có");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class)).isEqualTo(4);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contracts", Integer.class)).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoices", Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT deposit_verification_status FROM contracts WHERE id = 'HD-1001'", String.class))
                        .isEqualTo("LEGACY_UNVERIFIED");
                assertThat(jdbc.queryForObject("SELECT handover_meter_status FROM contracts WHERE id = 'HD-1001'", String.class))
                        .isEqualTo("LEGACY_UNVERIFIED");
            }
        }
    }

    @Test
    void upgradesPreFlywayV4DatabasePreservingIdsAmountsStatusesAndHistory() {
        var env = localH2Environment();
        try (var dataSource = (HikariDataSource) new ResilientDataSourceConfig().dataSource(env)) {
            applyRawSql(dataSource,
                    "db/migration/V1__init_schema.sql",
                    "db/migration/V2__seed_data.sql",
                    "db/migration/V3__automatic_no_show_cancellation.sql",
                    "db/migration/V4__verified_accounts.sql");
            var jdbc = new JdbcTemplate(dataSource);
            jdbc.update("UPDATE invoices SET total_amount = 11714000 WHERE id = 'INV-1001'");

            assertThat(MigrationPreflightValidator.inspectSchemaFingerprint(dataSource))
                    .isEqualTo(MigrationPreflightValidator.SchemaFingerprint.LEGACY_V4);

            strategy.migrate(AnCuFlywayMigrationStrategy.buildFlyway(dataSource));

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contracts", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contract_status_history", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT total_amount FROM invoices WHERE id = 'INV-1001'", Long.class))
                    .isEqualTo(11_714_000L);
            assertThat(jdbc.queryForObject("SELECT status FROM contracts WHERE id = 'HD-1001'", String.class))
                    .isEqualTo("SIGNED");
        }
    }

    @Test
    void preflightRejectsUnrecognizedSchemaWithoutBlindBaseline() {
        var env = localH2Environment();
        try (var dataSource = (HikariDataSource) new ResilientDataSourceConfig().dataSource(env)) {
            new JdbcTemplate(dataSource).execute("CREATE TABLE random_unrelated_table (id INT PRIMARY KEY)");

            assertThat(MigrationPreflightValidator.inspectSchemaFingerprint(dataSource))
                    .isEqualTo(MigrationPreflightValidator.SchemaFingerprint.UNRECOGNIZED);

            assertThatThrownBy(() -> strategy.migrate(AnCuFlywayMigrationStrategy.buildFlyway(dataSource)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Không tự động baseline");
        }
    }

    @Test
    void preflightReportsConflictsAndStopsWithoutDeletingExistingRecords() {
        var env = localH2Environment();
        try (var dataSource = (HikariDataSource) new ResilientDataSourceConfig().dataSource(env)) {
            applyRawSql(dataSource,
                    "db/migration/V1__init_schema.sql",
                    "db/migration/V2__seed_data.sql",
                    "db/migration/V3__automatic_no_show_cancellation.sql",
                    "db/migration/V4__verified_accounts.sql");
            var jdbc = new JdbcTemplate(dataSource);
            // Introduce an invalid SIGNED + UNPAID row (allowed by weak V1 schema, forbidden by V7)
            jdbc.update("UPDATE contracts SET payment_status = 'UNPAID' WHERE id = 'HD-1002'");

            assertThatThrownBy(() -> strategy.migrate(AnCuFlywayMigrationStrategy.buildFlyway(dataSource)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("HD-1002")
                    .hasMessageContaining("status=SIGNED");

            // Data was preserved untouched (nothing deleted)
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contracts", Integer.class)).isEqualTo(2);
        }
    }

    @Test
    void sqlServerProfileFailsFastWithoutCredentialsOrWhenUnreachableAndNeverFallsBackToH2() {
        // Missing password -> fail-fast immediately without guessing hardcoded passwords or falling back to H2
        var missingPassEnv = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:sqlserver://127.0.0.1:1433;databaseName=AnCuRental;encrypt=true;trustServerCertificate=true")
                .withProperty("spring.datasource.username", "sa")
                .withProperty("spring.datasource.password", "");
        missingPassEnv.setActiveProfiles("sqlserver");

        assertThatThrownBy(() -> new ResilientDataSourceConfig().dataSource(missingPassEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Thiếu thông tin xác thực SQL Server");

        // Unreachable SQL Server port -> fail-fast and do NOT log the password or fall back to H2
        String secretMarker = "SuperSecret_ShouldNeverAppearInError_999!";
        var unreachableEnv = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:sqlserver://127.0.0.1:19999;databaseName=NonExistentDb;encrypt=true;trustServerCertificate=true;loginTimeout=1")
                .withProperty("spring.datasource.username", "test_user")
                .withProperty("spring.datasource.password", secretMarker);
        unreachableEnv.setActiveProfiles("sqlserver");

        assertThatThrownBy(() -> new ResilientDataSourceConfig().dataSource(unreachableEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fail-fast")
                .hasMessageNotContaining(secretMarker);
    }

    private void applyRawSql(HikariDataSource dataSource, String... scripts) {
        var populator = new ResourceDatabasePopulator();
        populator.setSqlScriptEncoding("UTF-8");
        for (String script : scripts) {
            populator.addScript(new ClassPathResource(script));
        }
        populator.execute(dataSource);
    }

    private MockEnvironment localH2Environment() {
        return new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:h2:mem:schema_test_" + UUID.randomUUID()
                        + ";MODE=MSSQLServer;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }
}
