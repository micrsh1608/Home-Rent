package vn.ancu.rental.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final SessionAuthHelper sessionAuthHelper;
    private final AccountService accounts;
    private final OtpRateLimiter rateLimiter;

    public AuthController(AuthService authService, SessionAuthHelper sessionAuthHelper, AccountService accounts, OtpRateLimiter rateLimiter) {
        this.authService = authService;
        this.sessionAuthHelper = sessionAuthHelper;
        this.accounts = accounts;
        this.rateLimiter = rateLimiter;
    }

    public record LoginRequest(String email, String userId, String password) {
    }

    @GetMapping("/csrf")
    public Map<String, String> csrfToken(HttpServletRequest request, HttpServletResponse response) {
        String token = sessionAuthHelper.ensureCsrfToken(request, response);
        return Map.of("token", token);
    }

    @PostMapping("/login")
    public AuthUser login(@RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        if (body == null) {
            throw vn.ancu.rental.common.AppException.badRequest("INVALID_CREDENTIALS", "Vui lòng nhập tài khoản và mật khẩu.");
        }
        String identifier = (body.email() != null && !body.email().isBlank()) ? body.email() : body.userId();
        AuthUser user = authService.authenticate(identifier, body.password());
        sessionAuthHelper.saveUser(request, response, user);
        return user;
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpServletRequest request, HttpServletResponse response) {
        sessionAuthHelper.clearSession(request, response);
        return ResponseEntity.ok(Map.of("message", "Đã đăng xuất."));
    }

    @GetMapping("/me")
    public AuthUser currentAccount(HttpServletRequest request) {
        return sessionAuthHelper.requireAuthenticated(request);
    }

    @GetMapping("/accounts")
    public List<AuthUser> listAccounts() {
        return authService.listSampleAccounts();
    }

    @GetMapping("/contacts")
    public List<AuthUser> contacts(HttpServletRequest request) {
        return authService.listContacts(sessionAuthHelper.requireAuthenticated(request));
    }

    @PostMapping("/register/request-otp")
    public AccountService.OtpDelivery register(@Valid @RequestBody AccountService.RegistrationRequest body, HttpServletRequest request) {
        rateLimiter.check(request.getRemoteAddr());
        return accounts.requestRegistration(body);
    }

    @PostMapping("/register/confirm")
    public AuthUser confirmRegistration(@Valid @RequestBody AccountService.CodeRequest body, HttpServletRequest request, HttpServletResponse response) {
        AuthUser user = accounts.confirmRegistration(body);
        sessionAuthHelper.saveUser(request, response, user);
        return user;
    }

    @GetMapping("/profile")
    public AccountService.Profile profile(HttpServletRequest request) {
        return accounts.profile(sessionAuthHelper.requireAuthenticated(request));
    }

    @PutMapping("/profile")
    public AccountService.Profile updateProfile(@Valid @RequestBody AccountService.ProfileUpdate body, HttpServletRequest request) {
        return accounts.updateProfile(sessionAuthHelper.requireAuthenticated(request), body);
    }

    @PostMapping("/password/change/request-otp")
    public AccountService.OtpDelivery requestPasswordChange(@Valid @RequestBody AccountService.ChangePasswordRequest body, HttpServletRequest request) {
        AuthUser actor = sessionAuthHelper.requireAuthenticated(request);
        rateLimiter.check(request.getRemoteAddr());
        return accounts.requestPasswordChange(actor, body);
    }

    @PostMapping("/password/change/confirm")
    public Map<String, String> confirmPasswordChange(@Valid @RequestBody AccountService.CodeRequest body, HttpServletRequest request, HttpServletResponse response) {
        accounts.confirmPasswordChange(sessionAuthHelper.requireAuthenticated(request), body);
        sessionAuthHelper.clearSession(request, response);
        return Map.of("message", "Đã đổi mật khẩu. Vui lòng đăng nhập lại bằng mật khẩu mới.");
    }

    @PostMapping("/password/reset/request-otp")
    public AccountService.OtpDelivery requestPasswordReset(@Valid @RequestBody AccountService.ResetRequest body, HttpServletRequest request) {
        rateLimiter.check(request.getRemoteAddr());
        return accounts.requestPasswordReset(body);
    }

    @PostMapping("/password/reset/confirm")
    public Map<String, String> confirmPasswordReset(@Valid @RequestBody AccountService.ResetConfirmation body, HttpServletRequest request, HttpServletResponse response) {
        accounts.confirmPasswordReset(body);
        sessionAuthHelper.clearSession(request, response);
        return Map.of("message", "Đã đặt lại mật khẩu. Vui lòng đăng nhập bằng mật khẩu mới.");
    }
}
