# frontend/app.py
import json
import logging
import os
import sys
import threading
from pathlib import Path

from PyQt6.QtCore import Qt, QThread, pyqtSignal
from PyQt6.QtGui import QAction, QIcon, QKeySequence, QShortcut, QPixmap, QPainter, QColor
from PyQt6.QtWidgets import QApplication, QSystemTrayIcon, QMenu, QMessageBox

from frontend.chat_window import ChatWindow
from frontend.code_viewer import CodeViewerDialog
from frontend.config_dialog import ConfigDialog
from frontend.services.ollama import OllamaClient
from frontend.services.github import GitHubClient


def _setup_logging():
    log_dir = Path.home() / ".claude"
    log_dir.mkdir(parents=True, exist_ok=True)
    log_path = log_dir / "talkback-frontend.log"
    logging.basicConfig(
        level=logging.DEBUG,
        format="%(asctime)s %(levelname)s %(message)s",
        handlers=[logging.FileHandler(log_path, mode="a"), logging.StreamHandler(sys.stdout)],
    )
    # Also catch unhandled exceptions
    _orig_excepthook = sys.excepthook

    def _excepthook(exc_type, exc_value, tb):
        logging.error("Unhandled exception", exc_info=(exc_type, exc_value, tb))
        _orig_excepthook(exc_type, exc_value, tb)

    sys.excepthook = _excepthook
    return log_path


def _fix_qt_env():
    # Qt WebEngine crashes on some Windows GPUs; disable GPU acceleration
    if sys.platform == "win32":
        os.environ.setdefault("QTWEBENGINE_CHROMIUM_FLAGS", "--disable-gpu --no-sandbox")
        os.environ.setdefault("QT_OPENGL", "software")


def _log_step(step: str):
    logging.info(f"[startup] {step}")


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
    _models_loaded = pyqtSignal(list)
    _models_failed = pyqtSignal(str)

    def __init__(self, argv):
        _log_step("Creating QApplication")
        super().__init__(argv)
        self.setQuitOnLastWindowClosed(False)

        _log_step("Loading config")
        self._config = self._load_config()
        _log_step("Creating Ollama client")
        self._ollama = OllamaClient(self._config.get("ollama_url", "http://localhost:11434"))
        _log_step("Creating GitHub client")
        self._github = GitHubClient()
        self._history = []
        self._current_worker = None

        _log_step("Setting up tray")
        self._setup_tray()
        _log_step("Setting up chat window")
        self._setup_chat()
        _log_step("Setting up hotkey")
        self._setup_hotkey()
        _log_step("Refreshing models")
        self._models_loaded.connect(self._chat.set_models)
        self._models_failed.connect(self._chat.set_models)
        self._refresh_models()
        logging.info("TalkBackApp initialized successfully")

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

    def _create_tray_icon(self) -> QIcon:
        size = 64
        pixmap = QPixmap(size, size)
        pixmap.fill(Qt.GlobalColor.transparent)
        painter = QPainter(pixmap)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        painter.setBrush(QColor("#003566"))
        painter.setPen(Qt.PenStyle.NoPen)
        painter.drawEllipse(4, 4, size - 8, size - 8)
        painter.setBrush(QColor("#F5CC00"))
        painter.drawEllipse(size // 2 - 8, size // 2 - 8, 16, 16)
        painter.end()
        return QIcon(pixmap)

    def _setup_tray(self):
        if not QSystemTrayIcon.isSystemTrayAvailable():
            logging.warning("System tray not available; chat window will be visible on startup")
            return

        self._tray = QSystemTrayIcon(self)
        icon = self._create_tray_icon()
        self._tray.setIcon(icon)
        self._tray.setToolTip("TalkBack PR Reviewer")
        logging.info("Tray icon created")

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
        try:
            self._chat = ChatWindow()
            _log_step("ChatWindow created")
            self._chat.hide()
            self._chat.sendMessage.connect(self._on_message)
            self._chat.openSettings.connect(self._open_settings)
            self._chat.openFile.connect(self._on_open_file)
        except Exception:
            logging.exception("ChatWindow setup failed")
            raise

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
                if not names:
                    logging.warning("Ollama returned no models")
                    self._models_failed.emit(["No models installed"])
                else:
                    logging.info(f"Ollama models: {names}")
                    self._models_loaded.emit(names)
            except Exception as e:
                logging.warning(f"Failed to fetch Ollama models: {e}")
                self._models_failed.emit(["Ollama unreachable"])
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
        try:
            dialog.set_available_models([m["name"] for m in self._ollama.list_models()])
        except Exception as e:
            logging.warning(f"Could not fetch models for settings: {e}")
            dialog.set_available_models([])
        if dialog.exec() == ConfigDialog.DialogCode.Accepted:
            self._config = dialog.get_config()
            self._ollama = OllamaClient(self._config.get("ollama_url", "http://localhost:11434"))
            self._save_config()

    def _on_open_file(self, file_path: str):
        viewer = CodeViewerDialog(self._chat)
        viewer.load_diff(f"diff --git a/{file_path} b/{file_path}\n--- a/{file_path}\n+++ b/{file_path}\n@@ -1 +1 @@\n-old\n+new\n")
        viewer.exec()


def main():
    _fix_qt_env()
    log_path = _setup_logging()
    logging.info("=" * 40)
    logging.info("TalkBack PR Reviewer starting...")
    logging.info(f"Python: {sys.version}")
    logging.info(f"Platform: {sys.platform}")
    try:
        app = TalkBackApp(sys.argv)
        logging.info("Entering Qt event loop")
        sys.exit(app.exec())
    except Exception:
        logging.exception("Fatal startup error")
        raise


if __name__ == "__main__":
    main()
