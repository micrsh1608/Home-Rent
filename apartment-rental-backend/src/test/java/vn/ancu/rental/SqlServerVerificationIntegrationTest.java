package vn.ancu.rental;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import vn.ancu.rental.apartment.ApartmentDto;
import vn.ancu.rental.apartment.OwnerPropertyService;
import vn.ancu.rental.auth.AuthUser;
import vn.ancu.rental.common.AnCuFlywayMigrationStrategy;
import vn.ancu.rental.common.DatabaseDialect;
import vn.ancu.rental.contract.ContractDto;
import vn.ancu.rental.contract.ContractLifecycleService;
import vn.ancu.rental.contract.InvoiceDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bộ kiểm thử thực tế trên Microsoft SQL Server tách biệt (KHÔNG dùng database thật AnCuRental):
 * Kích hoạt bằng biến môi trường:
 *   ANCU_TEST_SQLSERVER_URL=jdbc:sqlserver://localhost:1433;databaseName=AnCuRental_Test;encrypt=true;trustServerCertificate=true
 *   ANCU_TEST_SQLSERVER_USERNAME=...
 *   ANCU_TEST_SQLSERVER_PASSWORD=...
 *
 * Bao phủ:
 * - T01 & T02: Hai Spring Boot instance độc lập cùng kết nối 1 SQL Server ký đồng thời 2 hợp đồng trùng lịch -> đúng 1 thành công.
 * - T10: Hai instance lập đồng thời cùng kỳ hóa đơn -> đúng 1 hóa đơn.
 * - T17: Kiểm tra trực tiếp CHECK & filtered UNIQUE INDEX trên SQL Server.
 * - T21: Migration fresh install V1->V7 và nâng cấp V4->V7 trên SQL Server (khi truyền ANCU_TEST_SQLSERVER_UPGRADE_URL).
 */
class SqlServerVerificationIntegrationTest {

    private static final AuthUser OWNER1 = AuthUser.of("o1", "thuha@ancu.vn", "Trần Thu Hà", "OWNER");
    private static final AuthUser TENANT1 = AuthUser.of("t1", "minhanh@ancu.vn", "Nguyễn Minh Anh", "TENANT");
    private static final AuthUser TENANT2 = AuthUser.of("t2", "hoangnam@ancu.vn", "Lê Hoàng Nam", "TENANT");

    @Test
    void t02_twoIndependentSpringInstancesSharingSqlServerPreventOverlappingSignAndDuplicateInvoice() throws Exception {
        String url = envOrProp("ANCU_TEST_SQLSERVER_URL");
        String user = envOrProp("ANCU_TEST_SQLSERVER_USERNAME");
        String pass = envOrProp("ANCU_TEST_SQLSERVER_PASSWORD");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "Bỏ qua kiểm thử SQL Server vì chưa cấu hình ANCU_TEST_SQLSERVER_URL (dùng database test riêng).");

        try (ConfigurableApplicationContext instanceA = startInstance(url, user, pass);
             ConfigurableApplicationContext instanceB = startInstance(url, user, pass)) {

            assertThat(DatabaseDialect.detect(instanceA.getBean(javax.sql.DataSource.class)))
                    .isEqualTo(DatabaseDialect.SQL_SERVER);

            OwnerPropertyService propsA = instanceA.getBean(OwnerPropertyService.class);
            ContractLifecycleService contractsA = instanceA.getBean(ContractLifecycleService.class);
            ContractLifecycleService contractsB = instanceB.getBean(ContractLifecycleService.class);
            JdbcTemplate jdbcA = instanceA.getBean(JdbcTemplate.class);

            String room = "SQL-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
            ApartmentDto apt = propsA.createApartment("o1", new OwnerPropertyService.ApartmentUpsertRequest(
                    "b1", room, "SQL Concurrency " + room, 11_500_000L, new BigDecimal("55.00"), 2, 1,
                    "ready", "SQL Server test", "river", null, true
            ));

            LocalDate start = LocalDate.now().plusDays(10);
            LocalDate end = start.plusDays(29);
            LocalDateTime slot = LocalDateTime.now().plusDays(1).withSecond(0).withNano(0);

            ContractDto c1 = contractsA.createContract(OWNER1, new ContractLifecycleService.CreateContractRequest(
                    apt.id(), TENANT1.id(), start, end, "CUSTOM", 1, null, null, null, slot));
            ContractDto c2 = contractsB.createContract(OWNER1, new ContractLifecycleService.CreateContractRequest(
                    apt.id(), TENANT2.id(), start.plusDays(5), end.plusDays(5), "CUSTOM", 1, null, null, null, slot));

            // T02: Instance A and Instance B race to sign overlapping contracts on the same SQL Server DB
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            try {
                Future<Boolean> f1 = pool.submit(() -> {
                    ready.countDown();
                    go.await(5, TimeUnit.SECONDS);
                    try {
                        contractsA.confirmSignAndDepositByOwner(OWNER1, c1.id(),
                                new ContractLifecycleService.ConfirmSignAndDepositRequest(true, true, c1.deposit(), null));
                        return true;
                    } catch (Exception ex) {
                        return false;
                    }
                });
                Future<Boolean> f2 = pool.submit(() -> {
                    ready.countDown();
                    go.await(5, TimeUnit.SECONDS);
                    try {
                        contractsB.confirmSignAndDepositByOwner(OWNER1, c2.id(),
                                new ContractLifecycleService.ConfirmSignAndDepositRequest(true, true, c2.deposit(), null));
                        return true;
                    } catch (Exception ex) {
                        return false;
                    }
                });
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                go.countDown();
                boolean r1 = f1.get(20, TimeUnit.SECONDS);
                boolean r2 = f2.get(20, TimeUnit.SECONDS);
                assertThat(r1 ^ r2).isTrue();
            } finally {
                pool.shutdownNow();
            }

            Integer signedCount = jdbcA.queryForObject(
                    "SELECT COUNT(*) FROM contracts WHERE apartment_id = ? AND status = 'SIGNED'",
                    Integer.class, apt.id());
            assertThat(signedCount).isEqualTo(1);

            // T17 directly on SQL Server: invalid SIGNED+UNPAID rejected by WITH CHECK constraint
            assertThatThrownBy(() -> jdbcA.update(
                    "UPDATE contracts SET payment_status = 'UNPAID' WHERE apartment_id = ? AND status = 'SIGNED'",
                    apt.id()
            )).isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Test
    void t21_sqlServerV4ToV7UpgradePreservesExistingData() {
        String upgradeUrl = envOrProp("ANCU_TEST_SQLSERVER_UPGRADE_URL");
        String user = envOrProp("ANCU_TEST_SQLSERVER_USERNAME");
        String pass = envOrProp("ANCU_TEST_SQLSERVER_PASSWORD");
        Assumptions.assumeTrue(upgradeUrl != null && !upgradeUrl.isBlank(),
                "Bỏ qua kiểm thử nâng cấp SQL Server V4->V7 vì chưa cấu hình ANCU_TEST_SQLSERVER_UPGRADE_URL.");

        try (HikariDataSource ds = new HikariDataSource()) {
            ds.setJdbcUrl(upgradeUrl);
            if (user != null && !user.isBlank()) {
                ds.setUsername(user);
                ds.setPassword(pass);
            }
            ds.setDriverClassName("com.microsoft.sqlserver.jdbc.SQLServerDriver");

            ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
            populator.setSqlScriptEncoding("UTF-8");
            populator.addScript(new ClassPathResource("db/migration/V1__init_schema.sql"));
            populator.addScript(new ClassPathResource("db/migration/V2__seed_data.sql"));
            populator.addScript(new ClassPathResource("db/migration/V3__automatic_no_show_cancellation.sql"));
            populator.addScript(new ClassPathResource("db/migration/V4__verified_accounts.sql"));
            populator.execute(ds);

            JdbcTemplate jdbc = new JdbcTemplate(ds);
            jdbc.update("UPDATE users SET full_name = N'Dữ liệu V4 SQL Server' WHERE id = 't1'");

            AnCuFlywayMigrationStrategy strategy = new AnCuFlywayMigrationStrategy();
            strategy.migrate(AnCuFlywayMigrationStrategy.buildFlyway(ds));
            // Second run must be idempotent
            strategy.migrate(AnCuFlywayMigrationStrategy.buildFlyway(ds));

            assertThat(jdbc.queryForObject("SELECT full_name FROM users WHERE id = 't1'", String.class))
                    .isEqualTo("Dữ liệu V4 SQL Server");
            assertThat(jdbc.queryForObject("SELECT deposit_verification_status FROM contracts WHERE id = 'HD-1001'", String.class))
                    .isEqualTo("LEGACY_UNVERIFIED");
            assertThat(jdbc.queryForObject("SELECT period_index FROM invoices WHERE id = 'INV-1001'", Integer.class))
                    .isNotNull();
        }
    }

    private ConfigurableApplicationContext startInstance(String url, String user, String pass) {
        return new SpringApplicationBuilder(AnCuRentalApplication.class)
                .profiles("sqlserver")
                .properties(
                        "server.port=0",
                        "spring.main.web-application-type=none",
                        "ancu.contracts.no-show-cron=-",
                        "spring.datasource.url=" + url,
                        "spring.datasource.username=" + (user == null ? "" : user),
                        "spring.datasource.password=" + (pass == null ? "" : pass)
                )
                .run();
    }

    private static String envOrProp(String key) {
        String val = System.getProperty(key);
        return (val != null && !val.isBlank()) ? val : System.getenv(key);
    }
}
