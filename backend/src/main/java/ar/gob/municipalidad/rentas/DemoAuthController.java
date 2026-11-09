package ar.gob.municipalidad.rentas;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/dev-auth")
@Profile("!prod")
@ConditionalOnProperty(name="rentas.security.dev-mode",havingValue="true")
class DemoAuthController {
    record LoginRequest(@NotBlank String username,@NotBlank String password) {}
    record BootstrapRequest(@NotBlank @Size(max=100) String username,@NotBlank @Size(min=8,max=100) String password,
        @NotBlank @Size(max=255) String displayName) {}
    private record BootstrapIdempotencyRequest(String secret,BootstrapRequest request) {}
    record CreateUserRequest(@NotBlank @Size(max=100) String username,@NotBlank @Size(min=8,max=100) String password,
        @NotBlank @Size(max=255) String displayName,@NotNull DemoRole role,@Positive Long taxpayerId) {}
    record UserResponse(Long id,String username,String displayName,DemoRole role,List<String> authorities,Long taxpayerId,boolean active,OffsetDateTime createdAt) {}
    record LoginResponse(String token,UserResponse user) {}

    private final DemoAuthService auth;
    private final TrustedClientAddressResolver clientAddresses;
    private final IdempotencyExecutor idempotency;
    DemoAuthController(DemoAuthService auth,TrustedClientAddressResolver clientAddresses,IdempotencyExecutor idempotency){this.auth=auth;this.clientAddresses=clientAddresses;this.idempotency=idempotency;}

    @PostMapping("/login")
    LoginResponse login(@Valid @RequestBody LoginRequest request,HttpServletRequest servletRequest){return auth.login(request,clientAddresses.resolve(servletRequest));}

    LoginResponse login(LoginRequest request){return auth.login(request,"local-test");}

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@RequestHeader(value=DemoAuthService.SESSION_HEADER,required=false) String token){auth.logout(token);}

    @GetMapping("/me")
    UserResponse me(@RequestHeader(value=DemoAuthService.SESSION_HEADER,required=false) String token){return auth.me(token);}

    @PostMapping("/bootstrap") @ResponseStatus(HttpStatus.CREATED)
    UserResponse bootstrap(@RequestHeader(value=DemoAuthService.BOOTSTRAP_HEADER,required=false) String secret,
            @Valid @RequestBody BootstrapRequest request,HttpServletRequest servletRequest,
            @RequestHeader(name="Idempotency-Key",required=false) String key){
        return idempotency.execute("POST:/dev-auth/bootstrap",key,new BootstrapIdempotencyRequest(secret,request),UserResponse.class,
            ()->auth.bootstrap(secret,request,clientAddresses.resolve(servletRequest)));
    }

    @PostMapping("/users") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('SUPERVISOR')")
    @Transactional UserResponse create(@Valid @RequestBody CreateUserRequest request,@RequestHeader(name="Idempotency-Key",required=false) String key){return idempotency.execute("POST:/dev-auth/users",key,request,UserResponse.class,()->auth.create(request));}

    UserResponse create(CreateUserRequest request){return auth.create(request);}

    @GetMapping("/users") @PreAuthorize("hasRole('SUPERVISOR')")
    List<UserResponse> list(){return auth.list();}
}
