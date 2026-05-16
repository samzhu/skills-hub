package io.github.samzhu.skillshub.shared.ai;

import java.util.List;
import java.util.stream.IntStream;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.google.genai.GoogleGenAiEmbeddingConnectionDetails;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingModel;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.genai.Client;

import io.github.samzhu.skillshub.SkillshubProperties;

/**
 * S171: Spring AI manual model wiring.
 *
 * <p>Provider builders stay in this config; runtime classes consume Spring AI interfaces
 * ({@link ChatClient}, {@link ChatModel}, {@link EmbeddingModel}) so chat and embedding
 * providers can be replaced from one place.
 *
 * @see io.github.samzhu.skillshub.security.scan.ScannerAiConfig
 * @see io.github.samzhu.skillshub.score.judge.QualityJudgeConfig
 * @see io.github.samzhu.skillshub.search.SearchConfig
 */
@Configuration(proxyBeanMethods = false)
public class AiModelConfig {

    private static final Logger log = LoggerFactory.getLogger(AiModelConfig.class);

    /**
     * Chat provider model — absent when no GenAI API key is configured.
     *
     * @param props resolved application properties
     * @return Gemini chat model or null when chat is unavailable
     */
    @Bean
    @Nullable
    ChatModel chatModel(SkillshubProperties props) {
        return createChatModel(props);
    }

    /**
     * Builds the concrete Google GenAI chat model behind the Spring AI {@link ChatModel} port.
     *
     * @param props resolved application properties
     * @return Gemini chat model or null when no API key is configured
     */
    public static @Nullable ChatModel createChatModel(SkillshubProperties props) {
        var apiKey = props.genai().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.info("No skillshub.genai.api-key configured — ChatModel not registered");
            return null;
        }
        log.info("Initialising shared ChatModel (Manual Config, API key mode, model=GEMINI_2_5_FLASH)");
        var client = Client.builder()
                .apiKey(apiKey)
                .build();
        return GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .defaultOptions(GoogleGenAiChatOptions.builder()
                        .model(GoogleGenAiChatModel.ChatModel.GEMINI_2_5_FLASH)
                        .temperature(0.0)
                        .build())
                .build();
    }

    /**
     * Required quality judge client.
     *
     * @param props application properties
     * @param chatModel provider for the shared chat model
     * @return named ChatClient for quality scoring
     */
    @Bean("qualityJudgeChatClient")
    @ConditionalOnProperty(name = "skillshub.quality.judge.enabled", matchIfMissing = true)
    ChatClient qualityJudgeChatClient(SkillshubProperties props, ObjectProvider<ChatModel> chatModel) {
        var model = chatModel.getIfAvailable();
        if (model == null) {
            throw new IllegalStateException("skillshub.genai.api-key is required when quality judge is enabled");
        }
        return ChatClient.builder(model).build();
    }

    /**
     * Optional scanner LLM client.
     *
     * @param props application properties
     * @param chatModel provider for the shared chat model
     * @return named ChatClient or null when scanner LLM cannot run
     */
    @Bean("scannerChatClient")
    @Nullable
    ChatClient scannerChatClient(SkillshubProperties props, ObjectProvider<ChatModel> chatModel) {
        if (!props.scanner().engines().llm().enabled()) {
            log.info("Scanner LLM engine disabled — scannerChatClient not registered");
            return null;
        }
        var model = chatModel.getIfAvailable();
        if (model == null) {
            log.info("Scanner LLM enabled but skillshub.genai.api-key absent — scannerChatClient not registered");
            return null;
        }
        return ChatClient.builder(model).build();
    }

    /**
     * Embedding provider model — real Google GenAI when configured, NoOp fallback otherwise.
     *
     * @param props resolved application properties
     * @return embedding model used by search indexing and query paths
     */
    @Bean
    EmbeddingModel embeddingModel(SkillshubProperties props) {
        return createEmbeddingModel(props);
    }

    /**
     * Builds the concrete embedding model behind the Spring AI {@link EmbeddingModel} port.
     *
     * @param props resolved application properties
     * @return Google GenAI embedding model, or NoOp fallback when no API key is configured
     */
    public static EmbeddingModel createEmbeddingModel(SkillshubProperties props) {
        var apiKey = props.genai().apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("No skillshub.genai.api-key configured — semantic search disabled (NoOp zero vector)");
            return new NoOpEmbeddingModel(props.genai().dimensions());
        }
        log.info("Initialising GoogleGenAiTextEmbeddingModel (Manual Config, API key mode)");
        var connectionDetails = GoogleGenAiEmbeddingConnectionDetails.builder()
                .apiKey(apiKey)
                .build();
        var options = GoogleGenAiTextEmbeddingOptions.builder()
                .model(props.genai().model())
                .dimensions(props.genai().dimensions())
                .build();
        return new GoogleGenAiTextEmbeddingModel(connectionDetails, options);
    }

    /**
     * NoOp embedding fallback — returns a zero vector with configured dimensions.
     */
    static final class NoOpEmbeddingModel implements EmbeddingModel {

        private final float[] zeroVector;

        NoOpEmbeddingModel(int dimensions) {
            this.zeroVector = new float[dimensions];
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            List<Embedding> embeddings = IntStream.range(0, request.getInstructions().size())
                    .mapToObj(i -> new Embedding(zeroVector, i))
                    .toList();
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(@Nullable Document document) {
            return zeroVector;
        }
    }
}
