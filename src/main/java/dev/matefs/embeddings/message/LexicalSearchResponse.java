package dev.matefs.embeddings.message;

public record LexicalSearchResponse(
        String messageId,
        String userId,
        String messageText,
        int vectorDimensions,
        int lexicalRank,
        double bm25Score
) {
}
