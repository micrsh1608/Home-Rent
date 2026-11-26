# Tài liệu Migration Cơ sở dữ liệu (SQL Server & H2)

> Áp dụng cho **An Cư Rental** — lộ trình nâng cấp an toàn từ lược đồ `V1–V4` lên `V5–V7` với một cơ chế migration thống nhất (**Flyway 9.22.3** + `flyway-sqlserver` + `AnCuFlywayMigrationStrategy`).

## 1. Kiến trúc Migration thống nhất

Trước đây, profile `sqlserver` tắt Flyway và tự chạy script qua `ResilientDataSourceConfig`, dễ gây lệch giữa H2 và SQL Server. Sau khi khắc phục:

- **Cơ chế duy nhất:** Flyway được bật cho cả `local` (H2) và `sqlserver` (Microsoft SQL Server).
- **Bố cục thư mục migration:**
  - `classpath:db/migration`: các script chuẩn dùng chung `V1__init_schema.sql` → `V5__add_integrity_columns.sql` (V1–V4 giữ nguyên hoàn toàn, không sửa checksum).
  - `classpath:vn/ancu/rental/db/migration`: Java migration `V6__BackfillIntegrityMetadata` (dùng trực tiếp `LeaseRentCalculator` để backfill `period_index` và gắn nhãn dữ liệu lịch sử).
  - `classpath:db/dialect/h2/V7__enforce_business_constraints.sql`: constraint & unique index tương thích H2 (`MODE=MSSQLServer`, sử dụng cột sinh `GENERATED ALWAYS AS` guard vì H2 không hỗ trợ filtered unique index).
  - `classpath:db/dialect/sqlserver/V7__enforce_business_constraints.sql`: constraint `WITH CHECK` & filtered unique index chuẩn T-SQL (`WHERE kind = 'MONTHLY'`, `WHERE kind = 'EARLY_MOVE_IN'`, `WHERE renewal_of_id IS NOT NULL AND status <> 'CANCELLED'`).
- **Tắt baseline mù quáng (`baseline-on-migrate: false`):** `AnCuFlywayMigrationStrategy` chỉ baseline tại V3 hoặc V4 sau khi `MigrationPreflightValidator` xác minh đúng toàn bộ bảng/cột của V3 hoặc V4. Nếu schema không khớp, ứng dụng dừng khởi động ngay.

## 2. Quy trình sao lưu (Backup) bắt buộc trước khi nâng cấp

Luôn sao lưu database trước khi nâng cấp database thật hoặc database có dữ liệu mẫu quan trọng:

```sql
-- Chạy trên SQL Server (SSMS) trước khi chạy migration
BACKUP DATABASE [AnCuRental]
TO DISK = N'D:\SQLBackups\AnCuRental_PreV5_Backup.bak'
WITH FORMAT, INIT, CHECKSUM, STATS = 10;
GO

RESTORE VERIFYONLY FROM DISK = N'D:\SQLBackups\AnCuRental_PreV5_Backup.bak';
GO
```

Khuyến nghị thực hiện chạy thử nâng cấp trên bản sao database test riêng (ví dụ khôi phục file `.bak` thành database `AnCuRental_UpgradeTest`) trước khi nâng cấp `AnCuRental`.

## 3. Preflight kiểm tra xung đột dữ liệu trước nâng cấp

Hệ thống cung cấp 2 lớp preflight (đều **chỉ đọc**, không bao giờ xóa dữ liệu):

1. **Tự động trong Java (`MigrationPreflightValidator`):** Chạy ngay trước `flyway.migrate()` khi khởi động backend.
2. **Thủ công trong SSMS:** Mở và chạy [03_preflight_check_V5.sql](../apartment-rental-backend/docs/sql/03_preflight_check_V5.sql).

### Các xung đột chặn migration (`BLOCKING_*`)

| Mã kiểm tra | Điều kiện vi phạm | Hướng xử lý thủ công an toàn |
|---|---|---|
| `BLOCKING_STATUS_PAYMENT` | `SIGNED` nhưng `UNPAID`, hoặc `AWAITING` nhưng `PAID` | Đối chiếu chứng từ thu cọc thực tế với chủ nhà; cập nhật đúng trạng thái hợp lệ hoặc hủy hợp đồng chờ ký. |
| `BLOCKING_FORFEIT` | `deposit_forfeited = 1` nhưng không phải `CANCELLED + PAID` | Kiểm tra lịch sử hợp đồng (`contract_status_history`) để hiệu chỉnh đúng cờ mất cọc. |
| `BLOCKING_SIGNED_META` | Hợp đồng `SIGNED` thiếu `signed_at` / `signed_confirmed_by` / `payment_confirmed_at` / `payment_confirmed_by` | Bổ sung thông tin xác nhận từ nhật ký/chứng từ gốc trước khi nâng cấp. |
| `BLOCKING_HANDOVER_DATES` | Có `actual_move_out` nhưng `actual_move_in IS NULL` hoặc `actual_move_out < actual_move_in` | Kiểm tra biên bản bàn giao để bổ sung `actual_move_in` đúng. |
| `BLOCKING_OVERLAPPING_SIGNED` | 2 hợp đồng `SIGNED` trùng khoảng ngày hiệu lực trên cùng căn hộ | Xác định hợp đồng hiệu lực thực tế, hủy hoặc hiệu chỉnh thời hạn hợp đồng sai trước khi nâng cấp. |
| `BLOCKING_DUPLICATE_RENEWAL` | Nhiều hơn 1 hợp đồng gia hạn chưa hủy cho cùng `renewal_of_id` | Hủy các bản nháp gia hạn thừa trước khi bật filtered unique index. |
| `BLOCKING_INVOICE_PAID_META` | Hóa đơn `PAID` thiếu `paid_at` hoặc `confirmed_by` | Bổ sung người xác nhận và ngày thu tiền từ lịch sử đối soát. |
| `BLOCKING_INVOICE_METERS` | Hóa đơn `MONTHLY` thiếu chỉ số hoặc `new < old`; `EARLY_MOVE_IN` có chỉ số | Đối soát chỉ số thực tế trước khi bật `ck_invoices_monthly_meters_strict`. |
| `BLOCKING_DUP_EARLY_PENALTY` / trùng `period_index` | Nhiều hơn 1 hóa đơn cùng kỳ `MONTHLY` hoặc cùng `EARLY_MOVE_IN` | Không tự xóa hóa đơn đã thu; lập biên bản điều chỉnh và xử lý trùng lặp trước khi chạy V6/V7. |

### Quy trình xử lý dữ liệu Legacy minh bạch (không bịa số liệu)

- **Hợp đồng `PAID` cũ chưa có `deposit_received_amount`:** `V6__BackfillIntegrityMetadata` giữ `deposit_received_amount = NULL` và đặt `deposit_verification_status = 'LEGACY_UNVERIFIED'`. Ràng buộc `ck_contracts_paid_deposit_verification` cho phép `LEGACY_UNVERIFIED` chỉ với `NULL`, còn mọi hợp đồng mới (`VERIFIED`) bắt buộc `deposit_received_amount >= deposit_amount`.
- **Hợp đồng đã nhận phòng cũ chưa có chỉ số bàn giao:** `V6` giữ `handover_electric_reading = NULL`, `handover_water_reading = NULL` và đặt `handover_meter_status = 'LEGACY_UNVERIFIED'` (không bao giờ gán mặc định `0`). Chủ nhà có thể cập nhật chỉ số bàn giao qua màn hình nhận phòng khi chưa có hóa đơn tháng.
- **Hóa đơn `MONTHLY` cũ không khớp liên tục chỉ số:** `V6` không sửa số liệu hóa đơn cũ; nếu phát hiện sai lệch giữa hai kỳ liên tiếp sẽ gắn `meter_verification_status = 'LEGACY_DISCREPANCY'` và ghi log cảnh báo danh sách hợp đồng cần đối soát, đồng thời chặn lập hóa đơn kỳ kế tiếp cho đến khi hoàn tất đối soát.
- **Khảo sát ràng buộc `uq_invoices_contract_month_kind` cũ:** Theo quy tắc `LeaseRentCalculator`, mọi tháng `m ∈ {1..12}` đều có `monthBasisDays(m) >= số ngày dương lịch thực tế của tháng m` (tháng 2 có 28/29 ngày nhưng `basis = 30`). Do đó 2 kỳ `MONTHLY` hợp lệ liên tiếp không bao giờ rơi vào cùng `billing_month` (`YYYY-MM`). Giữ `uq_invoices_contract_month_kind` song song với `uq_invoices_contract_monthly_period` an toàn và bổ sung tầng bảo vệ kép.

## 4. Hướng dẫn cài đặt mới & nâng cấp

### A. Nâng cấp database SQL Server đang ở V3 hoặc V4 (như `AnCuRental`)

1. Backup database theo Mục 2.
2. Chạy [03_preflight_check_V5.sql](../apartment-rental-backend/docs/sql/03_preflight_check_V5.sql) trong SSMS để bảo đảm không có dòng `BLOCKING_*`.
3. Thiết lập biến môi trường (khuyến nghị dùng tài khoản migration tách riêng có quyền DDL khi chạy nâng cấp, sau đó vận hành bằng tài khoản chỉ có `SELECT/INSERT/UPDATE/DELETE`):
   ```powershell
   $env:DB_URL = "jdbc:sqlserver://localhost:1433;databaseName=AnCuRental;encrypt=true;trustServerCertificate=true"
   $env:DB_USERNAME = "<migration_or_app_user>"
   $env:DB_PASSWORD = "<strong_password>"
   npm.cmd run dev:sqlserver
   ```
4. Backend tự động: xác minh dấu vết V4 → baseline tại phiên bản 4 → chạy V5, V6, V7 trong transaction → `/api/health` trả `databaseEngine: "Microsoft SQL Server"`.
5. Mở [02_inspect_AnCuRental.sql](../apartment-rental-backend/docs/sql/02_inspect_AnCuRental.sql) trong SSMS để xác minh toàn bộ bảng, dữ liệu cũ được bảo toàn, `flyway_schema_history` có phiên bản `7` và tất cả `CHECK` constraint có `is_not_trusted = 0`.

### B. Khởi tạo database SQL Server hoàn toàn trống

1. Tạo database trống `CREATE DATABASE AnCuRental;`.
2. Chạy [01_initialize_AnCuRental.sql](../apartment-rental-backend/docs/sql/01_initialize_AnCuRental.sql) trong SSMS (khởi tạo nguyên tử V1–V4, tách batch chuẩn T-SQL cho V4).
3. Khởi chạy backend với `-Psqlserver` để tự động baseline V4 đã xác minh và áp dụng V5 → V7.

*(Lưu ý kỹ thuật: tệp `V4__verified_accounts.sql` gốc chứa `ALTER TABLE users ADD is_demo` và `UPDATE users SET is_demo = 1` trong cùng tệp không có dấu tách batch `GO` để tương thích H2, nên với database SQL Server hoàn toàn trống, chạy `01_initialize_AnCuRental.sql` trước khi bật Flyway giúp giữ nguyên 100% checksum V1–V4 mà không gặp lỗi parse batch T-SQL.)*

### C. Môi trường Local / Test (H2)

- Chạy `npm.cmd run dev` hoặc `npm.cmd test`: H2 tự động chạy Flyway V1 → V7 sạch trên bộ nhớ mà không cần thao tác thủ công.

## 5. Kế hoạch Rollback

- **Lỗi trong quá trình chạy V5, V6 hoặc V7:** Flyway thực thi mỗi migration trong transaction; nếu preflight hoặc V6/V7 phát sinh lỗi, giao dịch tự rollback và dữ liệu trước đó còn nguyên vẹn.
- **Rollback sau khi đã nâng cấp hoàn tất:** Không chạy `DROP COLUMN` thủ công trực tiếp trên database vận hành vì có thể làm mất dữ liệu `deposit_received_amount` / `handover_*_reading` mới phát sinh. Quy trình chuẩn là **khôi phục từ bản backup `AnCuRental_PreV5_Backup.bak`** đã tạo ở Mục 2:
  ```sql
  USE master;
  GO
  ALTER DATABASE [AnCuRental] SET SINGLE_USER WITH ROLLBACK IMMEDIATE;
  RESTORE DATABASE [AnCuRental]
  FROM DISK = N'D:\SQLBackups\AnCuRental_PreV5_Backup.bak'
  WITH REPLACE, STATS = 10;
  ALTER DATABASE [AnCuRental] SET MULTI_USER;
  GO
  ```

## 6. Lệnh kiểm tra nhanh sau migration

```powershell
# 1. Kiểm thử toàn bộ migration V1->V7, V3->V7, V4->V7, preflight conflict & SQL Server fail-fast
mvn.cmd -f apartment-rental-backend/pom.xml test -Dtest=AccountSchemaUpgradeTest

# 2. Kiểm thử trực tiếp trên database SQL Server test biệt lập (KHÔNG trỏ vào AnCuRental thật)
$env:ANCU_TEST_SQLSERVER_URL = "jdbc:sqlserver://localhost:1433;databaseName=AnCuRental_Test;encrypt=true;trustServerCertificate=true"
$env:ANCU_TEST_SQLSERVER_UPGRADE_URL = "jdbc:sqlserver://localhost:1433;databaseName=AnCuRental_UpgradeTest;encrypt=true;trustServerCertificate=true"
$env:ANCU_TEST_SQLSERVER_USERNAME = "<sql_test_user>"
$env:ANCU_TEST_SQLSERVER_PASSWORD = "<sql_test_password>"
npm.cmd run test:sqlserver
```
