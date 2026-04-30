package net.martinstech.copiloto.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebSearchServiceTest {

    @Test
    void testSearchOllamaToolCalling() {
        WebSearchService svc = new WebSearchService();
        var results = svc.search("Ollama tool calling");
        assertFalse(results.isEmpty(), "Should find web search results for 'Ollama tool calling'");
        for (var r : results) {
            assertNotNull(r.title(), "Result should have a title");
            assertNotNull(r.url(), "Result should have a URL");
            assertTrue(r.url().startsWith("http"), "URL should start with http");
        }
    }

    @Test
    void testFormatForLlm() {
        WebSearchService svc = new WebSearchService();
        var results = svc.search("Java 25 release date");
        String formatted = svc.formatForLlm(results);
        assertNotNull(formatted);
        assertTrue(formatted.contains("Web search results:"), "Should contain header");
    }

    @Test
    void testEmptyResults() {
        WebSearchService svc = new WebSearchService();
        String formatted = svc.formatForLlm(java.util.List.of());
        assertEquals("No web search results found.", formatted);
    }
}
