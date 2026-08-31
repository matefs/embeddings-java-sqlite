package dev.matefs.embeddings.message;

public record HybridSearchResponse(
        String messageId,
        String userId,
        String messageText,
        int vectorDimensions,
        Integer vectorRank,
        Double vectorSimilarityScore,
        Integer lexicalRank,
        Double lexicalBm25Score,
        double rrfScore
) {
}
