# Quản trị và kiểm tra AnCuRental trong SQL Server Management Studio

## 1. Xem dữ liệu & ràng buộc

Mở `02_inspect_AnCuRental.sql` bằng SSMS và nhấn **F5** (chỉ đọc). Tệp hiển thị danh sách bảng, lịch sử `flyway_schema_history`, số bản ghi, dữ liệu không lộ hash mật khẩu/OTP, cùng danh sách `CHECK` constraints (`is_not_trusted = 0` chứng minh mọi constraint được bật với `WITH CHECK`, không dùng `NOCHECK`) và filtered unique indexes.

## 2. Preflight trước khi nâng cấp V4 → V5–V7

Trước khi nâng cấp bất kỳ database SQL Server đã có dữ liệu nào, **backup database trước** rồi mở và chạy `03_preflight_check_V5.sql` (chỉ đọc).
- Nếu các truy vấn `BLOCKING_*` trả về dòng vi phạm, xử lý đối soát thủ công trước khi khởi động ứng dụng; không xóa dữ liệu mù quáng.
- Các truy vấn `AUDIT_LEGACY_*` liệt kê hợp đồng lịch sử chưa có số tiền cọc thực nhận hoặc chỉ số bàn giao (hệ thống tự gắn nhãn `LEGACY_UNVERIFIED`, không bịa dữ liệu).

## 3. Khởi tạo database trống hoặc nâng cấp

- **Database SQL Server trống mới tạo (`CREATE DATABASE AnCuRental`)**: chạy `01_initialize_AnCuRental.sql` trong SSMS (khởi tạo nguyên tử V1–V4), sau đó chạy ứng dụng với profile `sqlserver` (`npm.cmd run dev:sqlserver`). Ứng dụng sẽ xác minh chính xác dấu vết V4, baseline an toàn tại phiên bản 4, rồi áp dụng tự động `V5`, `V6`, `V7` qua Flyway.
- **Database SQL Server đã tồn tại ở V3 hoặc V4**: cấu hình biến môi trường `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` và chạy `npm.cmd run dev:sqlserver`. `AnCuFlywayMigrationStrategy` tự chạy preflight, xác minh dấu vết V3/V4, baseline an toàn và nâng cấp lên V7 mà không mất dữ liệu hiện có.

## 4. Nguyên tắc Fail-Fast (không fallback ngầm sang H2)

- `npm.cmd run dev` (profile `local`): chạy chủ động với H2 in-memory (`MODE=MSSQLServer`).
- `npm.cmd run dev:sqlserver` (profile `sqlserver`): bắt buộc kết nối SQL Server bằng đúng `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` đã cấu hình. Nếu thiếu thông tin xác thực hoặc SQL Server không truy cập được, backend **dừng khởi động ngay (fail-fast)** và KHÔNG chuyển ngầm sang H2. `/api/health` trả về `databaseEngine: "Microsoft SQL Server"` và script `dev:sqlserver` từ chối sẵn sàng nếu nhận H2.
