package ar.gob.municipalidad.rentas;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/v1/dev-auth")
@ConditionalOnProperty(name="rentas.security.dev-mode",havingValue="true")
class DemoAuthController {
    record LoginRequest(@NotBlank String username,@NotBlank String password) {}
    record CreateUserRequest(@NotBlank @Size(max=100) String username,@NotBlank @Size(min=8,max=100) String password,
        @NotBlank @Size(max=255) String displayName,@NotNull DemoRole role,Long taxpayerId) {}
    record UserResponse(Long id,String username,String displayName,DemoRole role,List<String> authorities,Long taxpayerId,boolean active,OffsetDateTime createdAt) {}
    record LoginResponse(String token,UserResponse user) {}

    private final DemoUserRepository users; private final TaxpayerRepository taxpayers; private final PasswordEncoder passwords;
    private final CurrentIdentity identity; private final AuditService audit;
    DemoAuthController(DemoUserRepository users,TaxpayerRepository taxpayers,PasswordEncoder passwords,CurrentIdentity identity,AuditService audit) {
        this.users=users;this.taxpayers=taxpayers;this.passwords=passwords;this.identity=identity;this.audit=audit;
    }

    @PostMapping("/login")
    LoginResponse login(@Valid @RequestBody LoginRequest request) {
        DemoUser user=users.findByUsernameIgnoreCase(normalize(request.username()))
            .filter(x->x.active&&passwords.matches(request.password(),x.passwordHash))
            .orElseThrow(()->new BusinessException("INVALID_CREDENTIALS","Usuario o contraseña incorrectos",401));
        return new LoginResponse("dev-session",response(user));
    }

    @PostMapping("/users") @ResponseStatus(HttpStatus.CREATED) @PreAuthorize("hasRole('SUPERVISOR')")
    @Transactional UserResponse create(@Valid @RequestBody CreateUserRequest request) {
        String username=normalize(request.username());
        CatalogService.require(!users.existsByUsernameIgnoreCase(username),"DEMO_USERNAME_ALREADY_EXISTS","El usuario demo ya existe");
        if(request.role()==DemoRole.TAXPAYER) {
            CatalogService.require(request.taxpayerId()!=null,"DEMO_TAXPAYER_REQUIRED","El rol TAXPAYER requiere taxpayerId");
            taxpayers.findById(request.taxpayerId()).orElseThrow(()->CatalogService.notFound("Contribuyente"));
        } else CatalogService.require(request.taxpayerId()==null,"DEMO_TAXPAYER_NOT_ALLOWED","Sólo TAXPAYER puede vincular un taxpayerId");
        DemoUser user=new DemoUser();user.username=username;user.passwordHash=passwords.encode(request.password());
        user.displayName=request.displayName().trim();user.role=request.role();user.taxpayerId=request.taxpayerId();user.active=true;
        user.createdAt=user.updatedAt=OffsetDateTime.now();users.save(user);audit.record("DemoUser",user.id,"DEMO_USER_CREATED",response(user));
        return response(user);
    }

    @GetMapping("/users") @PreAuthorize("hasRole('SUPERVISOR')")
    List<UserResponse> list(){return users.findAll().stream().map(this::response).toList();}

    @GetMapping("/me")
    UserResponse me(){return users.findByUsernameIgnoreCase(identity.get().userId()).map(this::response)
        .orElseThrow(()->CatalogService.notFound("Usuario demo"));}

    private UserResponse response(DemoUser user){List<String> authorities=user.role==DemoRole.SUPERVISOR?List.of("RENTAS","SUPERVISOR"):List.of(user.role.name());return new UserResponse(user.id,user.username,user.displayName,user.role,authorities,user.taxpayerId,user.active,user.createdAt);}
    private String normalize(String username){return username.trim().toLowerCase();}
}
