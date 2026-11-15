package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.regex.Pattern;

final class OperationalLogSupport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9]{0,63}");
    private static final Pattern SAFE_VERSION = Pattern.compile("[0-9]{1,3}\\.[0-9]{1,3}");
    private static final Pattern SAFE_ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,79}");

    private OperationalLogSupport() {}

    static Throwable sanitizedStackTrace(Throwable exception) {
        Throwable safe = new Throwable(exception.getClass().getSimpleName());
        safe.setStackTrace(exception.getStackTrace());
        return safe;
    }

    static String uuidField(String json, String field) {
        try {
            JsonNode value = JSON.readTree(json).path(field);
            return value.isTextual() ? UUID.fromString(value.asText()).toString() : "unavailable";
        } catch (Exception ignored) {
            return "unavailable";
        }
    }

    static String safeIdentifierField(String json, String field) {
        try {
            JsonNode value = JSON.readTree(json).path(field);
            return value.isTextual() && SAFE_IDENTIFIER.matcher(value.asText()).matches()
                ? value.asText() : "unavailable";
        } catch (Exception ignored) {
            return "unavailable";
        }
    }

    static String safeVersionField(String json, String field) {
        try {
            JsonNode value = JSON.readTree(json).path(field);
            return value.isTextual() && SAFE_VERSION.matcher(value.asText()).matches() ? value.asText() : "unavailable";
        } catch (Exception ignored) {
            return "unavailable";
        }
    }

    static String safeFailureReason(Throwable exception) {
        if (exception instanceof BusinessException business && SAFE_ERROR_CODE.matcher(business.code).matches()) {
            return business.code;
        }
        return exception.getClass().getSimpleName();
    }
}
