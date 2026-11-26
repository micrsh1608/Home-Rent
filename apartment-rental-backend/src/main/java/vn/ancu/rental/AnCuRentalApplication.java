package vn.ancu.rental;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import vn.ancu.rental.common.VietnamTime;

import java.time.Clock;
import java.util.Arrays;

@SpringBootApplication
@EnableScheduling
public class AnCuRentalApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnCuRentalApplication.class, args);
    }

    @Bean
    public Clock serverVietnamClock() {
        return Clock.system(VietnamTime.ZONE);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public WebMvcConfigurer corsConfigurer(
            @Value("${ancu.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173,http://localhost:8080,http://127.0.0.1:8080}") String allowedOrigins) {
        String[] origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins(origins)
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("Content-Type", "X-CSRF-Token", "X-XSRF-TOKEN", "Accept")
                        .exposedHeaders("X-CSRF-Token")
                        .allowCredentials(true);
            }
        };
    }
}
