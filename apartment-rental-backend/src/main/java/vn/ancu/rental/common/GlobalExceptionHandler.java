package vn.ancu.rental.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiErrorResponse> handleAppException(AppException ex, HttpServletRequest request) {
        ApiErrorResponse body = new ApiErrorResponse(
                ex.getCode(),
                ex.getMessage(),
                request.getRequestURI(),
                OffsetDateTime.now(VietnamTime.ZONE),
                Map.of()
        );
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> details = new LinkedHashMap<>();
        String firstMessage = "Dữ liệu gửi lên không hợp lệ.";
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            details.put(fieldError.getField(), fieldError.getDefaultMessage());
            if ("Dữ liệu gửi lên không hợp lệ.".equals(firstMessage) && fieldError.getDefaultMessage() != null) {
                firstMessage = fieldError.getDefaultMessage();
            }
        }
        ApiErrorResponse body = new ApiErrorResponse(
                "VALIDATION_ERROR",
                firstMessage,
                request.getRequestURI(),
                OffsetDateTime.now(VietnamTime.ZONE),
                details
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        ApiErrorResponse body = new ApiErrorResponse(
                "BAD_REQUEST",
                ex.getMessage(),
                request.getRequestURI(),
                OffsetDateTime.now(VietnamTime.ZONE),
                Map.of()
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
