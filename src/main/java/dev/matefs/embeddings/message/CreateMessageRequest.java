package dev.matefs.embeddings.message;

import jakarta.validation.constraints.NotBlank;

public record CreateMessageRequest(
        @NotBlank(message = "userId é obrigatório") String userId,
        @NotBlank(message = "messageText é obrigatório") String messageText
) {
}
