package ar.gob.municipalidad.rentas;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.function.Supplier;

final class TestActorSupport {
    private TestActorSupport() {}

    static <T> T asApprover(Supplier<T> action) {
        Authentication previous = SecurityContextHolder.getContext().getAuthentication();
        Object priorPrincipal = previous.getPrincipal();
        Long taxpayerId = priorPrincipal instanceof AuthenticatedIdentity identity ? identity.taxpayerId() : null;
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            new AuthenticatedIdentity("fixture-supervisor", taxpayerId), null, previous.getAuthorities()));
        try {
            return action.get();
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }
}
