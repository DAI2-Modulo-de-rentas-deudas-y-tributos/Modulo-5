package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CoreM1InboundListenerTests {
    @Autowired CoreInboundEventDispatcher dispatcher;
    @Autowired ObjectMapper json;
    @Autowired TaxpayerRepository taxpayers;
    @Autowired TaxpayerRepresentationRepository representations;
    @Autowired IntegrationEventLogRepository logs;
    @Autowired ProcessedEventRepository processed;

    @Test void citizenVariantsArriveThroughCoreAndPreserveTheOriginalEnvelope() throws Exception {
        long citizenId=Math.abs(UUID.randomUUID().getMostSignificantBits());
        UUID id=UUID.randomUUID(), correlation=UUID.randomUUID(), causation=UUID.randomUUID();
        String registered=citizen(citizenId,"REGISTERED","""
            {"dni":"34567890","cuil":"20345678901","name":"Marcos","lastname":"Ferreyra","birthdate":"1990-04-12","state":"ACTIVE"}
            """,id,correlation,causation);
        send(registered,1);
        TaxpayerReference citizen=taxpayers.findByTaxpayerTypeAndExternalId(TaxpayerType.CITIZEN,Long.toString(citizenId)).orElseThrow();
        assertThat(citizen.externalStatus).isEqualTo(TaxpayerStatus.ACTIVE);
        assertThat(citizen.canonicalCitizenId).isNull();
        IntegrationEventLog recorded=logs.findFirstByExternalEventIdOrderByIdDesc(id.toString()).orElseThrow();
        ObjectNode saved=(ObjectNode)json.readTree(recorded.payload);
        assertThat(saved.path("correlationId").asText()).isEqualTo(correlation.toString());
        assertThat(saved.path("causationId").asText()).isEqualTo(causation.toString());
        assertThat(saved.path("data").path("citizenId").longValue()).isEqualTo(citizenId);
        assertThat(saved.has("producer")).isFalse();
        assertThat(recorded.sourceModule).isEqualTo("ciudadanos");

        long before=taxpayers.count();
        send(registered,2);
        assertThat(taxpayers.count()).isEqualTo(before);
        assertThat(processed.existsByExternalEventId(id.toString())).isTrue();

        send(citizen(citizenId,"ADDRESS_UPDATED","""
            {"previousAddress":{},"newAddress":{"street":"Nueva"}}
            """,UUID.randomUUID(),correlation,id),3);
        assertThat(taxpayers.findById(citizen.id).orElseThrow().externalStatus).isEqualTo(TaxpayerStatus.ACTIVE);
        send(citizen(citizenId,"BLOCKED","""
            {"reason":"DOCUMENTACION_APOCRIFA","reviewRequired":true}
            """,UUID.randomUUID(),correlation,id),4);
        assertThat(taxpayers.findById(citizen.id).orElseThrow().externalStatus).isEqualTo(TaxpayerStatus.BLOCKED);
        send(citizen(citizenId,"DECEASED","""
            {"deceasedAt":"2026-08-19","source":"REGISTRO_CIVIL"}
            """,UUID.randomUUID(),correlation,id),5);
        assertThat(taxpayers.findById(citizen.id).orElseThrow().externalStatus).isEqualTo(TaxpayerStatus.DECEASED);
        assertThat(taxpayers.count()).isEqualTo(before);
    }

    @Test void organizationAndSupportedRepresentationEventsUseNestedData() throws Exception {
        long organizationId=100000L+Math.floorMod(UUID.randomUUID().getLeastSignificantBits(),100000L);
        long representationId=100000L+Math.floorMod(UUID.randomUUID().getMostSignificantBits(),100000L);
        UUID thread=UUID.randomUUID();
        send(envelope("organizationRegistered","ciudadanos",UUID.randomUUID(),thread,null,"""
            {"cuit":%d,"taxId":"30712345678","legalName":"Comercio de prueba","tradeName":"Local","type":"COMPANY","status":"ACTIVE","holder":{"personId":8452,"personType":"CITIZEN"}}
            """.formatted(organizationId)),6);
        assertThat(taxpayers.findByTaxpayerTypeAndExternalId(TaxpayerType.ORGANIZATION,Long.toString(organizationId))).isPresent();
        send(envelope("representationGranted","ciudadanos",UUID.randomUUID(),thread,null,"""
            {"representationId":%d,"personId":8452,"cuit":%d,"scope":"LEGAL_REPRESENTATIVE","from":"2026-08-21","status":"VALID"}
            """.formatted(representationId,organizationId)),7);
        assertThat(representations.findByExternalRepresentationId(Long.toString(representationId)).orElseThrow().status).isEqualTo("VALID");
        send(envelope("representationExpired","ciudadanos",UUID.randomUUID(),thread,null,"""
            {"representationId":%d,"personId":8452,"cuit":%d,"until":"2026-09-14","status":"EXPIRED"}
            """.formatted(representationId,organizationId)),8);
        assertThat(representations.findByExternalRepresentationId(Long.toString(representationId)).orElseThrow().status).isEqualTo("EXPIRED");
        assertThat(representations.count()).isEqualTo(1);
    }

    @Test void invalidSourceMalformedEnvelopeAndUnconfirmedVariantNackWithoutRequeue() throws Exception {
        long citizenId=100000L+Math.floorMod(UUID.randomUUID().getMostSignificantBits(),100000L);
        UUID id=UUID.randomUUID();
        String valid=citizen(citizenId,"REGISTERED","""
            {"dni":"34567890","cuil":"20345678901","name":"Marcos","lastname":"Ferreyra","birthdate":"1990-04-12","state":"ACTIVE"}
            """,id,UUID.randomUUID(),null);
        reject(valid.replace("\"sourceModule\":\"ciudadanos\"","\"sourceModule\":\"habilitaciones\""),9);
        reject(valid.replace("\"occurredAt\":","\"occuredAt\":"),10);
        reject(valid.replace("\"eventVersion\":\"1.0\"","\"eventVersion\":\"2.0\""),11);
        reject(valid.replace("\"eventType\":\"citizenUpdated\"","\"eventType\":\"representationRevoked\""),12);
        reject(valid.replace("\"data\":","\"producer\":{},\"data\":"),13);
        assertThat(processed.existsByExternalEventId(id.toString())).isFalse();
        assertThat(taxpayers.findByTaxpayerTypeAndExternalId(TaxpayerType.CITIZEN,Long.toString(citizenId))).isEmpty();
    }

    private String citizen(long citizenId,String updateType,String details,UUID id,UUID correlation,UUID causation) throws Exception {
        return envelope("citizenUpdated","ciudadanos",id,correlation,causation,"""
            {"citizenId":%d,"updateType":"%s","details":%s,"updatedBy":{"type":"AREA_USER","id":"USR-M1-14"}}
            """.formatted(citizenId,updateType,details));
    }
    private String envelope(String type,String source,UUID id,UUID correlation,UUID causation,String data) throws Exception {
        ObjectNode root=json.createObjectNode();
        root.put("eventId",id.toString());root.put("eventType",type);root.put("eventVersion","1.0");
        root.put("occurredAt",OffsetDateTime.parse("2026-10-30T10:00:00-03:00").toString());
        root.put("sourceModule",source);
        if(correlation==null)root.putNull("correlationId");else root.put("correlationId",correlation.toString());
        if(causation==null)root.putNull("causationId");else root.put("causationId",causation.toString());
        root.set("data",json.readTree(data));return json.writeValueAsString(root);
    }
    private void send(String payload,long tag) throws Exception {
        Channel channel=mock(Channel.class);
        new CoreRabbitMqConsumer(dispatcher).receive(new Message(payload.getBytes(StandardCharsets.UTF_8),new MessageProperties()),channel,tag);
        verify(channel).basicAck(tag,false);verify(channel,never()).basicNack(eq(tag),anyBoolean(),anyBoolean());
    }
    private void reject(String payload,long tag) throws Exception {
        Channel channel=mock(Channel.class);
        new CoreRabbitMqConsumer(dispatcher).receive(new Message(payload.getBytes(StandardCharsets.UTF_8),new MessageProperties()),channel,tag);
        verify(channel).basicNack(tag,false,false);verify(channel,never()).basicAck(eq(tag),anyBoolean());
    }
}
