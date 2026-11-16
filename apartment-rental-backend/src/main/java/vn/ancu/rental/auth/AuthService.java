package vn.ancu.rental.auth;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import vn.ancu.rental.common.AppException;

import java.util.List;

@Service
public class AuthService {

    public static final String DEFAULT_DEMO_PASSWORD = "AnCu@123";

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    public AuthService(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    @PostConstruct
    public void ensureDemoPasswordsHashed() {
        List<UserRecord> users = jdbcTemplate.query(
                "SELECT id, email, password_hash, full_name, role, active FROM users WHERE id IN ('o1', 'o2', 't1', 't2')",
                (rs, rowNum) -> new UserRecord(
                        rs.getString("id"),
                        rs.getString("email"),
                        rs.getString("password_hash"),
                        rs.getString("full_name"),
                        rs.getString("role"),
                        rs.getBoolean("active")
                )
        );
        for (UserRecord user : users) {
            if (!passwordEncoder.matches(DEFAULT_DEMO_PASSWORD, user.passwordHash())) {
                String bcrypt = passwordEncoder.encode(DEFAULT_DEMO_PASSWORD);
                jdbcTemplate.update("UPDATE users SET password_hash = ? WHERE id = ?", bcrypt, user.id());
            }
        }
    }

    public AuthUser authenticate(String identifier, String rawPassword) {
        if (identifier == null || identifier.isBlank() || rawPassword == null || rawPassword.isBlank()) {
            throw AppException.badRequest("INVALID_CREDENTIALS", "Vui lòng nhập tài khoản và mật khẩu.");
        }
        String normalized = identifier.trim();
        List<UserRecord> matches = jdbcTemplate.query(
                "SELECT id, email, password_hash, full_name, role, active FROM users WHERE LOWER(email) = LOWER(?) OR id = ?",
                (rs, rowNum) -> new UserRecord(
                        rs.getString("id"),
                        rs.getString("email"),
                        rs.getString("password_hash"),
                        rs.getString("full_name"),
                        rs.getString("role"),
                        rs.getBoolean("active")
                ),
                normalized, normalized
        );
        if (matches.isEmpty()) {
            throw AppException.unauthorized("Email/tài khoản hoặc mật khẩu không đúng.");
        }
        UserRecord user = matches.get(0);
        if (!user.active() || !passwordEncoder.matches(rawPassword, user.passwordHash())) {
            throw AppException.unauthorized("Email/tài khoản hoặc mật khẩu không đúng.");
        }
        return AuthUser.of(user.id(), user.email(), user.fullName(), user.role());
    }

    public List<AuthUser> listSampleAccounts() {
        return jdbcTemplate.query(
                "SELECT id, email, full_name, role FROM users WHERE active = 1 ORDER BY role, id",
                (rs, rowNum) -> AuthUser.of(
                        rs.getString("id"),
                        rs.getString("email"),
                        rs.getString("full_name"),
                        rs.getString("role")
                )
        );
    }

    private record UserRecord(
            String id,
            String email,
            String passwordHash,
            String fullName,
            String role,
            boolean active
    ) {
    }
}
