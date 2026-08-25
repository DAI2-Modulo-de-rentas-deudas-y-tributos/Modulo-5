package ar.gob.municipalidad.rentas;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.time.OffsetDateTime;
import java.util.UUID;

class BusinessException extends RuntimeException {
    final String code; final int status;
    BusinessException(String code, String message, int status) { super(message); this.code=code; this.status=status; }
}

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiDtos.ErrorResponse> business(BusinessException ex, HttpServletRequest request) {
        return response(ex.status, ex.code, ex.getMessage(), request);
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiDtos.ErrorResponse> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream().findFirst()
            .map(e -> e.getField()+": "+e.getDefaultMessage()).orElse("Solicitud inválida");
        return response(422, "VALIDATION_ERROR", message, request);
    }
    private ResponseEntity<ApiDtos.ErrorResponse> response(int status, String code, String message, HttpServletRequest request) {
        String traceId = MDC.get("traceId"); if (traceId == null) traceId = UUID.randomUUID().toString();
        return ResponseEntity.status(status).body(new ApiDtos.ErrorResponse(OffsetDateTime.now(),status,code,message,request.getRequestURI(),traceId));
    }
}
