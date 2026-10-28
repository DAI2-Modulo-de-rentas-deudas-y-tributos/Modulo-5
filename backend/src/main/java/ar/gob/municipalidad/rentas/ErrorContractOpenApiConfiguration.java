package ar.gob.municipalidad.rentas;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ErrorContractOpenApiConfiguration {
    @Bean OpenApiCustomizer errorResponseSchema() {
        return openApi -> {
            if(openApi.getComponents()==null)openApi.setComponents(new Components());
            ModelConverters.getInstance().read(ApiDtos.ErrorResponse.class)
                .forEach(openApi.getComponents()::addSchemas);
            openApi.getComponents().addResponses("ApiError",new ApiResponse().description("Error generado por la API")
                .content(new Content().addMediaType("application/problem+json",new MediaType()
                    .schema(new Schema<>().$ref("#/components/schemas/ErrorResponse")))));
            if(openApi.getPaths()!=null)openApi.getPaths().forEach((pathName,path) -> path.readOperations().forEach(operation -> {
                for(String status:new String[]{"400","401","403","404","405","409","422","500"})
                    operation.getResponses().putIfAbsent(status,new ApiResponse().$ref("#/components/responses/ApiError"));
                if(pathName.equals("/api/v1/dev-auth/login")||pathName.equals("/api/v1/dev-auth/bootstrap"))
                    operation.getResponses().putIfAbsent("429",new ApiResponse().$ref("#/components/responses/ApiError"));
                if(pathName.matches("/api/v1/(tax-concepts|tax-configurations|payment-plan-configurations)/\\{id\\}") && path.getPatch()==operation){
                    operation.addParametersItem(new Parameter().name("If-Match").in("header").required(false).description("ETag recibido del GET. Si se omite, se conserva compatibilidad sin precondición.").schema(new StringSchema()));
                    operation.getResponses().putIfAbsent("412",new ApiResponse().$ref("#/components/responses/ApiError"));
                    ApiResponse response=operation.getResponses().get("200");if(response!=null)response.addHeaderObject("ETag",new io.swagger.v3.oas.models.headers.Header().description("Nueva versión del recurso").schema(new StringSchema()));
                }
                if(pathName.matches("/api/v1/(tax-concepts|tax-configurations|payment-plan-configurations)/\\{id\\}") && path.getGet()==operation){ApiResponse response=operation.getResponses().get("200");if(response!=null)response.addHeaderObject("ETag",new io.swagger.v3.oas.models.headers.Header().description("Versión actual del recurso").schema(new StringSchema()));}
                if(path.getPost()==operation&&operationHasIdempotencyKey(pathName,operation))
                    operation.addParametersItem(new Parameter().name("Idempotency-Key").in("header").required(false).description("Clave de reintento, acotada al usuario y operación. La ausencia mantiene compatibilidad.").schema(new StringSchema().maxLength(128)));
            }));
        };
    }

    private boolean operationHasIdempotencyKey(String pathName,io.swagger.v3.oas.models.Operation operation) {
        return pathName.matches("/api/v1/(liquidations|adjustments|exemption-requests|payment-plan-requests|payments/\\{id\\}/reversal-requests|payment-plan-requests/\\{id\\}/grant|payment-plans/\\{planId\\}/refinancing-requests|bills|payments|electronic-payments)")
            && operation.getParameters()!=null && operation.getParameters().stream().noneMatch(p->"Idempotency-Key".equalsIgnoreCase(p.getName()));
    }
}
