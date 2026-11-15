package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.data.util.TypeInformation;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingPathVariableException;
import org.springframework.web.method.support.HandlerMethodArgumentResolverComposite;
import org.springframework.web.multipart.MultipartException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Entradas inválidas que encontró Schemathesis devolviendo 500: son errores del cliente (Eje B, contrato).
class ClientErrorMappingTests {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(null);
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/bills");

    @Test
    void parametroRechazadoPorElFirewallEs400() {
        var response = handler.malformed(new RequestRejectedException("caracter no permitido"), request);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().code()).isEqualTo("INVALID_REQUEST");
    }

    @Test
    void parametroMalCodificadoAlResolverArgumentosEs400PeroOtraIllegalArgumentSigueSiendo500() {
        var resolving = new IllegalArgumentException("sort=%");
        resolving.setStackTrace(new StackTraceElement[]{
            new StackTraceElement(HandlerMethodArgumentResolverComposite.class.getName(), "resolveArgument", null, 1)});
        assertThat(handler.illegalArgument(resolving, request).getStatusCode().value()).isEqualTo(400);

        var internal = new IllegalArgumentException("error de programación");
        internal.setStackTrace(new StackTraceElement[]{new StackTraceElement("ar.gob.Servicio", "calcular", null, 1)});
        assertThat(handler.illegalArgument(internal, request).getStatusCode().value()).isEqualTo(500);
    }

    @Test
    void ordenPorPropiedadInexistenteEs400SoloSiVieneDelCliente() {
        var unknown = new PropertyReferenceException("nada", TypeInformation.of(Object.class), List.of());
        var response = handler.invalidSort(unknown, request);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().code()).isEqualTo("INVALID_SORT");

        var usage = new InvalidDataAccessApiUsageException("order by p.null");
        assertThat(handler.invalidSort(usage, request).getStatusCode().value()).isEqualTo(500);
        request.setParameter("sort", "null");
        assertThat(handler.invalidSort(usage, request).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void excepcionesEstandarConEstadoDeClienteConservanSuEstado() throws NoSuchMethodException {
        var unsupported = new HttpMediaTypeNotSupportedException(MediaType.MULTIPART_FORM_DATA, List.of(MediaType.APPLICATION_JSON));
        var response = handler.unexpected(unsupported, request);
        assertThat(response.getStatusCode().value()).isEqualTo(415);
        assertThat(response.getBody().code()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");

        MethodParameter id = new MethodParameter(ClientErrorMappingTests.class.getDeclaredMethod("conId", Long.class), 0);
        assertThat(handler.unexpected(new MissingPathVariableException("id", id, true), request).getStatusCode().value()).isEqualTo(400);
        // Sin conversión es un error de configuración del servidor: sigue siendo 500.
        assertThat(handler.unexpected(new MissingPathVariableException("id", id), request).getStatusCode().value()).isEqualTo(500);

        assertThat(handler.malformed(new MultipartException("sin cuerpo"), request).getStatusCode().value()).isEqualTo(400);
    }

    @SuppressWarnings("unused")
    private void conId(Long id) { }
}
