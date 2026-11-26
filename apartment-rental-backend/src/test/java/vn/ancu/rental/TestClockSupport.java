package vn.ancu.rental;

import jakarta.servlet.http.Cookie;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import vn.ancu.rental.auth.CsrfProtectionFilter;
import vn.ancu.rental.common.VietnamTime;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

public final class TestClockSupport {

    private TestClockSupport() {
    }

    public static final class MutableClock extends Clock {
        private final AtomicReference<Instant> fixedInstant = new AtomicReference<>(null);
        private final ZoneId zone;

        public MutableClock(ZoneId zone) {
            this.zone = zone;
        }

        public void fixTo(LocalDate date) {
            fixTo(date.atTime(10, 0));
        }

        public void fixTo(LocalDateTime dateTime) {
            fixedInstant.set(dateTime.atZone(zone).toInstant());
        }

        public void reset() {
            fixedInstant.set(null);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            MutableClock copy = new MutableClock(zone);
            copy.fixedInstant.set(this.fixedInstant.get());
            return copy;
        }

        @Override
        public Instant instant() {
            Instant fixed = fixedInstant.get();
            return fixed != null ? fixed : Instant.now();
        }
    }

    @TestConfiguration
    public static class MutableClockTestConfig {
        @Bean
        @Primary
        public MutableClock mutableTestClock() {
            return new MutableClock(VietnamTime.ZONE);
        }
    }

    public record AuthSession(MockHttpSession session, String csrfToken) {
        public MockHttpServletRequestBuilder attach(MockHttpServletRequestBuilder builder) {
            return builder
                    .session(session)
                    .header(CsrfProtectionFilter.CSRF_HEADER_NAME, csrfToken)
                    .cookie(new Cookie(CsrfProtectionFilter.CSRF_COOKIE_NAME, csrfToken));
        }
    }

    public static AuthSession extractAuthSession(MvcResult result) {
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        String token = result.getResponse().getHeader(CsrfProtectionFilter.CSRF_HEADER_NAME);
        if (token == null && session != null) {
            Object attr = session.getAttribute(CsrfProtectionFilter.SESSION_CSRF_KEY);
            if (attr instanceof String s) {
                token = s;
            }
        }
        return new AuthSession(session, token);
    }
}
