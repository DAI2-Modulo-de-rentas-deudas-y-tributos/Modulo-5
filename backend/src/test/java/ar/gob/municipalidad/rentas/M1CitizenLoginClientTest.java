package ar.gob.municipalidad.rentas;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class M1CitizenLoginClientTest {
    @ParameterizedTest
    @ValueSource(strings = {"http://m1.example.invalid", "file:///tmp/login",
            "https://user@m1.example.invalid", "https://m1.example.invalid/path",
            "https://m1.example.invalid?query=value", "https://m1.example.invalid/#fragment"})
    void rejectsUnsafeOrAmbiguousBaseUrls(String baseUrl) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new M1CitizenLoginClient(baseUrl, new ObjectMapper(), 1_000));
    }
}
