package vn.ancu.rental.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    public AuthController(AuthService authService, SessionAuthHelper sessionAuthHelper) {
        this.authService = authService;
        this.sessionAuthHelper = sessionAuthHelper;
    }

    public record LoginRequest(String email, String userId, String password) {
    }

    @PostMapping("/login")
    public AuthUser login(@RequestBody LoginRequest body, HttpServletRequest request) {
        String identifier = (body.email() != null && !body.email().isBlank()) ? body.email() : body.userId();
        AuthUser user = authService.authenticate(identifier, body.password());
        sessionAuthHelper.saveUser(request, user);
        return user;
    }

    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(HttpServletRequest request) {
        sessionAuthHelper.clearSession(request);
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
}
