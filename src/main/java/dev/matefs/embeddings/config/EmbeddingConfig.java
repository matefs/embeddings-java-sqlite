package dev.matefs.embeddings.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.OnnxEmbeddingModel;
import dev.langchain4j.model.embedding.PoolingMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class EmbeddingConfig {

    @Bean
    EmbeddingModel embeddingModel(
            ModelArtifactDownloader downloader,
            @Value("${app.embedding.model-directory}") Path modelDirectory
    ) {
        ModelArtifactDownloader.ModelFiles modelFiles = downloader.downloadIfMissing(modelDirectory);
        return new OnnxEmbeddingModel(
                modelFiles.modelPath(),
                modelFiles.tokenizerPath(),
                PoolingMode.MEAN
        );
    }
}
