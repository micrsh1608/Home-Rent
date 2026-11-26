package vn.ancu.rental.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import vn.ancu.rental.common.AppException;

@Service
public class OtpMailService {
    private final ObjectProvider<JavaMailSender> sender;
    private final String host;
    private final String from;

    public OtpMailService(ObjectProvider<JavaMailSender> sender,
                          @Value("${spring.mail.host:}") String host,
                          @Value("${ancu.mail.from:}") String from) {
        this.sender = sender;
        this.host = GmailMailCustomizer.cleanValue(host);
        this.from = GmailMailCustomizer.cleanValue(from);
    }

    public void requireConfigured() {
        if (host.isBlank() || from.isBlank() || sender.getIfAvailable() == null) {
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_NOT_CONFIGURED",
                    "Chức năng email chưa được cấu hình. Vui lòng liên hệ quản trị viên để bật gửi OTP.");
        }
    }

    public void send(String email, String purpose, String code) {
        requireConfigured();
        String action = switch (purpose) {
            case "REGISTER" -> "xác nhận đăng ký tài khoản";
            case "CHANGE_PASSWORD" -> "xác nhận đổi mật khẩu";
            default -> "đặt lại mật khẩu";
        };
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("An Cư – Mã OTP " + action);
        message.setText("Mã OTP để " + action + " của bạn là: " + code
                + "\n\nMã có hiệu lực trong 10 phút và chỉ sử dụng một lần."
                + "\nKhông chia sẻ mã này với bất kỳ ai."
                + "\nNếu bạn không yêu cầu thao tác này, hãy bỏ qua email.\n\nAn Cư");
        try {
            sender.getObject().send(message);
        } catch (MailException ex) {
            // Do not expose credentials, recipient details or the OTP through logs/errors.
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DELIVERY_FAILED",
                    "Chưa gửi được email xác nhận. Vui lòng thử lại sau hoặc liên hệ quản trị viên.");
        }
    }
}
