package ar.gob.municipalidad.rentas;

import org.springframework.data.domain.*;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
class IntegrationEventQueryService {
    private static final ZoneId MUNICIPAL_ZONE=ZoneId.of("America/Argentina/Buenos_Aires");
    private static final Map<String,String> SORT_COLUMNS=Map.ofEntries(
        Map.entry("eventId","event_id"),Map.entry("eventType","event_type"),
        Map.entry("sourceModule","source_module"),Map.entry("targetModule","target_module"),
        Map.entry("direction","direction"),Map.entry("status","status"),
        Map.entry("occurredAt","occurred_at"),Map.entry("receivedAt","received_at"),
        Map.entry("processedAt","processed_at"),Map.entry("lastRetryAt","last_retry_at")
    );
    private final NamedParameterJdbcTemplate jdbc;

    IntegrationEventQueryService(NamedParameterJdbcTemplate jdbc){this.jdbc=jdbc;}

    Page<ApiDtos.IntegrationEventResponse> list(Map<String,String> filters,Pageable pageable){
        String direction=normalizeDirection(filters.get("direction"));
        String status=normalizeStatus(filters.get("status"));
        if(status!=null&&!Set.of("RECEIVED","PENDING","PROCESSED","IGNORED","PUBLISHED","FAILED","DEAD_LETTER","LOCAL_ONLY","BROKER_CONFIRMED","CORE_ACCEPTED","RETRYING").contains(status))
            throw new BusinessException("INVALID_FILTER_VALUE","Valor de filtro inválido: "+filters.get("status"),400);
        String eventType=value(filters,"eventType"),source=value(filters,"sourceModule"),target=value(filters,"targetModule");
        OffsetDateTime from=date(filters.get("from"),false),to=date(filters.get("to"),true);
        Set<String> allowed=Set.of("direction","status","eventType","sourceModule","targetModule","from","to","page","size","sort");
        filters.keySet().stream().filter(key->!allowed.contains(key)).findFirst().ifPresent(key->{throw new BusinessException("INVALID_FILTER","Filtro no permitido: "+key,400);});

        MapSqlParameterSource params=new MapSqlParameterSource();
        String logWhere=where("INBOUND",direction,status,eventType,source,target,from,to,params,false);
        String outboxWhere=where("OUTBOUND",direction,status,eventType,source,target,from,to,params,true);
        String union="select id,event_id,event_type,source_module,target_module,direction,status,payload,retry_count,error_message,occurred_at,received_at,processed_at,last_retry_at,published_at from ("
            +"select id,coalesce(external_event_id,cast(event_id as varchar)) event_id,event_type,source_module,target_module,direction,cast(status as varchar) status,payload,retry_count,error_message,occurred_at,received_at,processed_at,last_retry_at,cast(null as timestamp with time zone) published_at from integration_event_log where "+logWhere
            +" union all select cast(null as bigint) id,cast(id as varchar) event_id,event_type,'rentas' source_module,target_module,'OUTBOUND' direction,cast(status as varchar) status,payload,retry_count,error_message,created_at occurred_at,cast(null as timestamp with time zone) received_at,cast(null as timestamp with time zone) processed_at,last_attempt_at last_retry_at,published_at from outbox_event where "+outboxWhere+") events";
        long total=jdbc.queryForObject("select count(*) from (select 1 from integration_event_log where "+logWhere+" union all select 1 from outbox_event where "+outboxWhere+") events",params,Long.class);
        String order=sort(pageable.getSort());
        params.addValue("limit",pageable.getPageSize()).addValue("offset",pageable.getOffset());
        List<ApiDtos.IntegrationEventResponse> content=jdbc.query(union+" order by "+order+" limit :limit offset :offset",params,(rs,row)->
            new ApiDtos.IntegrationEventResponse(rs.getObject("id",Long.class),rs.getString("event_id"),rs.getString("event_type"),rs.getString("source_module"),rs.getString("target_module"),EventDirection.valueOf(rs.getString("direction")),rs.getString("status"),rs.getString("payload"),rs.getInt("retry_count"),rs.getString("error_message"),rs.getObject("occurred_at",OffsetDateTime.class),rs.getObject("received_at",OffsetDateTime.class),rs.getObject("processed_at",OffsetDateTime.class),rs.getObject("last_retry_at",OffsetDateTime.class),rs.getObject("published_at",OffsetDateTime.class)));
        return new PageImpl<>(content,pageable,total);
    }

    private String where(String rowDirection,String direction,String status,String eventType,String source,String target,OffsetDateTime from,OffsetDateTime to,MapSqlParameterSource params,boolean outbox){
        boolean selected=direction==null||direction.equals(rowDirection);
        StringBuilder sql=new StringBuilder(selected?"1=1":"1=0");
        if("RETRYING".equals(status))sql.append(" and status in ('PENDING','FAILED') and retry_count>0");
        else if(status!=null){sql.append(" and status=:status");params.addValue("status",status);}
        if(eventType!=null){sql.append(" and event_type=:eventType");params.addValue("eventType",eventType);}
        if(source!=null){sql.append(outbox?" and 'rentas'=:source":" and source_module=:source");params.addValue("source",source);}
        if(target!=null){sql.append(" and target_module=:target");params.addValue("target",target);}
        String dateColumn=outbox?"created_at":"occurred_at";
        if(from!=null){sql.append(" and ").append(dateColumn).append(">=:from");params.addValue("from",from);}
        if(to!=null){sql.append(" and ").append(dateColumn).append("<=:to");params.addValue("to",to);}
        return sql.toString();
    }

    private String sort(Sort sort){
        if(sort.isUnsorted())return "occurred_at desc,event_id asc";
        List<String> orders=new ArrayList<>();
        for(Sort.Order order:sort){String column=SORT_COLUMNS.get(order.getProperty());if(column==null)throw new BusinessException("INVALID_SORT","Orden no permitido: "+order.getProperty(),400);orders.add(column+" "+order.getDirection().name().toLowerCase(Locale.ROOT));}
        orders.add("event_id asc");return String.join(",",orders);
    }
    private String normalizeDirection(String value){if(value==null||value.isBlank())return null;return switch(value.toUpperCase(Locale.ROOT)){case "IN","INBOUND"->"INBOUND";case "OUT","OUTBOUND"->"OUTBOUND";default->throw new BusinessException("INVALID_FILTER_VALUE","Valor de filtro inválido: "+value,400);};}
    private String normalizeStatus(String value){if(value==null||value.isBlank())return null;return "DLQ".equalsIgnoreCase(value)?"DEAD_LETTER":value.toUpperCase(Locale.ROOT);}
    private String value(Map<String,String> filters,String key){String value=filters.get(key);return value==null||value.isBlank()?null:value;}
    private OffsetDateTime date(String value,boolean end){if(value==null||value.isBlank())return null;try{if(value.length()==10){LocalDate date=LocalDate.parse(value);return (end?date.plusDays(1).atStartOfDay(MUNICIPAL_ZONE).minusNanos(1):date.atStartOfDay(MUNICIPAL_ZONE)).toOffsetDateTime();}return OffsetDateTime.parse(value);}catch(RuntimeException ex){throw new BusinessException("INVALID_FILTER_VALUE","Valor de filtro inválido: "+value,400);}}
}
