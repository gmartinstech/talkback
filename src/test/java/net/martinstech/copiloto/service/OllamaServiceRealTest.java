package net.martinstech.copiloto.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests against a real Ollama server.
 * Only runs when OLLAMA_TEST_ENABLED is set (to avoid failing CI).
 */
@EnabledIfEnvironmentVariable(named = "OLLAMA_TEST_ENABLED", matches = "true")
class OllamaServiceRealTest {

    @Test
    void testListModels() {
        var service = new OllamaService("http://localhost:11434");
        List<String> models = service.listModels();
        System.out.println("Available models: " + models);
        assertFalse(models.isEmpty(), "Ollama should have at least one model");
    }

    @Test
    void testChatStream() throws Exception {
        var service = new OllamaService("http://localhost:11434");
        List<String> chunks = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        service.chatStream(
            "qwen3-coder:latest",
            List.of(dev.langchain4j.data.message.UserMessage.userMessage("Say 'hello' and nothing else")),
            chunks::add,
            e -> { e.printStackTrace(); latch.countDown(); },
            latch::countDown
        );

        assertTrue(latch.await(60, TimeUnit.SECONDS), "Stream should complete within 60s");
        String response = String.join("", chunks);
        System.out.println("Response: " + response);
        assertTrue(response.toLowerCase().contains("hello"), "Response should contain 'hello'");
    }

    @Test
    void testChatWithTools() {
        var service = new OllamaService("http://localhost:11434");

        var tool = new OllamaService.ToolDefinition(
            "get_weather",
            "Get current weather for a location",
            java.util.Map.of(
                "type", "object",
                "properties", java.util.Map.of(
                    "location", java.util.Map.of("type", "string", "description", "City name")
                ),
                "required", List.of("location")
            )
        );

        String response = service.chatWithTools(
            "qwen3-coder:latest",
            List.of(new OllamaService.OllamaMessage("user", "What is the weather in Paris?", null, null, null)),
            List.of(tool),
            tc -> {
                System.out.println("Tool called: " + tc.name() + " with args: " + tc.arguments());
                return "Sunny, 25°C";
            }
        );

        System.out.println("Tool response: " + response);
        assertNotNull(response);
        assertFalse(response.isEmpty());
    }
}
