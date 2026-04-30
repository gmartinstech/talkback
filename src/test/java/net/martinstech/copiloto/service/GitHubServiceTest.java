package net.martinstech.copiloto.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GitHubServiceTest {

    private final GitHubService git = new GitHubService(Path.of("."));

    @Test
    void shouldParsePrUrl() {
        var result = git.parsePrUrl("https://github.com/gmartinstech/talkback/pull/42");
        assertTrue(result.isPresent());
        assertEquals("gmartinstech", result.get().owner());
        assertEquals("talkback", result.get().repo());
        assertEquals(42, result.get().number());
    }

    @Test
    void shouldRejectNonPrUrl() {
        assertTrue(git.parsePrUrl("https://github.com/gmartinstech/talkback/issues/42").isEmpty());
    }

    @Test
    void shouldExtractChangedFiles() {
        String diff = """
            diff --git a/src/Main.java b/src/Main.java
            --- a/src/Main.java
            +++ b/src/Main.java
            @@ -1 +1 @@
            -old
            +new
            diff --git a/README.md b/README.md
            --- a/README.md
            +++ b/README.md
            """;
        List<String> files = git.getChangedFiles(diff);
        assertEquals(List.of("src/Main.java", "README.md"), files);
    }
}
