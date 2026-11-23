package dev.a2ahub.vector;

import dev.a2ahub.agent.AgentCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EmbeddingService Unit Tests")
class EmbeddingServiceTest {

    private EmbeddingService embeddingService;

    @BeforeEach
    void setUp() {
        // Initialize with null/empty key to test deterministic fallback engine
        embeddingService = new EmbeddingService("");
    }

    @Test
    @DisplayName("Should return null for null or blank input")
    void shouldReturnNullForBlankInput() {
        assertThat(embeddingService.generateEmbedding(null)).isNull();
        assertThat(embeddingService.generateEmbedding("   ")).isNull();
    }

    @Test
    @DisplayName("Should generate formatted 768-dim vector for pgvector")
    void shouldGenerateFormattedVector() {
        String embedding = embeddingService.generateEmbedding("weather forecast assistant");

        assertThat(embedding).isNotNull();
        assertThat(embedding).startsWith("[").endsWith("]");

        String inner = embedding.substring(1, embedding.length() - 1);
        String[] dimensions = inner.split(",");
        assertThat(dimensions).hasSize(768);

        // Ensure all dimensions are valid finite numbers
        for (String dim : dimensions) {
            float val = Float.parseFloat(dim.trim());
            assertThat(Float.isFinite(val)).isTrue();
        }
    }

    @Test
    @DisplayName("Should produce deterministic embedding for identical text")
    void shouldProduceDeterministicEmbedding() {
        String text = "Autonomous code review agent with Java 21 support";
        String emb1 = embeddingService.generateEmbedding(text);
        String emb2 = embeddingService.generateEmbedding(text);

        assertThat(emb1).isEqualTo(emb2);
    }

    @Test
    @ResourceLock(Resources.LOCALE)
    void shouldProduceTheSameFallbackEmbeddingAcrossLocales() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.ROOT);
            String expected = embeddingService.generateEmbedding("INDEX");
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertThat(embeddingService.generateEmbedding("INDEX")).isEqualTo(expected);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    @DisplayName("Should produce different embeddings for different inputs")
    void shouldProduceDifferentEmbeddingsForDifferentInputs() {
        String emb1 = embeddingService.generateEmbedding("Database query optimization");
        String emb2 = embeddingService.generateEmbedding("Frontend UI styling with Tailwind");

        assertThat(emb1).isNotEqualTo(emb2);
    }

    @Test
    @DisplayName("Should generate embedding from AgentCard metadata")
    void shouldGenerateAgentEmbeddingFromCard() {
        AgentCard.Skill skill = new AgentCard.Skill("search", "Web Search", "Searches the web", List.of("search", "google"));
        AgentCard card = new AgentCard(
                "SearchAgent",
                "Performs search",
                "https://search.agent.io",
                "1.0.0",
                List.of(skill),
                Map.of("streaming", true),
                List.of("REST")
        );

        String embedding = embeddingService.generateAgentEmbedding(card);

        assertThat(embedding).isNotNull();
        assertThat(embedding).startsWith("[").endsWith("]");
        assertThat(embedding.split(",")).hasSize(768);
    }

    @Test
    @DisplayName("Should return null for null AgentCard")
    void shouldReturnNullForNullCard() {
        assertThat(embeddingService.generateAgentEmbedding(null)).isNull();
    }
}
