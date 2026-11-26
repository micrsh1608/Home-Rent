# Báo cáo Khắc phục Source Code — An Cư Rental

> Tài liệu đối chiếu trực tiếp với kế hoạch khắc phục `README_ANTIGRAVITY.md` trên repository hiện tại. Mọi trạng thái dưới đây phản ánh đúng kết quả biên dịch và kiểm thử thực tế đã chạy.

## 1. Tóm tắt tổng quan

| Nhóm | Tổng số mục | PASS | PARTIAL / BLOCKED (có lý do rõ) | DEFERRED |
|---|---:|---:|---:|---:|
| **P0 (Bắt buộc)** | 9 (`P0-01` → `P0-09`) | 8 | 1 (`P0-01` — H2 + SQL Server T-SQL lock PASS; riêng `T02` hai Java instance trên SQL Server chờ biến môi trường SQL Auth) | 0 |
| **P1 (Độ tin cậy)** | 6 (`P1-10` → `P1-15`) | 6 | 0 | 0 |
| **P2 (Cải tiến)** | 3 (`P2-01` → `P2-03`) | 2 (`P2-01`, `P2-02`) | 0 | 1 (`P2-03` tách lớn `ContractLifecycleService` — hoãn có chủ đích để giữ ổn định transaction boundary) |

---

## 2. Chi tiết theo từng hạng mục (P0 → P1 → P2)

### P0-01 — Race condition khi ký hợp đồng

- **Bằng chứng lỗi xác nhận trong source gốc:** `ContractLifecycleService.confirmSignAndDepositByOwner()` dùng `ConcurrentHashMap<String, Object> apartmentLocks` + khối `synchronized (lock)` bên trong `@Transactional`. Khóa JVM vô hiệu khi chạy nhiều instance và bị nhả trước khi Spring `PlatformTransactionManager` commit giao dịch DB.
- **File / phương thức đã thay đổi:**
  - Tạo mới `DatabaseLockService.java` (`lockApartment`, `lockContract`, `lockApartmentAndContract`) và `DatabaseDialect.java`.
  - Cập nhật `ContractLifecycleService.java` (xóa hoàn toàn `apartmentLocks` & `synchronized`; mọi đường ghi liên quan chiếm dụng dùng khóa DB).
  - Cập nhật `OwnerPropertyService.java` (`updateApartment`, `updatePublishStatus` dùng cùng khóa `lockApartment`).
  - Cập nhật `GlobalExceptionHandler.java` (ánh xạ `PessimisticLockingFailureException` / `DeadlockLoserDataAccessException` / `CannotAcquireLockException` / `QueryTimeoutException` về `409 LOCK_CONFLICT`).
- **Giải pháp:** Áp dụng thứ tự khóa phân cấp bắt buộc tại tầng CSDL trong transaction: **Level 1 `apartments` → Level 2 `contracts`**. SQL Server sử dụng `SELECT id FROM apartments WITH (UPDLOCK, HOLDLOCK) WHERE id = ?`; H2 dùng `SELECT id FROM apartments WHERE id = ? FOR UPDATE`. Khóa giữ đến khi commit/rollback. Đọc lại trạng thái, quyền sở hữu, tình trạng bảo trì và kiểm tra trùng lịch inclusive (`effective_start <= proposed_end AND end_date >= proposed_start`) sau khi đã giữ khóa. Hợp đồng bị từ chối trả `409 OVERLAPPING_SIGNED_CONTRACT` và **không** bị tự hủy.
- **Test liên quan:** `T01`, `T02`, `T03`, `T04` (`ConcurrencyRemediationTest`, `RemediationCoreIntegrationTest`, `SqlServerVerificationIntegrationTest`, kiểm chứng 2 kết nối đồng thời `UPDLOCK/HOLDLOCK` trên SQL Server test riêng).
- **Trạng thái:** **PARTIAL (Code & H2 & SQL Server T-SQL lock PASS; riêng T02 2 tiến trình Java trên SQL Server là BLOCKED do chưa có SQL Auth test credentials / native `mssql-jdbc_auth` DLL trên máy).**
- **Hạn chế:** Cần chạy `npm.cmd run test:sqlserver` với `ANCU_TEST_SQLSERVER_URL`, `ANCU_TEST_SQLSERVER_USERNAME`, `ANCU_TEST_SQLSERVER_PASSWORD` trên DB test riêng để hoàn tất bằng chứng T02 bằng 2 Spring Boot JVM độc lập.

### P0-02 — Bắt buộc xác nhận số tiền cọc thực nhận

- **Bằng chứng lỗi xác nhận:** `ContractLifecycleService.java` kiểm tra `if (req.receivedAmount() != null && req.receivedAmount() < contract.deposit())` khiến `null` vượt qua hợp lệ; schema không lưu số tiền thực nhận; `contracts.jsx` âm thầm gửi `receivedAmount: contract.deposit`.
- **File / phương thức đã thay đổi:**
  - Migration `V5__add_integrity_columns.sql`, `V6__BackfillIntegrityMetadata.java`, `db/dialect/{h2,sqlserver}/V7__enforce_business_constraints.sql`.
  - `ContractDto.java` (thêm `depositReceivedAmount`, `depositVerificationStatus`).
  - `ContractLifecycleService.confirmSignAndDepositByOwner()`.
  - Frontend `contracts.jsx` (ô nhập bắt buộc `Số tiền đặt cọc thực nhận (₫)` khởi tạo rỗng, hiển thị trạng thái xác minh/lịch sử).
- **Giải pháp:** Bắt buộc `receivedAmount != null`, `>= 0`, `>= contract.deposit()`, `<= MAX_DEPOSIT`. Lưu `deposit_received_amount` và `deposit_verification_status = 'VERIFIED'` trong transaction ký. Giữ tương thích chính sách nhận `>= cọc`: khoản vượt mức không tự khấu trừ vào tiền thuê và ghi chú đối soát rõ trong `contract_status_history`. Dữ liệu lịch sử `PAID` trước V5 không bị bịa số liệu mà gắn `deposit_verification_status = 'LEGACY_UNVERIFIED'` với `deposit_received_amount = NULL`.
- **Test liên quan:** `T05` (`RemediationCoreIntegrationTest.t05_p0_02_...`), `T17`, `T21`, `demo.test.js`.
- **Trạng thái:** **PASS**.

### P0-03 — Không để TENANT tự quyết giá thuê, tiền cọc

- **Bằng chứng lỗi xác nhận:** `ContractLifecycleService.createContract()` ưu tiên `req.rent()` và `req.deposit()` ngay cả khi actor có role `TENANT`.
- **File / phương thức đã thay đổi:**
  - `ContractLifecycleService.createContract()`, `ContractLifecycleService.updateAwaitingContract()`.
  - Frontend `public.jsx` (TENANT không gửi `rent`/`deposit` khi yêu cầu thuê), `contracts.jsx` (TENANT không gửi `rent` khi sửa hợp đồng chờ ký), `demo.js`.
- **Giải pháp:** Với `TENANT`, giá thuê và tiền cọc luôn đọc từ căn hộ trong CSDL (cọc mặc định = 1 tháng tiền thuê). Request gửi giá/cọc sai khác giá server bị từ chối với `400 TENANT_PRICE_TAMPERING` / `TENANT_DEPOSIT_TAMPERING` khi tạo và `403 FORBIDDEN` khi sửa. Chỉ `OWNER` sở hữu căn hộ mới được điều chỉnh giá/cọc ở trạng thái `AWAITING` và ghi nhận lịch sử thay đổi; snapshot hợp đồng đã ký bất biến khi giá niêm yết thay đổi.
- **Test liên quan:** `T06` (`RemediationCoreIntegrationTest.t06_p0_03_...`), `demo.test.js`.
- **Trạng thái:** **PASS**.

### P0-04 — Nhất quán chỉ số điện nước

- **Bằng chứng lỗi xác nhận:** `createPeriodInvoice()` chỉ kiểm tra `new >= old` trong từng request đơn lẻ, không lưu chỉ số bàn giao và không kiểm tra liên tục giữa các kỳ.
- **File / phương thức đã thay đổi:**
  - Migration `V5`, `V6`, `V7` (cột `handover_electric_reading`, `handover_water_reading`, `handover_meter_status` trên `contracts`; `meter_verification_status` trên `invoices`; CHECK `ck_contracts_handover_meters` & `ck_invoices_monthly_meters_strict`).
  - `ContractDto.java`, `InvoiceDto.java`, `NextInvoicePreviewDto.java`.
  - `ContractLifecycleService.applyHandover()`, `getNextInvoicePreview()`, `createPeriodInvoice()`.
  - `ContractController.java` (`GET /api/contracts/{id}/next-invoice-preview`, `POST /api/contracts/{id}/check-in`).
  - Frontend `contracts.jsx`, `owner.jsx`.
- **Giải pháp:** Bắt buộc ghi nhận chỉ số điện/nước ban đầu không âm khi nhận phòng (`handover_meter_status = 'VERIFIED'`). Hóa đơn lập tuần tự: kỳ 1 lấy chỉ số bàn giao đã xác minh (từ chối nếu chưa bàn giao hoặc là `LEGACY_UNVERIFIED`); kỳ `k > 1` bắt buộc đã có kỳ `k - 1` và lấy chỉ số chốt kỳ trước (`409 PREVIOUS_PERIOD_REQUIRED` nếu bỏ kỳ; `400 ELECTRIC/WATER_METER_CONTINUITY_ERROR` nếu gửi chỉ số cũ sai). Khóa chỉ số bàn giao sau khi đã lập hóa đơn tháng. Dữ liệu lịch sử không bị tự động gán `0` hay sửa đè mà được đánh dấu `LEGACY_UNVERIFIED` / `LEGACY_DISCREPANCY`.
- **Test liên quan:** `T07`, `T08` (`RemediationCoreIntegrationTest.t07_t08_t09_...`), `T17`, `T21`.
- **Trạng thái:** **PASS**.

### P0-05 — Chống lập hóa đơn trùng kỳ thuê

- **Bằng chứng lỗi xác nhận:** `createPeriodInvoice()` chọn `targetPeriod` theo `periodIndex` nhưng ưu tiên `req.month()` làm `billingMonth`, cho phép lập nhiều hóa đơn cho cùng một `periodIndex` với tháng khác nhau.
- **File / phương thức đã thay đổi:**
  - Migration `V5` (`period_index`), `V6` (backfill bằng `LeaseRentCalculator`), `V7` (filtered unique index `uq_invoices_contract_monthly_period` trên SQL Server và guard unique index tương đương trên H2, kèm `uq_invoices_contract_early_move_in`).
  - `ContractLifecycleService.resolveTargetPeriod()`, `createPeriodInvoice()`.
  - Frontend `owner.jsx` (dùng `/next-invoice-preview`, gửi `periodIndex` + `month` đồng bộ từ server).
- **Giải pháp:** Server xác định chính thức `period_index`, `billing_month`, `period_start`, `period_end`, tiền thuê từ `LeaseRentCalculator`; từ chối `400 PERIOD_MONTH_MISMATCH` nếu `month` và `periodIndex` mâu thuẫn; khóa hợp đồng bằng `lockContract` và ánh xạ unique violation sang `409 DUPLICATE_INVOICE`. Giữ `EARLY_MOVE_IN` tách biệt với `period_index = NULL`.
- **Test liên quan:** `T09`, `T10` (`RemediationCoreIntegrationTest`, `ConcurrencyRemediationTest`), `T17` (H2 + SQL Server).
- **Trạng thái:** **PASS**.

### P0-06 — Bảo toàn dữ liệu nhận/trả phòng

- **Bằng chứng lỗi xác nhận:** `recordHandover()` ghi đè trực tiếp `actual_move_in = null` nếu `req.actualMoveIn()` null; có thể xóa mất ngày nhận phòng và gây hủy no-show nhầm.
- **File / phương thức đã thay đổi:**
  - `ContractController.java` (bổ sung `POST /api/contracts/{id}/check-in`, `POST /api/contracts/{id}/check-out`, giữ tương thích `/handover`).
  - `ContractLifecycleService.recordCheckIn()`, `recordCheckOut()`, `recordHandover()`, `applyHandover()`.
  - Migration `V7` (`ck_contracts_handover_dates_strict`).
  - Frontend `contracts.jsx` (tách riêng modal Ghi nhận nhận phòng & chỉ số bàn giao và modal Ghi nhận trả phòng thực tế).
- **Giải pháp:** Giá trị `null`/vắng mặt tuyệt đối không xóa ngày nhận/trả đã lưu; mọi đường dẫn dùng chung khóa `lockApartmentAndContract` và validation (chỉ `OWNER`, hợp đồng `SIGNED`, không trả phòng trước nhận phòng, không ngày tương lai, trả `>=` nhận, nhận `<= start_date + 3`). Request lặp không làm đổi dữ liệu hay tạo lịch sử trùng. Phối hợp cùng khóa với tác vụ no-show.
- **Test liên quan:** `T11`, `T12` (`RemediationCoreIntegrationTest`, `ConcurrencyRemediationTest`), `T17` (H2 + SQL Server).
- **Trạng thái:** **PASS**.

### P0-07 — Không xóa hóa đơn nhận phòng sớm đã thanh toán

- **Bằng chứng lỗi xác nhận:** `upsertEarlyMoveInPenaltyIfApplicable()` chỉ chặn xóa khi `PAID` và ngày nhận thay đổi; nếu cùng ngày vẫn thực thi `DELETE FROM invoices WHERE id = ?` làm mất hóa đơn đã thu.
- **File / phương thức đã thay đổi:** `ContractLifecycleService.upsertEarlyMoveInPenaltyIfApplicable()`.
- **Giải pháp:** Nếu hóa đơn `EARLY_MOVE_IN` đã `PAID`: giữ nguyên 100% (ID, status, `total_amount`, `paid_at`, `confirmed_by`, `invoice_lines`) khi ngày nhận không đổi; từ chối `409 PENALTY_ALREADY_PAID` nếu cố đổi ngày nhận làm ảnh hưởng phí. Nếu `UNPAID`: cập nhật tại chỗ (`UPDATE ... WHERE id = ? AND status = 'UNPAID'`) thay vì xóa-tạo lại. Dùng chung khóa `lockContract` với `confirmInvoicePaid()`.
- **Test liên quan:** `T13` (`RemediationCoreIntegrationTest.t11_t13_...`, `ConcurrencyRemediationTest.t13_concurrent...`).
- **Trạng thái:** **PASS**.

### P0-08 — Không tin thời gian hiện tại do Client gửi

- **Bằng chứng lỗi xác nhận:** `ContractController.cancelNoShowAfterThreeDays()` nhận `@RequestParam LocalDate today`; `ScheduleAppointmentRequest` có trường `LocalDateTime now`; modal `cancel-no-show` ở `contracts.jsx` cho chọn ngày kiểm tra.
- **File / phương thức đã thay đổi:**
  - Tạo mới `ServerClock.java`, đăng ký `@Bean Clock` (múi giờ `Asia/Ho_Chi_Minh`) trong `AnCuRentalApplication.java`.
  - Xóa tham số `today` khỏi `ContractController.cancelNoShowAfterThreeDays()` và `now` khỏi `ScheduleAppointmentRequest`.
  - Cập nhật `ContractLifecycleService`, `NoShowCancellationJob`, `OwnerPropertyService`, `PublicApartmentService`, `OwnerDashboardService`, `FavoriteService`, `AccountService`, `HealthController`, `GlobalExceptionHandler` dùng chung `ServerClock`.
  - Frontend `contracts.jsx` & `demo.js` bỏ ô chọn `checkDate` và bỏ query `?today=`.
- **Giải pháp:** Mọi nghiệp vụ lấy giờ Việt Nam từ `ServerClock` (inject `Clock` cho test qua `TestClockSupport.MutableClock`). Mọi tham số `today`/`now` giả mạo từ client đều vô hiệu.
- **Test liên quan:** `T12`, `T14` (`RemediationCoreIntegrationTest.t14_p0_08_...`, `NoShowCancellationIntegrationTest`).
- **Trạng thái:** **PASS**.

### P0-09 — SQL Server fail-fast, không fallback ngầm

- **Bằng chứng lỗi xác nhận:** `ResilientDataSourceConfig.java` chứa danh sách mật khẩu cứng (`StrongPassword123!`, `123456`, ...) và tự fallback sang H2 in-memory khi kết nối SQL Server lỗi; `application-sqlserver.yml` có mật khẩu mặc định `StrongPassword123!` và tắt Flyway.
- **File / phương thức đã thay đổi:**
  - `ResilientDataSourceConfig.java` (xóa hoàn toàn danh sách mật khẩu cứng, xóa tự tạo DB trên `master`, xóa fallback H2 ngầm, kiểm tra kết nối `SELECT 1` và dừng ngay khi lỗi, che mật khẩu trong log URL).
  - `application-sqlserver.yml` (xóa mật khẩu mặc định, bật Flyway thống nhất).
  - `HealthController.java` (trả `databaseEngine`, `activeProfiles`, HTTP 503 khi DB DOWN).
  - `scripts/dev-readiness.mjs`, `scripts/fullstack.mjs`, `scripts/dev-readiness.test.mjs` (xác minh `databaseEngine` khớp mode `--sqlserver` vs local).
- **Test liên quan:** `T15` (`AccountSchemaUpgradeTest.sqlServerProfileFailsFast...`, `scripts/dev-readiness.test.mjs`).
- **Trạng thái:** **PASS**.

### P1-10 — Đồng thời tại các đường cập nhật

- **File / phương thức đã thay đổi:** `ContractLifecycleService` (`createContract`, `scheduleAppointment`, `updateAwaitingContract`, `cancelAwaitingContract`, `confirmSignAndDepositByOwner`, `cancelContractForNoShowAfterThreeDays`, `autoCancelExpiredNoShowContracts`, `applyHandover`, `respondRenewal`), `OwnerPropertyService` (`updateApartment`, `updatePublishStatus`), migration `V5` & `V7` (cột `version` và unique index `uq_contracts_active_renewal_of`).
- **Giải pháp:** Khóa bản ghi theo thứ tự chuẩn (`Apartment` → `Contract`), kiểm tra lại trạng thái/quyền dưới khóa, `UPDATE` có điều kiện kèm `version = version + 1 WHERE ... AND version = ?`, kiểm tra `affectedRows == 1` trước khi ghi `contract_status_history`. Xử lý `autoCancelExpiredNoShowContracts` độc lập theo từng hợp đồng. Bảo đảm mỗi hợp đồng gốc có tối đa 1 hợp đồng gia hạn chưa hủy ở cả tầng ứng dụng và CSDL.
- **Test liên quan:** `T16` (`ConcurrencyRemediationTest.t16_p1_10_...`), `T17` (SQL Server & H2).
- **Trạng thái:** **PASS**.

### P1-11 — Database constraints theo business rules

- **File / phương thức đã thay đổi:** `V5__add_integrity_columns.sql`, `V6__BackfillIntegrityMetadata.java`, `db/dialect/h2/V7__enforce_business_constraints.sql`, `db/dialect/sqlserver/V7__enforce_business_constraints.sql`.
- **Giải pháp:** Thay thế constraint lỏng lẻo từ V1 bằng các ràng buộc nghiêm ngặt (`WITH CHECK` trên SQL Server, không dùng `NOCHECK`): cấm `SIGNED + UNPAID`, cấm `AWAITING + PAID`, cấm `deposit_forfeited = 1` nếu không phải `CANCELLED + PAID`, yêu cầu metadata ký đầy đủ cho `SIGNED`, cấm `actual_move_out` khi `actual_move_in` null, yêu cầu đầy đủ chỉ số cho `MONTHLY`, yêu cầu `paid_at` & `confirmed_by` cho hóa đơn `PAID`, unique kỳ `MONTHLY`, unique `EARLY_MOVE_IN`, unique gia hạn đang hiệu lực.
- **Test liên quan:** `T17` (`RemediationCoreIntegrationTest.t17_p1_11_...` + xác minh trực tiếp trên SQL Server test DB).
- **Trạng thái:** **PASS**.

### P1-12 — Idempotency khi xác nhận thu hóa đơn

- **File / phương thức đã thay đổi:** `ContractLifecycleService.confirmInvoicePaid()`.
- **Giải pháp:** Khóa hợp đồng (`lockContract`, cùng thứ tự khóa với bàn giao/phạt nhận sớm), xác minh quyền `OWNER`, thực thi `UPDATE invoices SET status = 'PAID', paid_at = ?, confirmed_by = ? WHERE id = ? AND status = 'UNPAID'`, trả về bản ghi đã lưu mà không ghi đè `paid_at`/`confirmed_by` khi gọi lặp hoặc đồng thời.
- **Test liên quan:** `T18` (`ConcurrencyRemediationTest.t18_p1_12_...`).
- **Trạng thái:** **PASS**.

### P1-13 — Security, validation và cấu hình

- **File / phương thức đã thay đổi:**
  - Tạo mới `CsrfProtectionFilter.java`; cập nhật `SessionAuthHelper.java`, `AuthController.java` (`GET /api/auth/csrf`, xoay token CSRF khi đăng nhập/đăng ký, xóa cookie khi logout), `AnCuRentalApplication.java` (CORS allow `X-CSRF-Token`), `application.yml` (`SESSION_COOKIE_SECURE`), `.env.example`, `demo.js` (tự động gửi `X-CSRF-Token`).
  - `LeaseRentCalculator.java`, `ContractLifecycleService.java`, `OwnerPropertyService.java` (chặn giới hạn số tiền, thời hạn, chỉ số, độ dài chuỗi, dùng `Math.multiplyExact`/`addExact` chống overflow, bảo vệ cả `/api/contracts/rent-preview`).
  - `GlobalExceptionHandler.java` (ánh xạ lỗi 400/403/404/409/500 an toàn, không lộ stacktrace/SQL/secret).
- **Cảnh báo về file `.env` cục bộ:** Qua kiểm tra `git ls-files` và `git log --all`, tệp `apartment-rental-backend/.env` **không** được theo dõi trong Git và `.gitignore` đã loại trừ `.env`, `.env.*`, `smtp.local.properties`. Tuy nhiên tệp `.env` hiện đang tồn tại trên thư mục làm việc cục bộ (chưa xóa để tránh làm mất cấu hình máy cá nhân). **Chủ dự án cần loại bỏ `.env` khỏi gói nén khi nộp bài và thực hiện rotate mật khẩu SMTP/DB nếu tệp này từng được đóng gói trong `final_analysis.zip`.**
- **Test liên quan:** `T19`, `T24` (`RemediationCoreIntegrationTest.t19_p1_13_...`, `AccountIntegrationTest`, `LocalMailConfigurationTest`, `demo.test.js`).
- **Trạng thái:** **PASS**.

### P1-14 — Phân biệt Prototype và Application Mode

- **File / phương thức đã thay đổi:** `demo.js` (`IS_PROTOTYPE_MODE`, `runAppMutation`, lỗi mạng tường minh `isNetworkError`), `shared.jsx`, `contracts.jsx`, `owner.jsx`, `public.jsx`.
- **Giải pháp:** Loại bỏ hoàn toàn nhánh fallback mô phỏng cục bộ khi `apiConnected === false`. Application Mode là mặc định (`VITE_APP_MODE` không đặt): mọi thao tác ghi gọi trực tiếp backend; khi lỗi mạng/409/500 hiển thị lỗi thật và không cập nhật trạng thái thành công giả. Prototype Mode chỉ bật chủ động khi đặt `VITE_APP_MODE=prototype`, có biểu ngữ nhận diện rõ và tách biệt hoàn toàn với backend.
- **Test liên quan:** `T20` (`apartment-rental-frontend/test/demo.test.js`).
- **Trạng thái:** **PASS**.

### P1-15 — Migration thống nhất cho SQL Server và H2

- **File / phương thức đã thay đổi:** `pom.xml` (thêm `org.flywaydb:flyway-sqlserver`), `MigrationPreflightValidator.java`, `AnCuFlywayMigrationStrategy.java`, `V5__add_integrity_columns.sql`, `V6__BackfillIntegrityMetadata.java`, `db/dialect/{h2,sqlserver}/V7__enforce_business_constraints.sql`, `docs/sql/03_preflight_check_V5.sql`, `docs/sql/02_inspect_AnCuRental.sql`.
- **Giải pháp:** Một cơ chế Flyway duy nhất cho cả H2 và SQL Server; tắt `baseline-on-migrate` mù quáng; kiểm tra dấu vết lược đồ chính xác trước khi baseline V3/V4; chạy `MigrationPreflightValidator` báo cáo mọi xung đột dữ liệu trước khi sửa lược đồ và không bao giờ tự xóa bản ghi.
- **Test liên quan:** `T21` (`AccountSchemaUpgradeTest` trên H2 + kiểm chứng V1→V7 & constraints trực tiếp trên SQL Server test DB).
- **Trạng thái:** **PASS**.

### P2-01, P2-02, P2-03 — Cải tiến sau P0/P1

- **P2-01 (`PublicApartmentService`):** **PASS** — Chuyển thống kê `districtCounts`, `budgetCeiling`, lọc cấu trúc (`districts`, `minPrice`, `maxPrice`, `beds`, `minArea`), sắp xếp (`ORDER BY` whitelist) và phân trang (`OFFSET ? ROWS FETCH NEXT ? ROWS ONLY`) xuống SQL (đã kiểm chứng trên cả H2 và SQL Server), giữ nguyên tìm kiếm tiếng Việt không dấu `normalizeVietnamese`.
- **P2-02 (`listInvoicesForActor`):** **PASS** — Thay `2N + 1` truy vấn thành **1 truy vấn `LEFT JOIN invoice_lines`** nhóm bằng `LinkedHashMap`, giữ nguyên phân quyền và thứ tự.
- **P2-03 (Refactor lớn `ContractLifecycleService`):** **DEFERRED (có kiểm soát)** — Đã tách các phần hạ tầng ngang hàng (`DatabaseLockService`, `DatabaseDialect`, `ServerClock`, `MigrationPreflightValidator`, `AnCuFlywayMigrationStrategy`, `CsrfProtectionFilter`) nhưng giữ nguyên `ContractLifecycleService` làm facade điều phối giao dịch để không làm tăng rủi ro regression trong phạm vi đồ án.

---

## 3. Quyết định nghiệp vụ và giả định cần chủ dự án xác nhận

1. **Tiền cọc vượt mức yêu cầu (`receivedAmount > contract.deposit`):** Giữ tương thích cho phép nhận `>= cọc`, lưu chính xác số thực nhận vào `deposit_received_amount`, không tự trừ vào tiền thuê và ghi chú đối soát riêng trong `contract_status_history`. Nếu chủ dự án muốn chỉ cho phép thu **đúng bằng** tiền cọc, chỉ cần đổi dấu `<` thành `!=` tại `confirmSignAndDepositByOwner`.
2. **Lập hóa đơn tuần tự (`period_index = 1, 2, ...`):** Áp dụng chính sách lập tuần tự không bỏ kỳ và bắt buộc chỉ số bàn giao đã xác minh trước kỳ 1; ngoại lệ duy nhất cho dữ liệu seed `HD-1001` đã có sẵn kỳ 5 (`INV-1001`, không có sai lệch lịch sử) cho phép lập nối tiếp kỳ 6 từ chỉ số chốt của kỳ 5.
3. **Thay đổi ngày nhận phòng sau khi đã ghi nhận:** Nếu hóa đơn phạt `EARLY_MOVE_IN` đã `PAID`, từ chối mọi thay đổi ngày nhận phòng ảnh hưởng đến phí phạt; nếu đã có hóa đơn `MONTHLY`, khóa chỉ số bàn giao để không phá vỡ lịch sử.
4. **Cơ chế khóa bổ sung (`historyId` đơn điệu):** Bổ sung tiền tố thời gian + bộ đếm tăng dần cho `contract_status_history.id` để đảm bảo thứ tự hiển thị lịch sử luôn đúng ngay cả khi 2 sự kiện xảy ra trong cùng 1 mili-giây.
