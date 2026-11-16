package vn.ancu.rental.auth;

import java.io.Serializable;

public record AuthUser(
        String id,
        String email,
        String name,
        String role,
        String redirectPath
) implements Serializable {

    public static AuthUser of(String id, String email, String name, String role) {
        String redirect = "OWNER".equalsIgnoreCase(role) ? "/owner" : "/tenant";
        return new AuthUser(id, email, name, role.toUpperCase(), redirect);
    }
}
