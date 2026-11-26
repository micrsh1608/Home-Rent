# An Cư — Phân hệ Giao diện Người dùng (`apartment-rental-frontend`)

Phân hệ giao diện người dùng **React 19 + Vite 6 + React Router 7** của hệ thống quản lý và cho thuê căn hộ **An Cư Rental**, kết nối trực tiếp với REST API của phân hệ Backend (`apartment-rental-backend`).

> **Hướng dẫn tổng thể Full-Stack:** Xem [README.md ở thư mục gốc](../README.md).

---

## 1. Khởi chạy từ thư mục gốc (Khuyên dùng)

Từ thư mục gốc `final_analysis`, khởi chạy đồng bộ cả Backend và Frontend:

```powershell
# Chạy Full-Stack với H2 Local (không cần cài SQL Server)
npm.cmd run dev

# Hoặc chạy Full-Stack kết nối Microsoft SQL Server (Fail-Fast)
npm.cmd run dev:sqlserver
```

---

## 2. Chạy riêng phân hệ Frontend (`apartment-rental-frontend`)

Yêu cầu **Node.js 20/22/24+**. Vite dev server tự động proxy toàn bộ yêu cầu `/api/**` tới `http://127.0.0.1:8080` (tùy chỉnh qua `VITE_API_TARGET`):

```powershell
cd apartment-rental-frontend
npm.cmd ci
npm.cmd run dev
```

| Lệnh | Mục đích |
|---|---|
| `npm.cmd run dev` | Mở Vite dev server tại `http://127.0.0.1:5173` |
| `npm.cmd test` | Chạy kiểm thử tự động phía Frontend (`test/demo.test.js`) |
| `npm.cmd run build` | Đóng gói bản dựng production vào thư mục `dist/` |
| `npm.cmd run preview` | Xem trước bản dựng `dist/` trên `127.0.0.1` |

---

## 3. Chế độ hoạt động & Bảo mật giao tiếp API

- **Application Mode (Mặc định):** Mọi thao tác đọc/ghi đi trực tiếp tới Backend API (`/api/**`). Khi mất kết nối mạng hoặc Backend trả lỗi `400/403/409/500`, giao diện hiển thị thông báo lỗi rõ ràng từ server và **không fallback ngầm sang `localStorage`**.
- **Prototype Mode (Tùy chọn rõ ràng):** Chỉ kích hoạt khi đặt `VITE_APP_MODE=prototype` để xem thử giao diện độc lập khi không khởi chạy Backend.
- **Bảo vệ CSRF tự động:** `backendApi` trong `src/demo.js` tự đọc cookie `XSRF-TOKEN` (hoặc đồng bộ qua `GET /api/auth/csrf`) và gắn header `X-CSRF-Token` cho mọi thao tác `POST/PUT/PATCH/DELETE`.

---

## 4. Cấu trúc mã nguồn Frontend

| Tệp | Vai trò |
|---|---|
| `src/main.jsx` | Khởi tạo ứng dụng và định tuyến (`HashRouter`) cho 3 khu vực: Công khai (`/`), Khách thuê (`/tenant`) và Chủ nhà (`/owner`) |
| `src/shared.jsx` | `DemoProvider` quản lý phiên đăng nhập, đồng bộ dữ liệu thực tế từ `/api/**`, xử lý trạng thái lỗi kết nối và thông báo người dùng |
| `src/demo.js` | Client `backendApi` (hỗ trợ CSRF, phân loại lỗi mạng/API), cờ `IS_PROTOTYPE_MODE` và bộ tính chu kỳ tiền thuê (`calculateEndDateForTerm`, `calculateLeaseBreakdown`) |
| `src/public.jsx` | Trang khám phá căn hộ, chi tiết căn hộ & gửi yêu cầu thuê (không gửi giá/cọc từ client), danh sách yêu thích, hướng dẫn thuê và trang đăng nhập |
| `src/account.jsx` | Đăng ký tài khoản bằng email OTP, chỉnh sửa hồ sơ cá nhân, đổi mật khẩu và đặt lại mật khẩu qua OTP |
| `src/SearchFilters.jsx` | Bộ lọc khu vực, loại căn hộ và thanh trượt khoảng giá thuê |
| `src/owner.jsx` | Cổng Chủ nhà: Tổng quan, Tòa nhà & Căn hộ, Tin đăng, Hợp đồng, Khách thuê & Gia hạn, Lập hóa đơn tuần tự (đọc `/next-invoice-preview` từ server, chỉ số cũ chỉ đọc) |
| `src/contracts.jsx` | Chi tiết hợp đồng, bảng phân bổ tiền thuê, lịch sử trạng thái, đặt lịch ký, modal xác nhận ký + nhập số tiền cọc thực nhận, modal Check-in (chỉ số bàn giao) / Check-out riêng biệt, hủy quá hạn 3 ngày (theo `ServerClock`) và thu tiền hóa đơn |
| `test/demo.test.js` | Bộ kiểm thử nghiệp vụ tính tiền thuê, kiểm chứng Application Mode không fallback và truyền CSRF token |
