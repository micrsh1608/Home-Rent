# An Cư – Backend (`apartment-rental-backend`)

Backend Java 21 và Spring Boot 3 cho hệ thống cho thuê căn hộ **An Cư**, sử dụng **Flyway** để quản lý migration cơ sở dữ liệu **Microsoft SQL Server** (kiểm tra qua **SQL Server Management Studio 20**).

---

## 1. Cấu hình môi trường & Chạy ứng dụng

### Kết nối Microsoft SQL Server (Mặc định)
Tạo cơ sở dữ liệu trống `AnCuRental` trong SQL Server (qua SSMS 20), sau đó thiết lập các biến môi trường trước khi khởi động:

```powershell
$env:DB_URL = "jdbc:sqlserver://localhost:1433;databaseName=AnCuRental;encrypt=true;trustServerCertificate=true"
$env:DB_USERNAME = "sa"
$env:DB_PASSWORD = "StrongPassword123!"
mvn spring-boot:run -Psqlserver
```

Khi khởi động, Flyway tự động chạy:
- `src/main/resources/db/migration/V1__init_schema.sql`: Tạo các bảng `users`, `buildings`, `apartments`, `contracts`, `contract_status_history`, `invoices`, `invoice_lines`, `saved_apartments` cùng khóa ngoại (`FOREIGN KEY`), ràng buộc (`CHECK`, `UNIQUE`) và chỉ mục (`INDEX`).
- `src/main/resources/db/migration/V2__seed_data.sql`: Khởi tạo dữ liệu mẫu cho 2 chủ nhà, 2 khách thuê, 4 tòa nhà, 6 căn hộ, 2 hợp đồng và 1 hóa đơn mẫu.

### Chạy chế độ Local (Tương thích MSSQLServer trong bộ nhớ) & Kiểm thử
```powershell
mvn test
mvn spring-boot:run "-Dspring-boot.run.profiles=local"
```

---

## 2. Tài khoản mẫu

Mật khẩu mặc định cho tất cả tài khoản mẫu (băm bằng BCrypt): `AnCu@123`

| Mã | Họ tên | Email | Vai trò | Khu vực trên React |
|---|---|---|---|---|
| `o1` | Trần Thu Hà | `thuha@ancu.vn` | `OWNER` | `/owner` |
| `o2` | Phạm Gia Bảo | `giabao@ancu.vn` | `OWNER` | `/owner` |
| `t1` | Nguyễn Minh Anh | `minhanh@ancu.vn` | `TENANT` | `/tenant` |
| `t2` | Lê Hoàng Nam | `hoangnam@ancu.vn` | `TENANT` | `/tenant` |

---

## 3. Hai quyết định nghiệp vụ đã chốt (Mốc 1–4)

1. **Xác nhận ký, thu tiền ban đầu và hủy hợp đồng quá hạn nhận phòng (`POST /api/contracts/{id}/confirm-sign-and-deposit`, `POST /api/contracts/{id}/cancel-no-show`):**
   - Người môi giới / Chủ nhà quản lý (`OWNER`) xác nhận khách đã ký tại địa điểm hẹn (`signedAtLocation = true`) và ghi nhận đã nhận tiền đặt cọc (`depositReceived = true`).
   - Khi hệ thống nhận đủ xác nhận ký và khoản **Đã thanh toán** (tiền đặt cọc trên hợp đồng), hợp đồng mới chuyển trạng thái sang **Đã ký** (`SIGNED`) và **Đã thanh toán** (`PAID`), đồng thời ẩn tin đăng căn hộ và ghi lịch sử vào `contract_status_history`. Hợp đồng chưa ký không thể chuyển sang **Đã thanh toán**.
   - Backend quét hợp đồng đã ký **mỗi phút theo giờ Việt Nam**. Từ ngày sau hạn chót nhận phòng (`start_date + 3 ngày`), hợp đồng chưa ghi nhận nhận phòng thực tế sẽ tự chuyển sang **Đã hủy** (`CANCELLED`) và được đánh dấu **mất tiền đặt cọc** (`deposit_forfeited = 1`). Lịch sử ghi người thực hiện là **Hệ thống**. Ví dụ ngày nhận phòng là 20/11 thì tác vụ bắt đầu xử lý từ 24/11.
2. **Tính tiền thuê kỳ đầu và kỳ cuối theo chu kỳ từ ngày dọn vào (`GET /api/contracts/{id}/rent-schedule`, `POST /api/contracts/{id}/invoices`):**
   - Bắt đầu tính từ ngày dọn vào quy định trên hợp đồng (`start_date`) theo thời hạn khách đăng ký thuê (`1 tháng`, `1 năm` hoặc khoảng thời gian khách chọn).
   - Quy đổi 1 tháng theo số ngày của tháng bắt đầu kỳ đó: tháng có **30 ngày** tính 30 ngày, tháng có **31 ngày** tính 31 ngày; riêng **tháng 2 luôn tính theo 30 ngày** kể từ ngày bắt đầu trên hợp đồng.

---

## 4. Danh mục Endpoint đầy đủ (Mốc 1 – Mốc 6)

### Mốc 1: Nền tảng
- `GET /api/health` – Kiểm tra trạng thái ứng dụng, kết nối cơ sở dữ liệu và múi giờ `Asia/Ho_Chi_Minh`.

### Mốc 2: Tài khoản và Tin đăng
- **Xác thực & Phiên:**
  - `POST /api/auth/login` – Đăng nhập bằng `email` (hoặc `userId`) và `password` (BCrypt), trả về vai trò `OWNER` / `TENANT` và `redirectPath`.
  - `POST /api/auth/logout` – Đăng xuất, xóa phiên hiện tại.
  - `GET /api/auth/me` – Lấy thông tin tài khoản hiện tại từ phiên.
  - `GET /api/auth/accounts` – Danh sách tài khoản mẫu.
- **Khám phá công khai:**
  - `GET /api/public/apartments` – Lọc theo nhiều khu vực (`districts`), giá tối thiểu/tối đa (`minPrice`, `maxPrice`), số phòng ngủ (`beds`), diện tích (`minArea` / `area`), từ khóa không dấu (`query`), sắp xếp (`sort`), phân trang (`page`, `size`) và trả về thống kê số căn theo khu vực (`districtCounts`) + trần ngân sách (`budgetCeiling`).
  - `GET /api/public/apartments/{id}` – Chi tiết căn hộ công khai (ẩn tin không công khai, căn đang bảo trì hoặc đang có hợp đồng hiệu lực).
- **Quản lý dành cho Chủ nhà (`OWNER` – kiểm tra quyền sở hữu trên từng bản ghi):**
  - `GET /api/owner/buildings`, `GET /api/owner/buildings/{id}`, `POST /api/owner/buildings`, `PUT /api/owner/buildings/{id}`
  - `GET /api/owner/apartments`, `GET /api/owner/apartments/{id}`, `POST /api/owner/apartments`, `PUT /api/owner/apartments/{id}`
  - `PATCH /api/owner/apartments/{id}/listing` – Cập nhật tiêu đề, mô tả, ảnh minh họa / ảnh tải lên (<= 300 KB).
  - `PATCH /api/owner/apartments/{id}/publish` – Mở hoặc ẩn tin cho thuê.

### Mốc 3: Hợp đồng và Ký kết
- `GET /api/contracts` – Danh sách hợp đồng của chủ nhà hoặc khách thuê hiện tại.
- `GET /api/contracts/{id}` – Chi tiết hợp đồng (chỉ chủ nhà hoặc khách thuê của hợp đồng đó mới được xem).
- `GET /api/contracts/{id}/history` – Lịch sử thay đổi trạng thái hợp đồng và thanh toán (người thực hiện, thời điểm ghi nhận).
- `POST /api/contracts` – Tạo hợp đồng chờ ký (lưu bản chụp giá thuê, tiền cọc, đơn giá điện nước, địa điểm ký, phiên bản điều khoản `v1.0` và hạn chót nhận phòng 3 ngày).
- `POST /api/contracts/{id}/schedule` – Khách thuê đặt/đổi ngày giờ ký theo giờ Việt Nam.
- `POST /api/contracts/{id}/confirm-sign-and-deposit` – Người môi giới / Chủ nhà (`OWNER`) xác nhận khách đã ký tại địa điểm hẹn và ghi nhận đã thu tiền đặt cọc (khóa theo căn hộ chống ký trùng kỳ thuê, ẩn tin sau khi ký).
- `POST /api/contracts/{id}/cancel-no-show` – Hủy hợp đồng và giữ tiền đặt cọc nếu quá 3 ngày kể từ ngày dọn vào trên hợp đồng mà khách chưa nhận phòng.

### Mốc 4: Bàn giao và Hóa đơn
- `POST /api/contracts/{id}/handover` – Chủ nhà ghi nhận ngày nhận phòng và trả phòng thực tế (nhận phòng sớm tạo đúng 1 khoản phạt bằng 1 tháng tiền thuê; trả phòng sớm giữ nguyên tiền thuê).
- `GET /api/contracts/{id}/rent-schedule` & `GET /api/contracts/rent-preview` – Xem bảng tính tiền thuê kỳ đầu, các kỳ giữa và kỳ cuối theo quy tắc tháng 30/31 ngày (tháng 2 = 30 ngày).
- `POST /api/contracts/{id}/invoices` – Nhập chỉ số điện nước và lập hóa đơn theo kỳ (chỉ số mới >= chỉ số cũ, tối đa 1 hóa đơn thuê cho mỗi kỳ).
- `GET /api/invoices` & `GET /api/invoices/{id}` – Danh sách và chi tiết hóa đơn cho chủ nhà / khách thuê.
- `POST /api/invoices/{id}/confirm-paid` – Chủ nhà xác nhận đã thu tiền hóa đơn thuộc quyền quản lý.

### Mốc 5 & Mốc 6: Gia hạn, Tổng quan và Căn hộ đã lưu
- `GET /api/contracts/renewal-notices` – Danh sách hợp đồng cần nhắc gia hạn (mốc 30 ngày cho khách thuê) hoặc chờ lập hợp đồng mới (cho chủ nhà).
- `POST /api/contracts/{id}/renewal` – Khách thuê gửi phản hồi `requested` (Muốn gia hạn) hoặc `declined` (Không gia hạn) kèm thời điểm phản hồi.
- `GET /api/owner/dashboard` – Tổng hợp số căn trống, tỷ lệ lấp đầy, tiền đã thu trong tháng, hóa đơn chưa thu và hợp đồng sắp hết hạn trong 30 ngày cho trang Tổng quan của chủ nhà.
- `GET /api/favorites` & `POST /api/favorites/{apartmentId}/toggle` – Xem và lưu/bỏ lưu căn hộ yêu thích theo tài khoản đăng nhập.

### Định dạng phản hồi lỗi chuẩn (`ApiErrorResponse`)
```json
{
  "code": "FORBIDDEN",
  "message": "Bạn không có quyền truy cập hoặc chỉnh sửa căn hộ của chủ nhà khác.",
  "path": "/api/owner/apartments/a2",
  "timestamp": "2026-11-14T22:20:00+07:00",
  "details": {}
}
```
