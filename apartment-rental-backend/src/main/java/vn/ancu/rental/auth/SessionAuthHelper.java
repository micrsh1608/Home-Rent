package vn.ancu.rental.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import vn.ancu.rental.common.AppException;

@Component
public class SessionAuthHelper {
    public static final String SESSION_USER_KEY = "ANCU_AUTH_USER";
    private final AuthService authService;
    private final CsrfProtectionFilter csrfProtectionFilter;

    public SessionAuthHelper(AuthService authService, CsrfProtectionFilter csrfProtectionFilter) {
        this.authService = authService;
        this.csrfProtectionFilter = csrfProtectionFilter;
    }

    public String saveUser(HttpServletRequest request, HttpServletResponse response, AuthUser user) {
        HttpSession existing = request.getSession(false);
        if (existing != null) {
            existing.invalidate();
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(SESSION_USER_KEY, user);
        return csrfProtectionFilter.rotateSessionToken(session, response);
    }

    public void clearSession(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        if (response != null) {
            csrfProtectionFilter.clearCsrfCookie(response);
        }
    }

    public String ensureCsrfToken(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(true);
        return csrfProtectionFilter.ensureSessionToken(session, response);
    }

    public AuthUser requireAuthenticated(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw AppException.unauthorized("Phiên đăng nhập không tồn tại hoặc đã hết hạn.");
        }
        Object attr = session.getAttribute(SESSION_USER_KEY);
        if (!(attr instanceof AuthUser user)) {
            throw AppException.unauthorized("Phiên đăng nhập không hợp lệ.");
        }
        AuthUser current = authService.currentUser(user.id());
        if (current.sessionVersion() != user.sessionVersion()) {
            session.invalidate();
            throw AppException.unauthorized("Mật khẩu đã thay đổi. Vui lòng đăng nhập lại.");
        }
        return current;
    }

    public AuthUser requireRole(HttpServletRequest request, String expectedRole) {
        AuthUser user = requireAuthenticated(request);
        if (!expectedRole.equalsIgnoreCase(user.role())) {
            throw AppException.forbidden("Tài khoản không có quyền truy cập khu vực này.");
        }
        return user;
    }
}
