package ar.gob.municipalidad.rentas;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
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
                .content(new Content().addMediaType("application/json",new MediaType()
                    .schema(new Schema<>().$ref("#/components/schemas/ErrorResponse")))));
            if(openApi.getPaths()!=null)openApi.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
                for(String status:new String[]{"400","403","404","405","409","422","500"})
                    operation.getResponses().putIfAbsent(status,new ApiResponse().$ref("#/components/responses/ApiError"));
            }));
        };
    }
}
