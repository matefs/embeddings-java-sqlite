package dev.matefs.embeddings.message;

public record MessageResponse(String messageId, String userId, String messageText, int vectorDimensions) {

    static MessageResponse from(Message message) {
        return new MessageResponse(
                message.messageId(),
                message.userId(),
                message.messageText(),
                message.embedding().length
        );
    }
}
