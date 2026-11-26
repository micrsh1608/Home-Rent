package vn.ancu.rental.health;

import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.ancu.rental.common.DatabaseDialect;
import vn.ancu.rental.common.ServerClock;
import vn.ancu.rental.common.VietnamTime;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;
    private final Environment environment;
    private final ServerClock serverClock;

    public HealthController(
            JdbcTemplate jdbcTemplate,
            DataSource dataSource,
            Environment environment,
            ServerClock serverClock
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
        this.environment = environment;
        this.serverClock = serverClock;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> checkHealth() {
        Map<String, Object> body = new LinkedHashMap<>();
        String[] profiles = environment.getActiveProfiles();
        body.put("activeProfiles", profiles.length == 0 ? new String[]{"default"} : profiles);
        body.put("timezone", VietnamTime.ZONE.getId());
        body.put("timestamp", serverClock.offsetNow());

        try {
            Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            Integer userCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
            DatabaseDialect dialect = DatabaseDialect.detect(dataSource);
            boolean up = (one != null && one == 1);
            body.put("status", up ? "UP" : "DOWN");
            body.put("database", up ? "UP" : "DOWN");
            body.put("databaseEngine", dialect.displayName());
            body.put("seededUsers", userCount != null ? userCount : 0);
            return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
        } catch (Exception ex) {
            body.put("status", "DOWN");
            body.put("database", "DOWN");
            body.put("databaseEngine", "UNKNOWN");
            body.put("seededUsers", 0);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
    }
}
