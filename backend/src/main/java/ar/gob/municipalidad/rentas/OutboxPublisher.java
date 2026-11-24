package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
class OutboxPublisher {
    private static final Logger log=LoggerFactory.getLogger(OutboxPublisher.class);
    private static final ObjectMapper payloadJson=new ObjectMapper();
    private final OutboxRepository repository; private final EventPublisher publisher;
    private final boolean rabbitMode,coreHttpMode;
    private final Set<String> coreHttpAllowedEventTypes;
    private final Set<UUID> coreHttpAllowedEventIds;
    private final TransactionTemplate transactions;
    private final EntityManager entityManager;
    @org.springframework.beans.factory.annotation.Autowired
    OutboxPublisher(OutboxRepository repository,EventPublisher publisher,
        @org.springframework.beans.factory.annotation.Value("${BROKER_ADAPTER:local-log}") String brokerAdapter,
        @org.springframework.beans.factory.annotation.Value("${rentas.core.http.allowed-event-types:}") String allowedEventTypes,
        @org.springframework.beans.factory.annotation.Value("${rentas.core.http.allowed-event-ids:}") String allowedEventIds,
        ObjectProvider<PlatformTransactionManager> transactionManager,ObjectProvider<EntityManagerFactory> entityManagerFactory){
        this(repository,publisher,brokerAdapter,allowedEventTypes,allowedEventIds,transactionManager.getIfAvailable(),sharedEntityManager(entityManagerFactory.getIfAvailable()));
    }
    OutboxPublisher(OutboxRepository repository,EventPublisher publisher,String brokerAdapter,
        String allowedEventTypes,String allowedEventIds){this(repository,publisher,brokerAdapter,allowedEventTypes,allowedEventIds,(PlatformTransactionManager)null);}
    OutboxPublisher(OutboxRepository repository,EventPublisher publisher,String brokerAdapter,
        String allowedEventTypes,String allowedEventIds,PlatformTransactionManager transactionManager){this(repository,publisher,brokerAdapter,allowedEventTypes,allowedEventIds,transactionManager,null);}
    OutboxPublisher(OutboxRepository repository,EventPublisher publisher,String brokerAdapter,
        String allowedEventTypes,String allowedEventIds,PlatformTransactionManager transactionManager,EntityManager entityManager){
        this.repository=repository;this.publisher=publisher;this.rabbitMode="rabbitmq".equalsIgnoreCase(brokerAdapter);this.coreHttpMode="core-http".equalsIgnoreCase(brokerAdapter);
        this.coreHttpAllowedEventTypes=csv(allowedEventTypes);
        this.coreHttpAllowedEventIds=csvUuids(allowedEventIds);
        this.transactions=transactionManager==null?null:new TransactionTemplate(transactionManager);
        this.entityManager=entityManager;
        if(coreHttpMode&&(coreHttpAllowedEventTypes.isEmpty()||coreHttpAllowedEventIds.isEmpty()))
            throw new IllegalStateException("CORE_HTTP_EVENT_ALLOWLIST_REQUIRED");
    }
    private static EntityManager sharedEntityManager(EntityManagerFactory factory){return factory==null?null:SharedEntityManagerCreator.createSharedEntityManager(factory);}
    OutboxPublisher(OutboxRepository repository,EventPublisher publisher){this(repository,publisher,"local-log","","",(PlatformTransactionManager)null);}
    OutboxPublisher(OutboxRepository repository,EventPublisher publisher,String brokerAdapter){this(repository,publisher,brokerAdapter,"","",(PlatformTransactionManager)null);}
    @Scheduled(fixedDelayString="${rentas.outbox-delay-ms:5000}")
    void publishPending(){
        if(transactions==null){publishEvents(findCandidates());return;}
        if(!rabbitMode&&!coreHttpMode){
            transactions.executeWithoutResult(status->publishEvents(findCandidates()));
            return;
        }
        List<UUID> candidateIds=transactions.execute(status->findCandidates().stream().map(event->event.id).toList());
        if(candidateIds==null)return;
        for(UUID candidateId:candidateIds){
            transactions.executeWithoutResult(status->lockCandidate(candidateId)
                .filter(this::stillPublishable)
                .filter(event->!coreHttpMode||authorizedCoreHttpEvent(event))
                .ifPresent(event->publishEvents(List.of(event))));
        }
    }
    @SuppressWarnings("unchecked")
    private java.util.Optional<OutboxEvent> lockCandidate(UUID id){
        if(entityManager==null)throw new IllegalStateException("OUTBOX_ENTITY_MANAGER_REQUIRED");
        List<OutboxEvent> locked=entityManager.createNativeQuery("select * from outbox_event where id=:id for update skip locked",OutboxEvent.class)
            .setParameter("id",id).getResultList();
        return locked.stream().findFirst();
    }
    private List<OutboxEvent> findCandidates(){
        var page=org.springframework.data.domain.PageRequest.of(0,coreHttpMode?1:50);
        return coreHttpMode?repository.findPublishableForCoreHttp(coreHttpAllowedEventTypes,coreHttpAllowedEventIds,page)
            :repository.findPublishable(page);
    }
    private boolean stillPublishable(OutboxEvent event){
        if(event.status==OutboxStatus.PENDING||(coreHttpMode&&event.status==OutboxStatus.LOCAL_ONLY))return true;
        if(event.status!=OutboxStatus.FAILED)return false;
        long delaySeconds=event.retryCount<=1?5:event.retryCount==2?10:event.retryCount==3?20:40;
        return event.lastAttemptAt==null||!event.lastAttemptAt.isAfter(OffsetDateTime.now().minusSeconds(delaySeconds));
    }
    private void publishEvents(List<OutboxEvent> publishable){
        for(OutboxEvent event:publishable){
        if(coreHttpMode&&!authorizedCoreHttpEvent(event))continue;
        if("CORE".equals(event.targetModule)&&"overdueDebt".equals(event.eventType)){
            event.status=OutboxStatus.DEAD_LETTER;
            event.errorMessage="M8_V3_EVENT_NAME_REVIEW_REQUIRED: use debtOverdue";
            continue;
        }
        if(!validEconomicIdentifiers(event)){
            event.status=OutboxStatus.DEAD_LETTER;
            event.errorMessage="ECONOMIC_CONTRACT_REVIEW_REQUIRED: invalid M5 UUID identifiers";
            continue;
        }
        if(!validTrafficContract(event)){
            event.status=OutboxStatus.DEAD_LETTER;
            event.errorMessage="M7_CONTRACT_REVIEW_REQUIRED: invalid traffic origin or economic fact";
            continue;
        }
        if(!validM4Contract(event)){
            event.status=OutboxStatus.DEAD_LETTER;
            event.errorMessage="M4_CONTRACT_REVIEW_REQUIRED: invalid M4 origin or unapproved citizenId variant";
            continue;
        }
        if(!validExemptionIdentifiers(event)){
            event.status=OutboxStatus.DEAD_LETTER;
            event.errorMessage="M8_CONTRACT_REVIEW_REQUIRED: invalid exemption UUID identifiers";
            continue;
        }
        if(!validPaymentPlanIdentifiers(event)){
            event.status=OutboxStatus.DEAD_LETTER;
            event.errorMessage="M1_PLAN_CONTRACT_REVIEW_REQUIRED: invalid payment plan UUID payload";
            continue;
        }
        List<OutboxStatus> accepted=rabbitMode?List.of(OutboxStatus.BROKER_CONFIRMED):coreHttpMode?List.of(OutboxStatus.CORE_ACCEPTED):List.of(OutboxStatus.PUBLISHED,OutboxStatus.LOCAL_ONLY,OutboxStatus.BROKER_CONFIRMED);
        if("updateExemptionStatus".equals(event.eventType)
            &&!repository.existsByEventTypeAndAggregateTypeAndAggregateIdAndStatusIn("exemptionRequested",event.aggregateType,event.aggregateId,accepted))continue;
        if("updatePaymentPlanStatus".equals(event.eventType)
            &&!repository.existsByEventTypeAndAggregateTypeAndAggregateIdAndStatusIn("paymentPlanRequested",event.aggregateType,event.aggregateId,accepted))continue;
        try{
            event.lastAttemptAt=OffsetDateTime.now();
            OutboxStatus result;
            if("PLATFORM".equals(event.targetModule)){
                log.info("INTERNAL_OUTBOX eventType={} targetModule={}",event.eventType,event.targetModule);
                result=OutboxStatus.LOCAL_ONLY;
            }else result=publisher.publish(event.eventType,event.targetModule,event.payload);
            event.status=result==OutboxStatus.BROKER_CONFIRMED||result==OutboxStatus.CORE_ACCEPTED?result:OutboxStatus.LOCAL_ONLY;
            event.publishedAt=event.status==OutboxStatus.BROKER_CONFIRMED||event.status==OutboxStatus.CORE_ACCEPTED?OffsetDateTime.now():null;
            event.errorMessage=null;
        }catch(CoreHttpPublishException ex){
            event.retryCount++;
            event.errorMessage=ex.code();
            event.status=ex.retryable()&&event.retryCount<5?OutboxStatus.FAILED:OutboxStatus.DEAD_LETTER;
            log.warn("OUTBOX Core HTTP attempt outboxId={} eventId={} eventType={} status={} failureCode={}",event.id,
                OperationalLogSupport.uuidField(event.payload,"eventId"),event.eventType,event.status,ex.code());
        }catch(RuntimeException ex){
            event.retryCount++;
            event.errorMessage="CORE_BROKER_PUBLISH_FAILED";
            event.status=event.retryCount>=5?OutboxStatus.DEAD_LETTER:OutboxStatus.FAILED;
            log.warn("OUTBOX publish failed outboxId={} eventId={} correlationId={} eventType={} targetModule={} exceptionType={}",
                event.id,OperationalLogSupport.uuidField(event.payload,"eventId"),
                OperationalLogSupport.uuidField(event.payload,"correlationId"),event.eventType,event.targetModule,
                ex.getClass().getSimpleName(),OperationalLogSupport.sanitizedStackTrace(ex));
        }
    }}
    private boolean authorizedCoreHttpEvent(OutboxEvent event){
        if(!"CORE".equals(event.targetModule)||!coreHttpAllowedEventTypes.contains(event.eventType)
            ||!coreHttpAllowedEventIds.contains(event.id))return false;
        try{return event.id.toString().equals(payloadJson.readTree(event.payload).path("eventId").asText());}
        catch(Exception ex){return false;}
    }
    private static Set<String> csv(String value){
        if(value==null||value.isBlank())return Set.of();
        return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(part->!part.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    private static Set<UUID> csvUuids(String value){
        if(value==null||value.isBlank())return Set.of();
        try{return csv(value).stream().map(UUID::fromString).collect(java.util.stream.Collectors.toUnmodifiableSet());}
        catch(IllegalArgumentException ex){throw new IllegalStateException("CORE_HTTP_EVENT_ALLOWLIST_INVALID");}
    }
    private boolean validEconomicIdentifiers(OutboxEvent event){
        if(!"CORE".equals(event.targetModule)
            ||!java.util.Set.of("paymentRegistered","paymentReversed","debtSettled").contains(event.eventType))return true;
        try{
            var data=payloadJson.readTree(event.payload).path("data");
            java.util.UUID.fromString(data.path("debtId").asText());
            if(!"debtSettled".equals(event.eventType))java.util.UUID.fromString(data.path("paymentId").asText());
            if(data.hasNonNull("citizenId"))java.util.UUID.fromString(data.path("citizenId").asText());
            return true;
        }catch(Exception ex){return false;}
    }
    private boolean validTrafficContract(OutboxEvent event){
        if(!"CORE".equals(event.targetModule)
            ||!java.util.Set.of("paymentRegistered","paymentReversed","debtSettled").contains(event.eventType))return true;
        try{
            var envelope=payloadJson.readTree(event.payload);
            var data=envelope.path("data");
            if(!"TRAFFIC_FINE".equals(data.path("originType").asText()))return true;
            String originId=data.path("originId").asText();
            if(!java.util.UUID.fromString(originId).toString().equals(originId)
                ||!originId.equals(data.path("infractionId").asText())
                ||!event.id.toString().equals(envelope.path("eventId").asText())
                ||!event.eventType.equals(envelope.path("eventType").asText())
                ||!"rentas".equals(envelope.path("sourceModule").asText())
                ||!"1.0".equals(envelope.path("eventVersion").asText()))return false;
            String correlation=envelope.path("correlationId").asText();
            if(!java.util.UUID.fromString(correlation).toString().equals(correlation)
                ||(envelope.hasNonNull("causationId")
                    &&!java.util.UUID.fromString(envelope.path("causationId").asText()).toString()
                        .equals(envelope.path("causationId").asText())))return false;
            if("debtSettled".equals(event.eventType))return "TRAFFIC_INFRACTION".equals(data.path("conceptCode").asText())
                &&data.path("remainingBalance").isNumber()&&data.path("remainingBalance").decimalValue().signum()==0
                &&data.path("outstandingBalance").isNumber()&&data.path("outstandingBalance").decimalValue().signum()==0
                &&java.time.OffsetDateTime.parse(data.path("settledAt").asText())!=null;
            String amount="paymentRegistered".equals(event.eventType)?"amountApplied":"reversedAmount";
            String at="paymentRegistered".equals(event.eventType)?"paidAt":"reversedAt";
            return data.path(amount).isNumber()&&data.path(amount).decimalValue().signum()>0
                &&data.path("remainingBalance").isNumber()&&data.path("remainingBalance").decimalValue().signum()>=0
                &&java.time.OffsetDateTime.parse(data.path(at).asText())!=null
                &&("paymentRegistered".equals(event.eventType)||!data.path("reason").asText("").isBlank());
        }catch(Exception ex){return false;}
    }
    private boolean validM4Contract(OutboxEvent event){
        if(!"CORE".equals(event.targetModule)
            ||!java.util.Set.of("paymentRegistered","paymentReversed","debtSettled").contains(event.eventType))return true;
        try{
            var envelope=payloadJson.readTree(event.payload);
            var data=envelope.path("data");
            String originType=data.path("originType").asText();
            if(!java.util.Set.of("PERMIT_FEE","COMMERCIAL_FINE").contains(originType))return true;
            String originId=data.path("originId").asText();
            if(!java.util.UUID.fromString(originId).toString().equals(originId)||data.has("infractionId")
                ||!event.id.toString().equals(envelope.path("eventId").asText())
                ||!event.eventType.equals(envelope.path("eventType").asText())
                ||!"1.0".equals(envelope.path("eventVersion").asText())
                ||!"rentas".equals(envelope.path("sourceModule").asText()))return false;
            String correlation=envelope.path("correlationId").asText();
            if(!java.util.UUID.fromString(correlation).toString().equals(correlation))return false;
            if(envelope.hasNonNull("causationId"))java.util.UUID.fromString(envelope.path("causationId").asText());
            if("debtSettled".equals(event.eventType))return data.hasNonNull("citizenId")
                &&!data.path("conceptCode").asText("").isBlank()
                &&data.path("remainingBalance").isNumber()&&data.path("remainingBalance").decimalValue().signum()==0
                &&data.path("outstandingBalance").isNumber()&&data.path("outstandingBalance").decimalValue().signum()==0
                &&java.time.OffsetDateTime.parse(data.path("settledAt").asText())!=null;
            String amount="paymentRegistered".equals(event.eventType)?"amountApplied":"reversedAmount";
            String at="paymentRegistered".equals(event.eventType)?"paidAt":"reversedAt";
            return data.path(amount).isNumber()&&data.path(amount).decimalValue().signum()>0
                &&data.path("remainingBalance").isNumber()&&data.path("remainingBalance").decimalValue().signum()>=0
                &&java.time.OffsetDateTime.parse(data.path(at).asText())!=null
                &&("paymentRegistered".equals(event.eventType)||!data.path("reason").asText("").isBlank());
        }catch(Exception ex){return false;}
    }
    private boolean validExemptionIdentifiers(OutboxEvent event){
        if(!"exemptionRequested".equals(event.eventType)&&!"updateExemptionStatus".equals(event.eventType))return true;
        try{
            var data=payloadJson.readTree(event.payload).path("data");
            java.util.UUID.fromString(data.path("requestId").asText());
            java.util.UUID.fromString(data.path("citizenId").asText());
            if("updateExemptionStatus".equals(event.eventType)){
                String status=data.path("status").asText();
                if("APPROVED".equals(status))java.util.UUID.fromString(data.path("exemptionId").asText());
                else if(!"REJECTED".equals(status))return false;
            }
            return true;
        }catch(Exception ex){return false;}
    }
    private boolean validPaymentPlanIdentifiers(OutboxEvent event){
        if(!"CORE".equals(event.targetModule)
            ||!("paymentPlanRequested".equals(event.eventType)||"updatePaymentPlanStatus".equals(event.eventType)))return true;
        try{
            var data=payloadJson.readTree(event.payload).path("data");
            java.util.UUID.fromString(data.path("requestId").asText());
            java.util.UUID.fromString(data.path("citizenId").asText());
            if("paymentPlanRequested".equals(event.eventType)){
                var debtIds=data.path("debtIds");
                if(!debtIds.isArray()||debtIds.isEmpty()||data.path("totalDebt").decimalValue().signum()<=0||data.path("installments").intValue()<=0)return false;
                for(var debtId:debtIds)java.util.UUID.fromString(debtId.asText());
            }else{
                String status=data.path("status").asText();
                if("GRANTED".equals(status)){
                    java.util.UUID.fromString(data.path("planId").asText());
                    if(data.path("installments").intValue()<=0||data.path("totalAmount").decimalValue().signum()<=0||data.hasNonNull("reason"))return false;
                }else if("REJECTED".equals(status)){
                    if(data.hasNonNull("planId")||data.hasNonNull("installments")||data.hasNonNull("totalAmount")||data.path("reason").asText().isBlank())return false;
                }else return false;
            }
            return true;
        }catch(Exception ex){return false;}
    }
}
