package io.gmartinstech.talkback.domain;

import java.util.List;

/**
 * Represents a GitHub pull request review request, including the parsed metadata
 * and the raw diff text.
 */
public record PullRequest(String owner, String repo, int number, String diff, List<String> files) {
    public PullRequest {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("owner must not be blank");
        if (repo == null || repo.isBlank()) throw new IllegalArgumentException("repo must not be blank");
        if (number <= 0) throw new IllegalArgumentException("number must be positive");
    }

    public String fullName() {
        return owner + "/" + repo + "#" + number;
    }
}
