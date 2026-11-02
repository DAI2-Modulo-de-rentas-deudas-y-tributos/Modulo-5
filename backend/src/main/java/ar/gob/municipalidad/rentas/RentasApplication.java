package ar.gob.municipalidad.rentas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.data.web.config.PageableHandlerMethodArgumentResolverCustomizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.actuate.health.*;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Hidden;
import java.time.Clock;
import java.time.ZoneId;
import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

@EnableScheduling
@EnableSpringDataWebSupport(pageSerializationMode=VIA_DTO)
@SpringBootApplication
public class RentasApplication {
    public static void main(String[] args) { SpringApplication.run(RentasApplication.class, args); }

    @Bean PageableHandlerMethodArgumentResolverCustomizer pageableMaxPageSize(
            @Value("${spring.data.web.pageable.max-page-size:100}") int maxPageSize) {
        return resolver -> resolver.setMaxPageSize(maxPageSize);
    }

    @Bean Clock fiscalProcessingClock() {
        return Clock.system(ZoneId.of("America/Argentina/Buenos_Aires"));
    }
}

@Hidden
@RestController
class ApiHealthController {
    private final HealthEndpoint health;
    ApiHealthController(HealthEndpoint health){this.health=health;}
    @GetMapping("/api/v1/health") HealthComponent health(){return health.health();}
}
