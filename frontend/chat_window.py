import json
import os
from pathlib import Path

from PyQt6.QtCore import QObject, pyqtSignal, pyqtSlot, Qt, QUrl
from PyQt6.QtWidgets import QWidget, QVBoxLayout
from PyQt6.QtWebEngineWidgets import QWebEngineView
from PyQt6.QtWebChannel import QWebChannel


class ChatBridge(QObject):
    messageSent = pyqtSignal(str)
    modelSelectionChanged = pyqtSignal(str)
    openSettingsRequested = pyqtSignal()
    openFileRequested = pyqtSignal(str)

    @pyqtSlot(str)
    def sendMessage(self, text: str):
        self.messageSent.emit(text)

    @pyqtSlot(str)
    def modelChanged(self, model: str):
        self.modelSelectionChanged.emit(model)

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
        self._bridge.modelSelectionChanged.connect(self.modelChanged)
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
            import time
            div_id = f"stream-{int(time.time() * 1000)}"
            self._current_assistant_div = div_id
            js = f"""
                (function() {{
                    const div = window.appendMessage('assistant', '');
                    div.id = '{div_id}';
                    return '{div_id}';
                }})()
            """
            self._web.page().runJavaScript(js)

        escaped = json.dumps(text)
        js = f"""
            (function() {{
                const div = document.getElementById('{self._current_assistant_div}');
                if (div) div.innerHTML += {escaped};
                const messages = document.getElementById('messages');
                if (messages) messages.scrollTop = messages.scrollHeight;
            }})()
        """
        self._web.page().runJavaScript(js)

    def finalize_assistant_message(self):
        self._current_assistant_div = None

    def set_typing(self, show: bool):
        js = f"window.setTyping({'true' if show else 'false'});"
        self._web.page().runJavaScript(js)

    def set_models(self, models: list):
        models_json = json.dumps(models)
        js = f"window.setModels({models_json});"
        self._web.page().runJavaScript(js)
