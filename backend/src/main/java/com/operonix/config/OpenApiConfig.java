package com.operonix.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI operonixOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Operonix API")
                        .version("v1")
                        .description("Operonix — the operating platform for growing businesses. "
                                + "Multi-tenant, industry-neutral, global-ready. All endpoints require a "
                                + "bearer token except registration, login, refresh and public reference data."))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
