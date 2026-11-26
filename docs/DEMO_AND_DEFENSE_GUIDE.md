# Hướng dẫn Demo Thực tế (12–15 phút) & Gợi ý Vấn đáp Bảo vệ Đồ án

> Kịch bản bám sát 100% tính năng thật đang chạy trên **Java 21 + Spring Boot 3.2.5 + Spring JDBC + Flyway + React 19/Vite + SQL Server/H2**, không dùng mockup hay tính năng ngoài phạm vi.

---

## Phần A: Kịch bản Demo 12–15 phút

### Chuẩn bị trước buổi demo (2 phút trước khi lên trình bày)

- Nếu demo cùng **SQL Server (SSMS 20)** (khuyến nghị để chứng minh dữ liệu thực tế):
  ```powershell
  $env:DB_URL = "jdbc:sqlserver://localhost:1433;databaseName=AnCuRental;encrypt=true;trustServerCertificate=true"
  $env:DB_USERNAME = "<tai_khoan_sql>"
  $env:DB_PASSWORD = "<mat_khau_sql>"
  npm.cmd run dev:sqlserver
  ```
- Hoặc nếu máy trình chiếu không có SQL Server: `npm.cmd run dev` (chế độ Local H2 `MODE=MSSQLServer` được chọn chủ động).
- Mở sẵn 2 tab: `http://127.0.0.1:5173` (Ứng dụng) và `http://localhost:8080/api/health` (chỉ ra `status: "UP"` và `databaseEngine`).

### Diễn tiến Demo (13 phút)

1. **Phút 0–2 · Kiến trúc & Khám phá công khai (Public + SQL Search P2-01)**
   - Mở `http://localhost:8080/api/health`: giải thích backend chạy fail-fast, hiển thị đúng `databaseEngine` (`Microsoft SQL Server` hoặc `H2`) và múi giờ `Asia/Ho_Chi_Minh`.
   - Tại trang chủ `/#/`: lọc theo khu vực (`TP. Thủ Đức`, `Quận 7`), kéo thanh ngân sách, bấm **Lọc thêm** và gõ không dấu `thao dien`, đổi sắp xếp *Giá thấp trước* → giải thích phần lọc/sắp xếp/phân trang đã đẩy xuống SQL (`OFFSET/FETCH`) trong khi vẫn giữ chuẩn hóa tiếng Việt không dấu.

2. **Phút 2–4 · Đăng nhập phiên HTTP + CSRF & Yêu cầu thuê an toàn giá (P0-03, P1-13)**
   - Đăng nhập Khách thuê **Nguyễn Minh Anh** (`minhanh@ancu.vn` / `AnCu@123`). Chỉ vào nhãn xanh `Java Spring Boot API · ...` trên góc phải (Application Mode, không fallback giả lập).
   - Mở căn hộ **The River Thảo Điền (`A-12.06`, 12.500.000 ₫)** → bấm **Yêu cầu thuê**, chọn thời hạn `6 tháng`, ngày bắt đầu trong tương lai, đặt luôn lịch hẹn ký → bấm **Xem hợp đồng chờ ký**.
   - Nhấn mạnh: Frontend khách thuê không gửi `rent`/`deposit`; backend đọc trực tiếp giá niêm yết từ CSDL và từ chối nếu client cố tình giả mạo giá (`400 TENANT_PRICE_TAMPERING`).

3. **Phút 4–7 · Chủ nhà xác nhận ký & thu cọc thực nhận + Chống trùng lịch (P0-01, P0-02)**
   - Đăng xuất, đăng nhập Chủ nhà **Trần Thu Hà** (`thuha@ancu.vn` / `AnCu@123`).
   - Xem **Tổng quan** (`/owner`): 4 chỉ số thời gian thực từ `GET /api/owner/dashboard` và danh sách hợp đồng sắp hết hạn (`HD-1001`).
   - Vào **Hợp đồng** → mở hợp đồng chờ ký vừa tạo → bấm **Xác nhận khách đã ký & thu cọc**.
   - Chứng minh **P0-02**: nút xác nhận bị khóa nếu chưa nhập **Số tiền đặt cọc thực nhận** đủ `12.500.000 ₫` (không tự điền ngầm). Nhập `12500000`, tích 2 điều kiện → xác nhận thành công → hợp đồng sang `Đã ký` + `Đã thanh toán`, hiển thị `Tiền cọc thực nhận: 12.500.000 ₫`, tin đăng căn `A-12.06` tự động ẩn, và `Lịch sử trạng thái hợp đồng` ghi đầy đủ `CREATED → SCHEDULED → SIGN_AND_DEPOSIT_CONFIRMED`.
   - Giải thích **P0-01**: bên dưới giao dịch sử dụng khóa CSDL `WITH (UPDLOCK, HOLDLOCK)` theo thứ tự `Apartment → Contract` giúp chống ký trùng lịch ngay cả khi có nhiều request đồng thời.

4. **Phút 7–10 · Bàn giao nhận/trả phòng tách biệt & Chỉ số điện nước ban đầu (P0-04, P0-06, P0-07, P0-08)**
   - Trên hợp đồng vừa ký (hoặc tạo hợp đồng ngày bắt đầu hôm nay để demo nhận phòng ngay): bấm **Ghi nhận nhận phòng & chỉ số ban đầu**, nhập ngày nhận phòng = hôm nay, `Điện bàn giao = 100 kWh`, `Nước bàn giao = 10 m³` → lưu thành công.
   - Bấm **Ghi nhận trả phòng thực tế** → chỉ nhập ngày trả phòng → chỉ ra **ngày nhận phòng và chỉ số bàn giao được bảo toàn nguyên vẹn** (P0-06). Nếu có nhận phòng sớm và hóa đơn phạt đã thu (`PAID`), cập nhật trả phòng không xóa hóa đơn phạt (P0-07).
   - Nhắc đến **Hủy quá hạn 3 ngày (No-Show)**: modal không còn ô nhập ngày giả lập; máy chủ và cronjob `NoShowCancellationJob` cùng dùng `ServerClock` (giờ Việt Nam) nên không thể hủy sớm bằng thời gian client (P0-08).

5. **Phút 10–12 · Lập hóa đơn tuần tự & Xác nhận thu tiền Idempotent (P0-04, P0-05, P1-12)**
   - Sang trang **Hóa đơn** (`/owner/hoa-don`) → bấm **Lập hóa đơn**, chọn hợp đồng vừa bàn giao.
   - Chỉ ra giao diện tự nạp từ `GET /api/contracts/{id}/next-invoice-preview`: hiển thị **Kỳ 1** chính thức từ `LeaseRentCalculator`, hai ô **Điện cũ (100)** và **Nước cũ (10)** ở chế độ **chỉ đọc** do server quyết định. Nhập `Điện mới = 150`, `Nước mới = 14` → lưu hóa đơn.
   - Mở **Chi tiết** hóa đơn thấy rõ chu kỳ ngày, chỉ số cũ → mới và 3 dòng tiền (thuê + điện + nước). Bấm **Xác nhận đã thu** → cập nhật ngay doanh thu tháng trên **Tổng quan**.

6. **Phút 12–14 · Gia hạn hợp đồng & Kiểm chứng bằng Kiểm thử tự động / SSMS**
   - Mở `HD-1001` (sắp hết hạn trong 8 ngày): cho thấy quy trình gia hạn tạo hợp đồng mới độc lập qua `renewal_of_id` (bảo toàn hoàn toàn hợp đồng cũ, ràng buộc tối đa 1 hợp đồng gia hạn chưa hủy).
   - Chạy nhanh `npm.cmd test` (hoặc mở kết quả kiểm thử trong `docs/TEST_REPORT.md` và `02_inspect_AnCuRental.sql` trên SSMS) để minh chứng 46/46 test backend + 8/8 test frontend + 4/4 test startup + 17 kiểm chứng SQL Server đều đạt.

---

## Phần B: Câu hỏi Vấn đáp Trọng tâm & Cách Trả lời Ngắn gọn

1. **Tại sao không dùng `synchronized` trong Java để chống ký trùng hợp đồng mà phải chuyển sang khóa CSDL?**
   - `synchronized` chỉ khóa trong 1 JVM và nhả khóa trước khi Spring `@Transactional` commit xuống DB. Khi chạy 2 instance hoặc 2 request sát nhau, request thứ hai vẫn có thể đọc thấy dữ liệu chưa commit và ký trùng. Chuyển sang `SELECT id FROM apartments WITH (UPDLOCK, HOLDLOCK)` (SQL Server) giữ khóa đến hết transaction và bảo đảm nhất quán trên mọi instance.

2. **Làm sao tránh deadlock khi nhiều luồng vừa ký, vừa bàn giao, vừa chuyển bảo trì, vừa thu tiền hóa đơn?**
   - Áp dụng **thứ tự khóa phân cấp duy nhất (Lock Ordering)**: luôn khóa `apartments` (Level 1) trước, rồi mới khóa `contracts` (Level 2). Các nghiệp vụ chỉ chạm hóa đơn (`createPeriodInvoice`, `confirmInvoicePaid`) chỉ khóa `contracts` (Level 2). Vì đồ thị khóa là một chiều (`Apartment → Contract`), không thể tạo chu trình deadlock.

3. **Tại sao phải thêm cột `deposit_verification_status` và `handover_meter_status` thay vì gán mặc định số cọc = `deposit_amount` hay chỉ số bàn giao = `0` cho dữ liệu cũ?**
   - Không được bịa đặt dữ liệu tài chính/chỉ số lịch sử vì sẽ làm sai lệch kiểm toán và tính sai tiền điện nước kỳ đầu. Hệ thống gắn nhãn minh bạch `LEGACY_UNVERIFIED` cho dữ liệu trước nâng cấp, đồng thời áp đặt `CHECK` constraint chặt (`WITH CHECK`) bắt buộc dữ liệu mới (`VERIFIED`) phải có số thực nhận và chỉ số hợp lệ.

4. **Tại sao chọn quy ước tháng 2 luôn 30 ngày trong `LeaseRentCalculator` và lưu snapshot vào `contracts`?**
   - Quy ước tháng 2 = 30 ngày là chính sách nghiệp vụ đã thống nhất từ đầu của đồ án để đơn giản hóa chu kỳ thuê từ ngày dọn vào. Lưu snapshot (`monthly_rent`, `deposit_amount`, `electric_rate`, `water_rate`, `signing_location`) đảm bảo khi chủ nhà tăng giá phòng hoặc đổi đơn giá điện/nước về sau, các hợp đồng đã lập vẫn giữ nguyên thỏa thuận ban đầu.

5. **Tại sao không dùng JWT hay cổng thanh toán online, mà dùng HTTP Session + CSRF và chủ nhà xác nhận thu tiền?**
   - Ứng dụng là kiến trúc Monolithic phục vụ trực tiếp trên trình duyệt: HTTP Session (`HttpOnly`, `SameSite=Lax`) kết hợp `CsrfProtectionFilter` (`XSRF-TOKEN` + `X-CSRF-Token`) dễ thu hồi phiên ngay khi đổi mật khẩu (`auth_version`) và tránh rủi ro lưu token ở `localStorage`. Nghiệp vụ thu tiền theo phạm vi đồ án là ghi nhận kế toán chủ nhà đã thu tiền cọc/hóa đơn, không tích hợp cổng thanh toán.

6. **Nếu backend bị tắt giữa chừng hoặc SQL Server không kết nối được thì hệ thống hành xử ra sao?**
   - Profile `sqlserver` **fail-fast** ngay khi khởi động nếu thiếu thông tin kết nối hoặc không tới được SQL Server, tuyệt đối không fallback ngầm sang H2 làm mất dữ liệu. Trên giao diện React (Application Mode mặc định), mọi thao tác đều yêu cầu phản hồi thật từ backend và hiển thị lỗi rõ ràng chứ không tự cập nhật state cục bộ giả lập thành công.

7. **Những phần nào nằm ngoài phạm vi hiện tại và hướng phát triển tiếp?**
   - Hoàn cọc/khấu trừ hư hại khi thanh lý, thanh toán từng phần (công nợ dư nợ lũy kế), thay đồng hồ điện/nước giữa kỳ và cổng thanh toán trực tuyến được ghi nhận trong backlog mở rộng, không tự thêm vào đồ án để giữ đúng phạm vi đã chốt.
