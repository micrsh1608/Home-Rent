package vn.ancu.rental.common;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Nguồn thời gian chính thức của máy chủ theo múi giờ Việt Nam (Asia/Ho_Chi_Minh).
 * Tất cả nghiệp vụ (lịch hẹn, quá hạn 3 ngày, bàn giao, thu tiền) đọc thời gian từ bean này
 * thay vì tin cậy đồng hồ do client gửi lên. Trong kiểm thử có thể inject Clock cố định.
 */
@Component
public class ServerClock {

    private final Clock clock;

    public ServerClock(Clock clock) {
        this.clock = clock;
    }

    public Clock getClock() {
        return clock;
    }

    public Instant instant() {
        return Instant.now(clock);
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(VietnamTime.ZONE));
    }

    public LocalDateTime nowMinutePrecision() {
        return LocalDateTime.now(clock.withZone(VietnamTime.ZONE)).truncatedTo(ChronoUnit.MINUTES);
    }

    public OffsetDateTime offsetNow() {
        return OffsetDateTime.now(clock.withZone(VietnamTime.ZONE));
    }
}
