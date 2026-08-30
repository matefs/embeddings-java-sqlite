///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS io.javalin:javalin:6.1.3
//DEPS org.xerial:sqlite-jdbc:3.45.2.0
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.0
//DEPS dev.langchain4j:langchain4j-embeddings-all-minilm-l6-v2:0.30.0
//DEPS org.slf4j:slf4j-simple:2.0.12

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class UserMessageRagApi {

    private static final String DATABASE_URL = "jdbc:sqlite:user_messages.db";
    private static final int DEFAULT_QUERY_LIMIT = 20;
    private static final int MAX_QUERY_LIMIT = 100;
    private static final EmbeddingModel EMBEDDING_MODEL = new AllMiniLmL6V2EmbeddingModel();

    public record CreateMessageRequest(String userId, String messageText) {}
    public record MessageResponse(String messageId, String userId, String messageText, int vectorDimensions) {}
    public record SimilaritySearchResponse(String messageId, String userId, String messageText, double similarityScore) {}

    public static void main(String[] args) throws SQLException {
        initializeDatabase();

        Javalin application = Javalin.create().start(8080);

        application.post("/api/messages", UserMessageRagApi::handleCreateMessage);
        application.get("/api/messages/user/{userId}", UserMessageRagApi::handleListMessagesByUserId);
        application.delete("/api/messages/{messageId}", UserMessageRagApi::handleDeleteMessage);
        application.post("/api/messages/user/{userId}/search", UserMessageRagApi::handleSemanticSearchByUserId);
        application.get("/api/messages/search", UserMessageRagApi::handleSemanticSearchAcrossAllUsers);
    }

    private static void initializeDatabase() throws SQLException {
        try (Connection connection = DriverManager.getConnection(DATABASE_URL);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS user_messages (
                    message_id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    message_text TEXT NOT NULL,
                    embedding_blob BLOB NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_user_messages_user_id ON user_messages(user_id);
            """);
        }
    }

    private static void handleCreateMessage(Context context) throws SQLException {
        CreateMessageRequest request = context.bodyAsClass(CreateMessageRequest.class);
        if (request.userId() == null || request.userId().isBlank() || request.messageText() == null || request.messageText().isBlank()) {
            context.status(HttpStatus.BAD_REQUEST).result("userId e messageText sao obrigatorios");
            return;
        }

        Embedding embedding = EMBEDDING_MODEL.embed(request.messageText()).content();
        float[] vectorValues = embedding.vector();
        byte[] serializedVector = serializeFloatArrayToBytes(vectorValues);
        String generatedMessageId = UUID.randomUUID().toString();

        String insertQuery = "INSERT INTO user_messages (message_id, user_id, message_text, embedding_blob) VALUES (?, ?, ?, ?)";
        try (Connection connection = DriverManager.getConnection(DATABASE_URL);
             PreparedStatement preparedStatement = connection.prepareStatement(insertQuery)) {
            preparedStatement.setString(1, generatedMessageId);
            preparedStatement.setString(2, request.userId());
            preparedStatement.setString(3, request.messageText());
            preparedStatement.setBytes(4, serializedVector);
            preparedStatement.executeUpdate();
        }

        context.status(HttpStatus.CREATED).json(new MessageResponse(generatedMessageId, request.userId(), request.messageText(), vectorValues.length));
    }

    private static void handleListMessagesByUserId(Context context) throws SQLException {
        String userId = context.pathParam("userId");
        Integer limit = resolveQueryLimit(context);
        if (limit == null) {
            return;
        }
        List<MessageResponse> messageList = new ArrayList<>();

        String selectQuery = "SELECT message_id, user_id, message_text, embedding_blob FROM user_messages WHERE user_id = ? LIMIT ?";
        try (Connection connection = DriverManager.getConnection(DATABASE_URL);
             PreparedStatement preparedStatement = connection.prepareStatement(selectQuery)) {
            preparedStatement.setString(1, userId);
            preparedStatement.setInt(2, limit);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                while (resultSet.next()) {
                    byte[] blobBytes = resultSet.getBytes("embedding_blob");
                    float[] floatArray = deserializeBytesToFloatArray(blobBytes);
                    messageList.add(new MessageResponse(
                            resultSet.getString("message_id"),
                            resultSet.getString("user_id"),
                            resultSet.getString("message_text"),
                            floatArray.length
                    ));
                }
            }
        }
        context.json(messageList);
    }

    private static void handleDeleteMessage(Context context) throws SQLException {
        String messageId = context.pathParam("messageId");
        String deleteQuery = "DELETE FROM user_messages WHERE message_id = ?";
        try (Connection connection = DriverManager.getConnection(DATABASE_URL);
             PreparedStatement preparedStatement = connection.prepareStatement(deleteQuery)) {
            preparedStatement.setString(1, messageId);
            int rowsAffected = preparedStatement.executeUpdate();
            if (rowsAffected > 0) {
                context.status(HttpStatus.NO_CONTENT);
            } else {
                context.status(HttpStatus.NOT_FOUND).result("Mensagem nao encontrada");
            }
        }
    }

    private static void handleSemanticSearchByUserId(Context context) throws SQLException {
        String userId = context.pathParam("userId");
        Integer limit = resolveQueryLimit(context);
        if (limit == null) {
            return;
        }
        String queryText = context.body();
        if (queryText.isBlank()) {
            context.status(HttpStatus.BAD_REQUEST).result("Envie o texto de busca no corpo da requisicao");
            return;
        }

        Embedding targetEmbedding = EMBEDDING_MODEL.embed(queryText).content();
        float[] targetVector = targetEmbedding.vector();

        List<SimilaritySearchResponse> searchResults = new ArrayList<>();
        String selectQuery = "SELECT message_id, user_id, message_text, embedding_blob FROM user_messages WHERE user_id = ? LIMIT ?";

        try (Connection connection = DriverManager.getConnection(DATABASE_URL);
            PreparedStatement preparedStatement = connection.prepareStatement(selectQuery)) {
            preparedStatement.setString(1, userId);
            preparedStatement.setInt(2, limit);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                while (resultSet.next()) {
                    float[] storedVector = deserializeBytesToFloatArray(resultSet.getBytes("embedding_blob"));
                    double cosineScore = calculateCosineSimilarity(targetVector, storedVector);

                    searchResults.add(new SimilaritySearchResponse(
                            resultSet.getString("message_id"),
                            resultSet.getString("user_id"),
                            resultSet.getString("message_text"),
                            cosineScore
                    ));
                }
            }
        }

        searchResults.sort((firstElement, secondElement) -> Double.compare(secondElement.similarityScore(), firstElement.similarityScore()));
        context.json(searchResults);
    }

    private static void handleSemanticSearchAcrossAllUsers(Context context) throws SQLException {
        Integer limit = resolveQueryLimit(context);
        if (limit == null) {
            return;
        }
        String queryText = context.queryParam("query");
        if (queryText == null || queryText.isBlank()) {
            context.status(HttpStatus.BAD_REQUEST).result("Informe o texto de busca no parametro query");
            return;
        }

        Embedding targetEmbedding = EMBEDDING_MODEL.embed(queryText).content();
        float[] targetVector = targetEmbedding.vector();

        List<SimilaritySearchResponse> searchResults = new ArrayList<>();
        String selectQuery = "SELECT message_id, user_id, message_text, embedding_blob FROM user_messages LIMIT ?";

        try (Connection connection = DriverManager.getConnection(DATABASE_URL);
             PreparedStatement preparedStatement = connection.prepareStatement(selectQuery)) {
            preparedStatement.setInt(1, limit);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                while (resultSet.next()) {
                    float[] storedVector = deserializeBytesToFloatArray(resultSet.getBytes("embedding_blob"));
                    double cosineScore = calculateCosineSimilarity(targetVector, storedVector);

                    searchResults.add(new SimilaritySearchResponse(
                            resultSet.getString("message_id"),
                            resultSet.getString("user_id"),
                            resultSet.getString("message_text"),
                            cosineScore
                    ));
                }
            }
        }

        searchResults.sort((firstElement, secondElement) -> Double.compare(secondElement.similarityScore(), firstElement.similarityScore()));
        context.json(searchResults);
    }

    private static Integer resolveQueryLimit(Context context) {
        String limitParameter = context.queryParam("limit");
        if (limitParameter == null) {
            return DEFAULT_QUERY_LIMIT;
        }

        try {
            int limit = Integer.parseInt(limitParameter);
            if (limit < 1 || limit > MAX_QUERY_LIMIT) {
                context.status(HttpStatus.BAD_REQUEST)
                        .result("limit deve ser um numero entre 1 e " + MAX_QUERY_LIMIT);
                return null;
            }
            return limit;
        } catch (NumberFormatException exception) {
            context.status(HttpStatus.BAD_REQUEST)
                    .result("limit deve ser um numero entre 1 e " + MAX_QUERY_LIMIT);
            return null;
        }
    }

    private static byte[] serializeFloatArrayToBytes(float[] floatVector) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(floatVector.length * Float.BYTES);
        for (float value : floatVector) {
            byteBuffer.putFloat(value);
        }
        return byteBuffer.array();
    }

    private static float[] deserializeBytesToFloatArray(byte[] byteArray) {
        FloatBuffer floatBuffer = ByteBuffer.wrap(byteArray).asFloatBuffer();
        float[] floatArray = new float[floatBuffer.remaining()];
        floatBuffer.get(floatArray);
        return floatArray;
    }

    private static double calculateCosineSimilarity(float[] firstVector, float[] secondVector) {
        double dotProduct = 0.0;
        double firstNorm = 0.0;
        double secondNorm = 0.0;
        for (int index = 0; index < firstVector.length; index++) {
            dotProduct += firstVector[index] * secondVector[index];
            firstNorm += firstVector[index] * firstVector[index];
            secondNorm += secondVector[index] * secondVector[index];
        }
        return dotProduct / (Math.sqrt(firstNorm) * Math.sqrt(secondNorm));
    }
}
