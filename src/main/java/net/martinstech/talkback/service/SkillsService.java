package net.martinstech.talkback.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Service for managing agent skills via the {@code npx skills} CLI and the skills.sh API.
 * Wraps discovery, installation, removal, and inspection of skills.
 */
public class SkillsService {

    private static final String SKILLS_SEARCH_API = "https://skills.sh/api/search";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration CLI_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration INSTALLED_NAMES_TTL = Duration.ofMinutes(5);
    private static final Duration SEARCH_TTL = Duration.ofMinutes(5);

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final Path skillsDir;
    private final ConcurrentHashMap<String, CacheEntry<List<String>>> installedNamesCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CacheEntry<List<SkillInfo>>> searchCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<List<SkillInfo>>> searchInFlight = new ConcurrentHashMap<>();

    public SkillsService() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        this.mapper = new ObjectMapper();
        this.skillsDir = Path.of(System.getProperty("user.home"), ".agents", "skills");
    }

    /* ------------------------------------------------------------------ */
    /* Discovery                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * Searches for skills via the skills.sh API.
     *
     * @param query the search query
     * @return a list of {@link SkillInfo} representing found skills
     */
    public List<SkillInfo> findSkills(String query) {
        // 1. Check cache first
        CacheEntry<List<SkillInfo>> cached = searchCache.get(query);
        if (cached != null && !isExpired(cached, SEARCH_TTL)) {
            return cached.value;
        }

        // 2. Deduplicate concurrent requests for the same query
        CompletableFuture<List<SkillInfo>> future = searchInFlight.computeIfAbsent(query, k -> CompletableFuture.supplyAsync(() -> {
            try {
                return doFindSkills(query);
            } finally {
                searchInFlight.remove(query);
            }
        }));

        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (ExecutionException e) {
            System.err.println("Skill search failed: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()));
            return List.of();
        }
    }

    private List<SkillInfo> doFindSkills(String query) {
        try {
            CompletableFuture<HttpResponse<String>> responseFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
                    String url = SKILLS_SEARCH_API + "?q=" + encoded;

                    var request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Accept", "application/json")
                        .GET()
                        .timeout(HTTP_TIMEOUT)
                        .build();

                    return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                } catch (IOException e) {
                    throw new RuntimeException("Network error: skills.sh API is unreachable", e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Search request interrupted", e);
                }
            });

            CompletableFuture<List<String>> installedFuture = CompletableFuture.supplyAsync(() -> listInstalledSkillNames());

            HttpResponse<String> response = responseFuture.get();
            if (response.statusCode() != 200) {
                System.err.println("skills.sh search returned HTTP " + response.statusCode());
                return List.of();
            }

            List<String> installed = installedFuture.get();

            JsonNode root = mapper.readTree(response.body());
            var results = new ArrayList<SkillInfo>();
            if (root.has("skills") && root.get("skills").isArray()) {
                for (JsonNode skill : root.get("skills")) {
                    String id = skill.has("id") ? skill.get("id").asText() : "";
                    String skillId = skill.has("skillId") ? skill.get("skillId").asText() : "";
                    String name = skill.has("name") ? skill.get("name").asText() : skillId;
                    String source = skill.has("source") ? skill.get("source").asText() : "";
                    long installs = skill.has("installs") ? skill.get("installs").asLong() : 0L;
                    boolean isInstalled = installed.contains(skillId) || installed.contains(id);

                    results.add(new SkillInfo(
                        id.isEmpty() ? source + "/" + skillId : id,
                        name,
                        "",
                        source,
                        installs,
                        isInstalled,
                        ""
                    ));
                }
            }

            searchCache.put(query, new CacheEntry<>(results, System.currentTimeMillis()));
            return results;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re && re.getMessage() != null && re.getMessage().startsWith("Network error")) {
                System.err.println("Skill search network error: " + cause.getMessage());
            } else {
                System.err.println("Skill search failed: " + (cause != null ? cause.getMessage() : e.getMessage()));
            }
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Skill search interrupted: " + e.getMessage());
            return List.of();
        } catch (Exception e) {
            System.err.println("Skill search failed: " + e.getMessage());
            return List.of();
        }
    }

    /* ------------------------------------------------------------------ */
    /* Management                                                         */
    /* ------------------------------------------------------------------ */

    /**
     * Lists installed skills without reading SKILL.md files.
     *
     * @return a list of installed {@link SkillInfo}
     */
    public List<SkillInfo> listInstalledSkills() {
        List<String> names = listInstalledSkillNames();
        return names.stream()
            .map(name -> new SkillInfo(name, name, "", "", 0L, true, ""))
            .collect(Collectors.toList());
    }

    /**
     * Installs a skill package.
     *
     * @param packageSpec the package specifier (e.g. {@code "vercel-labs/agent-skills"})
     * @return a {@link SkillResult} with success flag and CLI output
     */
    public SkillResult addSkill(String packageSpec) {
        String output = runNpxSkills("add", packageSpec, "-y");
        return new SkillResult(output != null, output != null ? output : "Falha na instalação (sem saída do CLI)");
    }

    /**
     * Removes an installed skill.
     *
     * @param skillName the skill name or ID
     * @return a {@link SkillResult} with success flag and CLI output
     */
    public SkillResult removeSkill(String skillName) {
        String output = runNpxSkills("remove", skillName, "-y");
        return new SkillResult(output != null, output != null ? output : "Falha na remoção (sem saída do CLI)");
    }

    /**
     * Updates all installed skills asynchronously.
     *
     * @return a {@link CompletableFuture} that resolves to a {@link SkillResult}
     */
    public CompletableFuture<SkillResult> updateSkills() {
        return CompletableFuture.supplyAsync(() -> {
            String output = runNpxSkills("update", "-y");
            return new SkillResult(output != null, output != null ? output : "Falha na atualização (sem saída do CLI)");
        });
    }

    /**
     * Checks for available skill updates.
     *
     * @return the CLI output, or {@code null} on failure
     */
    public String checkForUpdates() {
        return runNpxSkills("check");
    }

    /**
     * Force-refreshes the installed skills cache.
     */
    public void refreshInstalledSkills() {
        installedNamesCache.clear();
    }

    /**
     * Checks whether the {@code npx skills} CLI is available.
     *
     * @return {@code true} if the CLI responds to {@code --version}
     */
    public boolean isNpxAvailable() {
        try {
            var pb = new ProcessBuilder("npx", "skills", "--version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                System.err.println("npx skills --version timed out");
                return false;
            }
            if (p.exitValue() != 0) {
                System.err.println("npx skills --version failed with exit code " + p.exitValue());
                return false;
            }
            return true;
        } catch (IOException e) {
            System.err.println("npx skills CLI not available: " + e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("npx skills availability check interrupted");
            return false;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Skill Info                                                         */
    /* ------------------------------------------------------------------ */

    /**
     * Reads and parses the SKILL.md for the given skill.
     *
     * @param skillPath the skill path (e.g. {@code "vercel-labs/agent-skills/react-best-practices"})
     * @return the populated {@link SkillInfo}, or {@code null} if not found
     */
    public SkillInfo getSkillDetails(String skillPath) {
        Path skillMd = resolveSkillMd(skillPath);
        if (!Files.exists(skillMd)) {
            return null;
        }

        try {
            String content = Files.readString(skillMd);
            FrontMatter fm = parseFrontMatter(content);

            String id = skillPath;
            String name = fm.name != null && !fm.name.isBlank() ? fm.name : skillPath;
            String description = fm.description != null ? fm.description : "";
            String source = extractSource(skillPath);

            return new SkillInfo(id, name, description, source, 0L, true, content);
        } catch (Exception e) {
            System.err.println("Failed to read skill details for " + skillPath + ": " + e.getMessage());
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Helpers                                                            */
    /* ------------------------------------------------------------------ */

    private String runNpxSkills(String... args) {
        var command = new ArrayList<String>();
        command.add("npx");
        command.add("skills");
        Collections.addAll(command, args);

        var pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process p = null;
        try {
            p = pb.start();
            try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String output = reader.lines().collect(Collectors.joining("\n"));
                boolean finished = p.waitFor(CLI_TIMEOUT.getSeconds(), TimeUnit.SECONDS);
                if (!finished) {
                    System.err.println("npx skills timed out");
                    p.destroyForcibly();
                    return null;
                }
                int exit = p.exitValue();
                if (exit != 0) {
                    System.err.println("npx skills failed with exit " + exit + ": " + output);
                    return null;
                }
                return output;
            }
        } catch (IOException e) {
            if (e.getMessage() != null && (e.getMessage().contains("CreateProcess error=2") || e.getMessage().contains("Cannot run program"))) {
                System.err.println("npx skills CLI not found. Ensure Node.js and npx are installed and in PATH.");
            } else {
                System.err.println("npx skills invocation failed: " + e.getMessage());
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("npx skills invocation interrupted");
            return null;
        } finally {
            if (p != null) {
                p.destroyForcibly();
            }
        }
    }

    private List<String> listInstalledSkillNames() {
        CacheEntry<List<String>> entry = installedNamesCache.get("names");
        if (entry != null && !isExpired(entry, INSTALLED_NAMES_TTL)) {
            return entry.value;
        }

        String output = runNpxSkills("list", "--json");
        if (output == null || output.isBlank()) {
            return List.of();
        }

        try {
            List<String> names = parseInstalledNames(output);
            installedNamesCache.put("names", new CacheEntry<>(names, System.currentTimeMillis()));
            return names;
        } catch (Exception e) {
            System.err.println("Failed to parse installed skills JSON, falling back to plain text: " + e.getMessage());
            List<String> fallback = parsePlainTextList(output);
            installedNamesCache.put("names", new CacheEntry<>(fallback, System.currentTimeMillis()));
            return fallback;
        }
    }

    private List<String> parseInstalledNames(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        var names = new ArrayList<String>();
        if (root.isArray()) {
            for (JsonNode skill : root) {
                String name = extractNameFromSkillNode(skill);
                if (name != null) names.add(name);
            }
        } else if (root.isObject() && root.has("skills") && root.get("skills").isArray()) {
            for (JsonNode skill : root.get("skills")) {
                String name = extractNameFromSkillNode(skill);
                if (name != null) names.add(name);
            }
        }
        return names;
    }

    private String extractNameFromSkillNode(JsonNode skill) {
        if (skill.isTextual()) {
            return skill.asText();
        }
        if (skill.has("name")) {
            return skill.get("name").asText();
        }
        if (skill.has("id")) {
            return skill.get("id").asText();
        }
        if (skill.has("skillId")) {
            return skill.get("skillId").asText();
        }
        return null;
    }

    private List<String> parsePlainTextList(String output) {
        return output.lines()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .collect(Collectors.toList());
    }

    private Path resolveSkillMd(String skillPath) {
        // skillPath may be "source/skillId" or "source/skillId@version"
        String normalized = skillPath;
        int atIndex = normalized.indexOf('@');
        if (atIndex != -1) {
            normalized = normalized.substring(0, atIndex);
        }
        return skillsDir.resolve(normalized).resolve("SKILL.md");
    }

    private String extractSource(String skillPath) {
        int slash = skillPath.indexOf('/');
        if (slash != -1) {
            return skillPath.substring(0, slash);
        }
        return "";
    }

    private FrontMatter parseFrontMatter(String content) {
        FrontMatter fm = new FrontMatter();
        if (!content.startsWith("---")) {
            return fm;
        }

        int end = content.indexOf("---", 3);
        if (end == -1) {
            return fm;
        }

        String yamlBlock = content.substring(3, end).trim();
        for (String line : yamlBlock.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("name:")) {
                fm.name = trimmed.substring(trimmed.indexOf(':') + 1).trim();
            } else if (trimmed.startsWith("description:")) {
                fm.description = trimmed.substring(trimmed.indexOf(':') + 1).trim();
            }
        }
        return fm;
    }

    private static <V> boolean isExpired(CacheEntry<V> entry, Duration ttl) {
        return entry == null || System.currentTimeMillis() - entry.timestamp > ttl.toMillis();
    }

    /* ------------------------------------------------------------------ */
    /* Records                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Represents a discovered or installed skill.
     *
     * @param id          skill ID (e.g. "vercel-labs/agent-skills@react-best-practices")
     * @param name        skill name from frontmatter or metadata
     * @param description skill description from frontmatter
     * @param source      source repo (e.g. "vercel-labs/agent-skills")
     * @param installs    install count (from search API)
     * @param installed   whether it's currently installed locally
     * @param content     full markdown content of SKILL.md (optional)
     */
    public record SkillInfo(String id, String name, String description,
                             String source, long installs, boolean installed,
                             String content) {}

    /**
     * Result of a skill CLI operation.
     */
    public record SkillResult(boolean success, String output) {}

    private static class CacheEntry<V> {
        final V value;
        final long timestamp;

        CacheEntry(V value, long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }
    }

    private static class FrontMatter {
        String name;
        String description;
    }
}
