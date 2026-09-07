package ar.gob.municipalidad.rentas;

import jakarta.persistence.criteria.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
class FilteredQueryService {
    private static final Set<String> TRANSPORT=Set.of("page","size","sort");
    private static final Map<Class<?>,Definition> DEFINITIONS=Map.ofEntries(
        entry(TaxpayerReference.class,fields("taxpayerType","externalId","dni","cuit","displayName","externalStatus","createdAt","updatedAt"),aliases("type","taxpayerType","status","externalStatus"),"createdAt",List.of("displayName","externalId","dni","cuit")),
        entry(TaxConcept.class,fields("code","name","type","originModule","active","createdAt","updatedAt"),aliases(),"createdAt",List.of("code","name")),
        entry(TaxConfiguration.class,fields("taxConceptId","version","calculationType","status","validFrom","validUntil","createdAt"),aliases("conceptId","taxConceptId"),"createdAt",List.of()),
        entry(Liquidation.class,fields("taxpayerId","taxConceptId","period","status","dueDate","issuedAt"),aliases("conceptId","taxConceptId"),"issuedAt",List.of()),
        entry(LiquidationRun.class,fields("taxConceptId","period","status","createdAt"),aliases("conceptId","taxConceptId"),"createdAt",List.of()),
        entry(AdjustmentRequest.class,fields("debtId","type","status","requestedAt","resolvedAt"),aliases(),"requestedAt",List.of()),
        entry(Debt.class,fields("taxpayerId","taxConceptId","originType","status","dueDate","createdAt","updatedAt"),aliases("conceptId","taxConceptId"),"createdAt",List.of()),
        entry(Bill.class,fields("number","taxpayerId","status","issueDate","dueDate","createdAt"),aliases(),"createdAt",List.of("number")),
        entry(Payment.class,fields("taxpayerId","billId","paymentMethod","status","allocationStatus","origin","paidAt","createdAt"),aliases("method","paymentMethod"),"paidAt",List.of("receiptNumber")),
        entry(PaymentAllocation.class,fields("paymentId","targetType","debtId","installmentId","status","allocatedAt"),aliases(),"allocatedAt",List.of()),
        entry(CreditBalance.class,fields("taxpayerId","sourcePaymentId","status","createdAt","updatedAt"),aliases(),"createdAt",List.of()),
        entry(PaymentReversalRequest.class,fields("paymentId","status","requestedBy","requestedAt","resolvedAt","executedAt"),aliases(),"requestedAt",List.of()),
        entry(PaymentPlanConfiguration.class,fields("version","active","validFrom","validUntil","createdAt"),aliases(),"createdAt",List.of()),
        entry(PaymentPlanRequest.class,fields("taxpayerId","status","exceptional","requestedAt","resolvedAt"),aliases(),"requestedAt",List.of()),
        entry(PaymentPlan.class,fields("taxpayerId","requestId","status","grantedAt","completedAt","expiredAt"),aliases(),"grantedAt",List.of()),
        entry(PlanExpirationRequest.class,fields("paymentPlanId","status","requestedAt","resolvedAt"),aliases("planId","paymentPlanId"),"requestedAt",List.of()),
        entry(RefinancingRequest.class,fields("originalPlanId","taxpayerId","status","exceptional","requestedAt","resolvedAt"),aliases("planId","originalPlanId"),"requestedAt",List.of()),
        entry(ExemptionRequest.class,fields("taxpayerId","taxConceptId","status","requestedAt","resolvedAt"),aliases("conceptId","taxConceptId"),"requestedAt",List.of()),
        entry(Exemption.class,fields("taxpayerId","taxConceptId","status","validFrom","validUntil","approvedAt"),aliases("conceptId","taxConceptId"),"approvedAt",List.of()),
        entry(TicketCase.class,fields("taxpayerId","category","priority","status","assignedTo","createdAt","updatedAt"),aliases(),"createdAt",List.of("externalTicketId","category","description")),
        entry(SocialBenefitReference.class,fields("taxpayerId","benefitType","externalStatus","validFrom","validUntil","updatedAt"),aliases("status","externalStatus"),"updatedAt",List.of("externalBenefitId","externalCitizenId","benefitType")),
        entry(ExternalObligation.class,fields("sourceModule","externalType","externalReferenceId","taxpayerId","taxConceptId","status","dueDate","receivedAt","processedAt"),aliases("conceptId","taxConceptId"),"receivedAt",List.of("externalReferenceId")),
        entry(AuditEntry.class,fields("entityType","entityId","action","userId","userRole","occurredAt"),aliases(),"occurredAt",List.of()),
        entry(IntegrationEventLog.class,fields("eventId","eventType","sourceModule","targetModule","direction","status","occurredAt","receivedAt","processedAt","lastRetryAt"),aliases(),"occurredAt",List.of("eventType","sourceModule","targetModule"))
    );

    <T> Page<T> list(FilteredRepository<T,?> repository,Class<T> type,Map<String,String> query,Pageable pageable){
        Definition definition=Optional.ofNullable(DEFINITIONS.get(type)).orElseThrow();
        validate(query,definition);validateSort(pageable,definition);
        Specification<T> specification=(root,cq,cb)->predicate(root,cb,query,definition,type);
        return repository.findAll(specification,pageable);
    }

    private <T> Predicate predicate(Root<T> root,CriteriaBuilder cb,Map<String,String> query,Definition definition,Class<T> type){
        List<Predicate> predicates=new ArrayList<>();
        query.forEach((parameter,value)->{
            if(TRANSPORT.contains(parameter)||value==null||value.isBlank())return;
            if(parameter.equals("q")){String pattern="%"+value.toLowerCase(Locale.ROOT)+"%";predicates.add(cb.or(definition.search.stream().map(field->cb.like(cb.lower(root.get(field)),pattern)).toArray(Predicate[]::new)));return;}
            if(parameter.equals("from")||parameter.equals("to")){Path<?> path=root.get(definition.dateField);Comparable<?> converted=(Comparable<?>)rangeValue(value,path.getJavaType(),parameter.equals("to"));predicates.add(parameter.equals("from")?cb.greaterThanOrEqualTo((Expression)path,(Comparable)converted):cb.lessThanOrEqualTo((Expression)path,(Comparable)converted));return;}
            String field=definition.aliases.getOrDefault(parameter,parameter);Path<?> path=root.get(field);
            if(type==Debt.class&&parameter.equals("status")&&value.equalsIgnoreCase("OVERDUE")){predicates.add(cb.and(cb.lessThan(root.get("dueDate"),LocalDate.now()),cb.greaterThan(root.get("outstandingBalance"),java.math.BigDecimal.ZERO)));return;}
            predicates.add(cb.equal(path,convert(value,path.getJavaType())));
        });
        return cb.and(predicates.toArray(Predicate[]::new));
    }

    private void validate(Map<String,String> query,Definition definition){
        for(String parameter:query.keySet()){
            boolean allowed=TRANSPORT.contains(parameter)
                || parameter.equals("from")
                || parameter.equals("to")
                || (parameter.equals("q")&&!definition.search.isEmpty())
                || definition.fields.contains(definition.aliases.getOrDefault(parameter,parameter));
            if(!allowed)throw new BusinessException("INVALID_FILTER","Filtro no permitido: "+parameter,400);
        }
    }
    private void validateSort(Pageable pageable,Definition definition){for(Sort.Order order:pageable.getSort())if(!definition.fields.contains(order.getProperty()))throw new BusinessException("INVALID_SORT","Orden no permitido: "+order.getProperty(),400);}
    private Object convert(String value,Class<?> type){try{if(type==String.class)return value;if(type==Long.class||type==long.class)return Long.valueOf(value);if(type==Integer.class||type==int.class)return Integer.valueOf(value);if(type==Boolean.class||type==boolean.class)return Boolean.valueOf(value);if(type==LocalDate.class)return LocalDate.parse(value);if(type==OffsetDateTime.class)return value.length()==10?LocalDate.parse(value).atStartOfDay().atOffset(ZoneOffset.UTC):OffsetDateTime.parse(value);if(type==UUID.class)return UUID.fromString(value);if(type.isEnum())return Enum.valueOf((Class<Enum>)type,value.toUpperCase(Locale.ROOT));return value;}catch(RuntimeException ex){throw new BusinessException("INVALID_FILTER_VALUE","Valor de filtro inválido: "+value,400);}}
    private Object rangeValue(String value,Class<?> type,boolean endOfDay){if(type==OffsetDateTime.class&&value.length()==10){LocalDate date=LocalDate.parse(value);return (endOfDay?date.plusDays(1).atStartOfDay().minusNanos(1):date.atStartOfDay()).atOffset(ZoneOffset.UTC);}return convert(value,type);}
    private static Map.Entry<Class<?>,Definition> entry(Class<?> type,Set<String> fields,Map<String,String> aliases,String dateField,List<String> search){return Map.entry(type,new Definition(fields,aliases,dateField,search));}
    private static Set<String> fields(String... values){return Set.of(values);}
    private static Map<String,String> aliases(String... values){Map<String,String> map=new HashMap<>();for(int i=0;i<values.length;i+=2)map.put(values[i],values[i+1]);return map;}
    private record Definition(Set<String> fields,Map<String,String> aliases,String dateField,List<String> search) {}
}
