package dev.matefs.embeddings.message;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.matefs.embeddings.config.ModelArtifactDownloader;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MessageService {

    private static final int RRF_RANK_CONSTANT = 60;
    private static final int MAX_HYBRID_CANDIDATES = 500;

    private final MessageRepository repository;
    private final EmbeddingModel embeddingModel;

    public MessageService(MessageRepository repository, EmbeddingModel embeddingModel) {
        this.repository = repository;
        this.embeddingModel = embeddingModel;
    }

    MessageResponse create(CreateMessageRequest request) {
        float[] embedding = embeddingModel.embed(request.messageText()).content().vector();
        Message message = new Message(
                UUID.randomUUID().toString(),
                request.userId(),
                request.messageText(),
                embedding,
                ModelArtifactDownloader.MODEL_ID
        );
        repository.save(message);
        return MessageResponse.from(message);
    }

    List<MessageResponse> findByUserId(String userId, int limit) {
        return repository.findByUserId(userId).stream()
                .limit(limit)
                .map(MessageResponse::from)
                .toList();
    }

    boolean delete(String messageId) {
        return repository.deleteById(messageId);
    }

    List<SimilaritySearchResponse> searchByUserId(String userId, String query, int limit) {
        return search(currentModelMessages(repository.findByUserId(userId)), query, limit);
    }

    List<SimilaritySearchResponse> searchAll(String query, int limit) {
        return search(currentModelMessages(repository.findAll()), query, limit);
    }

    List<LexicalSearchResponse> searchLexicallyByUserId(String userId, String query, int limit) {
        return repository.searchLexically(query, userId, limit);
    }

    List<LexicalSearchResponse> searchLexicallyAll(String query, int limit) {
        return repository.searchLexically(query, null, limit);
    }

    List<HybridSearchResponse> searchHybridByUserId(String userId, String query, int limit) {
        return hybridSearch(currentModelMessages(repository.findByUserId(userId)), userId, query, limit);
    }

    List<HybridSearchResponse> searchHybridAll(String query, int limit) {
        return hybridSearch(currentModelMessages(repository.findAll()), null, query, limit);
    }

    int reindexAll() {
        List<Message> messages = repository.findAll();
        messages.forEach(message -> repository.updateEmbedding(
                message.messageId(),
                embeddingModel.embed(message.messageText()).content().vector(),
                ModelArtifactDownloader.MODEL_ID
        ));
        return messages.size();
    }

    private List<Message> currentModelMessages(List<Message> messages) {
        return messages.stream()
                .filter(message -> ModelArtifactDownloader.MODEL_ID.equals(message.embeddingModel()))
                .toList();
    }

    private List<SimilaritySearchResponse> search(List<Message> messages, String query, int limit) {
        float[] target = embeddingModel.embed(query).content().vector();

        return messages.stream()
                .map(message -> new SimilaritySearchResponse(
                        message.messageId(),
                        message.userId(),
                        message.messageText(),
                        message.embedding().length,
                        cosineSimilarity(target, message.embedding())
                ))
                .sorted(Comparator.comparingDouble(SimilaritySearchResponse::similarityScore).reversed())
                .limit(limit)
                .toList();
    }

    private List<HybridSearchResponse> hybridSearch(
            List<Message> messages,
            String userId,
            String query,
            int limit
    ) {
        int candidateLimit = Math.min(Math.max(limit * 5, 50), MAX_HYBRID_CANDIDATES);
        List<SimilaritySearchResponse> vectorResults = search(messages, query, candidateLimit);
        List<LexicalSearchResponse> lexicalResults = repository.searchLexically(query, userId, candidateLimit);
        Map<String, HybridCandidate> candidates = new LinkedHashMap<>();

        for (int index = 0; index < vectorResults.size(); index++) {
            SimilaritySearchResponse result = vectorResults.get(index);
            int rank = index + 1;
            candidates.computeIfAbsent(result.messageId(), ignored -> HybridCandidate.from(result))
                    .addVectorResult(rank, result.similarityScore());
        }

        for (LexicalSearchResponse result : lexicalResults) {
            candidates.computeIfAbsent(result.messageId(), ignored -> HybridCandidate.from(result))
                    .addLexicalResult(result.lexicalRank(), result.bm25Score());
        }

        return candidates.values().stream()
                .map(HybridCandidate::toResponse)
                .sorted(Comparator.comparingDouble(HybridSearchResponse::rrfScore).reversed())
                .limit(limit)
                .toList();
    }

    private static final class HybridCandidate {
        private final String messageId;
        private final String userId;
        private final String messageText;
        private final int vectorDimensions;
        private Integer vectorRank;
        private Double vectorSimilarityScore;
        private Integer lexicalRank;
        private Double lexicalBm25Score;
        private double rrfScore;

        private HybridCandidate(String messageId, String userId, String messageText, int vectorDimensions) {
            this.messageId = messageId;
            this.userId = userId;
            this.messageText = messageText;
            this.vectorDimensions = vectorDimensions;
        }

        static HybridCandidate from(SimilaritySearchResponse result) {
            return new HybridCandidate(
                    result.messageId(), result.userId(), result.messageText(), result.vectorDimensions()
            );
        }

        static HybridCandidate from(LexicalSearchResponse result) {
            return new HybridCandidate(
                    result.messageId(), result.userId(), result.messageText(), result.vectorDimensions()
            );
        }

        HybridCandidate addVectorResult(int rank, double similarityScore) {
            vectorRank = rank;
            vectorSimilarityScore = similarityScore;
            rrfScore += reciprocalRank(rank);
            return this;
        }

        HybridCandidate addLexicalResult(int rank, double bm25Score) {
            lexicalRank = rank;
            lexicalBm25Score = bm25Score;
            rrfScore += reciprocalRank(rank);
            return this;
        }

        HybridSearchResponse toResponse() {
            return new HybridSearchResponse(
                    messageId,
                    userId,
                    messageText,
                    vectorDimensions,
                    vectorRank,
                    vectorSimilarityScore,
                    lexicalRank,
                    lexicalBm25Score,
                    rrfScore
            );
        }

        private static double reciprocalRank(int rank) {
            return 1.0 / (RRF_RANK_CONSTANT + rank);
        }
    }

    static double cosineSimilarity(float[] first, float[] second) {
        if (first.length != second.length) {
            throw new IllegalArgumentException("Os vetores precisam ter a mesma dimensão");
        }

        double dotProduct = 0;
        double firstNorm = 0;
        double secondNorm = 0;
        for (int index = 0; index < first.length; index++) {
            dotProduct += first[index] * second[index];
            firstNorm += first[index] * first[index];
            secondNorm += second[index] * second[index];
        }

        double denominator = Math.sqrt(firstNorm) * Math.sqrt(secondNorm);
        return denominator == 0 ? 0 : dotProduct / denominator;
    }
}
