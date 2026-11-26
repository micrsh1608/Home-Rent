package vn.ancu.rental.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ServerClock serverClock;

    public GlobalExceptionHandler(ServerClock serverClock) {
        this.serverClock = serverClock;
    }

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ApiErrorResponse> handleAppException(AppException ex, HttpServletRequest request) {
        return build(ex.getStatus(), ex.getCode(), ex.getMessage(), request, Map.of());
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
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", firstMessage, request, details);
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiErrorResponse> handleBadRequest(Exception ex, HttpServletRequest request) {
        String message = ex instanceof IllegalArgumentException && ex.getMessage() != null && !ex.getMessage().isBlank()
                ? ex.getMessage()
                : "Định dạng dữ liệu yêu cầu không hợp lệ.";
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message, request, Map.of());
    }

    @ExceptionHandler({
            PessimisticLockingFailureException.class,
            CannotAcquireLockException.class,
            DeadlockLoserDataAccessException.class,
            QueryTimeoutException.class
    })
    public ResponseEntity<ApiErrorResponse> handleLockConflict(Exception ex, HttpServletRequest request) {
        log.warn("Database lock/timeout conflict on {}: {}", request.getRequestURI(), ex.getClass().getSimpleName());
        return build(
                HttpStatus.CONFLICT,
                "LOCK_CONFLICT",
                "Dữ liệu đang được xử lý đồng thời bởi yêu cầu khác. Vui lòng thử lại.",
                request,
                Map.of()
        );
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ApiErrorResponse> handleDuplicateKey(DuplicateKeyException ex, HttpServletRequest request) {
        log.warn("Duplicate key conflict on {}", request.getRequestURI());
        return build(
                HttpStatus.CONFLICT,
                "DUPLICATE_CONFLICT",
                "Bản ghi đã tồn tại hoặc xung đột với dữ liệu hiện hành.",
                request,
                Map.of()
        );
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Data integrity violation on {}", request.getRequestURI());
        return build(
                HttpStatus.CONFLICT,
                "DATA_INTEGRITY_CONFLICT",
                "Thao tác vi phạm ràng buộc toàn vẹn dữ liệu.",
                request,
                Map.of()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error while handling {}", request.getRequestURI(), ex);
        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Hệ thống gặp lỗi khi xử lý yêu cầu. Vui lòng thử lại sau.",
                request,
                Map.of()
        );
    }

    private ResponseEntity<ApiErrorResponse> build(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request,
            Map<String, String> details
    ) {
        ApiErrorResponse body = new ApiErrorResponse(
                code,
                message,
                request.getRequestURI(),
                serverClock.offsetNow(),
                details
        );
        return ResponseEntity.status(status).body(body);
    }
}
