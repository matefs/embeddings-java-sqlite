package dev.matefs.embeddings;

import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Epic("API de embeddings")
class MessageApiIntegrationTest {

    private static final Path TEST_DIRECTORY = createTestDirectory();
    private static String vehicleMessageId;

    @LocalServerPort
    private int port;

    private final TestRestTemplate http = createLoggedHttpClient();

    @DynamicPropertySource
    static void configureTestDatabase(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.datasource.url",
                () -> "jdbc:sqlite:" + TEST_DIRECTORY.resolve("integration-test.db")
        );
    }

    @Test
    @Order(1)
    @DisplayName("Cria mensagens e embeddings pela API HTTP")
    void createsMessagesThroughRealHttpApi() {
        JsonNode vehicle = createMessage("usuario-portugues", "Quero comprar um carro novo");
        createMessage("usuario-portugues", "Estou estudando desenvolvimento de software em Java");
        createMessage("outro-usuario", "Minha cobrança no cartão de crédito não foi aprovada");

        vehicleMessageId = vehicle.path("messageId").asText();

        assertThat(vehicleMessageId).isNotBlank();
        assertThat(vehicle.path("userId").asText()).isEqualTo("usuario-portugues");
        assertThat(vehicle.path("messageText").asText()).isEqualTo("Quero comprar um carro novo");
        assertThat(vehicle.path("vectorDimensions").asInt()).isEqualTo(384);
    }

    @Test
    @Order(2)
    @DisplayName("Lista somente mensagens do usuário solicitado")
    void listsOnlyMessagesFromRequestedUser() {
        ResponseEntity<JsonNode> response = http.getForEntity(
                url("/api/messages/user/usuario-portugues?limit=20"),
                JsonNode.class
        );

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isArray()).isTrue();
        assertThat(response.getBody()).hasSize(2);
        assertThat(response.getBody().findValuesAsText("userId"))
                .containsOnly("usuario-portugues");
    }

    @Test
    @Order(3)
    @DisplayName("Executa busca semântica em português por usuário")
    void performsSemanticSearchInPortugueseForOneUser() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "plain", StandardCharsets.UTF_8));
        HttpEntity<String> request = new HttpEntity<>("aquisição de veículo", headers);

        ResponseEntity<JsonNode> response = http.exchange(
                url("/api/messages/user/usuario-portugues/search?limit=2"),
                HttpMethod.POST,
                request,
                JsonNode.class
        );

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode results = response.getBody();
        assertThat(results).isNotNull();
        assertThat(results).hasSize(2);
        assertThat(results.get(0).path("messageText").asText())
                .isEqualTo("Quero comprar um carro novo");
        assertThat(results.get(0).path("vectorDimensions").asInt()).isEqualTo(384);
        assertThat(results.get(0).path("similarityScore").asDouble())
                .isGreaterThan(results.get(1).path("similarityScore").asDouble())
                .isBetween(0.0, 1.0);
    }

    @Test
    @Order(4)
    @DisplayName("Executa busca semântica global")
    void performsGlobalSearchIndependentlyOfUser() {
        ResponseEntity<JsonNode> response = http.getForEntity(
                url("/api/messages/search?query={query}&limit=3"),
                JsonNode.class,
                "pagamento recusado no cartão"
        );

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode results = response.getBody();
        assertThat(results).isNotNull();
        assertThat(results).hasSize(3);
        assertThat(results.get(0).path("userId").asText()).isEqualTo("outro-usuario");
        assertThat(results.get(0).path("messageText").asText())
                .isEqualTo("Minha cobrança no cartão de crédito não foi aprovada");
        assertThat(results.get(0).path("vectorDimensions").asInt()).isEqualTo(384);
    }

    @Test
    @Order(5)
    @DisplayName("Retorna HTTP 400 para payload e limite inválidos")
    void returnsBadRequestForInvalidPayloadAndLimit() {
        ResponseEntity<JsonNode> invalidMessage = http.postForEntity(
                url("/api/messages"),
                Map.of("userId", "", "messageText", ""),
                JsonNode.class
        );
        ResponseEntity<JsonNode> invalidLimit = http.getForEntity(
                url("/api/messages/user/usuario-portugues?limit=101"),
                JsonNode.class
        );

        assertThat(invalidMessage.getStatusCode().value()).isEqualTo(400);
        assertThat(invalidLimit.getStatusCode().value()).isEqualTo(400);
        assertThat(invalidMessage.getBody()).isNotNull();
        assertThat(invalidMessage.getBody().path("messages")).hasSize(2);
        assertThat(invalidLimit.getBody()).isNotNull();
        assertThat(invalidLimit.getBody().path("messages").get(0).asText())
                .contains("limit");
    }

    @Test
    @Order(6)
    @DisplayName("Exclui uma mensagem e depois retorna HTTP 404")
    void deletesMessageAndThenReturnsNotFound() {
        ResponseEntity<Void> firstDeletion = http.exchange(
                url("/api/messages/" + vehicleMessageId),
                HttpMethod.DELETE,
                HttpEntity.EMPTY,
                Void.class
        );
        ResponseEntity<JsonNode> secondDeletion = http.exchange(
                url("/api/messages/" + vehicleMessageId),
                HttpMethod.DELETE,
                HttpEntity.EMPTY,
                JsonNode.class
        );

        assertThat(firstDeletion.getStatusCode().value()).isEqualTo(204);
        assertThat(secondDeletion.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @Order(7)
    @DisplayName("Expõe a especificação OpenAPI gerada")
    void exposesGeneratedOpenApiForRealEndpoints() {
        ResponseEntity<JsonNode> response = http.getForEntity(url("/v3/api-docs"), JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode document = response.getBody();
        assertThat(document).isNotNull();
        assertThat(document.path("info").path("title").asText())
                .isEqualTo("Embeddings Java SQLite API");
        assertThat(document.path("paths").has("/api/messages/search")).isTrue();
        assertThat(document.path("paths").has("/api/messages/{messageId}")).isTrue();
        assertThat(document.path("paths").has("/api/messages/reindex")).isTrue();
    }

    private JsonNode createMessage(String userId, String messageText) {
        ResponseEntity<JsonNode> response = http.postForEntity(
                url("/api/messages"),
                Map.of("userId", userId, "messageText", messageText),
                JsonNode.class
        );
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static Path createTestDirectory() {
        try {
            return Files.createTempDirectory("embeddings-api-integration-test-");
        } catch (IOException exception) {
            throw new IllegalStateException("Não foi possível criar o diretório temporário dos testes", exception);
        }
    }

    private static TestRestTemplate createLoggedHttpClient() {
        TestRestTemplate client = new TestRestTemplate();
        client.getRestTemplate().setRequestFactory(
                new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory())
        );
        client.getRestTemplate().setInterceptors(List.of((request, body, execution) -> {
            System.out.printf("[HTTP] --> %s %s%n", request.getMethod(), request.getURI());
            if (body.length > 0) {
                System.out.printf("[HTTP] Request: %s%n", abbreviate(new String(body, StandardCharsets.UTF_8)));
            }

            Instant startedAt = Instant.now();
            ClientHttpResponse response = execution.execute(request, body);
            long elapsedMilliseconds = Duration.between(startedAt, Instant.now()).toMillis();
            byte[] responseBody = response.getBody().readAllBytes();

            System.out.printf(
                    "[HTTP] <-- %d %s (%d ms)%n",
                    response.getStatusCode().value(),
                    HttpStatus.valueOf(response.getStatusCode().value()).getReasonPhrase(),
                    elapsedMilliseconds
            );
            if (responseBody.length > 0) {
                System.out.printf(
                        "[HTTP] Response: %s%n",
                        abbreviate(new String(responseBody, StandardCharsets.UTF_8))
                );
            }
            Allure.addAttachment(
                    request.getMethod() + " " + request.getURI().getPath()
                            + " — HTTP " + response.getStatusCode().value(),
                    "text/plain",
                    formatHttpExchange(request.getMethod().name(), request.getURI().toString(), body, response, responseBody),
                    ".txt"
            );
            return response;
        }));
        return client;
    }

    private static String abbreviate(String value) {
        String singleLine = value.replaceAll("\\s+", " ").trim();
        int maximumLength = 800;
        if (singleLine.length() <= maximumLength) {
            return singleLine;
        }
        return singleLine.substring(0, maximumLength) + "... [truncado]";
    }

    private static String formatHttpExchange(
            String method,
            String uri,
            byte[] requestBody,
            ClientHttpResponse response,
            byte[] responseBody
    ) throws IOException {
        String requestText = requestBody.length == 0
                ? "<sem corpo>"
                : new String(requestBody, StandardCharsets.UTF_8);
        String responseText = responseBody.length == 0
                ? "<sem corpo>"
                : new String(responseBody, StandardCharsets.UTF_8);
        return """
                REQUEST
                %s %s

                %s

                RESPONSE
                HTTP %d %s

                %s
                """.formatted(
                method,
                uri,
                requestText,
                response.getStatusCode().value(),
                HttpStatus.valueOf(response.getStatusCode().value()).getReasonPhrase(),
                responseText
        );
    }
}
