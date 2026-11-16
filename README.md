# An Cư — Hệ thống Quản lý & Cho thuê Căn hộ (Frontend React + Backend Java Spring Boot)

Dự án gồm **Frontend React + Vite** tại thư mục gốc và **Backend Java 21 + Spring Boot + Flyway + Microsoft SQL Server** tại thư mục `apartment-rental-backend/`.

## 1. Chạy Backend (`apartment-rental-backend`)

### Chạy với Microsoft SQL Server (SSMS 20)
1. Mở SSMS 20, tạo database trống `AnCuRental`.
2. Đảm bảo đã bật **TCP/IP (port 1433)** trong *SQL Server Configuration Manager* và chế độ đăng nhập **SQL Server and Windows Authentication mode** (tài khoản `sa` đã bật và đúng mật khẩu).
```powershell
$env:DB_URL="jdbc:sqlserver://localhost:1433;databaseName=AnCuRental;encrypt=true;trustServerCertificate=true"
$env:DB_USERNAME="sa"
$env:DB_PASSWORD="StrongPassword123!"
mvn -f apartment-rental-backend/pom.xml spring-boot:run
```

### Chạy nhanh Local (H2 ở chế độ tương thích MSSQLServer — không cần SQL Server)
```powershell
mvn -f apartment-rental-backend/pom.xml spring-boot:run "-Dspring-boot.run.profiles=local"
```

### Chạy kiểm thử tích hợp Backend (6 kịch bản nghiệm thu)
```powershell
mvn -f apartment-rental-backend/pom.xml test
```

## 2. Chạy Frontend React (`apartment-rental-frontend`)

Chạy bằng Node.js 22 trở lên. Vite đã được cấu hình proxy `/api` tới `http://localhost:8080`:

```powershell
npm.cmd ci
npm.cmd run dev
```

- Kiểm tra quy tắc nghiệp vụ phía Frontend: `npm.cmd test`
- Đóng gói bản dựng Production: `npm.cmd run build`

## 3. Tài khoản mẫu (Mật khẩu mặc định trên Backend: `AnCu@123`)

- **Nguyễn Minh Anh** (`t1` / `minhanh@ancu.vn` — `TENANT`): có hợp đồng `HD-1001` sắp hết hạn để thử phản hồi gia hạn và xem hóa đơn.
- **Trần Thu Hà** (`o1` / `thuha@ancu.vn` — `OWNER`): quản lý tòa nhà The River Thảo Điền & Urban Nest.
- **Lê Hoàng Nam** (`t2` / `hoangnam@ancu.vn` — `TENANT`): có hợp đồng `HD-1002` đã hết hạn và đã chọn không gia hạn.
- **Phạm Gia Bảo** (`o2` / `giabao@ancu.vn` — `OWNER`): quản lý tòa nhà Lumière Residence & The Greenhouse.

## 4. Quy tắc nghiệp vụ trọng tâm & Hai quyết định đã chốt

1. **Quyết định 1 — Xác nhận ký, thu tiền ban đầu (tiền đặt cọc) & hủy quá 3 ngày không nhận phòng**:
   - Địa điểm ký được sao lưu (snapshot) từ tòa nhà khi tạo hợp đồng. Đặt hoặc đổi lịch chỉ cập nhật ngày giờ hẹn ký theo múi giờ Việt Nam.
   - **Người môi giới / Chủ nhà (`OWNER`)** xác nhận khách hàng đã ký tại địa điểm hẹn (`signedAtLocation = true`) **và** ghi nhận đã thu khoản tiền đặt cọc trên hợp đồng (`depositReceived = true`). Khi hệ thống nhận đủ hai xác nhận này, hợp đồng chuyển sang **Đã ký (`SIGNED`)** và **Đã thu cọc (`PAID`)**, đồng thời tin đăng căn hộ tự động được ẩn.
   - Nếu đã quá **3 ngày** kể từ ngày dọn vào quy định trên hợp đồng (`today > startDate + 3 ngày`) mà khách hàng vẫn chưa nhận phòng thực tế (`actualMoveIn` trống), hợp đồng bị **hủy (`CANCELLED`)** và khách hàng **mất tiền đặt cọc (`depositForfeited = true`)**.
2. **Quyết định 2 — Tính tiền thuê kỳ đầu và kỳ cuối bắt đầu từ ngày dọn vào**:
   - Chu kỳ tính tiền thuê bắt đầu từ ngày dọn vào trên hợp đồng (`startDate`) theo thời hạn **1 tháng (`MONTH`)**, **1 năm (`YEAR`)** hoặc **thời gian khách hàng muốn thuê (`CUSTOM`)**.
   - **1 tháng** được tính theo số ngày của tháng bắt đầu chu kỳ (**30 hoặc 31 ngày**); **riêng tháng 2 luôn tính theo 30 ngày** kể từ ngày bắt đầu chu kỳ trên hợp đồng.
   - Kỳ cuối nếu lẻ ngày sẽ tính theo tỷ lệ `round(rent * daysInPeriod / monthBasisDays)`.
3. **Bàn giao sớm / trả phòng sớm & Gia hạn**:
   - Nhận phòng trước ngày bắt đầu hợp đồng tạo đúng một hóa đơn phạt bằng 1 tháng tiền thuê (`EARLY_MOVE_IN_PENALTY`). Trả phòng sớm giữ nguyên tiền thuê theo thỏa thuận.
   - Gia hạn tạo hợp đồng kỳ mới có mã riêng, liên kết với kỳ trước (`renewalOf`).

## Ảnh minh họa

Ảnh được tạo bằng công cụ ImageGen tích hợp và lưu trong `public/images`. Đây là hình minh họa cho dữ liệu mẫu.

- `hero.webp` dùng prompt: “Create a single photorealistic editorial architecture photograph for the hero banner of a premium Vietnamese apartment rental website. Widescreen 3:2 landscape or wider, high-end architecture magazine photography. Contemporary Ho Chi Minh City condo with floor-to-ceiling windows, very warm morning golden sunlight casting clean shadows, warm ivory linen curved sectional in the middle right, a walnut low coffee table, textured rug, indoor tree, muted taupe plaster, light oak wood, subtle high rise skyline in distance. Beautiful, plausible, understated high-end residential interior. The left third is a dark subdued shadowed wooden wall and furnishings in shadow, with no only focal subject there, designed to allow white text overlay later. Main focus on the bright right 65% of the image. No humans, no letters, no typography, no watermark, no graphics.”
- `river.webp`, `lumiere.webp`, `urban.webp` và `greenhouse.webp` dùng prompt: “Generate one high-resolution wide 1536 by 1024 image split EXACTLY into a 2 by 2 four-photo contact sheet with NO gutters and NO borders and NO overlap. Each exact quadrant is a different premium real estate editorial photograph used as a separate apartment listing crop. Top left: bright ivory and oak living room corner in Thao Dien with large balcony windows, pale sofa and round walnut table. Top right: cozy contemporary studio living room with mid-tone natural oak, clay cushions, curved cream sofa, modern art and leafy tree. Bottom left: sophisticated high-rise apartment living room, light grey sofa, walnut dining table, cyan blue sky visible through large windows. Bottom right: sunlit compact one-bedroom apartment with a forest green accent chair, cream sectional, green tree and polished light floors. Real Vietnamese urban apartments, photoreal natural light, refined and simple, editorial photography aesthetic. NO text, no people, no collage framing lines, no watermarks. Straight exact boundaries halfway horizontally and vertically. Four clearly independent scenes.”
