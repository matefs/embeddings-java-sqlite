package dev.matefs.embeddings.message;

public record Message(
        String messageId,
        String userId,
        String messageText,
        float[] embedding,
        String embeddingModel
) {
}
