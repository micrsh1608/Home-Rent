package vn.ancu.rental;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.transaction.TransactionalTestExecutionListener;
import org.springframework.test.context.web.ServletTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ancu_accounts_test;MODE=MSSQLServer;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Transactional
@TestExecutionListeners(listeners = { ServletTestExecutionListener.class, DependencyInjectionTestExecutionListener.class,
        TransactionalTestExecutionListener.class }, mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class AccountIntegrationTest {
    private static final TestSmtp SMTP = new TestSmtp();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.mail.javamail.JavaMailSender sender;

    @DynamicPropertySource
    static void smtpProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> "127.0.0.1");
        registry.add("spring.mail.port", SMTP::port);
        registry.add("spring.mail.properties[mail.smtp.auth]", () -> "false");
        registry.add("spring.mail.properties[mail.smtp.starttls.enable]", () -> "false");
        registry.add("spring.mail.properties[mail.smtp.starttls.required]", () -> "false");
        registry.add("ancu.mail.from", () -> "no-reply@ancu.vn");
    }

    @AfterAll static void stopSmtp() throws Exception { SMTP.close(); }

    @Test void smtpTransportConnectsToTheLocalTestServer() throws Exception {
        var transport = (org.springframework.mail.javamail.JavaMailSenderImpl) sender;
        assertThat(transport.getJavaMailProperties().getProperty("mail.smtp.auth")).isEqualTo("false");
        assertThat(transport.getJavaMailProperties().getProperty("mail.smtp.starttls.required")).isEqualTo("false");
        transport.testConnection();
    }

    @Test void registrationRequiresDeliveredCodeAndCreatesPrivateTenantProfile() throws Exception {
        String email = email();
        String id = requestRegistration(email);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email)).isZero();
        mvc.perform(body(post("/api/auth/login"), Map.of("email", email, "password", "Original123!"))).andExpect(status().isUnauthorized());
        String code = SMTP.code(email);
        assertThat(jdbc.queryForObject("SELECT code_hash FROM email_otp_challenges WHERE id = ?", String.class, id)).doesNotContain(code);
        MvcResult result = mvc.perform(body(post("/api/auth/register/confirm"), Map.of("challengeId", id, "code", code)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("TENANT"))
                .andExpect(jsonPath("$.sessionVersion").doesNotExist()).andReturn();
        TestClockSupport.AuthSession session = TestClockSupport.extractAuthSession(result);
        mvc.perform(get("/api/auth/profile").session(session.session())).andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerified").value(true)).andExpect(jsonPath("$.demo").value(false));
        mvc.perform(body(session.attach(put("/api/auth/profile")), Map.of("name", "Tên mới", "phone", "0901234567", "address", "12 Lê Lợi", "dateOfBirth", "2000-01-02", "role", "OWNER", "email", "other@gmail.com")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Tên mới"))
                .andExpect(jsonPath("$.email").value(email)).andExpect(jsonPath("$.role").value("TENANT"));
        mvc.perform(get("/api/auth/me").session(session.session())).andExpect(jsonPath("$.name").value("Tên mới"));
        String samples = mvc.perform(get("/api/auth/accounts")).andExpect(jsonPath("$.length()").value(4)).andReturn().getResponse().getContentAsString();
        assertThat(samples).doesNotContain(email);
        mvc.perform(get("/api/owner/apartments").session(session.session())).andExpect(status().isForbidden());
        mvc.perform(body(post("/api/auth/register/confirm"), Map.of("challengeId", id, "code", code))).andExpect(status().isBadRequest());
    }

    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void wrongAttemptsPersistAndCorrectCodeCannotBypassFiveAttemptLimit() throws Exception {
        String email = email();
        String id = requestRegistration(email);
        String valid = SMTP.code(email);
        String wrong = valid.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < 5; i++) mvc.perform(body(post("/api/auth/register/confirm"), Map.of("challengeId", id, "code", wrong)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_OTP"));
        assertThat(jdbc.queryForObject("SELECT attempts FROM email_otp_challenges WHERE id = ?", Integer.class, id)).isEqualTo(5);
        mvc.perform(body(post("/api/auth/register/confirm"), Map.of("challengeId", id, "code", valid)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OTP_LOCKED"));
    }

    @Test void expirationResendCooldownAndPreviousCodeAreEnforced() throws Exception {
        String email = email();
        String first = requestRegistration(email);
        String firstCode = SMTP.code(email);
        mvc.perform(body(post("/api/auth/register/request-otp"), registration(email))).andExpect(status().isTooManyRequests());
        allowResend(email);
        String second = requestRegistration(email);
        assertThat(second).isNotEqualTo(first);
        mvc.perform(body(post("/api/auth/register/confirm"), Map.of("challengeId", first, "code", firstCode))).andExpect(status().isBadRequest());
        jdbc.update("UPDATE email_otp_challenges SET expires_at = ? WHERE id = ?", Timestamp.from(Instant.now().minusSeconds(1)), second);
        mvc.perform(body(post("/api/auth/register/confirm"), Map.of("challengeId", second, "code", SMTP.code(email))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OTP_EXPIRED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email)).isZero();
    }

    @Test void passwordChangeRequiresCurrentPasswordAndOtpThenInvalidatesOtherSessions() throws Exception {
        String email = email();
        TestClockSupport.AuthSession first = register(email);
        TestClockSupport.AuthSession other = login(email, "Original123!");
        allowResend(email);
        mvc.perform(body(first.attach(post("/api/auth/password/change/request-otp")), Map.of("currentPassword", "Wrong123!", "newPassword", "Changed123!")))
                .andExpect(status().isBadRequest());
        String id = json.readTree(mvc.perform(body(first.attach(post("/api/auth/password/change/request-otp")), Map.of("currentPassword", "Original123!", "newPassword", "Changed123!")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("challengeId").asText();
        login(email, "Original123!");
        mvc.perform(body(post("/api/auth/login"), Map.of("email", email, "password", "Changed123!"))).andExpect(status().isUnauthorized());
        mvc.perform(body(post("/api/auth/register/confirm"), Map.of("challengeId", id, "code", SMTP.code(email)))).andExpect(status().isBadRequest());
        mvc.perform(body(first.attach(post("/api/auth/password/change/confirm")), Map.of("challengeId", id, "code", SMTP.code(email)))).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(other.session())).andExpect(status().isUnauthorized());
        mvc.perform(body(post("/api/auth/login"), Map.of("email", email, "password", "Original123!"))).andExpect(status().isUnauthorized());
        login(email, "Changed123!");
    }

    @Test void recoveryRequiresOtpAndDoesNotSendMailToDemoOrUnknownAccounts() throws Exception {
        String email = email();
        TestClockSupport.AuthSession existing = register(email);
        allowResend(email);
        String id = json.readTree(mvc.perform(body(post("/api/auth/password/reset/request-otp"), Map.of("email", email)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("challengeId").asText();
        mvc.perform(body(post("/api/auth/password/reset/confirm"), Map.of("challengeId", id, "code", SMTP.code(email), "newPassword", "Recovered123!"))).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").session(existing.session())).andExpect(status().isUnauthorized());
        login(email, "Recovered123!");
        String unknown = email();
        mvc.perform(body(post("/api/auth/password/reset/request-otp"), Map.of("email", unknown))).andExpect(status().isOk());
        mvc.perform(body(post("/api/auth/password/reset/request-otp"), Map.of("email", "minhanh@ancu.vn"))).andExpect(status().isOk());
        assertThat(SMTP.received.containsKey(unknown)).isFalse();
        assertThat(SMTP.received.containsKey("minhanh@ancu.vn")).isFalse();
    }

    @Test void demoAccountsStayUsableButTheirSharedCredentialsAndProfilesAreProtected() throws Exception {
        TestClockSupport.AuthSession demo = login("minhanh@ancu.vn", "AnCu@123");
        mvc.perform(body(demo.attach(post("/api/auth/password/change/request-otp")), Map.of("currentPassword", "AnCu@123", "newPassword", "Changed123!"))).andExpect(status().isForbidden());
        mvc.perform(body(demo.attach(put("/api/auth/profile")), Map.of("name", "Thay tên demo"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/profile")).andExpect(status().isUnauthorized());
        mvc.perform(body(post("/api/auth/register/request-otp"), Map.of("name", "Test", "email", "fake@example.com", "password", "Original123!"))).andExpect(status().isBadRequest());
        mvc.perform(body(post("/api/auth/register/request-otp"), Map.of("name", "Test", "email", email(), "password", "short"))).andExpect(status().isBadRequest());
    }

    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void failedSmtpDeliveryDoesNotLeaveAUsableChallengeOrCreateAnAccount() throws Exception {
        String email = email();
        SMTP.rejectedRecipient = email;
        try {
            mvc.perform(body(post("/api/auth/register/request-otp"), registration(email)))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("EMAIL_DELIVERY_FAILED"));
        } finally { SMTP.rejectedRecipient = null; }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_otp_challenges WHERE email = ?", Integer.class, email)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email)).isZero();
    }

    @Test void oneEmailCannotRequestMoreThanFiveCodesPerHour() throws Exception {
        String email = email();
        for (int i = 0; i < 5; i++) { if (i > 0) allowResend(email); requestRegistration(email); }
        allowResend(email);
        mvc.perform(body(post("/api/auth/register/request-otp"), registration(email))).andExpect(status().isTooManyRequests());
    }

    @Test void oneClientCannotBypassRequestLimitByUsingDifferentEmailAddresses() throws Exception {
        String client = "limited-" + UUID.randomUUID();
        for (int i = 0; i < 20; i++) {
            mvc.perform(body(post("/api/auth/password/reset/request-otp"), Map.of("email", email()))
                    .with(req -> { req.setRemoteAddr(client); return req; })).andExpect(status().isOk());
        }
        mvc.perform(body(post("/api/auth/password/reset/request-otp"), Map.of("email", email()))
                .with(req -> { req.setRemoteAddr(client); return req; })).andExpect(status().isTooManyRequests());
    }

    private String email() { return "test-" + UUID.randomUUID() + "@ancu.vn"; }
    private Map<String, String> registration(String email) { return Map.of("name", "Khách mới", "email", email, "password", "Original123!"); }
    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Object value) throws Exception {
        return request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(value))
                .with(req -> { req.setRemoteAddr("test-" + UUID.randomUUID()); return req; });
    }
    private String requestRegistration(String email) throws Exception {
        JsonNode result = json.readTree(mvc.perform(body(post("/api/auth/register/request-otp"), registration(email)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(result.has("code")).isFalse();
        return result.get("challengeId").asText();
    }
    private TestClockSupport.AuthSession register(String email) throws Exception {
        String id = requestRegistration(email);
        return TestClockSupport.extractAuthSession(mvc.perform(body(post("/api/auth/register/confirm"), Map.of("challengeId", id, "code", SMTP.code(email))))
                .andExpect(status().isOk()).andReturn());
    }
    private TestClockSupport.AuthSession login(String email, String password) throws Exception {
        return TestClockSupport.extractAuthSession(mvc.perform(body(post("/api/auth/login"), Map.of("email", email, "password", password)))
                .andExpect(status().isOk()).andReturn());
    }
    private void allowResend(String email) {
        jdbc.update("UPDATE email_otp_challenges SET next_send_at = ? WHERE email = ?", Timestamp.from(Instant.now().minusSeconds(1)), email);
    }

    private static final class TestSmtp implements AutoCloseable {
        final ServerSocket server;
        final Map<String, String> received = new ConcurrentHashMap<>();
        volatile String rejectedRecipient;
        TestSmtp() {
            try { server = new ServerSocket(0, 20, InetAddress.getByName("127.0.0.1")); }
            catch (Exception ex) { throw new IllegalStateException(ex); }
            Thread worker = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) { handle(socket); }
                    catch (Exception ex) { if (!server.isClosed()) throw new IllegalStateException(ex); }
                }
            }, "test-smtp");
            worker.setDaemon(true);
            worker.start();
        }
        int port() { return server.getLocalPort(); }
        String code(String email) {
            String text = received.get(email);
            assertThat(text).isNotNull();
            var matcher = Pattern.compile("\\b[0-9]{6}\\b").matcher(text);
            assertThat(matcher.find()).isTrue();
            return matcher.group();
        }
        void handle(Socket socket) throws Exception {
            socket.setSoTimeout(5000);
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            var writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            writer.print("220 localhost test SMTP\r\n"); writer.flush();
            String recipient = null;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("EHLO") || line.startsWith("HELO")) writer.print("250 localhost\r\n");
                else if (line.startsWith("RCPT TO:")) {
                    recipient = line.substring(line.indexOf('<') + 1, line.indexOf('>'));
                    writer.print(recipient.equals(rejectedRecipient) ? "550 recipient rejected\r\n" : "250 OK\r\n");
                } else if (line.equals("DATA")) {
                    writer.print("354 end with dot\r\n"); writer.flush();
                    StringBuilder data = new StringBuilder();
                    while ((line = reader.readLine()) != null && !line.equals(".")) data.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
                    var mime = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(data.toString().getBytes(StandardCharsets.UTF_8)));
                    received.put(recipient, (String) mime.getContent());
                    writer.print("250 queued\r\n");
                } else if (line.equals("QUIT")) { writer.print("221 bye\r\n"); writer.flush(); break; }
                else writer.print("250 OK\r\n");
                writer.flush();
            }
        }
        public void close() throws Exception { server.close(); }
    }
}
