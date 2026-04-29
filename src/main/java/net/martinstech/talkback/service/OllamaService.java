package net.martinstech.talkback.service;

import module java.net.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Service for interacting with a local Ollama LLM server.
 * Uses LangChain4j for streaming chat and the raw {@link HttpClient}
 * for model discovery via {@code /api/tags}.
 */
public class OllamaService {
    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public OllamaService(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        this.mapper = new ObjectMapper();
    }

    /**
     * Lists available models from the Ollama {@code /api/tags} endpoint.
     *
     * @return a list of model names (e.g. {@code "gemma4:4b"})
     */
    public List<String> listModels() {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/tags"))
            .GET()
            .timeout(Duration.ofSeconds(5))
            .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("Ollama returned HTTP " + response.statusCode());
                return List.of();
            }
            JsonNode root = mapper.readTree(response.body());
            var models = new ArrayList<String>();
            if (root.has("models")) {
                for (JsonNode model : root.get("models")) {
                    if (model.has("name")) {
                        models.add(model.get("name").asText());
                    }
                }
            }
            return models;
        } catch (Exception e) {
            throw new RuntimeException("Failed to list Ollama models", e);
        }
    }

    /**
     * Streams a chat completion from Ollama.
     *
     * @param model     the model name to use
     * @param history   the conversation history (LangChain4j messages)
     * @param onChunk   called for each token
     * @param onError   called on failure
     * @param onComplete called when the stream finishes
     */
    public void chatStream(String model, List<ChatMessage> history,
                           Consumer<String> onChunk, Consumer<Throwable> onError, Runnable onComplete) {
        try {
            var streamingModel = OllamaStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .modelName(model)
                .build();

            streamingModel.generate(history, new StreamingResponseHandler<AiMessage>() {
                @Override
                public void onNext(String token) {
                    onChunk.accept(token);
                }

                @Override
                public void onComplete(dev.langchain4j.model.output.Response<AiMessage> response) {
                    onComplete.run();
                }

                @Override
                public void onError(Throwable error) {
                    onError.accept(error);
                }
            });
        } catch (Exception e) {
            onError.accept(e);
        }
    }
}
