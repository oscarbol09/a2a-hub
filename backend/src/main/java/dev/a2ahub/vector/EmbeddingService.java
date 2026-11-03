package dev.a2ahub.vector;

import dev.a2ahub.agent.AgentCard;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;


@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
    private static final int VECTOR_DIMENSION = 768;

    private final EmbeddingModel geminiEmbeddingModel;

    public EmbeddingService(String geminiApiKey) {
        this(geminiApiKey, "text-embedding-004");
    }

    @Autowired
    public EmbeddingService(
            @Value("${a2ahub.gemini.api-key:${GEMINI_API_KEY:}}") String geminiApiKey,
            @Value("${a2ahub.gemini.embedding-model:${GEMINI_EMBEDDING_MODEL:text-embedding-004}}") String embeddingModelName) {
        if (geminiApiKey != null && !geminiApiKey.isBlank()) {
            log.info("Initializing LangChain4j Google AI Gemini Embedding Model ({})", embeddingModelName);
            this.geminiEmbeddingModel = GoogleAiEmbeddingModel.builder()
                    .apiKey(geminiApiKey.trim())
                    .modelName(embeddingModelName)
                    .outputDimensionality(768)
                    .timeout(Duration.ofSeconds(10))
                    .maxRetries(3)
                    .build();
        } else {
            log.info("No Gemini API key provided. Using deterministic fallback vector generator for local/air-gapped mode.");
            this.geminiEmbeddingModel = null;
        }
    }

    /**
     * Generates a 768-dimensional normalized embedding for text.
     */
    public String generateEmbedding(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        float[] vector;
        if (geminiEmbeddingModel != null) {
            try {
                Embedding embedding = geminiEmbeddingModel.embed(text).content();
                vector = embedding.vector();
            } catch (Exception e) {
                log.warn("Gemini embedding generation failed: {}. Falling back to deterministic vector.", e.getMessage());
                vector = generateDeterministicVector(text);
            }
        } else {
            vector = generateDeterministicVector(text);
        }

        return formatPgVector(vector);
    }

    /**
     * Generates embedding from structured AgentCard.
     */
    public String generateAgentEmbedding(AgentCard card) {
        if (card == null) return null;

        StringBuilder sb = new StringBuilder();
        if (card.name() != null) sb.append(card.name()).append(" ");
        if (card.description() != null) sb.append(card.description()).append(" ");

        if (card.skills() != null) {
            for (AgentCard.Skill s : card.skills()) {
                if (s.name() != null) sb.append(s.name()).append(" ");
                if (s.description() != null) sb.append(s.description()).append(" ");
                if (s.tags() != null) sb.append(String.join(" ", s.tags())).append(" ");
            }
        }

        return generateEmbedding(sb.toString().trim());
    }

    /**
     * Deterministic, normalized 768-dimensional hash vector for local/offline mode.
     */
    private float[] generateDeterministicVector(String text) {
        float[] vector = new float[VECTOR_DIMENSION];
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.toLowerCase().getBytes(StandardCharsets.UTF_8));

            for (int i = 0; i < VECTOR_DIMENSION; i++) {
                byte b = hash[i % hash.length];
                vector[i] = (float) Math.sin((b & 0xFF) * (i + 1));
            }

            // Normalize vector to unit length for cosine similarity
            double norm = 0.0;
            for (float v : vector) {
                norm += v * v;
            }
            norm = Math.sqrt(norm);
            if (norm > 0) {
                for (int i = 0; i < VECTOR_DIMENSION; i++) {
                    vector[i] /= norm;
                }
            }
        } catch (Exception ignored) {
            Arrays.fill(vector, 0.01f);
        }
        return vector;
    }

    private String formatPgVector(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }
}
