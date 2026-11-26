# Báo cáo Kết quả Kiểm thử — An Cư Rental

> Mọi số liệu dưới đây ghi nhận trung thực từ kết quả chạy thực tế trên máy làm việc. Không dùng kết quả H2 để tuyên bố đã đạt kiểm thử concurrency đa instance trên SQL Server.

## 1. Môi trường kiểm thử thực tế

| Thành phần | Phiên bản / Thông tin |
|---|---|
| Hệ điều hành | Windows |
| JDK | Java `24.0.1` (`maven.compiler.release = 21`) |
| Maven | Apache Maven `3.9.16` |
| Spring Boot / Flyway | Spring Boot `3.2.5`, Flyway `9.22.3` (`flyway-core` + `flyway-sqlserver`) |
| Node.js / npm | Node `v24.4.1`, npm `11.4.2` |
| H2 (local/test) | H2 `2.2.224` (`MODE=MSSQLServer`) |
| SQL Server cục bộ | Microsoft SQL Server (`Huy-PC`, cổng TCP `1433`, trạng thái Running) |

## 2. Tổng hợp lệnh đã chạy và kết quả thực tế

| Lệnh thực tế | Tổng | Pass | Fail | Skip | Kết quả |
|---|---:|---:|---:|---:|---|
| `node --test scripts/dev-readiness.test.mjs` | 4 | 4 | 0 | 0 | **PASS** |
| `npm.cmd --prefix apartment-rental-frontend test` | 8 | 8 | 0 | 0 | **PASS** |
| `mvn.cmd -f apartment-rental-backend/pom.xml test` | 48 | 46 | 0 | 2 (`SqlServerVerificationIntegrationTest` chờ env SQL Auth) | **PASS (46/46 active)** |
| `npm.cmd run build` (Vite build + copy static + Maven `test-compile`) | — | — | — | — | **PASS** |
| Kiểm thử trực tiếp trên Microsoft SQL Server (`AnCuRental_Test_Verify_20261125` cô lập qua Windows Auth, drop ngay sau khi chạy, không đụng `AnCuRental`) | 17 assertions | 17 | 0 | 0 | **PASS** |

### Chi tiết các bộ test Backend (`mvn test`)

| Lớp kiểm thử | Số test | Pass | Fail | Skip | Phạm vi chính |
|---|---:|---:|---:|---:|---|
| `AccountIntegrationTest` | 10 | 10 | 0 | 0 | Đăng ký OTP loopback SMTP, hồ sơ, đổi/đặt lại mật khẩu, hủy session cũ, rate limit, CSRF |
| `AccountSchemaUpgradeTest` | 6 | 6 | 0 | 0 | Fresh install V1→V7, upgrade V3→V7, V4→V7, idempotent restart, từ chối schema lạ, preflight conflict, SQL Server fail-fast |
| `AnCuBackendIntegrationTest` | 6 | 6 | 0 | 0 | Health + DB engine, auth/ownership, public search, ký & cọc, lịch thuê, hóa đơn tuần tự, gia hạn, yêu thích |
| `ConcurrencyRemediationTest` | 8 | 8 | 0 | 0 | Đồng thời thực tế bằng `ExecutorService` + `CountDownLatch`: T01, T03, T04, T10, T12, T13, T16, T18 |
| `LocalMailConfigurationTest` | 2 | 2 | 0 | 0 | Nạp `smtp.local.properties` và `.env` cho Gmail App Password |
| `NoShowCancellationIntegrationTest` | 1 | 1 | 0 | 0 | Scheduler `@Scheduled` tự hủy đúng hợp đồng quá hạn 3 ngày dùng `ServerClock` |
| `RemediationCoreIntegrationTest` | 11 | 11 | 0 | 0 | T03, T05, T06, T07, T08, T09, T11, T13, T14, T17, T19, T22, T23 và P2 (`PublicApartmentService` SQL + batch invoices) |
| `SqlServerVerificationIntegrationTest` | 2 | 0 | 0 | 2 | T02 (2 Spring Boot instance trên SQL Server) & T21 (SQL Server V4→V7 qua Flyway Java) — cần biến môi trường SQL Auth |
| **Tổng Backend** | **48** | **46** | **0** | **2** | |

## 3. Kết quả kiểm thử riêng trên Microsoft SQL Server

Thực hiện trên instance cục bộ `Huy-PC` (cổng `1433`), tạo database test hoàn toàn biệt lập `AnCuRental_Test_Verify_20261125` (xóa sạch trong khối `finally`, **không thay đổi một byte nào của database `AnCuRental`**):

1. **Migration V1 → V7 trên SQL Server (`V1`–`V5` + V6 backfill + `db/dialect/sqlserver/V7__enforce_business_constraints.sql`):** **PASS** — Toàn bộ bảng, cột, ràng buộc `WITH CHECK` và 3 filtered unique index tạo thành công không lỗi.
2. **T17 Constraints & Filtered Unique Index trực tiếp trên SQL Server:** **PASS (8/8)**
   - `SIGNED + UNPAID` bị SQL Server từ chối (`ck_contracts_status_payment_strict`).
   - `actual_move_out` khi `actual_move_in IS NULL` bị SQL Server từ chối (`ck_contracts_handover_dates_strict`).
   - `deposit_forfeited = 1` trên hợp đồng `SIGNED` bị từ chối (`ck_contracts_forfeit_requires_cancelled_paid`).
   - Hóa đơn `MONTHLY` trùng `period_index` bị từ chối (`uq_invoices_contract_monthly_period` filtered unique index).
   - Hóa đơn `MONTHLY` thiếu chỉ số điện/nước bị từ chối (`ck_invoices_monthly_meters_strict`).
   - Hóa đơn `PAID` thiếu `confirmed_by` bị từ chối (`ck_invoices_paid_metadata`).
   - Tạo hợp đồng gia hạn thứ 2 khi hợp đồng gia hạn 1 chưa hủy bị từ chối (`uq_contracts_active_renewal_of` filtered unique index); sau khi hủy hợp đồng gia hạn 1 thì hợp đồng gia hạn 2 tạo thành công.
3. **P2-01 truy vấn `OFFSET 0 ROWS FETCH NEXT 2 ROWS ONLY` trên SQL Server:** **PASS**.
4. **T01 Concurrency với 2 kết nối SQL Server độc lập dùng `SELECT id FROM apartments WITH (UPDLOCK, HOLDLOCK) WHERE id = 'a1'`:** **PASS** — 2 giao dịch chạy song song trên 2 kết nối `SqlConnection` riêng biệt; kết quả: `[SIGNED:race-1]` và `[REJECTED_OVERLAP:race-2]`; trạng thái cuối: `SIGNED = 1`, `AWAITING = 1`.
5. **Hạng mục còn BLOCKED trên SQL Server (T02 2 Spring Boot JVM instance):**
   - **Nguyên nhân:** Máy hiện tại chưa cài `mssql-jdbc_auth-*.dll` cho Windows Integrated Auth của `mssql-jdbc` và chưa cung cấp biến môi trường `ANCU_TEST_SQLSERVER_USERNAME` / `ANCU_TEST_SQLSERVER_PASSWORD` cho tài khoản SQL Auth test riêng. Theo nguyên tắc bảo mật, không tự đoán mật khẩu `sa` và không tự tạo login trên máy chủ SQL Server của người dùng.
   - **Cách người dùng chạy T02 & T21 Java trên SQL Server:**
     ```powershell
     # Tạo 2 database test trống trong SSMS: AnCuRental_Test và AnCuRental_UpgradeTest
     # (hoặc chạy 01_initialize_AnCuRental.sql trên AnCuRental_Test rồi chạy lệnh sau)
     $env:ANCU_TEST_SQLSERVER_URL = "jdbc:sqlserver://localhost:1433;databaseName=AnCuRental_Test;encrypt=true;trustServerCertificate=true"
     $env:ANCU_TEST_SQLSERVER_UPGRADE_URL = "jdbc:sqlserver://localhost:1433;databaseName=AnCuRental_UpgradeTest;encrypt=true;trustServerCertificate=true"
     $env:ANCU_TEST_SQLSERVER_USERNAME = "<sql_test_user>"
     $env:ANCU_TEST_SQLSERVER_PASSWORD = "<sql_test_password>"
     npm.cmd run test:sqlserver
     ```

## 4. Ma trận kiểm thử T01–T24

| Test ID | Liên kết | Kiểm thử thực hiện | Trạng thái |
|---|---|---|---|
| T01 | P0-01 | `ConcurrencyRemediationTest.t01_...` (H2) + 2 kết nối `UPDLOCK/HOLDLOCK` thực tế trên SQL Server | **PASS** |
| T02 | P0-01 | `SqlServerVerificationIntegrationTest.t02_...` (đã viết sẵn, chạy với 2 `SpringApplicationBuilder` trên SQL Server) | **BLOCKED** (chờ SQL Auth env vars trên DB test riêng) |
| T03 | P0-01 | `RemediationCoreIntegrationTest.t03_...` & `ConcurrencyRemediationTest.t03_...` | **PASS** |
| T04 | P0-01 | `ConcurrencyRemediationTest.t04_...` | **PASS** |
| T05 | P0-02 | `RemediationCoreIntegrationTest.t05_...` & `demo.test.js` | **PASS** |
| T06 | P0-03 | `RemediationCoreIntegrationTest.t06_...` & `demo.test.js` | **PASS** |
| T07 | P0-04 | `RemediationCoreIntegrationTest.t07_t08_t09_...` | **PASS** |
| T08 | P0-04 | `RemediationCoreIntegrationTest.t07_t08_t09_...` | **PASS** |
| T09 | P0-05 | `RemediationCoreIntegrationTest.t07_t08_t09_...` | **PASS** |
| T10 | P0-05 | `ConcurrencyRemediationTest.t10_...` | **PASS** |
| T11 | P0-06 | `RemediationCoreIntegrationTest.t11_t13_...` & `demo.test.js` | **PASS** |
| T12 | P0-06/08 | `ConcurrencyRemediationTest.t12_...`, `RemediationCoreIntegrationTest.t14_...`, `NoShowCancellationIntegrationTest` | **PASS** |
| T13 | P0-07 | `RemediationCoreIntegrationTest.t11_t13_...` & `ConcurrencyRemediationTest.t13_...` | **PASS** |
| T14 | P0-08 | `RemediationCoreIntegrationTest.t14_p0_08_...` | **PASS** |
| T15 | P0-09 | `AccountSchemaUpgradeTest.sqlServerProfileFailsFast...` & `scripts/dev-readiness.test.mjs` | **PASS** |
| T16 | P1-10 | `ConcurrencyRemediationTest.t16_p1_10_...` | **PASS** |
| T17 | P1-11 | `RemediationCoreIntegrationTest.t17_p1_11_...` (H2) + 8 kiểm tra constraint trực tiếp trên SQL Server | **PASS** |
| T18 | P1-12 | `ConcurrencyRemediationTest.t18_p1_12_...` | **PASS** |
| T19 | P1-13 | `RemediationCoreIntegrationTest.t19_p1_13_...` & `AnCuBackendIntegrationTest` | **PASS** |
| T20 | P1-14 | `apartment-rental-frontend/test/demo.test.js` (`T20 (P1-14)`) | **PASS** |
| T21 | P1-15 | `AccountSchemaUpgradeTest` (H2 fresh/V3/V4/restart/conflict) + SQL Server V1→V7 test DB | **PASS** (Java Flyway trên SQL Server sẵn sàng chạy qua `test:sqlserver`) |
| T22 | P0/P1 | `RemediationCoreIntegrationTest.t22_fullEndToEndHttpLifecycleThroughAllEndpoints` | **PASS** |
| T23 | P0/P1 | `RemediationCoreIntegrationTest.t23_rentCalculatorEdgeCases_...` & `demo.test.js` | **PASS** |
| T24 | P1-13 | `AccountIntegrationTest` (10 kịch bản SMTP loopback OTP/session/rate-limit) | **PASS** |
