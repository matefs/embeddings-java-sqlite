package dev.matefs.embeddings.message;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.matefs.embeddings.config.ModelArtifactDownloader;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class MessageService {

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
