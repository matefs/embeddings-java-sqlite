package dev.matefs.embeddings.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class ModelArtifactDownloader {

    public static final String MODEL_ID = "paraphrase-multilingual-MiniLM-L12-v2";

    private static final Logger LOGGER = LoggerFactory.getLogger(ModelArtifactDownloader.class);
    private static final String REVISION = "2c4055b12046f11709e9df2c122e59ffbdc2f900";
    private static final Artifact MODEL = new Artifact(
            "model_quantized.onnx",
            "https://huggingface.co/Xenova/paraphrase-multilingual-MiniLM-L12-v2/resolve/" + REVISION
                    + "/onnx/model_quantized.onnx",
            "66fc00f5f29afcaff34092e1bdd20008ca3918265a82fb9695a551e510cc4ebc"
    );
    private static final Artifact TOKENIZER = new Artifact(
            "tokenizer.json",
            "https://huggingface.co/Xenova/paraphrase-multilingual-MiniLM-L12-v2/resolve/" + REVISION
                    + "/tokenizer.json",
            "b60b6b43406a48bf3638526314f3d232d97058bc93472ff2de930d43686fa441"
    );

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    public ModelFiles downloadIfMissing(Path directory) {
        try {
            Files.createDirectories(directory);
            Path modelPath = ensureArtifact(directory, MODEL);
            Path tokenizerPath = ensureArtifact(directory, TOKENIZER);
            return new ModelFiles(modelPath, tokenizerPath);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Não foi possível preparar o modelo de embeddings", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Não foi possível preparar o modelo de embeddings", exception);
        }
    }

    private Path ensureArtifact(Path directory, Artifact artifact) throws IOException, InterruptedException {
        Path destination = directory.resolve(artifact.fileName());
        if (Files.exists(destination) && checksum(destination).equals(artifact.sha256())) {
            return destination;
        }

        LOGGER.info("Baixando {} pela primeira vez...", artifact.fileName());
        Path temporaryFile = Files.createTempFile(directory, artifact.fileName(), ".download");
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(artifact.url())).GET().build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Download retornou HTTP " + response.statusCode());
            }
            try (InputStream body = response.body()) {
                Files.copy(body, temporaryFile, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!checksum(temporaryFile).equals(artifact.sha256())) {
                throw new IOException("Checksum inválido para " + artifact.fileName());
            }
            Files.move(temporaryFile, destination, StandardCopyOption.REPLACE_EXISTING);
            return destination;
        } finally {
            Files.deleteIfExists(temporaryFile);
        }
    }

    private String checksum(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 não está disponível", exception);
        }
    }

    private record Artifact(String fileName, String url, String sha256) {
    }

    public record ModelFiles(Path modelPath, Path tokenizerPath) {
    }
}
