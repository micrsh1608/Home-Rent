package vn.ancu.rental.common;

import org.springframework.http.HttpStatus;

public class AppException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public AppException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static AppException badRequest(String code, String message) {
        return new AppException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static AppException unauthorized(String message) {
        return new AppException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }

    public static AppException forbidden(String message) {
        return new AppException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }

    public static AppException notFound(String message) {
        return new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static AppException conflict(String code, String message) {
        return new AppException(HttpStatus.CONFLICT, code, message);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
