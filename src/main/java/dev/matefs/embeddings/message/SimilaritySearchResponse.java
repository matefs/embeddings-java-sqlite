package dev.matefs.embeddings.message;

public record SimilaritySearchResponse(
        String messageId,
        String userId,
        String messageText,
        int vectorDimensions,
        double similarityScore
) {
}
