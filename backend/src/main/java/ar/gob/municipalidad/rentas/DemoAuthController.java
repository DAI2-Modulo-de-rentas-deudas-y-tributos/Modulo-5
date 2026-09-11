package ar.gob.municipalidad.rentas;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/dev-auth")
@ConditionalOnProperty(name="rentas.security.dev-mode",havingValue="true")
class DemoAuthController {
    record LoginRequest(@NotBlank String username,@NotBlank String password) {}
    record BootstrapRequest(@NotBlank @Size(max=100) String username,@NotBlank @Size(min=8,max=100) String password,
        @NotBlank @Size(max=255) String displayName) {}
    record CreateUserRequest(@NotBlank @Size(max=100) String username,@NotBlank @Size(min=8,max=100) String password,
        @NotBlank @Size(max=255) String displayName,@NotNull DemoRole role,Long taxpayerId) {}
    record UserResponse(Long id,String username,String displayName,DemoRole role,List<String> authorities,Long taxpayerId,boolean active,OffsetDateTime createdAt) {}
    record LoginResponse(String token,UserResponse user) {}

    private final DemoAuthService auth;
    DemoAuthController(DemoAuthService auth){this.auth=auth;}

    @PostMapping("/login")
    LoginResponse login(@Valid @RequestBody LoginRequest request){return auth.login(request);}

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@RequestHeader(value=DemoAuthService.SESSION_HEADER,required=false) String token){auth.logout(token);}

    @GetMapping("/me")
    UserResponse me(@RequestHeader(value=DemoAuthService.SESSION_HEADER,required=false) String token){return auth.me(token);}

    @PostMapping("/bootstrap") @ResponseStatus(HttpStatus.CREATED)
    UserResponse bootstrap(@RequestHeader(value=DemoAuthService.BOOTSTRAP_HEADER,required=false) String secret,
            @Valid @RequestBody BootstrapRequest request){return auth.bootstrap(secret,request);}

    @PostMapping("/users") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('SUPERVISOR')")
    @Transactional UserResponse create(@Valid @RequestBody CreateUserRequest request){return auth.create(request);}

    @GetMapping("/users") @PreAuthorize("hasRole('SUPERVISOR')")
    List<UserResponse> list(){return auth.list();}
}
