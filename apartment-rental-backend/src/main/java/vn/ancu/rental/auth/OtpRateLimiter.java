package vn.ancu.rental.auth;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.ancu.rental.common.AppException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class OtpRateLimiter {
    private final JdbcTemplate jdbc;

    public OtpRateLimiter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // Independent transaction so failed deliveries and invalid requests still count.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void check(String clientAddress) {
        String key;
        try {
            key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(clientAddress.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
        Instant now = Instant.now();
        int updated = jdbc.update("UPDATE email_otp_rate_limits SET request_count = request_count WHERE rate_key = ?", key);
        if (updated == 0) {
            try {
                jdbc.update("INSERT INTO email_otp_rate_limits (rate_key, window_start, request_count) VALUES (?, ?, 1)",
                        key, Timestamp.from(now));
                return;
            } catch (DuplicateKeyException ex) {
                throw limited();
            }
        }
        var rows = jdbc.query("SELECT window_start, request_count FROM email_otp_rate_limits WHERE rate_key = ?",
                (rs, row) -> new Limit(rs.getTimestamp(1).toInstant(), rs.getInt(2)), key);
        Limit current = rows.getFirst();
        boolean reset = !now.isBefore(current.start().plusSeconds(3600));
        if (!reset && current.count() >= 20) throw limited();
        jdbc.update("UPDATE email_otp_rate_limits SET window_start = ?, request_count = ? WHERE rate_key = ?",
                Timestamp.from(reset ? now : current.start()), reset ? 1 : current.count() + 1, key);
    }

    private AppException limited() {
        return new AppException(HttpStatus.TOO_MANY_REQUESTS, "OTP_RATE_LIMIT",
                "Bạn đã yêu cầu quá nhiều mã xác nhận. Vui lòng thử lại sau.");
    }
    private record Limit(Instant start, int count) { }
}
