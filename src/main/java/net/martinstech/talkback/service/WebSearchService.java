package net.martinstech.talkback.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Searches the web via DuckDuckGo HTML and returns structured results.
 * No API key required.
 */
public class WebSearchService {
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    private static final Pattern RESULT_PATTERN = Pattern.compile(
        "<a rel=\"nofollow\" href=\"(https?://[^\"]+)\"[^>]*>[^<]*</a>[^<]*<a[^>]*class=\"result__a\"[^>]*href=\"(https?://[^\"]+)\"[^>]*>([^<]+)</a>[^<]*<a[^>]*class=\"result__snippet\"[^>]*>([^<]+)</a>"
    );

    public WebSearchService() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        this.mapper = new ObjectMapper();
    }

    /**
     * Searches DuckDuckGo for the given query and returns top results.
     *
     * @param query the search query
     * @return a list of {@link SearchResult} objects (title, snippet, url)
     */
    public List<SearchResult> search(String query) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://html.duckduckgo.com/html/?q=" + encoded;

            var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.0")
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();

            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("DuckDuckGo returned HTTP " + response.statusCode());
                return List.of();
            }

            return parseResults(response.body());
        } catch (Exception e) {
            System.err.println("Web search failed: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Formats search results as a plain-text context string for LLM consumption.
     */
    public String formatForLlm(List<SearchResult> results) {
        if (results.isEmpty()) {
            return "No web search results found.";
        }
        StringBuilder sb = new StringBuilder("Web search results:\n\n");
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            sb.append(i + 1).append(". ").append(r.title()).append("\n")
              .append("   ").append(r.snippet()).append("\n")
              .append("   URL: ").append(r.url()).append("\n\n");
        }
        return sb.toString();
    }

    private List<SearchResult> parseResults(String html) {
        var results = new ArrayList<SearchResult>();

        // Extract result blocks using a simpler pattern
        // Each result has: result__a (title+url), result__snippet (snippet)
        Pattern resultPat = Pattern.compile(
            "<a rel=\"nofollow\" class=\"result__a\" href=\"//duckduckgo.com/l/\\?uddg=([^\"&]+)[^\"]*\">([^<]+)</a>"
        );
        Pattern snippetPat = Pattern.compile(
            "<a class=\"result__snippet\"[^\u003e]*>([^<]+)</a>"
        );

        Matcher titleMatcher = resultPat.matcher(html);
        Matcher snippetMatcher = snippetPat.matcher(html);

        while (titleMatcher.find()) {
            String encodedUrl = titleMatcher.group(1);
            String title = cleanHtml(titleMatcher.group(2));
            String url = decodeUrl(encodedUrl);

            String snippet = "";
            if (snippetMatcher.find()) {
                snippet = cleanHtml(snippetMatcher.group(1));
            }

            results.add(new SearchResult(title, snippet, url));
            if (results.size() >= 5) break;
        }

        return results;
    }

    private String decodeUrl(String encoded) {
        try {
            return java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return encoded;
        }
    }

    private String cleanHtml(String raw) {
        return raw.replace("&amp;", "&")
                  .replace("&lt;", "<")
                  .replace("&gt;", ">")
                  .replace("&quot;", "\"")
                  .replace("&#39;", "'")
                  .trim();
    }

    public record SearchResult(String title, String snippet, String url) {}
}
