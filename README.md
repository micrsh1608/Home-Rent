# An Cư

Frontend React và Vite cho hệ thống cho thuê căn hộ. Dữ liệu demo được lưu trong trình duyệt. Chạy bằng Node.js 22 trở lên.

```powershell
npm.cmd ci
npm.cmd run dev
```

Mở địa chỉ Local do Vite in ra. Dùng `npm.cmd run build` để tạo thư mục `dist` và `npm.cmd test` để kiểm tra quy tắc nghiệp vụ.

## Tài khoản mẫu

- **Nguyễn Minh Anh** có hợp đồng sắp hết hạn để thử phản hồi gia hạn. Có thể tạo một hợp đồng chờ ký từ bất kỳ căn hộ còn trống nào trên trang khám phá.
- **Trần Thu Hà** quản lý căn hộ River Garden của Minh Anh. Sau khi khách chọn muốn gia hạn, mục **Khách thuê** hiện nút **Lập hợp đồng mới**.
- **Lê Hoàng Nam** có hợp đồng đã hết hạn và đã chọn không gia hạn. Chủ nhà **Phạm Gia Bảo** có thể mở lại tin của căn Lumière Park từ mục **Tin đăng**.

## Quy tắc trong bản demo

- Địa điểm ký được sao lưu từ tòa nhà khi tạo hợp đồng. Đặt hoặc đổi lịch chỉ cập nhật ngày giờ theo múi giờ Việt Nam. Sau khi khách xác nhận ký mô phỏng, trạng thái đổi sang **Đã ký** và **Đã thanh toán**, đồng thời tin cho thuê được ẩn.
- Mỗi căn hộ có các hợp đồng riêng theo từng kỳ thuê. Sau khi ký, các kỳ thuê của cùng một căn hộ không được trùng nhau. Gia hạn tạo hợp đồng mới và giữ lịch sử của kỳ trước.
- Tiền thuê hàng tháng giữ nguyên khi trả phòng sớm. Khi chủ nhà ghi nhận ngày nhận phòng trước thỏa thuận, hệ thống tạo hóa đơn phạt riêng bằng một tháng tiền thuê.
- Thông báo trong ứng dụng xuất hiện từ 30 ngày trước khi hết hạn và tiếp tục hiển thị sau khi hết hạn nếu khách chưa phản hồi.
- Chủ nhà có thể dùng ảnh minh họa đi kèm hoặc tải lên một ảnh tối đa 300 KB cho mỗi tin. Tiền cọc mặc định bằng một tháng tiền thuê trong dữ liệu demo.

## Phạm vi giai đoạn này

Bản hiện tại dùng tài khoản mẫu, ghi nhận chữ ký và thanh toán mô phỏng, thông báo trong ứng dụng và dữ liệu cục bộ trong trình duyệt. Lớp dữ liệu demo nằm trong `src/demo.js`. Backend Java, xác thực và phân quyền phía máy chủ, lưu trữ SQL Server và tích hợp thanh toán là các phần của giai đoạn sau. SQL Server Management Studio 20 dùng để kết nối và quản trị SQL Server.

## Ảnh minh họa

Ảnh được tạo bằng công cụ ImageGen tích hợp và lưu trong `public/images`. Đây là hình minh họa cho dữ liệu mẫu.

- `hero.webp` dùng prompt: “Create a single photorealistic editorial architecture photograph for the hero banner of a premium Vietnamese apartment rental website. Widescreen 3:2 landscape or wider, high-end architecture magazine photography. Contemporary Ho Chi Minh City condo with floor-to-ceiling windows, very warm morning golden sunlight casting clean shadows, warm ivory linen curved sectional in the middle right, a walnut low coffee table, textured rug, indoor tree, muted taupe plaster, light oak wood, subtle high rise skyline in distance. Beautiful, plausible, understated high-end residential interior. The left third is a dark subdued shadowed wooden wall and furnishings in shadow, with no only focal subject there, designed to allow white text overlay later. Main focus on the bright right 65% of the image. No humans, no letters, no typography, no watermark, no graphics.”
- `river.webp`, `lumiere.webp`, `urban.webp` và `greenhouse.webp` dùng prompt: “Generate one high-resolution wide 1536 by 1024 image split EXACTLY into a 2 by 2 four-photo contact sheet with NO gutters and NO borders and NO overlap. Each exact quadrant is a different premium real estate editorial photograph used as a separate apartment listing crop. Top left: bright ivory and oak living room corner in Thao Dien with large balcony windows, pale sofa and round walnut table. Top right: cozy contemporary studio living room with mid-tone natural oak, clay cushions, curved cream sofa, modern art and leafy tree. Bottom left: sophisticated high-rise apartment living room, light grey sofa, walnut dining table, cyan blue sky visible through large windows. Bottom right: sunlit compact one-bedroom apartment with a forest green accent chair, cream sectional, green tree and polished light floors. Real Vietnamese urban apartments, photoreal natural light, refined and simple, editorial photography aesthetic. NO text, no people, no collage framing lines, no watermarks. Straight exact boundaries halfway horizontally and vertically. Four clearly independent scenes.”
