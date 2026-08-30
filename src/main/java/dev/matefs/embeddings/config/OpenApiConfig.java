package dev.matefs.embeddings.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI embeddingsOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Embeddings Java SQLite API")
                .description("API para armazenar mensagens e realizar buscas semânticas com embeddings locais")
                .version("v1"));
    }
}
