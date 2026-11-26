# An Cư — Phân hệ Backend (`apartment-rental-backend`)

Dịch vụ máy chủ **Java 21 + Spring Boot 3.2.5 + Spring JDBC + Flyway** cho hệ thống quản lý và cho thuê căn hộ **An Cư Rental**, hỗ trợ **Microsoft SQL Server** (profile `sqlserver`, cơ chế fail-fast) và **H2 MSSQLServer Mode** (chỉ dùng cho profile `local` và kiểm thử tự động).

> **Hướng dẫn tổng thể Full-Stack:** Xem [README.md ở thư mục gốc](../README.md).

---

## 1. Kiến trúc mã nguồn

```text
apartment-rental-backend/
├── pom.xml                                  # Maven config (Java 21, Spring Boot 3.2.5, flyway-sqlserver)
├── .env.example                             # Mẫu biến môi trường (không đưa bí mật thật vào repo)
├── docs/
│   ├── EMAIL_SETUP.md                       # Hướng dẫn cấu hình Gmail SMTP & OTP
│   └── sql/                                 # Script SSMS: 01_initialize, 02_inspect, 03_preflight_check_V5
└── src/
    ├── main/java/vn/ancu/rental/
    │   ├── apartment/                       # PublicApartmentService (SQL pagination), OwnerPropertyService, OwnerDashboardService, FavoriteService
    │   ├── auth/                            # AuthController, AccountService (OTP), CsrfProtectionFilter, SessionAuthHelper, OtpMailService
    │   ├── common/                          # ResilientDataSourceConfig, DatabaseLockService, ServerClock, GlobalExceptionHandler, MigrationPreflightValidator, AnCuFlywayMigrationStrategy
    │   ├── contract/                        # ContractController, InvoiceController, ContractLifecycleService, LeaseRentCalculator, NoShowCancellationJob
    │   ├── db/migration/                    # V6__BackfillIntegrityMetadata.java (Flyway Java migration)
    │   └── health/                          # HealthController (/api/health)
    ├── main/resources/
    │   ├── application.yml                  # Cấu hình chung (Flyway, CSRF, Session, SMTP)
    │   ├── application-sqlserver.yml        # Cấu hình SQL Server (không có mật khẩu hardcode)
    │   ├── db/migration/                    # V1__init_schema.sql .. V5__add_integrity_columns.sql
    │   └── db/dialect/{h2,sqlserver}/       # V7__enforce_business_constraints.sql theo từng dialect
    └── test/java/vn/ancu/rental/            # Bộ integration, concurrency, schema upgrade & SQL Server verification tests
```

---

## 2. Cấu hình môi trường & Khởi chạy

### Microsoft SQL Server (Fail-Fast — không fallback ngầm sang H2)

Không có mật khẩu mặc định trong mã nguồn. Khai báo biến môi trường hoặc tạo tệp `.env` cục bộ từ [.env.example](.env.example):

```powershell
$env:DB_URL = "jdbc:sqlserver://localhost:1433;databaseName=AnCuRental;encrypt=true;trustServerCertificate=true"
$env:DB_USERNAME = "<sql_user>"
$env:DB_PASSWORD = "<sql_password>"
mvn.cmd spring-boot:run -Psqlserver
```

- Khi khởi động, `AnCuFlywayMigrationStrategy` chạy `MigrationPreflightValidator`, xác minh dấu vết lược đồ `V3/V4` (không baseline mù quáng), sau đó áp dụng tuần tự `V5__add_integrity_columns.sql` → `V6__BackfillIntegrityMetadata` → `db/dialect/sqlserver/V7__enforce_business_constraints.sql`.
- Nếu thiếu thông tin kết nối hoặc SQL Server không sẵn sàng, tiến trình dừng khởi động ngay (`fail-fast`) và `/api/health` trả `503` khi kết nối DB lỗi.

### Chế độ Local / Kiểm thử (H2 MSSQLServer Mode)

```powershell
# Chạy toàn bộ bộ kiểm thử tự động (H2)
mvn.cmd test

# Chạy máy chủ Backend cục bộ trên H2
mvn.cmd spring-boot:run -Plocal
```

### Kiểm thử trên Microsoft SQL Server thật (DB kiểm thử tách biệt)

```powershell
$env:ANCU_TEST_SQLSERVER_URL = "jdbc:sqlserver://localhost:1433;databaseName=AnCuRental_Test;encrypt=true;trustServerCertificate=true"
$env:ANCU_TEST_SQLSERVER_UPGRADE_URL = "jdbc:sqlserver://localhost:1433;databaseName=AnCuRental_UpgradeTest;encrypt=true;trustServerCertificate=true"
$env:ANCU_TEST_SQLSERVER_USERNAME = "<sql_test_user>"
$env:ANCU_TEST_SQLSERVER_PASSWORD = "<sql_test_password>"
mvn.cmd test -Dtest=SqlServerVerificationIntegrationTest
```

---

## 3. Cơ chế toàn vẹn & bảo mật trọng tâm

- **Khóa giao dịch ở tầng CSDL (`DatabaseLockService`):** Thứ tự khóa chuẩn `Apartment → Contract` dùng `WITH (UPDLOCK, HOLDLOCK)` trên SQL Server và `FOR UPDATE` trên H2 trong phạm vi `@Transactional`, kết hợp cột `version` optimistic locking.
- **Nguồn thời gian tập trung (`ServerClock`):** Toàn bộ kiểm tra lịch hẹn, bàn giao, quá hạn 3 ngày no-show và OTP dùng `Clock` máy chủ múi giờ `Asia/Ho_Chi_Minh`.
- **Bảo vệ CSRF (`CsrfProtectionFilter`):** Cấp và đối chiếu cặp `XSRF-TOKEN` / `X-CSRF-Token` gắn session cho mọi thao tác `POST/PUT/PATCH/DELETE` sau khi đăng nhập, kèm kiểm tra `Origin`.
- **Xử lý lỗi an toàn (`GlobalExceptionHandler`):** Ánh xạ chuẩn `400/403/404/409/500` theo `ApiErrorResponse` (`LOCK_CONFLICT`, `DUPLICATE_CONFLICT`, `DATA_INTEGRITY_CONFLICT`...) mà không làm lộ stacktrace, câu lệnh SQL hoặc thông tin bí mật.

---

## 4. Tài liệu liên quan

- [Báo cáo khắc phục source code (P0 → P2)](../docs/REMEDIATION_REPORT.md)
- [Hướng dẫn Migration SQL Server & H2](../docs/DATABASE_MIGRATION.md)
- [Báo cáo kiểm thử & ma trận T01–T24](../docs/TEST_REPORT.md)
- [Cấu hình SMTP & tài khoản OTP](docs/EMAIL_SETUP.md)
- [Script quản trị & kiểm tra SSMS](docs/sql/README.md)
