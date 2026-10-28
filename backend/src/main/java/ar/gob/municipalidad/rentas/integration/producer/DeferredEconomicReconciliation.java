package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Immutable economic fact captured in the transaction that changed the balances. */
record EconomicMovementSnapshot(String eventType,String aggregateType,Long aggregateId,Long debtId,
    Long paymentId,Long allocationId,UUID sourceEventId,String originId,String targetModule,
    String idempotencyKey,UUID eventId,OffsetDateTime occurredAt,JsonNode data){
    boolean sameMovement(EconomicMovementSnapshot other){
        return other!=null&&Objects.equals(eventType,other.eventType)&&Objects.equals(aggregateType,other.aggregateType)
            &&Objects.equals(aggregateId,other.aggregateId)&&Objects.equals(debtId,other.debtId)
            &&Objects.equals(paymentId,other.paymentId)&&Objects.equals(allocationId,other.allocationId)
            &&Objects.equals(sourceEventId,other.sourceEventId)&&Objects.equals(originId,other.originId)
            &&Objects.equals(targetModule,other.targetModule)&&Objects.equals(idempotencyKey,other.idempotencyKey)
            &&Objects.equals(data,other.data);
    }
}

@Service
class DeferredEconomicReconciliation {
    private static final List<String> TYPES=List.of("paymentRegistered","debtSettled","paymentReversed");
    private final IntegrationEventLogRepository logs;private final OutboxRepository outbox;
    private final PaymentRepository payments;private final PaymentAllocationRepository allocations;private final DebtRepository debts;
    private final PaymentPlanPrincipalLedger principalLedger;private final ConfirmedIntegrationOutbox producer;
    private final AuditService audit;private final ObjectMapper json;

    DeferredEconomicReconciliation(IntegrationEventLogRepository logs,OutboxRepository outbox,
        PaymentRepository payments,PaymentAllocationRepository allocations,DebtRepository debts,
        PaymentPlanPrincipalLedger principalLedger,ConfirmedIntegrationOutbox producer,AuditService audit,ObjectMapper json){
        this.logs=logs;this.outbox=outbox;this.payments=payments;this.allocations=allocations;
        this.debts=debts;this.principalLedger=principalLedger;this.producer=producer;this.audit=audit;this.json=json;
    }

    @Transactional(readOnly=true) Page<Result> pending(Pageable pageable){
        return logs.findByDirectionAndStatusAndEventTypeInAndBusinessKeyStartingWith(EventDirection.OUTBOUND,
            IntegrationEventStatus.PENDING,TYPES,"outbound-deferred:",pageable).map(log->result(log,"DEFERRED",null));
    }

    @Transactional Result reconcile(Long logId){
        IntegrationEventLog log=logs.findByIdForUpdate(logId).orElseThrow(()->CatalogService.notFound("Aplazamiento económico"));
        if(log.direction!=EventDirection.OUTBOUND||!TYPES.contains(log.eventType)
            ||log.businessKey==null||!log.businessKey.startsWith("outbound-deferred:"))
            throw CatalogService.notFound("Aplazamiento económico");
        if(log.status==IntegrationEventStatus.PROCESSED)return result(log,"ALREADY_PREPARED",preparedFromLog(log));
        if(log.status!=IntegrationEventStatus.PENDING)return result(log,"NOT_PENDING",null);
        log.retryCount++;log.lastRetryAt=OffsetDateTime.now();
        EconomicMovementSnapshot snapshot;
        try{
            JsonNode root=read(log.payload);
            if(!"DEFERRED".equals(root.path("phase").asText())||root.path("snapshot").isMissingNode())
                return review(log,"HISTORICAL_MOVEMENT_SNAPSHOT_UNAVAILABLE");
            snapshot=json.treeToValue(root.path("snapshot"),EconomicMovementSnapshot.class);
        }catch(Exception ex){return review(log,"HISTORICAL_MOVEMENT_SNAPSHOT_UNAVAILABLE");}
        if(snapshot!=null&&legacyIdentifiers(snapshot.data()))return review(log,"LEGACY_ECONOMIC_IDS_REVIEW_REQUIRED");
        if(snapshot==null||!validSnapshot(log,snapshot))return review(log,"ECONOMIC_SNAPSHOT_REVIEW_REQUIRED");
        Debt debt=debts.findById(snapshot.debtId()).orElse(null);
        if(debt==null||debt.integrationId==null
            ||!debt.integrationId.toString().equals(snapshot.data().path("debtId").asText()))
            return review(log,"ECONOMIC_ORIGIN_REVIEW_REQUIRED");
        ConfirmedIntegrationOutbox.ExternalDebtOrigin origin;
        try{origin=producer.externalOrigin(debt).orElse(null);}
        catch(RuntimeException ex){return review(log,"ECONOMIC_ORIGIN_REVIEW_REQUIRED");}
        if(origin==null||!origin.targetModule().equals(snapshot.targetModule())
            ||!origin.sourceEventId().equals(snapshot.sourceEventId())
            ||!origin.externalReferenceId().equals(snapshot.originId())
            ||!("M7".equals(origin.targetModule())?"TRAFFIC_FINE":origin.externalType().name())
                .equals(snapshot.data().path("originType").asText())
            ||!validAllocation(snapshot))return review(log,"ECONOMIC_ORIGIN_REVIEW_REQUIRED");
        if(snapshot.allocationId()!=null&&!"paymentRegistered".equals(snapshot.eventType())
            &&!outbox.existsByIdempotencyKey("outbound:paymentRegistered:PaymentAllocation:"
                +snapshot.allocationId()+":Debt:"+snapshot.debtId()))
            return review(log,"PAYMENT_EVENT_NOT_PREPARED");
        Optional<String> correlation=producer.economicCorrelation(origin,debt.id);
        if(correlation.isEmpty()){
            log.errorMessage=origin.targetModule()+"_ORIGINAL_CORRELATION_ID_UNAVAILABLE";
            Result result=result(log,"STILL_DEFERRED",null);
            audit.record("IntegrationEventLog",log.id,"DEFERRED_ECONOMIC_RECONCILIATION_CHECKED",result);
            return result;
        }
        Optional<OutboxEvent> existing=outbox.findByIdempotencyKey(snapshot.idempotencyKey());
        OutboxEvent prepared;
        if(existing.isPresent()){
            prepared=existing.orElseThrow();
            if(!prepared.id.equals(snapshot.eventId())||!matchesPrepared(prepared,snapshot,correlation.orElseThrow()))
                return review(log,"EXISTING_OUTBOX_CONTRACT_REVIEW_REQUIRED");
        }else prepared=producer.prepareDeferredEconomic(snapshot,correlation.orElseThrow());
        log.status=IntegrationEventStatus.PROCESSED;log.processedAt=OffsetDateTime.now();log.errorMessage=null;
        log.payload=write(Map.of("phase","OUTBOX_PREPARED","snapshot",snapshot,
            "outboxEventId",prepared.id.toString(),"deliveryConfirmed",false));
        Result result=result(log,"PREPARED_IN_OUTBOX",prepared);
        audit.record("IntegrationEventLog",log.id,"DEFERRED_ECONOMIC_RECONCILED",result);
        return result;
    }

    private boolean validSnapshot(IntegrationEventLog log,EconomicMovementSnapshot snapshot){
        if(!log.eventType.equals(snapshot.eventType())||!TYPES.contains(snapshot.eventType())
            ||snapshot.aggregateId()==null||snapshot.debtId()==null||snapshot.sourceEventId()==null
            ||snapshot.originId()==null||snapshot.originId().isBlank()||snapshot.eventId()==null
            ||snapshot.eventId().version()!=4||snapshot.occurredAt()==null||snapshot.data()==null
            ||!snapshot.data().isObject()||!Set.of("M4","M7").contains(snapshot.targetModule()))return false;
        String aggregate=snapshot.eventType().equals("debtSettled")?"Debt":"PaymentAllocation";
        if(!aggregate.equals(snapshot.aggregateType())||!snapshot.aggregateId().equals(
            snapshot.eventType().equals("debtSettled")?snapshot.debtId():snapshot.allocationId()))return false;
        if((snapshot.paymentId()==null)!=(snapshot.allocationId()==null))return false;
        String expectedMarker="outbound-deferred:"+snapshot.eventType()+":"+aggregate+":"+snapshot.aggregateId()+":Debt:"+snapshot.debtId();
        String expectedKey=snapshot.eventType().equals("debtSettled")&&snapshot.allocationId()==null
            ?"outbound:debtSettled:Debt:"+snapshot.debtId()
            :"outbound:"+snapshot.eventType()+":PaymentAllocation:"+snapshot.allocationId()+":Debt:"+snapshot.debtId();
        if(!expectedMarker.equals(log.businessKey)||!expectedKey.equals(snapshot.idempotencyKey()))return false;
        JsonNode data=snapshot.data();
        if(!canonicalUuid(data.path("debtId").asText())
            ||!snapshot.originId().equals(data.path("originId").asText())
            ||!validPayloadShape(snapshot))return false;
        if("M7".equals(snapshot.targetModule())){
            if(!"TRAFFIC_FINE".equals(data.path("originType").asText())
                ||!snapshot.originId().equals(data.path("infractionId").asText()))return false;
            try{UUID.fromString(snapshot.originId());}catch(IllegalArgumentException ex){return false;}
        }else if(!Set.of("PERMIT_FEE","COMMERCIAL_FINE").contains(data.path("originType").asText())
            ||data.has("infractionId"))return false;
        if(snapshot.eventType().equals("debtSettled")){
            return data.path("settledAt").isTextual()&&money(data,"remainingBalance",false)
                &&money(data,"outstandingBalance",false)
                &&data.path("remainingBalance").decimalValue().signum()==0
                &&data.path("outstandingBalance").decimalValue().signum()==0;
        }
        if(snapshot.paymentId()==null||snapshot.allocationId()==null
            ||!canonicalUuid(data.path("paymentId").asText()))return false;
        return snapshot.eventType().equals("paymentRegistered")
            ?money(data,"amountApplied",true)&&money(data,"remainingBalance",false)&&data.path("paidAt").isTextual()
            :money(data,"reversedAmount",true)&&money(data,"remainingBalance",false)
                &&data.path("reversedAt").isTextual()&&!data.path("reason").asText("").isBlank();
    }

    private boolean validPayloadShape(EconomicMovementSnapshot snapshot){
        try{
            JsonNode data=snapshot.data();Set<String> fields=new java.util.HashSet<>();
            data.fieldNames().forEachRemaining(fields::add);
            Set<String> expected=switch(snapshot.eventType()){
                case "paymentRegistered"->Set.of("paymentId","debtId","originType","originId",
                    "amountApplied","paidAt","remainingBalance");
                case "paymentReversed"->Set.of("paymentId","debtId","originType","originId",
                    "reversedAmount","reversedAt","remainingBalance","reason");
                case "debtSettled"->Set.of("debtId","conceptCode","originType","originId",
                    "settledAt","remainingBalance","outstandingBalance");
                default->Set.of();
            };
            if("M7".equals(snapshot.targetModule())){
                fields.remove("infractionId");
                if(!data.has("infractionId"))return false;
            }
            if("debtSettled".equals(snapshot.eventType())){
                if(data.has("citizenId")){
                    if(!data.path("citizenId").isTextual())return false;
                    fields.remove("citizenId");
                }
                if(!data.path("conceptCode").isTextual()||data.path("conceptCode").asText().isBlank())return false;
            }
            if(!fields.equals(expected))return false;
            String dateField=switch(snapshot.eventType()){
                case "paymentRegistered"->"paidAt";
                case "paymentReversed"->"reversedAt";
                default->"settledAt";
            };
            OffsetDateTime.parse(data.path(dateField).asText());
            return true;
        }catch(Exception ex){return false;}
    }

    private boolean validAllocation(EconomicMovementSnapshot snapshot){
        if(snapshot.allocationId()==null)return snapshot.eventType().equals("debtSettled")&&snapshot.paymentId()==null;
        PaymentAllocation allocation=allocations.findById(snapshot.allocationId()).orElse(null);
        Payment payment=payments.findById(snapshot.paymentId()).orElse(null);
        if(allocation==null||payment==null||payment.integrationId==null
            ||(!"debtSettled".equals(snapshot.eventType())
                &&!payment.integrationId.toString().equals(snapshot.data().path("paymentId").asText()))
            ||!snapshot.paymentId().equals(allocation.paymentId))return false;
        if(allocation.targetType==AllocationTargetType.DEBT)return snapshot.debtId().equals(allocation.debtId)
            &&(snapshot.eventType().equals("debtSettled")||movementAmount(snapshot).compareTo(allocation.amount)==0);
        if(allocation.targetType!=AllocationTargetType.INSTALLMENT)return false;
        try{return principalLedger.find(allocation).stream().flatMap(List::stream)
            .anyMatch(share->snapshot.debtId().equals(share.debtId())
                &&(snapshot.eventType().equals("debtSettled")||movementAmount(snapshot).compareTo(share.amount())==0));}
        catch(RuntimeException ex){return false;}
    }
    private BigDecimal movementAmount(EconomicMovementSnapshot snapshot){return snapshot.data().path(
        snapshot.eventType().equals("paymentRegistered")?"amountApplied":"reversedAmount").decimalValue();}
    private boolean canonicalUuid(String value){
        try{return UUID.fromString(value).toString().equals(value);}
        catch(Exception ex){return false;}
    }
    private boolean legacyIdentifiers(JsonNode data){
        return data!=null&&(data.path("debtId").asText("").startsWith("debt-")
            ||data.path("paymentId").asText("").startsWith("payment-"));
    }
    private boolean money(JsonNode data,String field,boolean positive){JsonNode amount=data.path(field);return amount.isNumber()
        &&(positive?amount.decimalValue().signum()>0:amount.decimalValue().signum()>=0);}
    private boolean matchesPrepared(OutboxEvent event,EconomicMovementSnapshot snapshot,String correlation){
        try{JsonNode payload=read(event.payload);return "CORE".equals(event.targetModule)
            &&snapshot.eventType().equals(payload.path("eventType").asText())
            &&snapshot.eventId().toString().equals(payload.path("eventId").asText())
            &&correlation.equals(payload.path("correlationId").asText())
            &&snapshot.data().equals(payload.path("data"));}
        catch(Exception ex){return false;}
    }
    private OutboxEvent preparedFromLog(IntegrationEventLog log){
        try{return outbox.findById(UUID.fromString(read(log.payload).path("outboxEventId").asText())).orElse(null);}
        catch(Exception ex){return null;}
    }
    private JsonNode read(String payload)throws Exception{
        return json.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(payload);
    }
    private Result review(IntegrationEventLog log,String reason){
        log.errorMessage=reason;Result result=result(log,"REVIEW_REQUIRED",null);
        audit.record("IntegrationEventLog",log.id,"DEFERRED_ECONOMIC_REVIEW_REQUIRED",result);return result;
    }
    private Result result(IntegrationEventLog log,String outcome,OutboxEvent event){
        return new Result(log.id,log.eventType,log.status.name(),log.errorMessage,outcome,
            event==null?null:event.id,event==null?null:event.status.name(),"NOT_CONFIRMED_BY_CORE");
    }
    private String write(Object value){try{return json.writeValueAsString(value);}
        catch(Exception ex){throw new BusinessException("INVALID_EVENT_PAYLOAD","No se pudo serializar la conciliación",422);}}

    record Result(Long deferralId,String eventType,String deferralStatus,String reason,String outcome,
        UUID outboxEventId,String outboxStatus,String deliveryStatus) {}
}

@RestController
@RequestMapping("/api/v1/integrations/deferred/economic")
class DeferredEconomicReconciliationController {
    private final DeferredEconomicReconciliation reconciliation;
    private final ar.gob.municipalidad.rentas.IdempotencyExecutor idempotency;
    DeferredEconomicReconciliationController(DeferredEconomicReconciliation reconciliation,ar.gob.municipalidad.rentas.IdempotencyExecutor idempotency){this.reconciliation=reconciliation;this.idempotency=idempotency;}

    @GetMapping @PreAuthorize("hasAnyRole('SUPERVISOR','TECHNICAL')")
    Page<DeferredEconomicReconciliation.Result> pending(Pageable pageable){return reconciliation.pending(pageable);}

    @PostMapping("/{deferralId}/reconcile") @PreAuthorize("hasAnyRole('SUPERVISOR','TECHNICAL')")
    DeferredEconomicReconciliation.Result reconcile(@PathVariable Long deferralId,@org.springframework.web.bind.annotation.RequestHeader(name="Idempotency-Key",required=false) String key){return idempotency.execute("POST:/integrations/deferred/economic/{deferralId}/reconcile:"+deferralId,key,null,DeferredEconomicReconciliation.Result.class,()->reconciliation.reconcile(deferralId));}
}
