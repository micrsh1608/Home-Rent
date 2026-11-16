package vn.ancu.rental.health;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.ancu.rental.common.VietnamTime;

import java.time.OffsetDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public Map<String, Object> checkHealth() {
        Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        Integer userCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        return Map.of(
                "status", (one != null && one == 1) ? "UP" : "DOWN",
                "database", "UP",
                "seededUsers", userCount != null ? userCount : 0,
                "timezone", VietnamTime.ZONE.getId(),
                "timestamp", OffsetDateTime.now(VietnamTime.ZONE)
        );
    }
}
