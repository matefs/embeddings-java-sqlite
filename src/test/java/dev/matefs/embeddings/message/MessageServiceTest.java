package dev.matefs.embeddings.message;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageServiceTest {

    @Test
    void calculatesCosineSimilarity() {
        assertThat(MessageService.cosineSimilarity(
                new float[]{1, 0},
                new float[]{1, 0}
        )).isEqualTo(1.0);

        assertThat(MessageService.cosineSimilarity(
                new float[]{1, 0},
                new float[]{0, 1}
        )).isEqualTo(0.0);
    }

    @Test
    void rejectsVectorsWithDifferentDimensions() {
        assertThatThrownBy(() -> MessageService.cosineSimilarity(
                new float[]{1},
                new float[]{1, 2}
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
