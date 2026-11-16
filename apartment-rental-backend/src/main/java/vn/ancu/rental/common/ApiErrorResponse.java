package vn.ancu.rental.common;

import java.time.OffsetDateTime;
import java.util.Map;

public record ApiErrorResponse(
        String code,
        String message,
        String path,
        OffsetDateTime timestamp,
        Map<String, String> details
) {
}
