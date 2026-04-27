import os
import re
import subprocess
from typing import Optional, List, Tuple


class GitHubClient:
    PR_URL_RE = re.compile(
        r"https://github\.com/([^/]+)/([^/]+)/pull/(\d+)"
    )

    def __init__(self, repos_dir: str = os.path.expanduser("~/.talkback/repos")):
        self.repos_dir = repos_dir

    def parse_pr_url(self, url: str) -> Optional[Tuple[str, str, str]]:
        m = self.PR_URL_RE.match(url.strip())
        if not m:
            return None
        return m.group(1), m.group(2), m.group(3)

    def clone_repo(self, owner: str, repo: str) -> str:
        dest = os.path.join(self.repos_dir, owner, repo)
        if os.path.isdir(os.path.join(dest, ".git")):
            return dest
        os.makedirs(dest, exist_ok=True)
        subprocess.run(
            ["gh", "repo", "clone", f"{owner}/{repo}", dest],
            check=True,
            capture_output=True,
            text=True,
        )
        return dest

    def fetch_pr_diff(self, owner: str, repo: str, pr_number: str) -> str:
        result = subprocess.run(
            ["gh", "pr", "diff", pr_number, "--repo", f"{owner}/{repo}"],
            capture_output=True,
            text=True,
            check=True,
        )
        return result.stdout

    def get_changed_files(self, diff_text: str) -> List[str]:
        files = []
        for line in diff_text.splitlines():
            stripped = line.strip()
            if stripped.startswith("diff --git a/"):
                parts = stripped.split()
                if len(parts) >= 3:
                    files.append(parts[2][2:])  # strip 'b/'
        return files
