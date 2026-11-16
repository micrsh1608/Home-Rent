package vn.ancu.rental.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

public final class VietnamTime {
    public static final String ZONE_ID = "Asia/Ho_Chi_Minh";
    public static final ZoneId ZONE = ZoneId.of(ZONE_ID);

    private VietnamTime() {
    }

    public static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    public static LocalDateTime nowMinutePrecision() {
        return LocalDateTime.now(ZONE).truncatedTo(ChronoUnit.MINUTES);
    }
}
