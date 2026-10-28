package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

final class EntityTags {
    private EntityTags() {}

    static String taxConcept(TaxConcept concept) {
        return quoted("tax-concept-" + concept.id + "-" + concept.lockVersion);
    }

    static String taxConfiguration(TaxConfiguration configuration) {
        return quoted("tax-configuration-" + configuration.id + "-" + configuration.lockVersion);
    }

    static String paymentPlanConfiguration(PaymentPlanConfiguration configuration) {
        return quoted("payment-plan-configuration-" + configuration.id + "-" + configuration.version);
    }

    static void requireIfMatch(String current, String provided) {
        if (provided == null) return;
        boolean matches = List.of(provided.split(",")).stream()
            .map(String::trim)
            .anyMatch(candidate -> candidate.equals("*") || candidate.equals(current));
        if (!matches) throw new BusinessException("PRECONDITION_FAILED", "El recurso cambió desde la última lectura", 412);
    }

    private static String quoted(String value) { return "\"" + value + "\""; }
}

/** Persistencia transaccional compartida de respuestas idempotentes para operaciones nuevas. */
@Service
class IdempotencyService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CurrentIdentity identity;
    private final DataSource dataSource;

    IdempotencyService(JdbcTemplate jdbc, ObjectMapper json, CurrentIdentity identity, DataSource dataSource) {
        this.jdbc = jdbc;
        this.json = json;
        this.identity = identity;
        this.dataSource = dataSource;
    }

    @Transactional
    <T> T execute(String operation, String key, Object request, Class<T> responseType, Supplier<T> action) {
        if (key == null) return action.get();
        String normalized = key.trim();
        if (!normalized.matches("[A-Za-z0-9._:-]{1,128}"))
            throw new BusinessException("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key debe contener entre 1 y 128 caracteres alfanuméricos, punto, guion, guion bajo o dos puntos", 400);
        String actor = identity.get().userId();
        String fingerprint = sha256(serialize(request));
        int inserted = insert(actor, operation, normalized, fingerprint);
        if (inserted == 0) {
            StoredResponse existing = jdbc.queryForObject("select request_fingerprint,response_body from idempotency_request where actor_id=? and operation=? and idempotency_key=? for update",
                (rs, row) -> new StoredResponse(rs.getString(1), rs.getString(2)), actor, operation, normalized);
            if (!fingerprint.equals(existing.fingerprint()))
                throw new BusinessException("IDEMPOTENCY_KEY_REUSED", "La clave de idempotencia ya fue utilizada con otro contenido", 409);
            if (existing.body() == null)
                throw new BusinessException("IDEMPOTENCY_RESULT_UNAVAILABLE", "El resultado de la operación aún no está disponible", 409);
            return deserialize(existing.body(), responseType);
        }
        T response = action.get();
        jdbc.update("update idempotency_request set response_body=? where actor_id=? and operation=? and idempotency_key=?",
            serialize(response), actor, operation, normalized);
        return response;
    }

    private int insert(String actor, String operation, String key, String fingerprint) {
        if (isPostgreSql()) {
            return jdbc.update("insert into idempotency_request(actor_id,operation,idempotency_key,request_fingerprint,created_at) values(?,?,?,?,?) on conflict(actor_id,operation,idempotency_key) do nothing",
                actor, operation, key, fingerprint, OffsetDateTime.now());
        }
        try {
            return jdbc.update("insert into idempotency_request(actor_id,operation,idempotency_key,request_fingerprint,created_at) values(?,?,?,?,?)",
                actor, operation, key, fingerprint, OffsetDateTime.now());
        } catch (DuplicateKeyException duplicate) {
            return 0;
        }
    }

    private boolean isPostgreSql() {
        try (var connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgresql");
        } catch (SQLException ex) {
            throw new IllegalStateException("No se pudo identificar la base de datos para idempotencia", ex);
        }
    }

    private String serialize(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("No se pudo conservar el resultado idempotente", ex); }
    }

    private <T> T deserialize(String value, Class<T> responseType) {
        try { return json.readValue(value, responseType); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("No se pudo recuperar el resultado idempotente", ex); }
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 no disponible", ex); }
    }

    private record StoredResponse(String fingerprint, String body) {}
}
