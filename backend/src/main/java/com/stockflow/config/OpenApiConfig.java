package com.stockflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class OpenApiConfig {
    @Bean org.springdoc.core.customizers.OpenApiCustomizer errorResponses() {
        return api -> api.getPaths().forEach((path, item) -> {
            if (!path.startsWith("/api/v1/")) return;
            item.readOperations().forEach(operation -> {
                var responses = operation.getResponses();
                var statuses = java.util.Map.of("400", "Invalid request or field validation", "401", "Authentication required or invalid credentials",
                        "403", "Insufficient permissions", "404", "Resource not found", "409", "Duplicate value, stock/business conflict, archived record, last-admin protection or lock conflict", "500", "Unexpected server failure");
                statuses.forEach((status, description) -> {
                    if (!responses.containsKey(status)) responses.addApiResponse(status, new io.swagger.v3.oas.models.responses.ApiResponse().description(description)
                            .content(new io.swagger.v3.oas.models.media.Content().addMediaType("application/json", new io.swagger.v3.oas.models.media.MediaType()
                                    .schema(new io.swagger.v3.oas.models.media.Schema<>().$ref("#/components/schemas/ApiError")))));
                });
            });
        });
    }

    @Bean OpenAPI stockFlowApi() {
        return new OpenAPI().info(new Info().title("StockFlow REST API").version("v1")
                .description("Sign in with /api/v1/auth/login, then paste the access token into Authorize. Monetary values are decimal strings; timestamps are UTC. Admins record stock receipts and adjustments; movements are immutable. Both roles create and read draft orders with saved price snapshots. Drafts do not reserve stock; confirmation/cancellation follow in Phase 6."))
                .components(new Components().addSecuritySchemes("bearerAuth", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}
