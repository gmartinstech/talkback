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
        viewer = CodeViewerDialog(self._chat)
        viewer.load_diff(f"diff --git a/{file_path} b/{file_path}\n--- a/{file_path}\n+++ b/{file_path}\n@@ -1 +1 @@\n-old\n+new\n")
        viewer.exec()


def main():
    app = TalkBackApp(sys.argv)
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
