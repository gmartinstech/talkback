package net.martinstech.copiloto.async;

import net.martinstech.copiloto.domain.PullRequest;
import net.martinstech.copiloto.service.GitHubService;

import java.util.concurrent.StructuredTaskScope;

/**
 * Structured concurrency scope for the PR review pipeline.
 * Uses {@link StructuredTaskScope.ShutdownOnFailure} so that if any subtask
 * fails (e.g. cloning or diff fetching), the remaining tasks are cancelled
 * immediately and the failure is reported.
 */
public class ReviewScope {

    /**
     * Fetches the PR diff and repository metadata concurrently.
     *
     * @param url  the GitHub PR URL
     * @param git  the GitHub service
     * @return the populated {@link PullRequest}
     * @throws Exception if any subtask fails
     */
    public static PullRequest runReview(String url, GitHubService git) throws Exception {
        var info = git.parsePrUrl(url).orElseThrow(() -> new IllegalArgumentException("Invalid PR URL"));

        try (var scope = java.util.concurrent.StructuredTaskScope.<String>open()) {
            var diffTask = scope.fork(() -> git.fetchPrDiff(info.owner(), info.repo(), info.number()));
            scope.fork(() -> { git.cloneRepo(info.owner(), info.repo()); return null; });
            scope.join();

            String diff = diffTask.get();
            var files = git.getChangedFiles(diff);
            return new PullRequest(info.owner(), info.repo(), info.number(), diff, files);
        }
    }
}
