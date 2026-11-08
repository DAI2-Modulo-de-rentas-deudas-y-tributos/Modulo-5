package ar.gob.municipalidad.rentas;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.headers.Header;
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
                for(String status:new String[]{"400","401","403","404","405","409","415","422","500"})
                    operation.getResponses().putIfAbsent(status,new ApiResponse().$ref("#/components/responses/ApiError"));
                if(pathName.equals("/api/v1/dev-auth/login")||pathName.equals("/api/v1/dev-auth/bootstrap"))
                    operation.getResponses().putIfAbsent("429",new ApiResponse().$ref("#/components/responses/ApiError"));
                if(pathName.matches("/api/v1/(tax-concepts|tax-configurations|payment-plan-configurations)/\\{id\\}") && path.getPatch()==operation)
                    documentIfMatch(operation,"ETag recibido del GET. Si se omite, se conserva compatibilidad sin precondición.","Nueva versión del recurso");
                if(pathName.matches("/api/v1/(tax-concepts|tax-configurations|payment-plan-configurations)/\\{id\\}") && path.getGet()==operation)
                    documentEtag(operation,"Versión actual del recurso");
                if(pathName.matches("/api/v1/tax-configurations/\\{id\\}/(submit|approve|reject|deactivate)") && path.getPost()==operation)
                    documentIfMatch(operation,"ETag recibido del detalle de la configuración. Si se omite, se conserva compatibilidad sin precondición.","Versión actualizada de la configuración");
                if(pathName.matches("/api/v1/(debts|liquidations|payment-plans|exemption-requests|adjustments|payment-reversals)/\\{id\\}") && path.getGet()==operation)
                    documentEtag(operation,"Hash de la representación actual del recurso");
                if((pathName.equals("/api/v1/refinancing-requests/{id}")||pathName.equals("/api/v1/tickets/{id}")) && path.getGet()==operation)
                    documentEtag(operation,"Hash de la representación actual del recurso");
                if(pathName.matches("/api/v1/liquidation-runs/\\{id\\}") && path.getGet()==operation)
                    documentEtag(operation,"Hash de la representación actual de la corrida y sus ítems");
                if(pathName.matches("/api/v1/payment-plan-requests/\\{id\\}") && path.getGet()==operation)
                    documentEtag(operation,"Hash de la representación actual de la solicitud de plan");
                if(pathName.matches("/api/v1/liquidation-runs/\\{id\\}/(preview|submit|approve|reject|execute)") && path.getPost()==operation){
                    documentIfMatch(operation,"ETag recibido del detalle de la corrida. Si se omite, se conserva compatibilidad sin precondición.","Hash de la representación actualizada de la corrida y sus ítems");
                }
                if(pathName.matches("/api/v1/payment-plan-requests/\\{id\\}/(grant|reject|submit-exception|approve-exception|reject-exception)") && path.getPost()==operation){
                    documentIfMatch(operation,"ETag recibido del detalle de la solicitud. Si se omite, se conserva compatibilidad sin precondición.","Hash de la solicitud actualizada");
                }
                if((pathName.matches("/api/v1/debts/\\{id\\}/late-charges")
                    ||pathName.matches("/api/v1/payments/\\{id\\}/allocations")
                    ||pathName.matches("/api/v1/payment-plans/\\{planId\\}/expiration-requests")
                    ||pathName.matches("/api/v1/payment-plan-expirations/\\{id\\}/(approve|reject)")) && path.getPost()==operation)
                    documentIfMatch(operation,"ETag recibido del recurso de deuda o plan relacionado. Si se omite, se conserva compatibilidad sin precondición.","Hash de la representación actual del recurso relacionado");
                if(pathName.equals("/api/v1/payment-plans/{planId}/refinancing-requests") && path.getPost()==operation)
                    documentIfMatch(operation,"ETag del plan relacionado; si se omite, se conserva compatibilidad sin precondición.","Hash de la solicitud de refinanciación creada");
                if(pathName.matches("/api/v1/refinancing-requests/\\{id\\}/(grant|reject|submit-exception|approve-exception|reject-exception)") && path.getPost()==operation)
                    documentIfMatch(operation,"ETag recibido del detalle de la solicitud. Si se omite, se conserva compatibilidad sin precondición.","Hash de la representación actualizada de la solicitud");
                if(pathName.matches("/api/v1/tickets/\\{id\\}/(assign|updates|request-information|return|complete|reject)") && path.getPost()==operation)
                    documentIfMatch(operation,"ETag recibido del detalle del ticket. Si se omite, se conserva compatibilidad sin precondición.","Hash de la representación actualizada del ticket");
                if(pathName.matches("/api/v1/exemption-requests/\\{id\\}/(start-review|request-documentation|documentation|submit-resolution|approve|reject)") && path.getPost()==operation)
                    documentIfMatch(operation,"ETag recibido del detalle de la solicitud. Si se omite, se conserva compatibilidad sin precondición.","Hash de la solicitud actualizada");
                if((pathName.matches("/api/v1/adjustments/\\{id\\}/(approve|reject)")
                    ||pathName.matches("/api/v1/payment-reversals/\\{id\\}/(approve|reject|execute)")) && path.getPost()==operation)
                    documentIfMatch(operation,"ETag recibido del detalle del recurso. Si se omite, se conserva compatibilidad sin precondición.","Hash de la representación actualizada del recurso");
                if(path.getPost()==operation&&!pathName.startsWith("/api/v1/dev-auth/")&&!isReadOnlyCalculation(pathName)
                    &&(operation.getParameters()==null||operation.getParameters().stream().noneMatch(p->"Idempotency-Key".equalsIgnoreCase(p.getName()))))
                    operation.addParametersItem(new Parameter().name("Idempotency-Key").in("header").required(false).description("Clave opcional para reintentos; misma clave y cuerpo repite la respuesta, distinto cuerpo produce 409.").schema(new StringSchema().maxLength(128)));
            }));
            // Jackson serializa como null las propiedades opcionales sin valor: el contrato lo declara.
            openApi.getComponents().getSchemas().values().forEach(this::allowNullInOptionalProperties);
        };
    }

    private void documentIfMatch(Operation operation,String ifMatchDescription,String etagDescription){
        Parameter ifMatch=operation.getParameters()==null?null:operation.getParameters().stream()
            .filter(parameter->"If-Match".equalsIgnoreCase(parameter.getName())).findFirst().orElse(null);
        if(ifMatch==null){ifMatch=new Parameter().name("If-Match");operation.addParametersItem(ifMatch);}
        ifMatch.setIn("header");ifMatch.setRequired(false);ifMatch.setDescription(ifMatchDescription);ifMatch.setSchema(new StringSchema());
        operation.getResponses().putIfAbsent("412",new ApiResponse().$ref("#/components/responses/ApiError"));
        documentEtag(operation,etagDescription);
    }

    private void documentEtag(Operation operation,String description){
        ApiResponse response=operation.getResponses().get("200");
        if(response!=null)response.addHeaderObject("ETag",new Header().description(description).schema(new StringSchema()));
        ApiResponse created=operation.getResponses().get("201");
        if(created!=null)created.addHeaderObject("ETag",new Header().description(description).schema(new StringSchema()));
    }

    private void allowNullInOptionalProperties(Schema<?> schema){
        if(schema.getItems()!=null)allowNullInOptionalProperties(schema.getItems());
        if(schema.getProperties()==null)return;
        schema.getProperties().forEach((name,property) -> {
            boolean required=schema.getRequired()!=null&&schema.getRequired().contains(name);
            if(!required&&property.getTypes()!=null)property.addType("null");
            allowNullInOptionalProperties(property);
        });
    }

    private boolean isReadOnlyCalculation(String pathName){
        return pathName.equals("/api/v1/liquidations/preview")
            ||pathName.equals("/api/v1/electronic-payments/preview")
            ||pathName.equals("/api/v1/debts/{id}/late-charge-preview")
            ||pathName.equals("/api/v1/payment-plans/simulations")
            ||pathName.equals("/api/v1/payment-plans/{planId}/refinancing/simulations");
    }

}
