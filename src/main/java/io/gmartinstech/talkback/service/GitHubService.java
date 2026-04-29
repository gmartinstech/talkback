package io.gmartinstech.talkback.service;

import io.gmartinstech.talkback.domain.PullRequest;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Service for GitHub PR operations using the {@code gh} CLI.
 * Requires the user to have authenticated {@code gh auth login} beforehand.
 */
public class GitHubService {
    private static final Pattern PR_PATTERN = Pattern.compile(
        "github\\.com/([^/]+)/([^/]+)/pull/(\\d+)");
    private final Path reposDir;

    public GitHubService(Path reposDir) {
        this.reposDir = reposDir;
    }

    /**
     * Parses a GitHub PR URL into its owner, repo and number components.
     *
     * @param url the URL to parse
     * @return the parsed PR info, or empty if the URL is not a PR link
     */
    public Optional<PrInfo> parsePrUrl(String url) {
        Matcher m = PR_PATTERN.matcher(url);
        if (m.find()) {
            return Optional.of(new PrInfo(m.group(1), m.group(2), Integer.parseInt(m.group(3))));
        }
        return Optional.empty();
    }

    /**
     * Fetches the diff text for a pull request.
     *
     * @param owner  the repository owner
     * @param repo   the repository name
     * @param number the PR number
     * @return the raw diff text
     * @throws Exception if the {@code gh} invocation fails
     */
    public String fetchPrDiff(String owner, String repo, int number) throws Exception {
        var pb = new ProcessBuilder("gh", "pr", "diff", String.valueOf(number),
            "--repo", owner + "/" + repo);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String output = reader.lines().collect(Collectors.joining("\n"));
            int exit = p.waitFor();
            if (exit != 0) {
                throw new RuntimeException("gh pr diff failed with exit " + exit + ": " + output);
            }
            return output;
        }
    }

    /**
     * Ensures the repository is cloned locally.
     *
     * @param owner the repository owner
     * @param repo  the repository name
     * @return the local path to the repository
     * @throws Exception if cloning fails
     */
    public Path cloneRepo(String owner, String repo) throws Exception {
        Path target = reposDir.resolve(owner).resolve(repo);
        if (!Files.exists(target)) {
            Files.createDirectories(target.getParent());
            var pb = new ProcessBuilder("gh", "repo", "clone", owner + "/" + repo, target.toString());
            pb.inheritIO();
            int exit = pb.start().waitFor();
            if (exit != 0) {
                throw new RuntimeException("gh repo clone failed with exit " + exit);
            }
        }
        return target;
    }

    /**
     * Extracts changed file paths from a unified diff.
     *
     * @param diff the raw diff text
     * @return a list of file paths
     */
    public List<String> getChangedFiles(String diff) {
        List<String> files = new ArrayList<>();
        Pattern p = Pattern.compile("^diff --git a/(.+) b/.+$", Pattern.MULTILINE);
        Matcher m = p.matcher(diff);
        while (m.find()) {
            files.add(m.group(1));
        }
        return files;
    }

    /**
     * Creates a {@link PullRequest} domain object from a URL by fetching its diff and files.
     *
     * @param url the PR URL
     * @return the populated PullRequest
     * @throws Exception if fetching fails
     */
    public PullRequest createPullRequest(String url) throws Exception {
        var info = parsePrUrl(url).orElseThrow(() -> new IllegalArgumentException("Invalid PR URL"));
        cloneRepo(info.owner(), info.repo());
        String diff = fetchPrDiff(info.owner(), info.repo(), info.number());
        List<String> files = getChangedFiles(diff);
        return new PullRequest(info.owner(), info.repo(), info.number(), diff, files);
    }

    public record PrInfo(String owner, String repo, int number) {}
}
