package ar.gob.municipalidad.rentas;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TrustedClientAddressResolverTests {
    @Test void resolvesForwardedAddressOnlyFromTrustedDirectPeer(){
        var resolver=new TrustedClientAddressResolver("10.0.0.0/8");
        assertThat(resolver.resolve(request("10.1.2.3","203.0.113.99, 198.51.100.7"))).isEqualTo("198.51.100.7");
        assertThat(resolver.resolve(request("198.51.100.5","203.0.113.99"))).isEqualTo("198.51.100.5");
    }

    @Test void walksTrustedProxyHopsFromRightToLeft(){
        var resolver=new TrustedClientAddressResolver("10.0.0.0/8");
        assertThat(resolver.resolve(request("10.1.2.3","198.51.100.7, 10.2.3.4"))).isEqualTo("198.51.100.7");
    }

    @Test void malformedForwardedChainFallsBackToDirectPeer(){
        var resolver=new TrustedClientAddressResolver("10.0.0.0/8");
        assertThat(resolver.resolve(request("10.1.2.3","203.0.113.1,not-an-ip"))).isEqualTo("10.1.2.3");
        assertThat(resolver.resolve(request("10.1.2.3","x".repeat(1025)))).isEqualTo("10.1.2.3");
    }

    @Test void invalidProxyConfigurationFailsAtStartup(){
        assertThatThrownBy(()->new TrustedClientAddressResolver("proxy.internal/24"))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("CIDR de proxy confiable inválido");
    }

    private MockHttpServletRequest request(String remoteAddress,String forwardedFor){
        var request=new MockHttpServletRequest();request.setRemoteAddr(remoteAddress);
        if(forwardedFor!=null)request.addHeader("X-Forwarded-For",forwardedFor);
        return request;
    }
}
