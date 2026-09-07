package ar.gob.municipalidad.rentas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.boot.actuate.health.*;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.Hidden;
import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

@EnableScheduling
@EnableSpringDataWebSupport(pageSerializationMode=VIA_DTO)
@SpringBootApplication
public class RentasApplication {
    public static void main(String[] args) { SpringApplication.run(RentasApplication.class, args); }
}

@Hidden
@RestController
class ApiHealthController {
    private final HealthEndpoint health;
    ApiHealthController(HealthEndpoint health){this.health=health;}
    @GetMapping("/api/v1/health") HealthComponent health(){return health.health();}
}
