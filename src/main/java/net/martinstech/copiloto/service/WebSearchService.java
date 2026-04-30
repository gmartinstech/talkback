package net.martinstech.copiloto.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Searches the web via DuckDuckGo Lite HTML and returns structured results.
 * No API key required.
 */
public class WebSearchService {
    private final HttpClient httpClient;

    private static final Pattern LINK_PATTERN = Pattern.compile(
        "<a rel=\"nofollow\" href=\"(https?://[^\"]+)\" class='result-link'>([^<]+)</a>"
    );
    private static final Pattern SNIPPET_PATTERN = Pattern.compile(
        "<td class='result-snippet'>(.*?)</td>", Pattern.DOTALL
    );

    public WebSearchService() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    /**
     * Searches DuckDuckGo Lite for the given query and returns top results.
     *
     * @param query the search query
     * @return a list of {@link SearchResult} objects (title, snippet, url)
     */
    public List<SearchResult> search(String query) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String body = "q=" + encoded + "&kl=us-en";

            var request = HttpRequest.newBuilder()
                .uri(URI.create("https://lite.duckduckgo.com/lite/"))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(BodyPublishers.ofString(body))
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

        Matcher linkMatcher = LINK_PATTERN.matcher(html);
        Matcher snippetMatcher = SNIPPET_PATTERN.matcher(html);

        while (linkMatcher.find()) {
            String url = linkMatcher.group(1);
            String title = cleanHtml(linkMatcher.group(2));

            String snippet = "";
            if (snippetMatcher.find()) {
                snippet = cleanHtml(stripHtmlTags(snippetMatcher.group(1)));
            }

            results.add(new SearchResult(title, snippet, url));
            if (results.size() >= 5) break;
        }

        return results;
    }

    private String stripHtmlTags(String raw) {
        return raw.replaceAll("<[^>]+>", "").trim();
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