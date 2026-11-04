package ar.gob.municipalidad.rentas;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.multipart.MultipartException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

class BusinessException extends RuntimeException {
    private static final Set<String> STATE_CONFLICT_CODES=Set.of("INVALID_TICKET_TRANSITION","INVALID_SOCIAL_RULE_TRANSITION","ALLOCATION_NOT_REVERSIBLE","PLAN_NOT_REVERSIBLE","INSTALLMENT_NOT_REVERSIBLE","INSTALLMENT_CANCELLED",
        "TAXPAYER_BLOCKED","DEBT_NOT_PAYABLE","DEBT_NOT_BILLABLE","BILL_NOT_PAYABLE",
        "DEBT_IN_ACTIVE_PAYMENT_PLAN","DEBT_ALREADY_IN_PAYMENT_PLAN","DEBT_ALREADY_IN_ACTIVE_PAYMENT_PLAN",
        "DEBT_NOT_ADJUSTABLE","DEBT_NOT_ELIGIBLE_FOR_PLAN","PAYMENT_NOT_ALLOCATABLE",
        "PAYMENT_BALANCE_CONVERTED_TO_CREDIT","INSTALLMENT_NOT_PAYABLE","PAYMENT_NOT_REVERSIBLE",
        "REVERSAL_ALREADY_EXISTS","REVERSAL_NOT_PENDING","REVERSAL_NOT_APPROVED","ADJUSTMENT_NOT_PENDING",
        "RUN_NOT_DRAFT","RUN_NOT_READY","RUN_NOT_PENDING","RUN_NOT_APPROVED","RUN_CONFIGURATION_CHANGED",
        "INVALID_CONFIGURATION_TRANSITION","CONFIGURATION_NOT_EDITABLE","CONFIGURATION_NOT_ACTIVE",
        "INVALID_EXEMPTION_TRANSITION","DOCUMENTATION_NOT_REQUIRED","INVALID_PLAN_REQUEST_TRANSITION",
        "PLAN_DEBT_CHANGED","PLAN_EXCEPTION_APPROVAL_REQUIRED","PLAN_NOT_ACTIVE","PLAN_NOT_DEFAULTED",
        "EXPIRATION_ALREADY_PENDING","EXPIRATION_NOT_PENDING","REFINANCING_ALREADY_PENDING",
        "INVALID_REFINANCING_TRANSITION","REFINANCING_NOT_PENDING","REFINANCING_EXCEPTION_APPROVAL_REQUIRED",
        "PLAN_NOT_REFINANCING_ELIGIBLE","PLAN_BALANCE_CHANGED","PLAN_WITHOUT_BALANCE","MAX_REFINANCING_REACHED",
        "TICKET_ALREADY_CLOSED","INTEGRATION_EVENT_NOT_REPROCESSABLE","EXTERNAL_OBLIGATION_NOT_RETRYABLE",
        "RECONCILIATION_ALREADY_RESOLVED","RECONCILIATION_PAYMENT_MISMATCH");
    final String code; final int status; final Integer retryAfterSeconds;
    BusinessException(String code, String message, int status) { this(code,message,status,null); }
    BusinessException(String code, String message, int status,Integer retryAfterSeconds) { super(message); this.code=code; this.status=status==422&&STATE_CONFLICT_CODES.contains(code)?409:status; this.retryAfterSeconds=retryAfterSeconds; }
}

@RestControllerAdvice
class GlobalExceptionHandler {
    private static final Logger log=LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final AuditService audit;
    // Opcional: los tests con MockMvc standalone no levantan AuditService.
    GlobalExceptionHandler(@org.springframework.lang.Nullable AuditService audit) { this.audit=audit; }
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<ApiDtos.ErrorResponse> business(BusinessException ex, HttpServletRequest request) {
        if(ex.status==403&&audit!=null) audit.recordAccessDenied(request,ex.code);
        ResponseEntity<ApiDtos.ErrorResponse> error=response(ex.status, ex.code, ex.getMessage(), request);
        if(ex.retryAfterSeconds==null) return error;
        HttpHeaders headers=new HttpHeaders();headers.set(HttpHeaders.RETRY_AFTER,Integer.toString(ex.retryAfterSeconds));headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(error.getBody(),headers,HttpStatus.valueOf(ex.status));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiDtos.ErrorResponse> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream().findFirst()
            .map(e -> e.getField()+": "+e.getDefaultMessage()).orElse("Solicitud inválida");
        Map<String,List<String>> fieldErrors=new TreeMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error -> fieldErrors
            .computeIfAbsent(error.getField(),ignored -> new ArrayList<>())
            .add(Objects.requireNonNullElse(error.getDefaultMessage(),"Valor inválido")));
        List<Map<String,Object>> errors=fieldErrors.entrySet().stream()
            .map(entry -> Map.<String,Object>of("field",entry.getKey(),"messages",entry.getValue())).toList();
        return response(400,"VALIDATION_ERROR",message,request,traceId(),Map.of("fieldErrors",errors));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,MissingServletRequestParameterException.class,RequestRejectedException.class,MultipartException.class})
    ResponseEntity<ApiDtos.ErrorResponse> malformed(Exception ex,HttpServletRequest request) {
        return response(400,"INVALID_REQUEST","La solicitud no tiene un formato válido",request);
    }
    // Un parámetro mal codificado (p. ej. sort=%) falla al resolver los argumentos del controlador:
    // es un error del cliente. Cualquier otra IllegalArgumentException sigue siendo un error interno.
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiDtos.ErrorResponse> illegalArgument(IllegalArgumentException ex,HttpServletRequest request) {
        boolean resolvingArguments=Arrays.stream(ex.getStackTrace())
            .anyMatch(frame -> frame.getClassName().endsWith(".HandlerMethodArgumentResolverComposite"));
        return resolvingArguments?malformed(ex,request):unexpected(ex,request);
    }
    // Un orden por una propiedad inexistente (sort=foo) llega al repositorio y falla ahí: es un error del cliente.
    @ExceptionHandler({PropertyReferenceException.class,InvalidDataAccessApiUsageException.class})
    ResponseEntity<ApiDtos.ErrorResponse> invalidSort(RuntimeException ex,HttpServletRequest request) {
        boolean clientSort=ex instanceof PropertyReferenceException||request.getParameter("sort")!=null;
        return clientSort?response(400,"INVALID_SORT","El criterio de orden no es válido",request):unexpected(ex,request);
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
        if(audit!=null) audit.recordAccessDenied(request,"ACCESS_DENIED");
        return response(403,"ACCESS_DENIED","No tiene permisos para realizar esta operación",request);
    }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiDtos.ErrorResponse> methodNotAllowed(HttpRequestMethodNotSupportedException ex,HttpServletRequest request) {
        ResponseEntity<ApiDtos.ErrorResponse> error=response(405,"METHOD_NOT_ALLOWED","El método solicitado no está permitido para este recurso",request);
        HttpHeaders headers=new HttpHeaders();
        headers.putAll(error.getHeaders());
        if(ex.getSupportedHttpMethods()!=null) headers.setAllow(ex.getSupportedHttpMethods());
        return new ResponseEntity<>(error.getBody(),headers,HttpStatus.METHOD_NOT_ALLOWED);
    }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiDtos.ErrorResponse> resourceNotFound(NoResourceFoundException ex,HttpServletRequest request) {
        return response(404,"NOT_FOUND","El recurso solicitado no existe",request);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiDtos.ErrorResponse> unexpected(Exception ex,HttpServletRequest request) {
        // Excepciones estándar de Spring MVC que ya traen un estado de cliente (415, ruta con id vacío, etc.).
        if(ex instanceof org.springframework.web.ErrorResponse standard&&standard.getStatusCode().is4xxClientError()){
            int status=standard.getStatusCode().value();
            return status==415?response(415,"UNSUPPORTED_MEDIA_TYPE","El tipo de contenido no es compatible con este recurso",request)
                :response(status,"INVALID_REQUEST","La solicitud no tiene un formato válido",request);
        }
        String traceId=traceId();log.error("Unexpected API error traceId={} exceptionType={}",traceId,ex.getClass().getSimpleName(),OperationalLogSupport.sanitizedStackTrace(ex));
        return response(500,"INTERNAL_ERROR","Ocurrió un error interno",request,traceId,null);
    }
    private ResponseEntity<ApiDtos.ErrorResponse> response(int status, String code, String message, HttpServletRequest request) {
        return response(status,code,message,request,traceId(),null);
    }
    private ResponseEntity<ApiDtos.ErrorResponse> response(int status,String code,String message,HttpServletRequest request,String traceId,Object details) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(new ApiDtos.ErrorResponse("about:blank",HttpStatus.valueOf(status).getReasonPhrase(),OffsetDateTime.now(),status,
                message,request.getRequestURI(),code,message,request.getRequestURI(),traceId,details));
    }
    private String traceId(){String traceId=MDC.get("traceId");return traceId==null?UUID.randomUUID().toString():traceId;}
}
