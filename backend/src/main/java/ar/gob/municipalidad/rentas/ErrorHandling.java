package ar.gob.municipalidad.rentas;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import java.time.OffsetDateTime;
import java.util.UUID;

class BusinessException extends RuntimeException {
    final String code; final int status;
    BusinessException(String code, String message, int status) { super(message); this.code=code; this.status=status; }
}

@RestControllerAdvice
class GlobalExceptionHandler {
    private static final Logger log=LoggerFactory.getLogger(GlobalExceptionHandler.class);
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiDtos.ErrorResponse> business(BusinessException ex, HttpServletRequest request) {
        return response(ex.status, ex.code, ex.getMessage(), request);
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiDtos.ErrorResponse> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream().findFirst()
            .map(e -> e.getField()+": "+e.getDefaultMessage()).orElse("Solicitud inválida");
        return response(400, "VALIDATION_ERROR", message, request);
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,MissingServletRequestParameterException.class})
    ResponseEntity<ApiDtos.ErrorResponse> malformed(Exception ex,HttpServletRequest request) {
        return response(400,"INVALID_REQUEST","La solicitud no tiene un formato válido",request);
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiDtos.ErrorResponse> integrity(DataIntegrityViolationException ex,HttpServletRequest request) {
        return response(409,"DATA_INTEGRITY_VIOLATION","La operación contradice una restricción de integridad",request);
    }
    @ExceptionHandler(ConcurrencyFailureException.class)
    ResponseEntity<ApiDtos.ErrorResponse> concurrency(ConcurrencyFailureException ex,HttpServletRequest request) {
        return response(409,"CONCURRENT_MODIFICATION","El recurso fue modificado simultáneamente; reintente",request);
    }
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiDtos.ErrorResponse> accessDenied(AccessDeniedException ex,HttpServletRequest request) {
        return response(403,"ACCESS_DENIED","No tiene permisos para realizar esta operación",request);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiDtos.ErrorResponse> unexpected(Exception ex,HttpServletRequest request) {
        String traceId=traceId();log.error("Unexpected API error traceId={}",traceId,ex);
        return response(500,"INTERNAL_ERROR","Ocurrió un error interno",request,traceId);
    }
    private ResponseEntity<ApiDtos.ErrorResponse> response(int status, String code, String message, HttpServletRequest request) {
        return response(status,code,message,request,traceId());
    }
    private ResponseEntity<ApiDtos.ErrorResponse> response(int status,String code,String message,HttpServletRequest request,String traceId) {
        return ResponseEntity.status(status).body(new ApiDtos.ErrorResponse(OffsetDateTime.now(),status,code,message,request.getRequestURI(),traceId,null));
    }
    private String traceId(){String traceId=MDC.get("traceId");return traceId==null?UUID.randomUUID().toString():traceId;}
}
