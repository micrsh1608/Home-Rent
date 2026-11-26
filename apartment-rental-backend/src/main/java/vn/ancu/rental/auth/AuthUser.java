package vn.ancu.rental.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.io.Serializable;

public record AuthUser(
        String id,
        String email,
        String name,
        String role,
        String redirectPath,
        @JsonIgnore int sessionVersion
) implements Serializable {

    public static AuthUser of(String id, String email, String name, String role) {
        return of(id, email, name, role, 0);
    }

    public static AuthUser of(String id, String email, String name, String role, int version) {
        String redirect = "OWNER".equalsIgnoreCase(role) ? "/owner" : "/tenant";
        return new AuthUser(id, email, name, role.toUpperCase(), redirect, version);
    }
}
