package vn.ancu.rental.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import vn.ancu.rental.common.ApiErrorResponse;
import vn.ancu.rental.common.ServerClock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bảo vệ CSRF cho xác thực bằng HTTP Session Cookie (ANCU_SESSION):
 * - Cấp mã CSRF ngẫu nhiên 256-bit gắn với phiên (ANCU_CSRF_TOKEN) và đồng bộ qua cookie XSRF-TOKEN + header X-CSRF-Token.
 * - Mọi yêu cầu làm thay đổi trạng thái (POST/PUT/PATCH/DELETE) trên /api/** khi đã đăng nhập bắt buộc phải gửi
 *   header X-CSRF-Token (hoặc X-XSRF-TOKEN) khớp với token của phiên hiện tại.
 * - Kiểm tra thêm Origin header khi có mặt theo danh sách ancu.cors.allowed-origins.
 */
@Component
public class CsrfProtectionFilter extends OncePerRequestFilter {

    public static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    public static final String CSRF_HEADER_NAME = "X-CSRF-Token";
    public static final String CSRF_ALT_HEADER_NAME = "X-XSRF-TOKEN";
    public static final String SESSION_CSRF_KEY = "ANCU_CSRF_TOKEN";

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectMapper objectMapper;
    private final ServerClock serverClock;
    private final boolean csrfEnabled;
    private final boolean cookieSecure;
    private final Set<String> allowedOrigins;

    public CsrfProtectionFilter(
            ObjectMapper objectMapper,
            ServerClock serverClock,
            @Value("${ancu.security.csrf-enabled:true}") boolean csrfEnabled,
            @Value("${ancu.security.cookie-secure:false}") boolean cookieSecure,
            @Value("${ancu.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173,http://localhost:8080,http://127.0.0.1:8080}") String allowedOriginsCsv
    ) {
        this.objectMapper = objectMapper;
        this.serverClock = serverClock;
        this.csrfEnabled = csrfEnabled;
        this.cookieSecure = cookieSecure;
        this.allowedOrigins = Arrays.stream(allowedOriginsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    public String ensureSessionToken(HttpSession session, HttpServletResponse response) {
        Object existing = session.getAttribute(SESSION_CSRF_KEY);
        String token = (existing instanceof String s && !s.isBlank()) ? s : generateToken();
        session.setAttribute(SESSION_CSRF_KEY, token);
        writeTokenToResponse(token, response);
        return token;
    }

    public String rotateSessionToken(HttpSession session, HttpServletResponse response) {
        String token = generateToken();
        session.setAttribute(SESSION_CSRF_KEY, token);
        writeTokenToResponse(token, response);
        return token;
    }

    public void clearCsrfCookie(HttpServletResponse response) {
        ResponseCookie cleared = ResponseCookie.from(CSRF_COOKIE_NAME, "")
                .path("/")
                .httpOnly(false)
                .sameSite("Lax")
                .secure(cookieSecure)
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cleared.toString());
    }

    private void writeTokenToResponse(String token, HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(CSRF_COOKIE_NAME, token)
                .path("/")
                .httpOnly(false)
                .sameSite("Lax")
                .secure(cookieSecure)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        response.setHeader(CSRF_HEADER_NAME, token);
    }

    public static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!csrfEnabled || path == null || !path.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String method = request.getMethod();
        if (SAFE_METHODS.contains(method)) {
            HttpSession session = request.getSession(false);
            if (session != null && session.getAttribute(SessionAuthHelper.SESSION_USER_KEY) != null) {
                ensureSessionToken(session, response);
            }
            filterChain.doFilter(request, response);
            return;
        }

        // Defense-in-depth: validate Origin header if browser sent one
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank() && !allowedOrigins.contains(origin.trim())) {
            rejectCsrf(request, response, "CSRF_ORIGIN_REJECTED", "Nguồn gửi yêu cầu (Origin) không được phép.");
            return;
        }

        // For state-changing requests carrying an authenticated session, enforce CSRF token validation
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(SessionAuthHelper.SESSION_USER_KEY) != null) {
            Object expectedObj = session.getAttribute(SESSION_CSRF_KEY);
            String expected = expectedObj instanceof String s ? s : null;
            String provided = request.getHeader(CSRF_HEADER_NAME);
            if (provided == null || provided.isBlank()) {
                provided = request.getHeader(CSRF_ALT_HEADER_NAME);
            }
            if (expected == null || expected.isBlank() || provided == null || provided.isBlank()
                    || !constantTimeEquals(expected, provided.trim())) {
                rejectCsrf(request, response, "CSRF_INVALID",
                        "Mã xác thực bảo mật CSRF không hợp lệ hoặc bị thiếu. Vui lòng tải lại trang và thử lại.");
                return;
            }
            // If XSRF-TOKEN cookie is also present, ensure double-submit consistency
            String cookieToken = findCookieValue(request, CSRF_COOKIE_NAME);
            if (cookieToken != null && !cookieToken.isBlank() && !constantTimeEquals(expected, cookieToken.trim())) {
                rejectCsrf(request, response, "CSRF_INVALID",
                        "Cookie bảo mật CSRF không khớp phiên hiện tại. Vui lòng tải lại trang.");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private void rejectCsrf(HttpServletRequest request, HttpServletResponse response, String code, String message)
            throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        ApiErrorResponse error = new ApiErrorResponse(
                code,
                message,
                request.getRequestURI(),
                serverClock.offsetNow(),
                Map.of()
        );
        objectMapper.writeValue(response.getWriter(), error);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String findCookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
