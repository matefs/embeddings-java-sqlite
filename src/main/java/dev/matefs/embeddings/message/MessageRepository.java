package dev.matefs.embeddings.message;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Repository
public class MessageRepository {

    private static final Pattern SEARCH_TOKEN = Pattern.compile("[\\p{L}\\p{N}_]+");

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
        initializeFullTextSearch();
    }

    private void initializeFullTextSearch() {
        jdbcTemplate.execute("""
                CREATE VIRTUAL TABLE IF NOT EXISTS user_messages_fts USING fts5(
                    message_id UNINDEXED,
                    user_id UNINDEXED,
                    message_text,
                    tokenize = 'unicode61 remove_diacritics 2'
                )
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER IF NOT EXISTS user_messages_fts_after_insert
                AFTER INSERT ON user_messages
                BEGIN
                    INSERT INTO user_messages_fts(message_id, user_id, message_text)
                    VALUES (new.message_id, new.user_id, new.message_text);
                END
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER IF NOT EXISTS user_messages_fts_after_delete
                AFTER DELETE ON user_messages
                BEGIN
                    DELETE FROM user_messages_fts WHERE message_id = old.message_id;
                END
                """);
        jdbcTemplate.execute("""
                CREATE TRIGGER IF NOT EXISTS user_messages_fts_after_update
                AFTER UPDATE OF user_id, message_text ON user_messages
                BEGIN
                    DELETE FROM user_messages_fts WHERE message_id = old.message_id;
                    INSERT INTO user_messages_fts(message_id, user_id, message_text)
                    VALUES (new.message_id, new.user_id, new.message_text);
                END
                """);

        // Sincroniza registros criados antes da introdução do FTS5.
        jdbcTemplate.execute("DELETE FROM user_messages_fts");
        jdbcTemplate.execute("""
                INSERT INTO user_messages_fts(message_id, user_id, message_text)
                SELECT message_id, user_id, message_text FROM user_messages
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

    List<LexicalSearchResponse> searchLexically(String query, String userId, int limit) {
        String ftsQuery = toFtsQuery(query);
        if (ftsQuery.isBlank()) {
            return List.of();
        }

        String userFilter = userId == null ? "" : " AND m.user_id = ?";
        String sql = """
                SELECT m.message_id,
                       m.user_id,
                       m.message_text,
                       length(m.embedding_blob) / 4 AS vector_dimensions,
                       bm25(user_messages_fts) AS bm25_score
                FROM user_messages_fts
                JOIN user_messages m ON m.message_id = user_messages_fts.message_id
                WHERE user_messages_fts MATCH ?
                """ + userFilter + " ORDER BY bm25_score ASC LIMIT ?";

        Object[] parameters = userId == null
                ? new Object[]{ftsQuery, limit}
                : new Object[]{ftsQuery, userId, limit};

        return jdbcTemplate.query(sql, (resultSet, rowNumber) -> new LexicalSearchResponse(
                resultSet.getString("message_id"),
                resultSet.getString("user_id"),
                resultSet.getString("message_text"),
                resultSet.getInt("vector_dimensions"),
                rowNumber + 1,
                resultSet.getDouble("bm25_score")
        ), parameters);
    }

    private String toFtsQuery(String query) {
        Matcher matcher = SEARCH_TOKEN.matcher(query.toLowerCase(Locale.ROOT));
        StringBuilder ftsQuery = new StringBuilder();
        while (matcher.find()) {
            if (!ftsQuery.isEmpty()) {
                ftsQuery.append(" OR ");
            }
            ftsQuery.append('"').append(matcher.group()).append('"');
        }
        return ftsQuery.toString();
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
