package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.util.Map;
import java.util.UUID;

final class M1CitizenLoginClient {
    private final URI endpoint;
    private final ObjectMapper json;
    private final int timeoutMillis;

    M1CitizenLoginClient(String baseUrl, ObjectMapper json, int timeoutMillis) {
        URI base = URI.create(baseUrl);
        boolean loopback = "http".equals(base.getScheme()) && "127.0.0.1".equals(base.getHost());
        if (base.getHost() == null || base.getRawUserInfo() != null || base.getRawQuery() != null
                || base.getRawFragment() != null || !base.getPath().matches("/?")
                || (!loopback && !"https".equals(base.getScheme()))
                || timeoutMillis < 100 || timeoutMillis > 10_000) {
            throw new IllegalArgumentException("Configuración de autenticación M1 inválida");
        }
        this.endpoint = base.resolve("/auth/usuario/login");
        this.json = json;
        this.timeoutMillis = timeoutMillis;
    }

    UUID authenticate(String username, String password) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) endpoint.toURL().openConnection(Proxy.NO_PROXY);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            byte[] body = json.writeValueAsBytes(Map.of("gmail", username, "password", password));
            connection.setFixedLengthStreamingMode(body.length);
            try (var output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            if (status == 401 || status == 403) throw denied();
            if (status == 429) {
                String value = connection.getHeaderField("Retry-After");
                int seconds = value != null && value.matches("[0-9]{1,4}")
                        ? Math.max(1, Math.min(300, Integer.parseInt(value))) : 60;
                throw new BusinessException("CITIZEN_LOGIN_RATE_LIMITED", "Volvé a intentar más tarde", 429, seconds);
            }
            if (status >= 500) throw unavailable();
            if (status != 200 || connection.getContentType() == null
                    || !MediaType.APPLICATION_JSON.includes(MediaType.parseMediaType(connection.getContentType()))) {
                throw invalidResponse();
            }
            try (var input = connection.getInputStream()) {
                byte[] response = input.readNBytes(16_385);
                if (response.length > 16_384) throw invalidResponse();
                return identity(response);
            }
        } catch (IOException ex) {
            throw unavailable();
        } catch (IllegalArgumentException ex) {
            throw invalidResponse();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private UUID identity(byte[] response) {
        JsonNode body;
        try {
            body = json.readerFor(JsonNode.class).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(response);
        } catch (IOException ex) {
            throw invalidResponse();
        }
        if (body == null || !body.isObject() || !body.path("auth").isBoolean()) throw invalidResponse();
        if (!body.path("auth").booleanValue() || !"CIUDADANO".equals(body.path("actorType").textValue())
                || !"CIUDADANO".equals(body.path("role").textValue())) throw denied();
        UUID citizen = uuid(body.path("citizenId"));
        if (!citizen.equals(uuid(body.path("actorId"))) || !citizen.equals(uuid(body.path("uuid")))) {
            throw invalidResponse();
        }
        return citizen;
    }

    private UUID uuid(JsonNode node) {
        String value = node.textValue();
        if (value == null || !value.matches("(?i)[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) throw invalidResponse();
        UUID uuid = UUID.fromString(value);
        if (uuid.getMostSignificantBits() == 0 && uuid.getLeastSignificantBits() == 0) throw invalidResponse();
        return uuid;
    }

    static BusinessException denied() {
        return new BusinessException("INVALID_CREDENTIALS", "No pudimos validar el acceso al Portal del Contribuyente", 401);
    }

    private static BusinessException invalidResponse() {
        return new BusinessException("M1_INVALID_RESPONSE", "No pudimos verificar la identidad con Ciudadanos", 502);
    }

    private static BusinessException unavailable() {
        return new BusinessException("M1_UNAVAILABLE", "Ciudadanos no está disponible en este momento", 503);
    }
}
