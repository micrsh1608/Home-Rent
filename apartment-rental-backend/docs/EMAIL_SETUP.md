# Tài khoản cá nhân và xác nhận email OTP

## Chức năng đã có

- `/dang-ky` cho phép đăng ký **khách thuê** bằng họ tên, email và mật khẩu. Hệ thống gửi OTP rồi mới tạo tài khoản khi mã hợp lệ. Người đăng ký được đăng nhập sau khi xác nhận thành công.
- `/dang-nhap` giữ hai tài khoản demo cho mỗi vai trò. Danh sách công khai chỉ chứa tài khoản mẫu, không chứa email khách hàng đăng ký mới.
- `/tenant/ho-so` và `/owner/ho-so` hiển thị hồ sơ. Tài khoản cá nhân có thể cập nhật họ tên, số điện thoại, ngày sinh và địa chỉ. Email đã xác thực và vai trò được giữ cố định.
- Đổi mật khẩu yêu cầu mật khẩu hiện tại, mật khẩu mới và OTP gửi đến email đã xác thực. Mật khẩu chỉ thay đổi sau khi xác nhận mã. Tất cả phiên đăng nhập cũ hết hiệu lực.
- `/quen-mat-khau` hỗ trợ đặt lại mật khẩu bằng OTP. Phản hồi gửi mã không tiết lộ email có tồn tại hay không.
- Tài khoản demo dùng chung giữ mật khẩu `AnCu@123`. Hồ sơ và mật khẩu demo được bảo vệ để không ảnh hưởng những người đang trải nghiệm.

## Cấu hình SMTP

Khi chưa cấu hình tài khoản gửi email, API trả `503 EMAIL_NOT_CONFIGURED`. Hệ thống không tạo tài khoản chưa xác thực, không trả OTP trong API và không ghi OTP ra log.

### Thiết lập nhanh bằng Gmail

1. Đăng nhập Google bằng Gmail sẽ dùng làm hộp thư gửi và bật [Xác minh 2 bước](https://myaccount.google.com/signinoptions/two-step-verification).
2. Mở [Mật khẩu ứng dụng](https://myaccount.google.com/apppasswords), tạo một mật khẩu cho ứng dụng tên `An Cu OTP`. Google cung cấp mật khẩu ứng dụng gồm 16 ký tự. Đây là thông tin được nhập ở bước sau.
3. Mở Terminal/PowerShell tại `D:\final_analysis` và chạy `npm.cmd run setup:email`. Nhập Gmail gửi thư và mật khẩu ứng dụng khi được hỏi. Nội dung mật khẩu sẽ được ẩn khi nhập.
4. Khởi động lại backend rồi thử gửi mã xác nhận ở trang đăng ký. Kiểm tra hộp thư đến và thư rác của email đăng ký.

Lệnh thiết lập lưu `apartment-rental-backend/smtp.local.properties`, đã được loại khỏi Git. Spring Boot tự nạp tệp khi chạy từ thư mục gốc hoặc thư mục backend. Không gửi tệp hoặc mật khẩu ứng dụng qua chat. Hộp thư gửi phục vụ toàn website; mỗi khách hàng chỉ cần nhập email cá nhân để nhận OTP.

Nếu không thấy mục Mật khẩu ứng dụng, kiểm tra các điều kiện tài khoản trong [hướng dẫn chính thức của Google](https://support.google.com/accounts/answer/185833?hl=vi). Một số tài khoản do tổ chức quản lý hoặc bật chế độ bảo vệ đặc biệt có thể không dùng được cách này.

Gmail sử dụng `smtp.gmail.com:587` với STARTTLS. Tham khảo [tài liệu SMTP của Google](https://developers.google.com/workspace/gmail/imap/imap-smtp).

### Dịch vụ SMTP khác hoặc biến môi trường

Backend tự đọc `apartment-rental-backend/.env` khi chạy từ thư mục gốc hoặc thư mục backend. Nếu cấu hình thủ công, sao chép `.env.example` thành **`.env`** rồi sửa tệp `.env`. Tệp `.env.example` luôn chỉ giữ giá trị mẫu.

```dotenv
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=your-email@gmail.com
SMTP_PASSWORD=your-16-character-app-password
SMTP_FROM=your-email@gmail.com
SMTP_AUTH=true
SMTP_STARTTLS=true
SMTP_SSL=false
```

Với Gmail, `SMTP_FROM` dùng Gmail gửi thư, `SMTP_PASSWORD` dùng mật khẩu ứng dụng Google. Backend tự loại dấu cách chia nhóm trong mật khẩu ứng dụng. Tệp `.env` được loại khỏi Git và cần khởi động lại backend sau khi sửa.

`npm.cmd run dev` nạp `.env` và đợi `/api/health` báo `UP` rồi mới khởi động Vite. Nếu Java dừng hoặc không sẵn sàng trong 120 giây, lệnh báo lỗi thay vì mở frontend đang mất kết nối. Khi dừng bằng `Ctrl+C`, các tiến trình thuộc lần chạy này được đóng cùng nhau.

Sau khi có thông tin từ nhà cung cấp SMTP, mở PowerShell tại thư mục `D:\final_analysis` và thiết lập các biến môi trường trong **cùng cửa sổ sẽ chạy backend**.

```powershell
$env:SMTP_HOST = 'smtp.your-provider.com'
$env:SMTP_PORT = '587'
$env:SMTP_USERNAME = 'your-smtp-username'
$smtpSecret = Read-Host 'Nhập mật khẩu SMTP hoặc mật khẩu ứng dụng' -AsSecureString
$env:SMTP_PASSWORD = [System.Net.NetworkCredential]::new('', $smtpSecret).Password
$env:SMTP_FROM = 'no-reply@your-verified-domain.com'
$env:SMTP_AUTH = 'true'
$env:SMTP_STARTTLS = 'true'
$env:SMTP_SSL = 'false'

# Cùng cửa sổ này, khởi động lại ứng dụng với cấu hình SQL Server của bạn.
npm.cmd run dev:sqlserver
```

Nếu backend đã chạy, dừng tiến trình đó trước khi khởi động lại để nạp biến môi trường và mã nguồn mới. Không đặt mật khẩu SMTP vào mã nguồn, `.env.example` hoặc Git.

Tệp `smtp.local.properties` được ưu tiên hơn các biến `SMTP_*` được tham chiếu trong `application.yml`. Khi chuyển từ Gmail sang dịch vụ khác, cập nhật hoặc bỏ tệp cấu hình cục bộ để tránh tiếp tục dùng thông tin cũ. Biến môi trường chuẩn của Spring như `SPRING_MAIL_HOST` và `SPRING_MAIL_PASSWORD` vẫn được Spring ưu tiên hơn tệp cấu hình.

- Cổng **587** thường dùng STARTTLS với `SMTP_STARTTLS=true`, `SMTP_SSL=false`.
- Nếu nhà cung cấp yêu cầu SSL trực tiếp trên **465**, đặt `SMTP_PORT=465`, `SMTP_STARTTLS=false`, `SMTP_SSL=true`.
- `SMTP_FROM` phải là địa chỉ được nhà cung cấp cho phép gửi. Hoàn tất xác minh tên miền và cấu hình SPF/DKIM theo hướng dẫn của nhà cung cấp nếu họ yêu cầu.
- Lỗi gửi thư trả `503 EMAIL_DELIVERY_FAILED`. Tài khoản sẽ chưa được tạo. Kiểm tra tài khoản SMTP, quyền gửi và cấu hình TLS/cổng.

Với dịch vụ thật, dùng SQL Server đã cấu hình thành công để lưu tài khoản và hồ sơ lâu dài. Chế độ `local` sử dụng H2 trong bộ nhớ để phát triển; dữ liệu trong chế độ này mất khi dừng backend. Cấu hình hiện tại có cơ chế chuyển sang H2 khi không kết nối được SQL Server, vì vậy cần kiểm tra log kết nối trước khi nhận đăng ký thực tế.

## Quy tắc OTP

| Điều kiện | Giá trị |
|---|---|
| Độ dài | 6 chữ số sinh ngẫu nhiên bằng `SecureRandom` |
| Thời hạn | 10 phút, kiểm tra ở backend |
| Sử dụng | Một lần, ràng buộc theo email, mục đích và tài khoản |
| Gửi lại | Chờ ít nhất 60 giây; mã và yêu cầu cũ hết hiệu lực khi gửi lại thành công |
| Giới hạn email | Tối đa 5 mã mỗi giờ |
| Giới hạn địa chỉ máy khách | Tối đa 20 yêu cầu gửi mã mỗi giờ, lưu trong cơ sở dữ liệu |
| Nhập sai | Tối đa 5 lần cho mỗi mã |
| Lưu trữ | BCrypt cho mật khẩu và OTP; xóa nội dung băm của mã và mật khẩu chờ sau khi dùng |
| Mật khẩu | Ít nhất 8 ký tự, tối đa 72 byte UTF-8 do giới hạn BCrypt |

Phía proxy triển khai cần truyền địa chỉ máy khách theo cấu hình hạ tầng tin cậy; ứng dụng không tự tin cậy tiêu đề `X-Forwarded-For` do người gọi tùy ý gửi.

## API mới

| Phương thức | Đường dẫn | Nội dung |
|---|---|---|
| POST | `/api/auth/register/request-otp` | `{ "name", "email", "password" }` |
| POST | `/api/auth/register/confirm` | `{ "challengeId", "code" }`, tạo tài khoản và phiên |
| GET | `/api/auth/profile` | Hồ sơ của phiên hiện tại |
| PUT | `/api/auth/profile` | `{ "name", "phone", "address", "dateOfBirth" }` |
| GET | `/api/auth/contacts` | Tài khoản demo, bản thân và các bên có hợp đồng liên quan |
| POST | `/api/auth/password/change/request-otp` | `{ "currentPassword", "newPassword" }`, cần đăng nhập |
| POST | `/api/auth/password/change/confirm` | `{ "challengeId", "code" }`, cần đúng tài khoản yêu cầu mã |
| POST | `/api/auth/password/reset/request-otp` | `{ "email" }` |
| POST | `/api/auth/password/reset/confirm` | `{ "challengeId", "code", "newPassword" }` |

Ngày sinh gửi theo `yyyy-MM-dd` hoặc `null`. Yêu cầu gửi mã thành công trả `challengeId`, `expiresAt`, `resendAfterSeconds`, `message`.

## Cơ sở dữ liệu và kiểm thử

Migration `V4__verified_accounts.sql` bổ sung cờ email xác thực, cờ demo, phiên bản xác thực và các trường hồ sơ, bảng yêu cầu OTP và bảng giới hạn gửi. Flyway áp dụng V4 trong môi trường bật Flyway. Bộ khởi tạo lược đồ của profile `sqlserver` cũng hỗ trợ bổ sung V4 vào dữ liệu cũ.

```powershell
mvn.cmd -f apartment-rental-backend/pom.xml test
npm.cmd run test:frontend
npm.cmd run build:frontend
```

`AccountIntegrationTest` dùng máy chủ SMTP thử nghiệm ở loopback để thực sự truyền và nhận thư qua JavaMail, kiểm tra OTP, hồ sơ, đổi/đặt lại mật khẩu, hết phiên, giới hạn gửi, hết hạn và lỗi SMTP. Không gửi thư ra email bên ngoài. Cần kiểm tra hộp thư thật sau khi bạn cấu hình nhà cung cấp SMTP.

Tham khảo cấu hình [Spring Boot Mail](https://docs.spring.io/spring-boot/docs/3.2.8/reference/html/io.html#io.email) và các nguyên tắc mã dùng một lần trong [OWASP Forgot Password Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html).
