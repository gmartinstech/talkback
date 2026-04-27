import os
import pytest
from unittest.mock import patch, MagicMock

from frontend.services.github import GitHubClient


class TestGitHubClient:
    def test_parse_pr_url_extracts_owner_repo_number(self):
        client = GitHubClient()
        result = client.parse_pr_url("https://github.com/gmartinstech/talkback/pull/42")
        assert result == ("gmartinstech", "talkback", "42")

    def test_parse_pr_url_invalid_returns_none(self):
        client = GitHubClient()
        assert client.parse_pr_url("https://example.com") is None

    def test_clone_repo_returns_path(self):
        with patch("frontend.services.github.subprocess.run") as mock_run:
            mock_run.return_value = MagicMock(returncode=0, stdout="", stderr="")
            client = GitHubClient("/tmp/repos")
            path = client.clone_repo("gmartinstech", "talkback")
        assert path == os.path.join("/tmp/repos", "gmartinstech", "talkback")
        mock_run.assert_called_once()

    def test_fetch_pr_diff_returns_text(self):
        with patch("frontend.services.github.subprocess.run") as mock_run:
            mock_run.return_value = MagicMock(
                returncode=0, stdout="diff --git a/foo.py b/foo.py\n+bar", stderr=""
            )
            client = GitHubClient("/tmp/repos")
            diff = client.fetch_pr_diff("gmartinstech", "talkback", "42")
        assert "diff --git" in diff

    def test_get_changed_files_parses_diff(self):
        diff_text = """diff --git a/src/main.py b/src/main.py
index 123..456 100644
--- a/src/main.py
+++ b/src/main.py
@@ -1,5 +1,5 @@
 def hello():
-    return "hi"
+    return "hello"

 diff --git a/README.md b/README.md
--- a/README.md
+++ b/README.md
@@ -1 +1 @@
-Old
+New
"""
        client = GitHubClient()
        files = client.get_changed_files(diff_text)
        assert files == ["src/main.py", "README.md"]
