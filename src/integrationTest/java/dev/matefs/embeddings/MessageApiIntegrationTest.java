package dev.matefs.embeddings;

import com.fasterxml.jackson.databind.JsonNode;
import io.qameta.allure.Allure;
import io.qameta.allure.Description;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
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
    @Feature("Criação de mensagens")
    @Description("""
            Envia três requests POST /api/messages contra o servidor Spring Boot real.
            Valida HTTP 201, geração de messageId, preservação de userId e messageText e
            geração de um embedding com exatamente 384 dimensões.
            """)
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
    @Feature("Consulta por usuário")
    @Description("""
            Chama GET /api/messages/user/usuario-portugues?limit=20 depois de criar mensagens
            para dois usuários diferentes. O teste passa somente se a resposta for HTTP 200,
            contiver exatamente duas mensagens e nenhuma pertencer ao outro usuário.
            """)
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
    @Feature("Busca semântica por usuário")
    @Description("""
            Envia 'aquisição de veículo' para POST /api/messages/user/usuario-portugues/search.
            Valida que 'Quero comprar um carro novo' seja o primeiro resultado, que seu score
            seja maior que o segundo e que o embedding informado tenha 384 dimensões.
            """)
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
    @Feature("Busca semântica global")
    @Description("""
            Consulta GET /api/messages/search sem informar userId usando o texto
            'pagamento recusado no cartão'. Valida que a pesquisa considere todos os usuários
            e retorne primeiro a mensagem de cobrança cadastrada para 'outro-usuario'.
            """)
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
    @DisplayName("Retorna similaridade alta para textos semanticamente equivalentes")
    @Feature("Qualidade da similaridade")
    @Description("""
            Compara a consulta 'pagamento não autorizado no cartão de crédito' com a mensagem
            'Minha cobrança no cartão de crédito não foi aprovada'. O teste exige que essa
            paráfrase seja o primeiro resultado e produza similarityScore maior que 0,75.
            """)
    void returnsHighScoreForStrongSemanticMatch() {
        ResponseEntity<JsonNode> response = http.getForEntity(
                url("/api/messages/search?query={query}&limit=1"),
                JsonNode.class,
                "pagamento não autorizado no cartão de crédito"
        );

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode results = response.getBody();
        assertThat(results).isNotNull();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).path("messageText").asText())
                .isEqualTo("Minha cobrança no cartão de crédito não foi aprovada");
        assertThat(results.get(0).path("similarityScore").asDouble())
                .as("uma paráfrase forte deve produzir similaridade alta")
                .isGreaterThan(0.75);
    }

    @Test
    @Order(6)
    @DisplayName("Retorna similaridade baixa para textos sem relação semântica")
    @Feature("Qualidade da similaridade")
    @Description("""
            Pesquisa 'receita de bolo de chocolate com morangos' em uma base contendo apenas
            mensagens sobre veículo, software e cobrança. O teste passa somente se até o maior
            similarityScore retornado for menor que 0,35, evitando falso positivo semântico.
            """)
    void returnsLowScoreForUnrelatedText() {
        ResponseEntity<JsonNode> response = http.getForEntity(
                url("/api/messages/search?query={query}&limit=3"),
                JsonNode.class,
                "receita de bolo de chocolate com morangos"
        );

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode results = response.getBody();
        assertThat(results).isNotNull();
        assertThat(results).hasSize(3);

        double highestScore = results.get(0).path("similarityScore").asDouble();
        assertThat(highestScore)
                .as("um assunto sem relação não deve produzir similaridade alta")
                .isLessThan(0.35);
    }

    @Test
    @Order(7)
    @DisplayName("Encontra termos exatos e códigos com FTS5 e BM25")
    @Feature("Busca lexical")
    @Description("""
            Cadastra uma mensagem com os códigos raros ERR-XPTO-409 e ABC-12345 e executa as
            buscas léxicas global e por usuário. Valida que o FTS5 encontre o código exato,
            coloque a mensagem na primeira posição pelo BM25 e respeite o filtro de userId.
            """)
    void findsExactTermsAndCodesWithFts5AndBm25() {
        createMessage("suporte", "Erro ERR-XPTO-409 ao processar pedido ABC-12345");
        createMessage("suporte", "Falha genérica ao processar uma solicitação");

        ResponseEntity<JsonNode> globalResponse = http.getForEntity(
                url("/api/messages/search/lexical?query={query}&limit=5"),
                JsonNode.class,
                "ERR-XPTO-409"
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "plain", StandardCharsets.UTF_8));
        ResponseEntity<JsonNode> userResponse = http.exchange(
                url("/api/messages/user/suporte/search/lexical?limit=5"),
                HttpMethod.POST,
                new HttpEntity<>("ABC-12345", headers),
                JsonNode.class
        );

        assertThat(globalResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(globalResponse.getBody()).isNotNull();
        assertThat(globalResponse.getBody().get(0).path("messageText").asText())
                .contains("ERR-XPTO-409");
        assertThat(globalResponse.getBody().get(0).path("lexicalRank").asInt()).isEqualTo(1);
        assertThat(globalResponse.getBody().get(0).path("bm25Score").asDouble()).isNegative();

        assertThat(userResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(userResponse.getBody()).isNotNull();
        assertThat(userResponse.getBody().get(0).path("userId").asText()).isEqualTo("suporte");
        assertThat(userResponse.getBody().get(0).path("messageText").asText())
                .contains("ABC-12345");
    }

    @Test
    @Order(8)
    @DisplayName("Combina busca vetorial e lexical usando Reciprocal Rank Fusion")
    @Feature("Busca híbrida")
    @Description("""
            Pesquisa uma frase que contém significado semântico e o código ERR-XPTO-409 nos
            endpoints híbridos global e por usuário. Valida que o resultado correto fique em
            primeiro, participe dos rankings vetorial e lexical e tenha o score RRF calculado
            pela soma 1/(60 + vectorRank) + 1/(60 + lexicalRank).
            """)
    void combinesVectorAndLexicalSearchWithRrf() {
        String query = "falha no pedido ERR-XPTO-409";
        ResponseEntity<JsonNode> globalResponse = http.getForEntity(
                url("/api/messages/search/hybrid?query={query}&limit=3"),
                JsonNode.class,
                query
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "plain", StandardCharsets.UTF_8));
        ResponseEntity<JsonNode> userResponse = http.exchange(
                url("/api/messages/user/suporte/search/hybrid?limit=3"),
                HttpMethod.POST,
                new HttpEntity<>(query, headers),
                JsonNode.class
        );

        assertThat(globalResponse.getStatusCode().value()).isEqualTo(200);
        JsonNode firstResult = globalResponse.getBody().get(0);
        assertThat(firstResult.path("messageText").asText()).contains("ERR-XPTO-409");
        assertThat(firstResult.path("vectorRank").isInt()).isTrue();
        assertThat(firstResult.path("lexicalRank").isInt()).isTrue();
        assertThat(firstResult.path("vectorSimilarityScore").isNumber()).isTrue();
        assertThat(firstResult.path("lexicalBm25Score").isNumber()).isTrue();

        int vectorRank = firstResult.path("vectorRank").asInt();
        int lexicalRank = firstResult.path("lexicalRank").asInt();
        double expectedRrfScore = 1.0 / (60 + vectorRank) + 1.0 / (60 + lexicalRank);
        assertThat(firstResult.path("rrfScore").asDouble()).isCloseTo(
                expectedRrfScore,
                org.assertj.core.data.Offset.offset(0.0000000001)
        );

        assertThat(userResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(userResponse.getBody()).isNotNull();
        assertThat(userResponse.getBody().get(0).path("userId").asText()).isEqualTo("suporte");
        assertThat(userResponse.getBody().get(0).path("messageText").asText())
                .contains("ERR-XPTO-409");
    }

    @Test
    @Order(9)
    @DisplayName("Produz sinal híbrido alto quando vetores e FTS5 concordam")
    @Feature("Qualidade da busca híbrida")
    @Description("""
            Pesquisa 'problema ao processar pedido ERR-XPTO-409', que é semanticamente próximo
            e compartilha termos raros com a mensagem cadastrada. O teste exige rank 1 nas duas
            fontes, similaridade vetorial maior que 0,75 e RRF acima de 0,03.
            """)
    void producesStrongHybridSignalWhenVectorAndLexicalRankingsAgree() {
        ResponseEntity<JsonNode> response = http.getForEntity(
                url("/api/messages/search/hybrid?query={query}&limit=3"),
                JsonNode.class,
                "problema ao processar pedido ERR-XPTO-409"
        );

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        JsonNode firstResult = response.getBody().get(0);

        assertThat(firstResult.path("messageText").asText()).contains("ERR-XPTO-409");
        assertThat(firstResult.path("vectorRank").asInt()).isEqualTo(1);
        assertThat(firstResult.path("lexicalRank").asInt()).isEqualTo(1);
        assertThat(firstResult.path("vectorSimilarityScore").asDouble()).isGreaterThan(0.75);
        assertThat(firstResult.path("rrfScore").asDouble()).isGreaterThan(0.03);
    }

    @Test
    @Order(10)
    @DisplayName("Produz sinal híbrido baixo para consulta sem relação e sem termos em comum")
    @Feature("Qualidade da busca híbrida")
    @Description("""
            Pesquisa 'culinária sobremesa confeitaria morangos', sem termos presentes na base e
            sem relação com veículo, software, cobrança ou suporte. Valida ausência de ranking
            FTS5, similaridade vetorial abaixo de 0,30 e RRF formado somente pelo rank vetorial.
            """)
    void producesWeakHybridSignalForUnrelatedQueryWithoutLexicalMatches() {
        ResponseEntity<JsonNode> response = http.getForEntity(
                url("/api/messages/search/hybrid?query={query}&limit=3"),
                JsonNode.class,
                "culinária sobremesa confeitaria morangos"
        );

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        JsonNode firstResult = response.getBody().get(0);

        assertThat(firstResult.path("lexicalRank").isNull()).isTrue();
        assertThat(firstResult.path("lexicalBm25Score").isNull()).isTrue();
        assertThat(firstResult.path("vectorSimilarityScore").asDouble()).isLessThan(0.30);

        int vectorRank = firstResult.path("vectorRank").asInt();
        double expectedVectorOnlyRrf = 1.0 / (60 + vectorRank);
        assertThat(firstResult.path("rrfScore").asDouble()).isCloseTo(
                expectedVectorOnlyRrf,
                org.assertj.core.data.Offset.offset(0.0000000001)
        );
    }

    @Test
    @Order(11)
    @DisplayName("Retorna HTTP 400 para payload e limite inválidos")
    @Feature("Validação de entrada")
    @Description("""
            Envia um POST /api/messages com userId e messageText vazios e uma listagem com
            limit=101. Valida HTTP 400 nos dois casos e confere se a resposta estruturada contém
            duas mensagens para o payload e uma mensagem identificando o parâmetro limit.
            """)
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
    @Order(12)
    @DisplayName("Exclui uma mensagem e depois retorna HTTP 404")
    @Feature("Exclusão de mensagens")
    @Description("""
            Executa DELETE /api/messages/{messageId} duas vezes para o mesmo registro.
            A primeira chamada deve retornar HTTP 204 e remover a mensagem; a segunda deve
            retornar HTTP 404, comprovando que o registro deixou de existir.
            """)
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
    @Order(13)
    @DisplayName("Expõe a especificação OpenAPI gerada")
    @Feature("Documentação da API")
    @Description("""
            Chama GET /v3/api-docs contra a aplicação real. Valida HTTP 200, o título da API e
            a presença dos endpoints de busca global, exclusão e reindexação na especificação
            OpenAPI gerada automaticamente pelo Springdoc.
            """)
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
        assertThat(document.path("paths").has("/api/messages/search/lexical")).isTrue();
        assertThat(document.path("paths").has("/api/messages/search/hybrid")).isTrue();
        assertThat(document.path("paths").has("/api/messages/user/{userId}/search/lexical")).isTrue();
        assertThat(document.path("paths").has("/api/messages/user/{userId}/search/hybrid")).isTrue();
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
