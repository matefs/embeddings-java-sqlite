package dev.matefs.embeddings.message;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/messages")
@Tag(name = "Mensagens", description = "Armazenamento e busca semântica de mensagens")
public class MessageController {

    private final MessageService service;

    public MessageController(MessageService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Cria uma mensagem e gera seu embedding")
    ResponseEntity<MessageResponse> create(@Valid @RequestBody CreateMessageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "Lista as mensagens de um usuário")
    List<MessageResponse> findByUserId(
            @PathVariable String userId,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return service.findByUserId(userId, limit);
    }

    @DeleteMapping("/{messageId}")
    @Operation(summary = "Exclui uma mensagem")
    ResponseEntity<Void> delete(@PathVariable String messageId) {
        if (!service.delete(messageId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mensagem não encontrada");
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/user/{userId}/search", consumes = "text/plain")
    @Operation(summary = "Busca mensagens semanticamente semelhantes de um usuário")
    List<SimilaritySearchResponse> searchByUserId(
            @PathVariable String userId,
            @RequestBody @NotBlank String query,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return service.searchByUserId(userId, query, limit);
    }

    @GetMapping("/search")
    @Operation(summary = "Busca mensagens semanticamente semelhantes de todos os usuários")
    List<SimilaritySearchResponse> searchAll(
            @RequestParam @NotBlank String query,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return service.searchAll(query, limit);
    }

    @PostMapping(value = "/user/{userId}/search/lexical", consumes = "text/plain")
    @Operation(summary = "Busca lexical com SQLite FTS5 e BM25 para um usuário")
    List<LexicalSearchResponse> searchLexicallyByUserId(
            @PathVariable String userId,
            @RequestBody @NotBlank String query,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return service.searchLexicallyByUserId(userId, query, limit);
    }

    @GetMapping("/search/lexical")
    @Operation(summary = "Busca lexical global com SQLite FTS5 e BM25")
    List<LexicalSearchResponse> searchLexicallyAll(
            @RequestParam @NotBlank String query,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return service.searchLexicallyAll(query, limit);
    }

    @PostMapping(value = "/user/{userId}/search/hybrid", consumes = "text/plain")
    @Operation(summary = "Busca híbrida por usuário combinando vetores, FTS5, BM25 e RRF")
    List<HybridSearchResponse> searchHybridByUserId(
            @PathVariable String userId,
            @RequestBody @NotBlank String query,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return service.searchHybridByUserId(userId, query, limit);
    }

    @GetMapping("/search/hybrid")
    @Operation(summary = "Busca híbrida global combinando vetores, FTS5, BM25 e RRF")
    List<HybridSearchResponse> searchHybridAll(
            @RequestParam @NotBlank String query,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return service.searchHybridAll(query, limit);
    }

    @PostMapping("/reindex")
    @Operation(summary = "Recria todos os embeddings com o modelo atual")
    ReindexResponse reindex() {
        return new ReindexResponse(service.reindexAll());
    }

    record ReindexResponse(int reindexedMessages) {
    }
}
