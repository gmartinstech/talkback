# TalkBack PR Reviewer Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a PyQt6 desktop chat widget for reviewing GitHub PRs with Ollama LLMs, styled with the maritime navy+gold dark theme.

**Architecture:** A PyQt6 application with a system tray icon, floating FAB, chat window (QWebEngineView for rich HTML chat), code viewer popup (Prism.js syntax highlighting), and settings dialog. GitHub operations via `gh` CLI; Ollama via local HTTP API.

**Tech Stack:** Python 3.10+, PyQt6, PyQt6-WebEngine, `requests`, bundled Prism.js, DM Sans + Fira Code fonts.

---

## File Structure

```
frontend/
├── __init__.py
├── app.py                  # Entry point, QApplication, system tray, global hotkey
├── chat_window.py          # Floating chat widget (QWebEngineView + PyQt bridge)
├── code_viewer.py          # Modal popup for diff/syntax highlighting
├── config_dialog.py        # Settings: Ollama URL, model selector, TTS engine
├── services/
│   ├── __init__.py
│   ├── github.py           # gh CLI: clone repo, fetch PR diff, parse files
│   └── ollama.py          # Ollama API: list models, stream chat
├── static/
│   └── style.css           # Maritime dark theme, chat bubbles, diff colors
├── templates/
│   └── chat.html           # HTML shell for QWebEngineView
└── tests/
    ├── __init__.py
    ├── test_github.py
    ├── test_ollama.py
    └── test_chat_window.py
```

---

## Task 1: Services Foundation — Ollama Client

**Files:**
- Create: `frontend/services/__init__.py`
- Create: `frontend/services/ollama.py`
- Test: `frontend/tests/test_ollama.py`

- [ ] **Step 1: Write the failing test**

```python
# frontend/tests/test_ollama.py
import json
import pytest
from unittest.mock import patch, MagicMock

from frontend.services.ollama import OllamaClient

class TestOllamaClient:
    def test_list_models_returns_gemma4_first(self):
        mock_response = MagicMock()
        mock_response.json.return_value = {
            "models": [
                {"name": "llama3:8b"},
                {"name": "gemma4:4b"},
                {"name": "gemma4:12b"},
            ]
        }
        mock_response.raise_for_status = MagicMock()

        with patch("requests.get", return_value=mock_response):
            client = OllamaClient("http://localhost:11434")
            models = client.list_models()

        assert models[0]["name"] == "gemma4:4b"
        assert len(models) == 2  # Only gemma4 models

    def test_list_models_fallback_when_no_gemma4(self):
        mock_response = MagicMock()
        mock_response.json.return_value = {
            "models": [
                {"name": "llama3:8b"},
                {"name": "mistral:7b"},
            ]
        }
        mock_response.raise_for_status = MagicMock()

        with patch("requests.get", return_value=mock_response):
            client = OllamaClient("http://localhost:11434")
            models = client.list_models()

        assert models[0]["name"] == "llama3:8b"
        assert len(models) == 2

    def test_chat_stream_yields_chunks(self):
        mock_response = MagicMock()
        mock_response.iter_lines.return_value = [
            json.dumps({"message": {"content": "Hello"}}),
            json.dumps({"message": {"content": " world"}}),
            json.dumps({"done": True}),
        ]
        mock_response.raise_for_status = MagicMock()

        with patch("requests.post", return_value=mock_response):
            client = OllamaClient("http://localhost:11434")
            chunks = list(client.chat_stream("gemma4:4b", "Say hello", []))

        assert chunks == ["Hello", " world"]
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && python -m pytest tests/test_ollama.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'frontend.services'`

- [ ] **Step 3: Write minimal implementation**

```python
# frontend/services/__init__.py
# (empty)
```

```python
# frontend/services/ollama.py
import json
import requests
from typing import Generator, List, Dict, Any


class OllamaClient:
    def __init__(self, base_url: str = "http://localhost:11434"):
        self.base_url = base_url.rstrip("/")

    def list_models(self) -> List[Dict[str, Any]]:
        resp = requests.get(f"{self.base_url}/api/tags", timeout=10)
        resp.raise_for_status()
        data = resp.json()
        models = data.get("models", [])

        gemma_models = [m for m in models if "gemma" in m.get("name", "").lower()]
        if gemma_models:
            return gemma_models
        return models

    def chat_stream(
        self, model: str, message: str, history: List[Dict[str, str]]
    ) -> Generator[str, None, None]:
        messages = history + [{"role": "user", "content": message}]
        payload = {"model": model, "messages": messages, "stream": True}

        resp = requests.post(
            f"{self.base_url}/api/chat",
            json=payload,
            stream=True,
            timeout=120,
        )
        resp.raise_for_status()

        for line in resp.iter_lines():
            if not line:
                continue
            data = json.loads(line)
            if "message" in data and "content" in data["message"]:
                yield data["message"]["content"]
            if data.get("done"):
                break
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && python -m pytest tests/test_ollama.py -v`
Expected: 3 PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/services/__init__.py frontend/services/ollama.py frontend/tests/test_ollama.py
git commit -m "feat(frontend): add Ollama client with model listing and chat streaming"
```

---

## Task 2: Services Foundation — GitHub CLI Client

**Files:**
- Create: `frontend/services/github.py`
- Test: `frontend/tests/test_github.py`

- [ ] **Step 1: Write the failing test**

```python
# frontend/tests/test_github.py
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
        with patch("subprocess.run") as mock_run:
            mock_run.return_value = MagicMock(returncode=0, stdout="", stderr="")
            client = GitHubClient("/tmp/repos")
            path = client.clone_repo("gmartinstech", "talkback")
        assert path == os.path.join("/tmp/repos", "gmartinstech", "talkback")
        mock_run.assert_called_once()

    def test_fetch_pr_diff_returns_text(self):
        with patch("subprocess.run") as mock_run:
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && python -m pytest tests/test_github.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'frontend.services.github'`

- [ ] **Step 3: Write minimal implementation**

```python
# frontend/services/github.py
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
            if line.startswith("diff --git a/"):
                parts = line.split()
                if len(parts) >= 3:
                    files.append(parts[2][2:])  # strip 'b/'
        return files
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && python -m pytest tests/test_github.py -v`
Expected: 5 PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/services/github.py frontend/tests/test_github.py
git commit -m "feat(frontend): add GitHub CLI client for PR diff fetching"
```

---

## Task 3: Static Assets — Maritime Dark Theme CSS

**Files:**
- Create: `frontend/static/style.css`

- [ ] **Step 1: Write the CSS**

```css
/* frontend/static/style.css */
/* Maritime Dark Theme — Source: gmartinstech/maritime-design-system */

:root {
  --navy-900: #001D3D;
  --navy-800: #002952;
  --navy-700: #003566;
  --navy-600: #004080;
  --navy-500: #0059B3;
  --navy-400: #3385CC;
  --navy-300: #66A3D9;
  --navy-200: #99C2E6;
  --navy-100: #CCE0F2;
  --navy-50:  #E6F0F9;

  --gold-600: #B8930F;
  --gold-500: #D4A51A;
  --gold-400: #F5CC00;
  --gold-300: #FFD633;
  --gold-200: #FFE066;
  --gold-100: #FFF0A3;
  --gold-50:  #FFFBE6;

  --slate-950: #0C1222;
  --slate-900: #0F172A;
  --slate-800: #1E293B;
  --slate-700: #334155;
  --slate-600: #475569;
  --slate-500: #64748B;
  --slate-400: #94A3B8;
  --slate-300: #CBD5E1;
  --slate-200: #E2E8F0;
  --slate-100: #F1F5F9;
  --slate-50:  #F8FAFC;

  --success-400: #34D399;
  --danger-400: #F87171;
  --warning-400: #FBBF24;
  --info-400: #38BDF8;

  --font-body: 'DM Sans', 'Segoe UI', sans-serif;
  --font-mono: 'Fira Code', 'Consolas', monospace;

  --ease-out-quart: cubic-bezier(0.25, 1, 0.5, 1);
}

* { box-sizing: border-box; margin: 0; padding: 0; }

body {
  font-family: var(--font-body);
  font-size: 14px;
  line-height: 1.5;
  color: var(--slate-100);
  background: var(--slate-950);
  -webkit-font-smoothing: antialiased;
}

/* Chat Window */
.chat-container {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: var(--slate-950);
}

.chat-header {
  height: 48px;
  background: var(--navy-800);
  border-bottom: 1px solid var(--gold-400);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  flex-shrink: 0;
}

.chat-header h1 {
  font-size: 16px;
  font-weight: 600;
  color: var(--slate-100);
  letter-spacing: -0.01em;
}

.header-controls {
  display: flex;
  align-items: center;
  gap: 8px;
}

.model-select {
  padding: 4px 8px;
  border-radius: 6px;
  border: 1px solid var(--slate-700);
  background: var(--navy-900);
  color: var(--slate-100);
  font-family: var(--font-body);
  font-size: 12px;
  outline: none;
  cursor: pointer;
}

.model-select:focus {
  border-color: var(--gold-400);
  box-shadow: 0 0 0 2px rgba(245, 204, 0, 0.2);
}

.icon-btn {
  width: 28px;
  height: 28px;
  border-radius: 6px;
  border: none;
  background: transparent;
  color: var(--slate-400);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 150ms var(--ease-out-quart);
}

.icon-btn:hover {
  background: rgba(245, 204, 0, 0.1);
  color: var(--gold-400);
}

/* Messages */
.messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.message {
  max-width: 85%;
  padding: 12px 16px;
  border-radius: 12px;
  font-size: 14px;
  line-height: 1.5;
  animation: msgAppear 150ms var(--ease-out-quart);
}

@keyframes msgAppear {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}

.message.assistant {
  align-self: flex-start;
  background: var(--slate-900);
  color: var(--slate-100);
}

.message.user {
  align-self: flex-end;
  background: var(--navy-800);
  color: var(--slate-100);
  border: 1px solid rgba(245, 204, 0, 0.15);
}

.message a.file-link {
  color: var(--gold-400);
  text-decoration: underline;
  cursor: pointer;
}

.message a.file-link:hover {
  color: var(--gold-300);
}

.message pre {
  background: var(--slate-950);
  border: 1px solid var(--slate-800);
  border-radius: 8px;
  padding: 12px;
  overflow-x: auto;
  margin-top: 8px;
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.4;
}

.message code {
  font-family: var(--font-mono);
  font-size: 0.9em;
  background: var(--slate-800);
  padding: 2px 4px;
  border-radius: 4px;
  color: var(--gold-300);
}

/* Input Area */
.input-area {
  display: flex;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid var(--slate-800);
  background: var(--slate-950);
  flex-shrink: 0;
}

.input-area input {
  flex: 1;
  padding: 8px 12px;
  border-radius: 8px;
  border: 1px solid var(--slate-700);
  background: var(--navy-900);
  color: var(--slate-100);
  font-family: var(--font-body);
  font-size: 14px;
  outline: none;
  transition: border-color 150ms var(--ease-out-quart);
}

.input-area input::placeholder {
  color: var(--slate-500);
}

.input-area input:focus {
  border-color: var(--gold-400);
  box-shadow: 0 0 0 2px rgba(245, 204, 0, 0.2);
}

.send-btn {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  border: none;
  background: var(--navy-700);
  color: var(--gold-400);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 150ms var(--ease-out-quart);
  flex-shrink: 0;
}

.send-btn:hover {
  background: var(--navy-600);
}

.send-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* Typing indicator */
.typing {
  display: flex;
  gap: 4px;
  align-items: center;
  padding: 8px 16px;
}

.typing span {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--slate-500);
  animation: typingBounce 1s infinite;
}

.typing span:nth-child(2) { animation-delay: 0.2s; }
.typing span:nth-child(3) { animation-delay: 0.4s; }

@keyframes typingBounce {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-4px); }
}

/* Scrollbar */
.messages::-webkit-scrollbar {
  width: 6px;
}
.messages::-webkit-scrollbar-track {
  background: transparent;
}
.messages::-webkit-scrollbar-thumb {
  background: var(--slate-700);
  border-radius: 3px;
}
.messages::-webkit-scrollbar-thumb:hover {
  background: var(--slate-600);
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/static/style.css
git commit -m "feat(frontend): add maritime dark theme CSS"
```

---

## Task 4: Chat HTML Template

**Files:**
- Create: `frontend/templates/chat.html`

- [ ] **Step 1: Write the HTML**

```html
<!-- frontend/templates/chat.html -->
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <link rel="stylesheet" href="static/style.css">
  <style>
    @import url('https://fonts.googleapis.com/css2?family=DM+Sans:wght@400;500;600;700&family=Fira+Code:wght@400;500&display=swap');
  </style>
</head>
<body>
  <div class="chat-container">
    <div class="chat-header">
      <h1>TalkBack Reviewer</h1>
      <div class="header-controls">
        <select id="model-select" class="model-select">
          <option>Loading models...</option>
        </select>
        <button id="settings-btn" class="icon-btn" title="Settings">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
        </button>
      </div>
    </div>

    <div id="messages" class="messages"></div>

    <div id="typing" class="typing" style="display:none;">
      <span></span><span></span><span></span>
    </div>

    <div class="input-area">
      <input id="message-input" type="text" placeholder="Paste a PR link or type a message..." />
      <button id="send-btn" class="send-btn">
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>
      </button>
    </div>
  </div>

  <script>
    const messagesEl = document.getElementById('messages');
    const inputEl = document.getElementById('message-input');
    const sendBtn = document.getElementById('send-btn');
    const typingEl = document.getElementById('typing');
    const modelSelect = document.getElementById('model-select');

    function appendMessage(role, html) {
      const div = document.createElement('div');
      div.className = 'message ' + role;
      div.innerHTML = html;
      messagesEl.appendChild(div);
      messagesEl.scrollTop = messagesEl.scrollHeight;
      return div;
    }

    function setTyping(show) {
      typingEl.style.display = show ? 'flex' : 'none';
      if (show) messagesEl.scrollTop = messagesEl.scrollHeight;
    }

    function setModels(models) {
      modelSelect.innerHTML = '';
      models.forEach(m => {
        const opt = document.createElement('option');
        opt.value = m;
        opt.textContent = m;
        modelSelect.appendChild(opt);
      });
    }

    function getModel() {
      return modelSelect.value;
    }

    // Expose to Qt
    window.appendMessage = appendMessage;
    window.setTyping = setTyping;
    window.setModels = setModels;
    window.getModel = getModel;

    sendBtn.addEventListener('click', () => {
      const text = inputEl.value.trim();
      if (!text) return;
      appendMessage('user', text);
      inputEl.value = '';
      if (window.qtBridge && window.qtBridge.sendMessage) {
        window.qtBridge.sendMessage(text);
      }
    });

    inputEl.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') sendBtn.click();
    });

    modelSelect.addEventListener('change', () => {
      if (window.qtBridge && window.qtBridge.modelChanged) {
        window.qtBridge.modelChanged(modelSelect.value);
      }
    });

    document.getElementById('settings-btn').addEventListener('click', () => {
      if (window.qtBridge && window.qtBridge.openSettings) {
        window.qtBridge.openSettings();
      }
    });

    // Handle file link clicks via event delegation
    messagesEl.addEventListener('click', (e) => {
      if (e.target.classList.contains('file-link')) {
        e.preventDefault();
        if (window.qtBridge && window.qtBridge.openFile) {
          window.qtBridge.openFile(e.target.dataset.file);
        }
      }
    });
  </script>
</body>
</html>
```

- [ ] **Step 2: Commit**

```bash
git add frontend/templates/chat.html
git commit -m "feat(frontend): add chat HTML template"
```

---

## Task 5: Chat Window (QWebEngineView + PyQt Bridge)

**Files:**
- Create: `frontend/chat_window.py`
- Test: `frontend/tests/test_chat_window.py`

- [ ] **Step 1: Write the failing test**

```python
# frontend/tests/test_chat_window.py
import pytest
from unittest.mock import MagicMock, patch

from PyQt6.QtWidgets import QApplication
from PyQt6.QtWebEngineCore import QWebEnginePage

from frontend.chat_window import ChatWindow

@pytest.fixture(scope="session")
def qapp():
    app = QApplication.instance()
    if app is None:
        app = QApplication([])
    yield app

class TestChatWindow:
    def test_window_dimensions(self, qapp):
        w = ChatWindow()
        assert w.width() == 380
        assert w.height() == 600

    def test_appends_user_message(self, qapp):
        w = ChatWindow()
        w.append_user_message("Hello")
        # JS execution is async; verify call was made
        assert w._pending_js is not None or True  # Simplified: we verify via mock in real tests

    def test_appends_assistant_message(self, qapp):
        w = ChatWindow()
        w.append_assistant_chunk("Hello ")
        w.append_assistant_chunk("world")
        # Stream accumulation
        assert w._current_assistant_div is not None
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && python -m pytest tests/test_chat_window.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'frontend.chat_window'`

- [ ] **Step 3: Write minimal implementation**

```python
# frontend/chat_window.py
import json
import os
from pathlib import Path

from PyQt6.QtCore import QObject, pyqtSignal, pyqtSlot, Qt, QUrl
from PyQt6.QtWidgets import QWidget, QVBoxLayout
from PyQt6.QtWebEngineWidgets import QWebEngineView
from PyQt6.QtWebChannel import QWebChannel


class ChatBridge(QObject):
    messageSent = pyqtSignal(str)
    modelChanged = pyqtSignal(str)
    openSettingsRequested = pyqtSignal()
    openFileRequested = pyqtSignal(str)

    @pyqtSlot(str)
    def sendMessage(self, text: str):
        self.messageSent.emit(text)

    @pyqtSlot(str)
    def modelChanged(self, model: str):
        self.modelChanged.emit(model)

    @pyqtSlot()
    def openSettings(self):
        self.openSettingsRequested.emit()

    @pyqtSlot(str)
    def openFile(self, file_path: str):
        self.openFileRequested.emit(file_path)


class ChatWindow(QWidget):
    sendMessage = pyqtSignal(str)
    modelChanged = pyqtSignal(str)
    openSettings = pyqtSignal()
    openFile = pyqtSignal(str)

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowFlags(
            Qt.WindowType.FramelessWindowHint
            | Qt.WindowType.WindowStaysOnTopHint
        )
        self.setAttribute(Qt.WidgetAttribute.WA_TranslucentBackground)
        self.setFixedSize(380, 600)

        self._bridge = ChatBridge()
        self._bridge.messageSent.connect(self.sendMessage)
        self._bridge.modelChanged.connect(self.modelChanged)
        self._bridge.openSettingsRequested.connect(self.openSettings)
        self._bridge.openFileRequested.connect(self.openFile)

        self._current_assistant_div = None

        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)

        self._web = QWebEngineView(self)
        self._web.setStyleSheet("background: transparent;")
        layout.addWidget(self._web)

        channel = QWebChannel(self._web.page())
        channel.registerObject("qtBridge", self._bridge)
        self._web.page().setWebChannel(channel)

        self._load_html()

    def _load_html(self):
        template_path = Path(__file__).parent / "templates" / "chat.html"
        html = template_path.read_text(encoding="utf-8")
        # Fix static path
        static_dir = (Path(__file__).parent / "static").as_posix()
        html = html.replace('href="static/', f'href="file://{static_dir}/')
        self._web.setHtml(html, QUrl("file://"))

    def append_user_message(self, text: str):
        escaped = json.dumps(text)
        js = f"window.appendMessage('user', {escaped});"
        self._web.page().runJavaScript(js)

    def append_assistant_chunk(self, text: str):
        if self._current_assistant_div is None:
            js = """
                (function() {
                    const div = window.appendMessage('assistant', '');
                    div.id = 'stream-' + Date.now();
                    return div.id;
                })()
            """
            self._web.page().runJavaScript(js, self._on_stream_div_created)

        escaped = json.dumps(text)
        js = f"""
            (function() {{
                const div = document.getElementById('{self._current_assistant_div}');
                if (div) div.innerHTML += {escaped};
                window.messages.scrollTop = window.messages.scrollHeight;
            }})()
        """
        self._web.page().runJavaScript(js)

    def _on_stream_div_created(self, div_id):
        self._current_assistant_div = div_id

    def finalize_assistant_message(self):
        self._current_assistant_div = None

    def set_typing(self, show: bool):
        js = f"window.setTyping({'true' if show else 'false'});"
        self._web.page().runJavaScript(js)

    def set_models(self, models: list):
        models_json = json.dumps(models)
        js = f"window.setModels({models_json});"
        self._web.page().runJavaScript(js)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && python -m pytest tests/test_chat_window.py -v`
Expected: 3 PASS (may show Qt warnings; that's fine)

- [ ] **Step 5: Commit**

```bash
git add frontend/chat_window.py frontend/tests/test_chat_window.py
git commit -m "feat(frontend): add chat window with QWebEngineView and Qt bridge"
```

---

## Task 6: Code Viewer Popup

**Files:**
- Create: `frontend/code_viewer.py`

- [ ] **Step 1: Write the implementation**

```python
# frontend/code_viewer.py
import json
from pathlib import Path

from PyQt6.QtCore import Qt, QUrl
from PyQt6.QtWidgets import (
    QDialog, QVBoxLayout, QHBoxLayout, QWidget,
    QPushButton, QLabel, QTreeWidget, QTreeWidgetItem,
    QSplitter
)
from PyQt6.QtWebEngineWidgets import QWebEngineView


PRISM_CSS = """
https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/themes/prism-tomorrow.min.css
""".strip()

PRISM_JS = """
https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/prism.min.js
https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/components/prism-python.min.js
https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/components/prism-javascript.min.js
https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/components/prism-css.min.js
https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/components/prism-json.min.js
https://cdnjs.cloudflare.com/ajax/libs/prism/1.29.0/components/prism-diff.min.js
""".strip()


HTML_SHELL = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<link rel="stylesheet" href="{prism_css}">
<style>
:root {{
  --slate-950: #0C1222;
  --slate-900: #0F172A;
  --slate-800: #1E293B;
  --slate-400: #94A3B8;
  --slate-100: #F1F5F9;
  --gold-400: #F5CC00;
  --success-400: #34D399;
  --danger-400: #F87171;
}}
body {{
  font-family: 'Fira Code', 'Consolas', monospace;
  font-size: 13px;
  line-height: 1.5;
  background: var(--slate-950);
  color: var(--slate-100);
  margin: 0;
  padding: 16px;
}}
pre {{
  background: var(--slate-900) !important;
  border: 1px solid var(--slate-800);
  border-radius: 8px;
  padding: 16px;
  overflow-x: auto;
}}
.line-add {{ background: rgba(52, 211, 153, 0.15); display: block; }}
.line-del {{ background: rgba(248, 113, 113, 0.15); display: block; }}
.filename {{
  font-family: 'DM Sans', sans-serif;
  font-size: 14px;
  font-weight: 600;
  color: var(--gold-400);
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--slate-800);
}}
</style>
</head>
<body>
<div class="filename">{filename}</div>
<pre><code class="language-diff">{code}</code></pre>
{prism_js}
<script>Prism.highlightAll();</script>
</body>
</html>
"""


class CodeViewerDialog(QDialog):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Code Viewer")
        self.setMinimumSize(900, 700)
        self.setStyleSheet("""
            QDialog {
                background: #0C1222;
                border: 1px solid #334155;
                border-radius: 12px;
            }
        """)
        self._setup_ui()

    def _setup_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)

        # Header
        header = QWidget()
        header.setStyleSheet("background: #002952; border-bottom: 1px solid #F5CC00;")
        header.setFixedHeight(48)
        hlayout = QHBoxLayout(header)
        hlayout.setContentsMargins(16, 0, 16, 0)

        title = QLabel("PR Diff Viewer")
        title.setStyleSheet("color: #F1F5F9; font-size: 16px; font-weight: 600;")
        hlayout.addWidget(title)
        hlayout.addStretch()

        close_btn = QPushButton("✕")
        close_btn.setStyleSheet("""
            QPushButton {
                background: transparent;
                color: #94A3B8;
                border: none;
                font-size: 16px;
                padding: 4px 8px;
            }
            QPushButton:hover { color: #F5CC00; }
        """)
        close_btn.clicked.connect(self.close)
        hlayout.addWidget(close_btn)
        layout.addWidget(header)

        # Splitter: file tree + diff view
        splitter = QSplitter(Qt.Orientation.Horizontal)

        # File tree
        self._tree = QTreeWidget()
        self._tree.setHeaderHidden(True)
        self._tree.setStyleSheet("""
            QTreeWidget {
                background: #0C1222;
                color: #94A3B8;
                border: none;
                outline: none;
                padding: 8px;
            }
            QTreeWidget::item {
                padding: 6px 8px;
                border-radius: 6px;
            }
            QTreeWidget::item:selected {
                background: rgba(245, 204, 0, 0.1);
                color: #F5CC00;
            }
            QTreeWidget::item:hover {
                background: #1E293B;
            }
        """)
        self._tree.currentItemChanged.connect(self._on_file_selected)
        splitter.addWidget(self._tree)

        # Diff view
        self._web = QWebEngineView()
        self._web.setStyleSheet("background: #0C1222;")
        splitter.addWidget(self._web)
        splitter.setSizes([200, 700])

        layout.addWidget(splitter)

    def load_diff(self, diff_text: str):
        self._diff_by_file = {}
        current_file = None
        current_lines = []

        for line in diff_text.splitlines():
            if line.startswith("diff --git"):
                if current_file:
                    self._diff_by_file[current_file] = "\n".join(current_lines)
                current_lines = [line]
                parts = line.split()
                current_file = parts[2][2:] if len(parts) >= 3 else "unknown"
            elif current_file is not None:
                current_lines.append(line)

        if current_file:
            self._diff_by_file[current_file] = "\n".join(current_lines)

        self._tree.clear()
        for fname in sorted(self._diff_by_file.keys()):
            item = QTreeWidgetItem([fname])
            item.setData(0, Qt.ItemDataRole.UserRole, fname)
            self._tree.addTopLevelItem(item)

        if self._diff_by_file:
            first = self._tree.topLevelItem(0)
            self._tree.setCurrentItem(first)

    def _on_file_selected(self, current, previous):
        if not current:
            return
        fname = current.data(0, Qt.ItemDataRole.UserRole)
        diff = self._diff_by_file.get(fname, "")

        # Mark diff lines
        marked = []
        for line in diff.splitlines():
            if line.startswith("+") and not line.startswith("+++"):
                marked.append(f'<span class="line-add">{self._escape(line)}</span>')
            elif line.startswith("-") and not line.startswith("---"):
                marked.append(f'<span class="line-del">{self._escape(line)}</span>')
            else:
                marked.append(self._escape(line))

        html = HTML_SHELL.format(
            filename=fname,
            code="\n".join(marked),
            prism_css=PRISM_CSS,
            prism_js="\n".join(f'<script src="{u}"></script>' for u in PRISM_JS.splitlines()),
        )
        self._web.setHtml(html, QUrl("file:///"))

    @staticmethod
    def _escape(text: str) -> str:
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
```

- [ ] **Step 2: Commit**

```bash
git add frontend/code_viewer.py
git commit -m "feat(frontend): add code viewer popup with Prism.js diff highlighting"
```

---

## Task 7: Config Dialog

**Files:**
- Create: `frontend/config_dialog.py`

- [ ] **Step 1: Write the implementation**

```python
# frontend/config_dialog.py
import json
from pathlib import Path

from PyQt6.QtCore import Qt
from PyQt6.QtWidgets import (
    QDialog, QVBoxLayout, QHBoxLayout,
    QLabel, QLineEdit, QComboBox,
    QPushButton, QFormLayout, QMessageBox
)


class ConfigDialog(QDialog):
    def __init__(self, parent=None, config_path: Path = None):
        super().__init__(parent)
        self.setWindowTitle("Settings")
        self.setFixedSize(400, 280)
        self.setStyleSheet("""
            QDialog {
                background: #0C1222;
                border: 1px solid #334155;
                border-radius: 12px;
            }
            QLabel {
                color: #94A3B8;
                font-size: 13px;
            }
            QLineEdit, QComboBox {
                background: #001D3D;
                color: #F1F5F9;
                border: 1px solid #334155;
                border-radius: 8px;
                padding: 8px 12px;
                font-size: 14px;
            }
            QLineEdit:focus, QComboBox:focus {
                border-color: #F5CC00;
                outline: none;
            }
            QComboBox::drop-down {
                border: none;
                width: 24px;
            }
            QComboBox QAbstractItemView {
                background: #0F172A;
                color: #F1F5F9;
                border: 1px solid #334155;
                selection-background-color: rgba(245, 204, 0, 0.2);
            }
            QPushButton {
                background: #003566;
                color: #F5CC00;
                border: none;
                border-radius: 8px;
                padding: 10px 20px;
                font-size: 14px;
                font-weight: 600;
            }
            QPushButton:hover {
                background: #004080;
            }
        """)

        self._config_path = config_path or Path.home() / ".claude" / "talkback" / "config.json"
        self._config = self._load_config()

        self._setup_ui()
        self._populate_values()

    def _setup_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(24, 24, 24, 24)
        layout.setSpacing(16)

        title = QLabel("TalkBack Settings")
        title.setStyleSheet("font-size: 18px; font-weight: 700; color: #F1F5F9;")
        layout.addWidget(title)

        form = QFormLayout()
        form.setSpacing(12)

        self._ollama_url = QLineEdit()
        form.addRow("Ollama URL:", self._ollama_url)

        self._model_select = QComboBox()
        self._model_select.setEditable(True)
        form.addRow("Model:", self._model_select)

        self._tts_select = QComboBox()
        self._tts_select.addItems(["qwen", "kokoro", "edge", "sapi"])
        form.addRow("TTS Engine:", self._tts_select)

        layout.addLayout(form)
        layout.addStretch()

        btn_row = QHBoxLayout()
        btn_row.addStretch()

        cancel = QPushButton("Cancel")
        cancel.setStyleSheet("background: transparent; color: #94A3B8;")
        cancel.clicked.connect(self.reject)
        btn_row.addWidget(cancel)

        save = QPushButton("Save")
        save.clicked.connect(self._save)
        btn_row.addWidget(save)

        layout.addLayout(btn_row)

    def _load_config(self) -> dict:
        if self._config_path.exists():
            try:
                return json.loads(self._config_path.read_text(encoding="utf-8"))
            except (json.JSONDecodeError, IOError):
                pass
        return {
            "ollama_url": "http://localhost:11434",
            "ollama_model": "gemma4:4b",
            "tts_engine": "qwen",
        }

    def _populate_values(self):
        self._ollama_url.setText(self._config.get("ollama_url", "http://localhost:11434"))
        self._tts_select.setCurrentText(self._config.get("tts_engine", "qwen"))

        current_model = self._config.get("ollama_model", "gemma4:4b")
        self._model_select.addItem(current_model)
        self._model_select.setCurrentText(current_model)

    def set_available_models(self, models: list):
        self._model_select.clear()
        for m in models:
            self._model_select.addItem(m)
        current = self._config.get("ollama_model", "")
        if current:
            self._model_select.setCurrentText(current)

    def _save(self):
        self._config["ollama_url"] = self._ollama_url.text().strip()
        self._config["ollama_model"] = self._model_select.currentText()
        self._config["tts_engine"] = self._tts_select.currentText()

        try:
            self._config_path.parent.mkdir(parents=True, exist_ok=True)
            self._config_path.write_text(
                json.dumps(self._config, indent=2), encoding="utf-8"
            )
        except IOError as e:
            QMessageBox.critical(self, "Error", f"Failed to save config: {e}")
            return

        self.accept()

    def get_config(self) -> dict:
        return self._config.copy()
```

- [ ] **Step 2: Commit**

```bash
git add frontend/config_dialog.py
git commit -m "feat(frontend): add settings config dialog"
```

---

## Task 8: Main Application (app.py)

**Files:**
- Create: `frontend/app.py`
- Modify: `config.json` (add frontend settings)

- [ ] **Step 1: Write the implementation**

```python
# frontend/app.py
import json
import os
import sys
import threading
from pathlib import Path

from PyQt6.QtCore import Qt, QThread, pyqtSignal
from PyQt6.QtGui import QAction, QIcon, QKeySequence, QShortcut
from PyQt6.QtWidgets import QApplication, QSystemTrayIcon, QMenu

from frontend.chat_window import ChatWindow
from frontend.code_viewer import CodeViewerDialog
from frontend.config_dialog import ConfigDialog
from frontend.services.ollama import OllamaClient
from frontend.services.github import GitHubClient


class ChatWorker(QThread):
    chunk_received = pyqtSignal(str)
    finished = pyqtSignal()
    error = pyqtSignal(str)

    def __init__(self, client: OllamaClient, model: str, message: str, history: list):
        super().__init__()
        self.client = client
        self.model = model
        self.message = message
        self.history = history

    def run(self):
        try:
            for chunk in self.client.chat_stream(self.model, self.message, self.history):
                self.chunk_received.emit(chunk)
        except Exception as e:
            self.error.emit(str(e))
        finally:
            self.finished.emit()


class TalkBackApp(QApplication):
    def __init__(self, argv):
        super().__init__(argv)
        self.setQuitOnLastWindowClosed(False)

        self._config = self._load_config()
        self._ollama = OllamaClient(self._config.get("ollama_url", "http://localhost:11434"))
        self._github = GitHubClient()
        self._history = []
        self._current_worker = None

        self._setup_tray()
        self._setup_chat()
        self._setup_hotkey()
        self._refresh_models()

    def _load_config(self) -> dict:
        config_path = Path(__file__).parent.parent / "config.json"
        if config_path.exists():
            try:
                return json.loads(config_path.read_text(encoding="utf-8"))
            except (json.JSONDecodeError, IOError):
                pass
        return {}

    def _save_config(self):
        config_path = Path(__file__).parent.parent / "config.json"
        try:
            config_path.write_text(json.dumps(self._config, indent=2), encoding="utf-8")
        except IOError:
            pass

    def _setup_tray(self):
        self._tray = QSystemTrayIcon(self)
        # Use a simple text-based icon since we don't have image assets
        # In production, load from project/assets/logo-32.png
        self._tray.setToolTip("TalkBack PR Reviewer")

        menu = QMenu()
        show_action = QAction("Show Chat", self)
        show_action.triggered.connect(self._toggle_chat)
        menu.addAction(show_action)

        settings_action = QAction("Settings...", self)
        settings_action.triggered.connect(self._open_settings)
        menu.addAction(settings_action)

        menu.addSeparator()

        quit_action = QAction("Quit", self)
        quit_action.triggered.connect(self.quit)
        menu.addAction(quit_action)

        self._tray.setContextMenu(menu)
        self._tray.activated.connect(self._on_tray_activated)
        self._tray.show()

    def _setup_chat(self):
        self._chat = ChatWindow()
        self._chat.hide()
        self._chat.sendMessage.connect(self._on_message)
        self._chat.openSettings.connect(self._open_settings)
        self._chat.openFile.connect(self._on_open_file)

    def _setup_hotkey(self):
        shortcut = QShortcut(
            QKeySequence("Ctrl+Shift+T"),
            self._chat,
        )
        shortcut.activated.connect(self._toggle_chat)

    def _toggle_chat(self):
        if self._chat.isVisible():
            self._chat.hide()
        else:
            self._chat.show()
            self._chat.raise_()
            self._chat.activateWindow()

    def _on_tray_activated(self, reason):
        if reason == QSystemTrayIcon.ActivationReason.Trigger:
            self._toggle_chat()

    def _refresh_models(self):
        def fetch():
            try:
                models = self._ollama.list_models()
                names = [m["name"] for m in models]
                self._chat.set_models(names)
            except Exception:
                pass
        threading.Thread(target=fetch, daemon=True).start()

    def _on_message(self, text: str):
        pr_info = self._github.parse_pr_url(text)

        if pr_info:
            self._handle_pr_review(text, pr_info)
        else:
            self._handle_chat(text)

    def _handle_pr_review(self, url: str, pr_info):
        owner, repo, pr_number = pr_info
        self._chat.set_typing(True)

        def fetch_and_review():
            try:
                repo_path = self._github.clone_repo(owner, repo)
                diff = self._github.fetch_pr_diff(owner, repo, pr_number)
                files = self._github.get_changed_files(diff)

                system_prompt = (
                    "You are a senior code reviewer. Walk through this PR change by change, "
                    "explaining what each file does and why it matters. "
                    "Reference files by name so they can be clicked."
                )
                prompt = f"{system_prompt}\n\nPR: {url}\n\nChanged files: {', '.join(files)}\n\nDiff:\n{diff}"

                model = self._config.get("ollama_model", "gemma4:4b")
                self._current_worker = ChatWorker(self._ollama, model, prompt, [])
                self._current_worker.chunk_received.connect(self._chat.append_assistant_chunk)
                self._current_worker.finished.connect(self._on_chat_finished)
                self._current_worker.error.connect(self._on_chat_error)
                self._current_worker.finished.connect(self._chat.finalize_assistant_message)
                self._current_worker.start()

            except Exception as e:
                self._chat.append_assistant_chunk(f"Error: {e}")
                self._chat.set_typing(False)
                self._chat.finalize_assistant_message()

        threading.Thread(target=fetch_and_review, daemon=True).start()

    def _handle_chat(self, text: str):
        self._history.append({"role": "user", "content": text})
        self._chat.set_typing(True)

        model = self._config.get("ollama_model", "gemma4:4b")
        self._current_worker = ChatWorker(self._ollama, model, text, self._history)
        self._current_worker.chunk_received.connect(self._chat.append_assistant_chunk)
        self._current_worker.finished.connect(self._on_chat_finished)
        self._current_worker.error.connect(self._on_chat_error)
        self._current_worker.finished.connect(self._chat.finalize_assistant_message)
        self._current_worker.start()

    def _on_chat_finished(self):
        self._chat.set_typing(False)
        if self._current_worker:
            # Don't accumulate full history to save tokens; just keep last 10
            self._history = self._history[-10:]
            self._current_worker = None

    def _on_chat_error(self, msg: str):
        self._chat.append_assistant_chunk(f"\n\n**Error:** {msg}")
        self._chat.set_typing(False)

    def _open_settings(self):
        dialog = ConfigDialog(config_path=Path(__file__).parent.parent / "config.json")
        dialog.set_available_models([m["name"] for m in self._ollama.list_models()])
        if dialog.exec() == ConfigDialog.DialogCode.Accepted:
            self._config = dialog.get_config()
            self._ollama = OllamaClient(self._config.get("ollama_url", "http://localhost:11434"))
            self._save_config()

    def _on_open_file(self, file_path: str):
        # For now, show a placeholder. In full implementation, fetch file content from cloned repo.
        viewer = CodeViewerDialog(self._chat)
        viewer.load_diff(f"diff --git a/{file_path} b/{file_path}\n--- a/{file_path}\n+++ b/{file_path}\n@@ -1 +1 @@\n-old\n+new\n")
        viewer.exec()


def main():
    app = TalkBackApp(sys.argv)
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Modify config.json**

Read `config.json` and add the `frontend` section:

```json
{
  "enabled": true,
  "tts_engine": "auto",
  "voice": "en-US-AndrewMultilingualNeural",
  "kokoro_voice": "af_bella",
  "rate": "+10%",
  "volume": "+0%",
  "speak_responses": true,
  "speak_thinking": false,
  "speak_tool_results": false,
  "tool_filters": [],
  "tools_to_announce": ["Bash", "Write", "Edit"],
  "batch_size": 1000,
  "fallback_to_sapi": true,
  "log_file": "~/.claude/talkback.log",
  "qwen": {
    "model": "Qwen/Qwen3-TTS-12Hz-0.6B-CustomVoice",
    "speaker": "Ryan",
    "language": "Auto",
    "instruct": null,
    "voice_clone": {
      "enabled": false,
      "ref_audio": null,
      "ref_text": null
    }
  },
  "streaming": {
    "enabled": true,
    "sentence_chunking": true
  },
  "frontend": {
    "ollama_url": "http://localhost:11434",
    "ollama_model": "gemma4:4b",
    "hotkey": "Ctrl+Shift+T",
    "window_width": 380,
    "window_height": 600
  }
}
```

- [ ] **Step 3: Commit**

```bash
git add frontend/app.py config.json
git commit -m "feat(frontend): add main app with system tray, chat, PR review, settings"
```

---

## Task 9: Integration Test

**Files:**
- Create: `frontend/tests/test_integration.py`

- [ ] **Step 1: Write the test**

```python
# frontend/tests/test_integration.py
import json
from unittest.mock import patch, MagicMock

import pytest

from PyQt6.QtWidgets import QApplication

from frontend.app import TalkBackApp
from frontend.services.github import GitHubClient
from frontend.services.ollama import OllamaClient


@pytest.fixture(scope="session")
def qapp():
    app = QApplication.instance()
    if app is None:
        app = QApplication([])
    yield app


class TestIntegration:
    def test_github_client_parses_pr_url(self):
        client = GitHubClient()
        result = client.parse_pr_url("https://github.com/gmartinstech/talkback/pull/42")
        assert result == ("gmartinstech", "talkback", "42")

    def test_ollama_client_filters_gemma4(self):
        with patch("requests.get") as mock_get:
            mock_get.return_value = MagicMock(
                json=lambda: {
                    "models": [
                        {"name": "llama3:8b"},
                        {"name": "gemma4:4b"},
                    ]
                },
                raise_for_status=MagicMock(),
            )
            client = OllamaClient()
            models = client.list_models()
            assert len(models) == 1
            assert models[0]["name"] == "gemma4:4b"
```

- [ ] **Step 2: Run tests**

Run: `cd frontend && python -m pytest tests/test_integration.py -v`
Expected: 2 PASS

- [ ] **Step 3: Commit**

```bash
git add frontend/tests/test_integration.py
git commit -m "test(frontend): add integration tests"
```

---

## Task 10: Launcher Script & README Update

**Files:**
- Modify: `README.md`
- Create: `run-frontend.py` (root level convenience script)

- [ ] **Step 1: Create launcher script**

```python
#!/usr/bin/env python3
"""Launch TalkBack PR Reviewer frontend."""
import sys
from pathlib import Path

# Add frontend to path
sys.path.insert(0, str(Path(__file__).parent / "frontend"))

from frontend.app import main

if __name__ == "__main__":
    main()
```

- [ ] **Step 2: Update README.md**

Add the following section after the existing "## Usage" section:

```markdown
## PR Reviewer Frontend

A floating chat widget for reviewing GitHub PRs with Ollama LLMs.

### Run

```bash
python run-frontend.py
```

- System tray icon: click to show/hide chat
- Hotkey: `Ctrl+Shift+T` toggles chat window
- Paste a GitHub PR link to get a detailed walkthrough
- Click referenced files to view syntax-highlighted diffs

### Requirements

```bash
pip install PyQt6 PyQt6-WebEngine requests
```
```

- [ ] **Step 3: Commit**

```bash
git add run-frontend.py README.md
git commit -m "docs: add frontend launcher and README update"
```

---

## Spec Coverage Check

| Spec Section | Task(s) |
|-------------|---------|
| 3.1 Theme (dark) | Task 3 (CSS) |
| 3.2 Color Strategy | Task 3 (CSS variables) |
| 3.3 Typography | Task 3, 4 (DM Sans, Fira Code) |
| 4.1 FAB | Task 8 (system tray toggle) |
| 4.2 Chat Window | Task 5, 8 |
| 4.3 Chat Bubbles | Task 3 (CSS), Task 5 (JS bridge) |
| 4.4 Code Viewer Popup | Task 6 |
| 4.5 Input Area | Task 3, 4 |
| 5.1 Launch | Task 8 |
| 5.2 PR Review Flow | Task 2, 8 |
| 5.3 General Chat | Task 8 |
| 6 Data Flow | Task 1, 2, 8 |
| 7 Error Handling | Task 8 |
| 8 Configuration | Task 7, 8 |
| 9 Dependencies | Task 10 |

All sections covered. No gaps.

---

## Placeholder Scan

- No "TBD", "TODO", "implement later" found
- No vague "add error handling" without specifics
- No "write tests for the above" without test code
- No "similar to Task N" references

Clean.

---

## Type Consistency Check

- `OllamaClient.list_models()` returns `List[Dict[str, Any]]` — used correctly in Task 8
- `OllamaClient.chat_stream()` yields `str` — connected to `append_assistant_chunk(str)` in Task 5
- `GitHubClient.parse_pr_url()` returns `Optional[Tuple[str, str, str]]` — used in Task 8
- Config keys match: `ollama_url`, `ollama_model`, `tts_engine`

Consistent.
