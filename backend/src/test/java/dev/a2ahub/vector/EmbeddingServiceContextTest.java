package dev.a2ahub.vector;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingServiceContextTest {

    @Test
    void springCreatesTheConfiguredServiceInOfflineMode() {
        new ApplicationContextRunner()
                .withUserConfiguration(EmbeddingService.class)
                .withPropertyValues(
                        "a2ahub.gemini.api-key=",
                        "a2ahub.gemini.embedding-model=offline-test-model")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(EmbeddingService.class);
                    EmbeddingService service = context.getBean(EmbeddingService.class);
                    String embedding = service.generateEmbedding("synthetic offline agent");
                    assertThat(embedding).startsWith("[").endsWith("]");
                    assertThat(embedding.substring(1, embedding.length() - 1).split(",")).hasSize(768);
                });
    }
}
