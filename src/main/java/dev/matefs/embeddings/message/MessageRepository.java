package dev.matefs.embeddings.message;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Repository
public class MessageRepository {

    private final JdbcTemplate jdbcTemplate;

    public MessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void initializeDatabase() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS user_messages (
                    message_id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    message_text TEXT NOT NULL,
                    embedding_blob BLOB NOT NULL,
                    embedding_model TEXT NOT NULL DEFAULT 'all-MiniLM-L6-v2'
                )
                """);
        addEmbeddingModelColumnIfMissing();
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_user_messages_user_id
                ON user_messages(user_id)
                """);
    }

    private void addEmbeddingModelColumnIfMissing() {
        List<String> columns = jdbcTemplate.query(
                "PRAGMA table_info(user_messages)",
                (resultSet, rowNumber) -> resultSet.getString("name")
        );
        if (!columns.contains("embedding_model")) {
            jdbcTemplate.execute("""
                    ALTER TABLE user_messages
                    ADD COLUMN embedding_model TEXT NOT NULL DEFAULT 'all-MiniLM-L6-v2'
                    """);
        }
    }

    void save(Message message) {
        jdbcTemplate.update(
                "INSERT INTO user_messages (message_id, user_id, message_text, embedding_blob, embedding_model) VALUES (?, ?, ?, ?, ?)",
                message.messageId(),
                message.userId(),
                message.messageText(),
                serialize(message.embedding()),
                message.embeddingModel()
        );
    }

    List<Message> findByUserId(String userId) {
        return jdbcTemplate.query(
                "SELECT message_id, user_id, message_text, embedding_blob, embedding_model FROM user_messages WHERE user_id = ?",
                this::mapMessage,
                userId
        );
    }

    List<Message> findAll() {
        return jdbcTemplate.query(
                "SELECT message_id, user_id, message_text, embedding_blob, embedding_model FROM user_messages",
                this::mapMessage
        );
    }

    void updateEmbedding(String messageId, float[] embedding, String embeddingModel) {
        jdbcTemplate.update(
                "UPDATE user_messages SET embedding_blob = ?, embedding_model = ? WHERE message_id = ?",
                serialize(embedding),
                embeddingModel,
                messageId
        );
    }

    boolean deleteById(String messageId) {
        return jdbcTemplate.update("DELETE FROM user_messages WHERE message_id = ?", messageId) > 0;
    }

    private Message mapMessage(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Message(
                resultSet.getString("message_id"),
                resultSet.getString("user_id"),
                resultSet.getString("message_text"),
                deserialize(resultSet.getBytes("embedding_blob")),
                resultSet.getString("embedding_model")
        );
    }

    private byte[] serialize(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    private float[] deserialize(byte[] bytes) {
        FloatBuffer buffer = ByteBuffer.wrap(bytes).asFloatBuffer();
        float[] vector = new float[buffer.remaining()];
        buffer.get(vector);
        return vector;
    }
}
