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
                .description("API para mensagens com busca vetorial, lexical FTS5/BM25 e híbrida RRF")
                .version("v1"));
    }
}
