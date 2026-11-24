package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.domain.Page;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.math.*;
import java.time.*;
import java.util.*;

@Service
class AuditService {
    private final AuditRepository repository; private final CurrentIdentity identity; private final ObjectMapper json;
    private final TrustedClientAddressResolver clientAddresses;
    private final TransactionTemplate requiresNew;
    AuditService(AuditRepository repository, CurrentIdentity identity, ObjectMapper json, TrustedClientAddressResolver clientAddresses, PlatformTransactionManager transactionManager) { this.repository=repository; this.identity=identity; this.json=json; this.clientAddresses=clientAddresses; this.requiresNew=new TransactionTemplate(transactionManager); this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); }
    void record(String entityType, Object entityId, String action, Object newData) {
        record(entityType,entityId,action,null,newData);
    }
    void record(String entityType, Object entityId, String action, Object previousData, Object newData) {
        recordAsRole(entityType,entityId,action,previousData,newData,identity.role());
    }
    void recordAsRole(String entityType, Object entityId, String action, Object newData, String effectiveRole) {
        recordAsRole(entityType,entityId,action,null,newData,effectiveRole);
    }
    private void recordAsRole(String entityType, Object entityId, String action, Object previousData, Object newData, String effectiveRole) {
        AuditEntry e=new AuditEntry(); e.entityType=entityType; e.entityId=String.valueOf(entityId); e.action=action;
        e.userId=identity.get().userId(); e.userRole=effectiveRole; e.correlationId=MDC.get("traceId"); e.occurredAt=OffsetDateTime.now();
        e.previousData=serialize(previousData); e.newData=serialize(newData);
        repository.save(e);
    }
    void recordSecurityEvent(String action,String actorId,String actorRole,Object data) {
        repository.save(securityEvent(action,actorId,actorRole,data));
    }
    void recordSecurityEventRequiresNew(String action,String actorId,String actorRole,Object data) {
        requiresNew.executeWithoutResult(status -> repository.save(securityEvent(action,actorId,actorRole,data)));
    }
    void recordAccessDenied(jakarta.servlet.http.HttpServletRequest request,String code) {
        Authentication authentication=SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated=authentication!=null&&authentication.isAuthenticated()&&!(authentication instanceof AnonymousAuthenticationToken);
        String actorId=authenticated&&authentication.getPrincipal() instanceof AuthenticatedIdentity actor
            ?actor.integrationActorId()!=null?actor.integrationActorId().toString():"authenticated":"anonymous";
        String actorRole=authenticated?authentication.getAuthorities().stream().map(org.springframework.security.core.GrantedAuthority::getAuthority).sorted().collect(java.util.stream.Collectors.joining(",")):"ANONYMOUS";
        String path=routeFamily(request.getRequestURI());
        recordSecurityEventRequiresNew("ACCESS_DENIED",actorId,actorRole,Map.of("method",request.getMethod(),"path",path,"action",request.getMethod()+" "+path,"clientIp",clientAddresses.resolve(request),"code",code));
    }
    private String routeFamily(String requestUri) {
        String[] segments=requestUri.split("/");
        int included=Math.min(3,segments.length-1);
        return included<=0?"/":"/"+String.join("/",Arrays.copyOfRange(segments,1,included+1));
    }
    private AuditEntry securityEvent(String action,String actorId,String actorRole,Object data) {
        AuditEntry e=new AuditEntry();e.entityType="SecurityEvent";e.entityId=UUID.randomUUID().toString();e.action=action;
        e.userId=actorId==null||actorId.isBlank()?"anonymous":actorId;e.userRole=actorRole==null||actorRole.isBlank()?"ANONYMOUS":actorRole;
        e.correlationId=MDC.get("traceId");e.occurredAt=OffsetDateTime.now();e.newData=serialize(withClientAddress(data));return e;
    }
    private Object withClientAddress(Object data) {
        if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes))return data;
        Map<String,Object> details=new LinkedHashMap<>();
        if(data instanceof Map<?,?> values)values.forEach((key,value)->{if(key instanceof String name)details.put(name,value);});
        else details.put("details",data);
        details.put("clientIp",clientAddresses.resolve(attributes.getRequest()));
        return details;
    }
    void requireDifferentActor(String entityType,Object entityId,String... initiatingActors) {
        String actor=identity.get().userId();
        if(initiatingActors==null||Arrays.stream(initiatingActors).filter(Objects::nonNull).noneMatch(actor::equals))return;
        requiresNew.executeWithoutResult(status->{AuditEntry e=new AuditEntry();e.entityType=entityType;e.entityId=String.valueOf(entityId);e.action="SELF_APPROVAL_FORBIDDEN";e.userId=actor;e.userRole=identity.role();e.correlationId=MDC.get("traceId");e.occurredAt=OffsetDateTime.now();e.newData=serialize(Map.of("initiatingActor",actor));repository.save(e);});
        throw new BusinessException("SELF_APPROVAL_FORBIDDEN","No puede aprobar una operación que solicitó o envió",403);
    }
    private String serialize(Object value) { if(value==null)return null;try{return json.writeValueAsString(value);}catch(JsonProcessingException ignored){return "{}";} }
}
