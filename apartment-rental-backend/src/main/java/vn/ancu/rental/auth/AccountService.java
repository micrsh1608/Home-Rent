package vn.ancu.rental.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.ancu.rental.common.AppException;
import vn.ancu.rental.common.ServerClock;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class AccountService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final OtpMailService mail;
    private final AuthService auth;
    private final ServerClock serverClock;

    public AccountService(JdbcTemplate jdbc, PasswordEncoder passwords, OtpMailService mail, AuthService auth, ServerClock serverClock) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.mail = mail;
        this.auth = auth;
        this.serverClock = serverClock;
    }

    public record RegistrationRequest(
            @NotBlank @Size(max = 150) String name,
            @NotBlank @Email @Size(max = 150) String email,
            @NotBlank @Size(min = 8, max = 72) String password) { }
    public record CodeRequest(@NotBlank String challengeId, @NotBlank @Size(max = 12) String code) { }
    public record ChangePasswordRequest(@NotBlank String currentPassword,
                                        @NotBlank @Size(min = 8, max = 72) String newPassword) { }
    public record ResetRequest(@NotBlank @Email @Size(max = 150) String email) { }
    public record ResetConfirmation(@NotBlank String challengeId, @NotBlank @Size(max = 12) String code,
                                     @NotBlank @Size(min = 8, max = 72) String newPassword) { }
    public record ProfileUpdate(@NotBlank @Size(max = 150) String name,
                                @Pattern(regexp = "[+0-9(). \\-]{0,30}", message = "Số điện thoại không hợp lệ.") String phone,
                                @Size(max = 300) String address, @Past LocalDate dateOfBirth) { }
    public record Profile(String id, String email, String name, String role, String phone,
                          String address, LocalDate dateOfBirth, boolean emailVerified, boolean demo) { }
    public record OtpDelivery(String challengeId, Instant expiresAt, int resendAfterSeconds, String message) { }

    @Transactional
    public OtpDelivery requestRegistration(RegistrationRequest request) {
        mail.requireConfigured();
        String email = normalizeEmail(request.email());
        validatePassword(request.password());
        if (userByEmail(email) != null) throw AppException.conflict("EMAIL_EXISTS", "Email này đã được sử dụng. Vui lòng đăng nhập hoặc đặt lại mật khẩu.");
        return issue(email, "REGISTER", null, null, request.name().trim(), passwords.encode(request.password()));
    }

    @Transactional(noRollbackFor = AppException.class)
    public AuthUser confirmRegistration(CodeRequest request) {
        Challenge challenge = consume(request.challengeId(), request.code(), "REGISTER", null);
        if (userByEmail(challenge.email()) != null) throw AppException.conflict("EMAIL_EXISTS", "Email này đã được đăng ký.");
        String id = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO users (id, email, password_hash, full_name, role, active, email_verified, is_demo)
                    VALUES (?, ?, ?, ?, 'TENANT', 1, 1, 0)
                    """, id, challenge.email(), challenge.passwordHash(), challenge.name());
        } catch (DuplicateKeyException ex) {
            throw AppException.conflict("EMAIL_EXISTS", "Email này đã được đăng ký.");
        }
        return auth.currentUser(id);
    }

    public Profile profile(AuthUser actor) {
        return jdbc.query("SELECT * FROM users WHERE id = ? AND active = 1", (rs, row) -> new Profile(
                rs.getString("id"), rs.getString("email"), rs.getString("full_name"), rs.getString("role"),
                rs.getString("phone"), rs.getString("address"), rs.getObject("date_of_birth", LocalDate.class),
                rs.getBoolean("email_verified"), rs.getBoolean("is_demo")), actor.id())
                .stream().findFirst().orElseThrow(() -> AppException.unauthorized("Tài khoản không còn hoạt động."));
    }

    @Transactional
    public Profile updateProfile(AuthUser actor, ProfileUpdate request) {
        if (profile(actor).demo()) throw AppException.forbidden("Tài khoản demo dùng chung. Hãy tạo tài khoản riêng để chỉnh sửa hồ sơ.");
        if (request.name().isBlank() || (request.dateOfBirth() != null && !request.dateOfBirth().isBefore(serverClock.today()))) {
            throw AppException.badRequest("INVALID_PROFILE", "Vui lòng kiểm tra họ tên và ngày sinh.");
        }
        jdbc.update("UPDATE users SET full_name = ?, phone = ?, address = ?, date_of_birth = ? WHERE id = ? AND active = 1",
                request.name().trim(), trim(request.phone()), trim(request.address()),
                request.dateOfBirth() == null ? null : Date.valueOf(request.dateOfBirth()), actor.id());
        return profile(actor);
    }

    @Transactional
    public OtpDelivery requestPasswordChange(AuthUser actor, ChangePasswordRequest request) {
        User user = requireRealUser(actor.id());
        if (!passwords.matches(request.currentPassword(), user.hash())) throw AppException.badRequest("WRONG_PASSWORD", "Mật khẩu hiện tại không đúng.");
        validatePassword(request.newPassword());
        mail.requireConfigured();
        return issue(user.email(), "CHANGE_PASSWORD", user.id(), user.version(), null, passwords.encode(request.newPassword()));
    }

    @Transactional(noRollbackFor = AppException.class)
    public void confirmPasswordChange(AuthUser actor, CodeRequest request) {
        Challenge challenge = consume(request.challengeId(), request.code(), "CHANGE_PASSWORD", actor.id());
        replacePassword(challenge, challenge.passwordHash());
    }

    @Transactional
    public OtpDelivery requestPasswordReset(ResetRequest request) {
        mail.requireConfigured();
        String email = normalizeEmail(request.email());
        User user = userByEmail(email);
        // Same response for unknown addresses and demo accounts. Never send to demo mailboxes.
        if (user == null || user.demo() || !user.verified() || !user.active()) return delivery(UUID.randomUUID().toString(), Instant.now().plusSeconds(600));
        return issue(email, "RESET_PASSWORD", user.id(), user.version(), null, null);
    }

    @Transactional(noRollbackFor = AppException.class)
    public void confirmPasswordReset(ResetConfirmation request) {
        validatePassword(request.newPassword());
        Challenge challenge = consume(request.challengeId(), request.code(), "RESET_PASSWORD", null);
        replacePassword(challenge, passwords.encode(request.newPassword()));
    }

    private void replacePassword(Challenge challenge, String hash) {
        int updated = jdbc.update("""
                UPDATE users SET password_hash = ?, auth_version = auth_version + 1
                WHERE id = ? AND email = ? AND auth_version = ? AND active = 1 AND email_verified = 1 AND is_demo = 0
                """, hash, challenge.userId(), challenge.email(), challenge.version());
        if (updated != 1) throw AppException.conflict("ACCOUNT_CHANGED", "Tài khoản đã thay đổi. Vui lòng yêu cầu mã mới.");
    }

    private OtpDelivery issue(String email, String purpose, String userId, Integer version, String name, String hash) {
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        String code = String.format(Locale.ROOT, "%06d", RANDOM.nextInt(1_000_000));
        String codeHash = passwords.encode(code);
        // A write lock on the email row serializes resend/verify across backend instances.
        int exists = jdbc.update("UPDATE email_otp_challenges SET sent_count = sent_count WHERE email = ?", email);
        if (exists == 0) {
            try {
                jdbc.update("""
                        INSERT INTO email_otp_challenges
                        (email, id, purpose, user_id, auth_version, code_hash, pending_name, pending_password_hash,
                         expires_at, next_send_at, window_start, sent_count, attempts, consumed)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 0, 0)
                        """, email, id, purpose, userId, version, codeHash, name, hash,
                        Timestamp.from(now.plusSeconds(600)), Timestamp.from(now.plusSeconds(60)), Timestamp.from(now));
            } catch (DuplicateKeyException ex) { throw rateLimit(); }
        } else {
            SendLimit previous = jdbc.query("SELECT next_send_at, window_start, sent_count FROM email_otp_challenges WHERE email = ?",
                    (rs, row) -> new SendLimit(rs.getTimestamp(1).toInstant(), rs.getTimestamp(2).toInstant(), rs.getInt(3)), email).getFirst();
            boolean reset = !now.isBefore(previous.windowStart().plusSeconds(3600));
            if (now.isBefore(previous.nextSend()) || (!reset && previous.count() >= 5)) throw rateLimit();
            jdbc.update("""
                    UPDATE email_otp_challenges SET id = ?, purpose = ?, user_id = ?, auth_version = ?, code_hash = ?,
                        pending_name = ?, pending_password_hash = ?, expires_at = ?, next_send_at = ?, window_start = ?,
                        sent_count = ?, attempts = 0, consumed = 0 WHERE email = ?
                    """, id, purpose, userId, version, codeHash, name, hash, Timestamp.from(now.plusSeconds(600)),
                    Timestamp.from(now.plusSeconds(60)), Timestamp.from(reset ? now : previous.windowStart()), reset ? 1 : previous.count() + 1, email);
        }
        mail.send(email, purpose, code);
        return delivery(id, now.plusSeconds(600));
    }

    private Challenge consume(String id, String code, String purpose, String expectedUserId) {
        jdbc.update("UPDATE email_otp_challenges SET attempts = attempts WHERE id = ? AND purpose = ?", id, purpose);
        Challenge challenge = jdbc.query("SELECT * FROM email_otp_challenges WHERE id = ? AND purpose = ?", (rs, row) -> new Challenge(
                rs.getString("email"), rs.getString("user_id"), (Integer) rs.getObject("auth_version"),
                rs.getString("code_hash"), rs.getString("pending_name"), rs.getString("pending_password_hash"),
                rs.getTimestamp("expires_at").toInstant(), rs.getInt("attempts"), rs.getBoolean("consumed")), id, purpose)
                .stream().findFirst().orElseThrow(this::invalidCode);
        if (expectedUserId != null && !expectedUserId.equals(challenge.userId())) throw invalidCode();
        if (challenge.consumed() || !Instant.now().isBefore(challenge.expiresAt())) throw AppException.badRequest("OTP_EXPIRED", "Mã đã hết hạn hoặc đã sử dụng. Vui lòng yêu cầu mã mới.");
        if (challenge.attempts() >= 5) throw AppException.badRequest("OTP_LOCKED", "Bạn đã nhập sai mã 5 lần. Vui lòng yêu cầu mã mới.");
        if (!code.matches("[0-9]{6}") || !passwords.matches(code, challenge.codeHash())) {
            jdbc.update("UPDATE email_otp_challenges SET attempts = attempts + 1 WHERE id = ?", id);
            throw invalidCode();
        }
        jdbc.update("UPDATE email_otp_challenges SET consumed = 1, pending_password_hash = NULL, code_hash = '' WHERE id = ?", id);
        return challenge;
    }

    private User requireRealUser(String id) {
        User user = jdbc.query("SELECT * FROM users WHERE id = ?", (rs, row) -> mapUser(rs), id)
                .stream().findFirst().orElseThrow(() -> AppException.unauthorized("Tài khoản không tồn tại."));
        if (user.demo()) throw AppException.forbidden("Tài khoản demo dùng mật khẩu chung. Hãy tạo tài khoản riêng để quản lý mật khẩu.");
        if (!user.active() || !user.verified()) throw AppException.forbidden("Email tài khoản chưa được xác thực.");
        return user;
    }

    private User userByEmail(String email) {
        return jdbc.query("SELECT * FROM users WHERE LOWER(email) = ?", (rs, row) -> mapUser(rs), email)
                .stream().findFirst().orElse(null);
    }

    private User mapUser(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new User(rs.getString("id"), rs.getString("email"), rs.getString("password_hash"),
                rs.getInt("auth_version"), rs.getBoolean("email_verified"), rs.getBoolean("is_demo"), rs.getBoolean("active"));
    }

    private String normalizeEmail(String value) {
        String email = value.trim().toLowerCase(Locale.ROOT);
        int at = email.lastIndexOf('@');
        String domain = at < 1 ? "" : email.substring(at + 1);
        if (!domain.contains(".") || Set.of("example.com", "example.net", "example.org").contains(domain)
                || domain.matches(".*\\.(test|invalid|localhost|example)$")) {
            throw AppException.badRequest("INVALID_EMAIL", "Vui lòng sử dụng địa chỉ email thật có thể nhận thư.");
        }
        return email;
    }

    private void validatePassword(String value) {
        if (value == null || value.length() < 8 || value.getBytes(StandardCharsets.UTF_8).length > 72 || value.isBlank()) {
            throw AppException.badRequest("INVALID_PASSWORD", "Mật khẩu cần ít nhất 8 ký tự và tối đa 72 byte UTF-8.");
        }
    }
    private String trim(String value) { return value == null ? null : value.trim(); }
    private OtpDelivery delivery(String id, Instant expires) {
        return new OtpDelivery(id, expires, 60, "Nếu email đủ điều kiện, mã xác nhận đã được gửi. Vui lòng kiểm tra hộp thư và thư rác.");
    }
    private AppException invalidCode() { return AppException.badRequest("INVALID_OTP", "Mã xác nhận không đúng. Vui lòng kiểm tra lại email."); }
    private AppException rateLimit() {
        return new AppException(HttpStatus.TOO_MANY_REQUESTS, "OTP_RATE_LIMIT", "Vui lòng chờ 60 giây giữa hai lần gửi mã. Mỗi email nhận tối đa 5 mã trong một giờ.");
    }
    private record User(String id, String email, String hash, int version, boolean verified, boolean demo, boolean active) { }
    private record SendLimit(Instant nextSend, Instant windowStart, int count) { }
    private record Challenge(String email, String userId, Integer version, String codeHash, String name,
                             String passwordHash, Instant expiresAt, int attempts, boolean consumed) { }
}
