package net.martinstech.copiloto.service;

import module java.net.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

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

    /* ------------------------------------------------------------------ */
    /* Tool-calling chat (raw HTTP for full Ollama API control)            */
    /* ------------------------------------------------------------------ */

    /**
     * Streams a chat completion with optional tool support.
     * If the model calls a tool, the {@code toolExecutor} is invoked and the
     * result is fed back into a follow-up streaming request.
     *
     * @param model        the model name to use
     * @param messages     Ollama-format messages
     * @param tools        available tool definitions (empty = no tools)
     * @param toolExecutor called when the model invokes a tool; returns the tool result string
     * @param onChunk      called for each token of the final streamed response
     * @param onError      called on failure
     * @param onComplete   called when the stream finishes
     */
    public void chatStreamWithTools(String model, List<OllamaMessage> messages,
                                     List<ToolDefinition> tools,
                                     Function<ToolCall, String> toolExecutor,
                                     Consumer<String> onChunk,
                                     Consumer<Throwable> onError,
                                     Runnable onComplete) {
        try {
            var body = buildChatBody(model, messages, tools, true);
            String responseJson = sendChat(body);

            // Parse NDJSON streaming response
            StringBuilder contentBuilder = new StringBuilder();
            List<ToolCall> toolCalls = null;
            try (var reader = new BufferedReader(new StringReader(responseJson))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    JsonNode chunk = mapper.readTree(line);
                    if (chunk.has("message")) {
                        JsonNode msg = chunk.get("message");
                        if (msg.has("content")) {
                            String token = msg.get("content").asText();
                            contentBuilder.append(token);
                        }
                        if (msg.has("tool_calls")) {
                            toolCalls = parseToolCalls(msg.get("tool_calls"));
                        }
                    }
                    if (chunk.has("done") && chunk.get("done").asBoolean()) {
                        break;
                    }
                }
            }

            if (toolCalls != null && !toolCalls.isEmpty()) {
                // Execute each tool call and build result messages
                var toolMessages = new ArrayList<OllamaMessage>();
                for (ToolCall tc : toolCalls) {
                    String result = toolExecutor.apply(tc);
                    toolMessages.add(new OllamaMessage("tool", result, null, tc.name(), null));
                }

                // Build follow-up conversation
                var followUp = new ArrayList<OllamaMessage>(messages);
                // Add assistant message with tool_calls
                followUp.add(new OllamaMessage("assistant", contentBuilder.toString(), toolCalls, null, null));
                followUp.addAll(toolMessages);

                // Stream the final response
                String finalBody = buildChatBody(model, followUp, List.of(), true);
                streamChat(finalBody, onChunk, onError, onComplete);
            } else {
                // No tool call — just emit the accumulated content as if it streamed
                String content = contentBuilder.toString();
                if (!content.isEmpty()) {
                    onChunk.accept(content);
                }
                onComplete.run();
            }
        } catch (Exception e) {
            onError.accept(e);
        }
    }

    /**
     * Non-streaming chat with tools. Used when the caller does not need
     * token-by-token streaming.
     */
    public String chatWithTools(String model, List<OllamaMessage> messages,
                                 List<ToolDefinition> tools,
                                 Function<ToolCall, String> toolExecutor) {
        try {
            String body = buildChatBody(model, messages, tools, false);
            String responseJson = sendChat(body);
            JsonNode root = mapper.readTree(responseJson);
            JsonNode msg = root.get("message");
            String content = msg.has("content") ? msg.get("content").asText() : "";

            if (msg.has("tool_calls")) {
                List<ToolCall> toolCalls = parseToolCalls(msg.get("tool_calls"));
                var followUp = new ArrayList<OllamaMessage>(messages);
                followUp.add(new OllamaMessage("assistant", content, toolCalls, null, null));
                for (ToolCall tc : toolCalls) {
                    String result = toolExecutor.apply(tc);
                    followUp.add(new OllamaMessage("tool", result, null, tc.name(), null));
                }
                String finalBody = buildChatBody(model, followUp, List.of(), false);
                JsonNode finalRoot = mapper.readTree(sendChat(finalBody));
                return finalRoot.get("message").get("content").asText();
            }
            return content;
        } catch (Exception e) {
            throw new RuntimeException("Tool chat failed", e);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                            */
    /* ------------------------------------------------------------------ */

    private String buildChatBody(String model, List<OllamaMessage> messages,
                                  List<ToolDefinition> tools, boolean stream) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("stream", stream);

        ArrayNode msgs = body.putArray("messages");
        for (OllamaMessage m : messages) {
            ObjectNode msgNode = msgs.addObject();
            msgNode.put("role", m.role());
            msgNode.put("content", m.content());
            if (m.images() != null && !m.images().isEmpty()) {
                ArrayNode imgArray = msgNode.putArray("images");
                for (String img : m.images()) {
                    imgArray.add(img);
                }
            }
            if (m.toolCalls() != null && !m.toolCalls().isEmpty()) {
                ArrayNode tcArray = msgNode.putArray("tool_calls");
                for (ToolCall tc : m.toolCalls()) {
                    ObjectNode tcNode = tcArray.addObject();
                    tcNode.put("function", tcNode.objectNode()
                        .put("name", tc.name())
                        .set("arguments", mapper.valueToTree(tc.arguments())));
                }
            }
            if (m.name() != null) {
                msgNode.put("name", m.name());
            }
        }

        if (!tools.isEmpty()) {
            ArrayNode toolsArray = body.putArray("tools");
            for (ToolDefinition td : tools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", "function");
                ObjectNode func = toolNode.putObject("function");
                func.put("name", td.name());
                func.put("description", td.description());
                func.set("parameters", mapper.valueToTree(td.parameters()));
            }
        }

        return body.toString();
    }

    private String sendChat(String body) throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/chat"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(120))
            .build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama chat HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    private void streamChat(String body,
                            Consumer<String> onChunk,
                            Consumer<Throwable> onError,
                            Runnable onComplete) throws Exception {
        var request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/api/chat"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(120))
            .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new RuntimeException("Ollama chat HTTP " + response.statusCode());
        }

        try (var reader = new BufferedReader(new StringReader(response.body()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode chunk = mapper.readTree(line);
                if (chunk.has("message") && chunk.get("message").has("content")) {
                    onChunk.accept(chunk.get("message").get("content").asText());
                }
                if (chunk.has("done") && chunk.get("done").asBoolean()) {
                    break;
                }
            }
        }
        onComplete.run();
    }

    /**
     * Streams a chat completion using raw Ollama messages (supports images).
     */
    public void chatStreamMessages(String model, List<OllamaMessage> messages,
                                    Consumer<String> onChunk, Consumer<Throwable> onError, Runnable onComplete) {
        try {
            String body = buildChatBody(model, messages, List.of(), true);
            streamChat(body, onChunk, onError, onComplete);
        } catch (Exception e) {
            onError.accept(e);
        }
    }

    private List<ToolCall> parseToolCalls(JsonNode toolCallsNode) {
        var calls = new ArrayList<ToolCall>();
        for (JsonNode tc : toolCallsNode) {
            JsonNode func = tc.get("function");
            String name = func.get("name").asText();
            Map<String, Object> args = mapper.convertValue(func.get("arguments"), Map.class);
            calls.add(new ToolCall(name, args));
        }
        return calls;
    }

    /* ------------------------------------------------------------------ */
    /* Records                                                            */
    /* ------------------------------------------------------------------ */

    public record OllamaMessage(String role, String content,
                                 List<ToolCall> toolCalls, String name,
                                 List<String> images) {}

    public record ToolDefinition(String name, String description,
                                  Map<String, Object> parameters) {}

    public record ToolCall(String name, Map<String, Object> arguments) {}
}
